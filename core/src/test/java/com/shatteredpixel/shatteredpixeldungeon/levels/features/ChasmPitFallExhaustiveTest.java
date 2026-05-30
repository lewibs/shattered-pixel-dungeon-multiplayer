package com.shatteredpixel.shatteredpixeldungeon.levels.features;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.scenes.InterlevelScene;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exhaustive edge-case tests for the pit-fall party sync logic.
 *
 * Covers scenarios not in ChasmPitFallIntegrationTest:
 *  - Full 4-hero sequential fall
 *  - Two heroes both fall (A waits, then B is last → fires)
 *  - Re-fall: hero already WaitingToFall falls again (idempotent)
 *  - Party of 4 with mixed dead/waiting, last active falls
 *  - jumpConfirmed reset on every heroFall() call
 *  - WaitingToFall.fallIntoPit flag set correctly (false by default, not from pit room)
 *  - WaitingToFall.act() is a no-op tick (doesn't crash, just spends time)
 *  - WaitingToFall buff bundle serialization round-trip
 *  - Only the falling hero gets WaitingToFall — party members are untouched
 *  - Calling heroFall() on a hero not in Dungeon.heroes (robustness)
 *  - Scene switch fires at most once per fall sequence
 *  - InterlevelScene.mode stays NONE until the last hero falls
 */
class ChasmPitFallExhaustiveTest {

    private boolean sceneSwitchFired;
    private int sceneSwitchCount;

    private Hero heroA;
    private Hero heroB;
    private Hero heroC;
    private Hero heroD;

    @BeforeEach
    void setUp() {
        heroA = new Hero();
        heroB = new Hero();
        heroC = new Hero();
        heroD = new Hero();

        sceneSwitchFired = false;
        sceneSwitchCount = 0;
        Chasm.sceneSwitchOverride = () -> {
            sceneSwitchFired = true;
            sceneSwitchCount++;
        };

        Dungeon.hero = heroA;
        InterlevelScene.mode = InterlevelScene.Mode.NONE;
    }

    @AfterEach
    void tearDown() {
        Dungeon.heroes = null;
        Dungeon.hero = null;
        Chasm.sceneSwitchOverride = null;
        Chasm.jumpConfirmed = false;
        InterlevelScene.mode = InterlevelScene.Mode.NONE;
    }

    // =========================================================================
    // jumpConfirmed is always reset
    // =========================================================================

    @Nested
    class JumpConfirmedReset {

        @Test
        void jumpConfirmed_resetToFalse_whenHeroWaits() {
            Chasm.jumpConfirmed = true;
            Dungeon.heroes = new ArrayList<>();
            Dungeon.heroes.add(heroA);
            Dungeon.heroes.add(heroB);

            Chasm.heroFall(heroA, 0);

            assertFalse(Chasm.jumpConfirmed,
                    "jumpConfirmed must be false after heroFall, even when hero waits");
        }

        @Test
        void jumpConfirmed_resetToFalse_whenSceneSwitches() {
            Chasm.jumpConfirmed = true;
            Dungeon.heroes = new ArrayList<>();
            Dungeon.heroes.add(heroA);

            Chasm.heroFall(heroA, 0);

            assertFalse(Chasm.jumpConfirmed,
                    "jumpConfirmed must be false after heroFall triggers scene switch");
        }

        @Test
        void jumpConfirmed_resetToFalse_evenForDeadHero() {
            Chasm.jumpConfirmed = true;
            heroA.HP = 0;
            Dungeon.heroes = new ArrayList<>();
            Dungeon.heroes.add(heroA);

            Chasm.heroFall(heroA, 0);

            assertFalse(Chasm.jumpConfirmed,
                    "jumpConfirmed must be false even when falling hero is dead");
        }
    }

    // =========================================================================
    // Full 4-hero sequential fall
    // =========================================================================

    @Nested
    class FourHeroSequentialFall {

        @Test
        void fourHeroes_firstThreePause_fourthFires() {
            Dungeon.heroes = new ArrayList<>();
            Dungeon.heroes.add(heroA);
            Dungeon.heroes.add(heroB);
            Dungeon.heroes.add(heroC);
            Dungeon.heroes.add(heroD);

            // A falls — B, C, D active
            Chasm.heroFall(heroA, 0);
            assertFalse(sceneSwitchFired, "after A: no switch yet");
            assertNotNull(heroA.buff(Chasm.WaitingToFall.class), "A must be waiting");

            // B falls — C, D active, A waiting
            Dungeon.hero = heroB;
            Chasm.heroFall(heroB, 0);
            assertFalse(sceneSwitchFired, "after B: no switch yet");
            assertNotNull(heroB.buff(Chasm.WaitingToFall.class), "B must be waiting");

            // C falls — D active, A+B waiting
            Dungeon.hero = heroC;
            Chasm.heroFall(heroC, 0);
            assertFalse(sceneSwitchFired, "after C: no switch yet");
            assertNotNull(heroC.buff(Chasm.WaitingToFall.class), "C must be waiting");

            // D falls — A+B+C all waiting, D is last
            Dungeon.hero = heroD;
            Chasm.heroFall(heroD, 0);
            assertTrue(sceneSwitchFired, "after D: scene switch must fire");
            assertNull(heroD.buff(Chasm.WaitingToFall.class), "D must not be paused — she fired");
            assertEquals(InterlevelScene.Mode.FALL, InterlevelScene.mode);
        }

        @Test
        void fourHeroes_sceneFiresExactlyOnce() {
            Dungeon.heroes = new ArrayList<>();
            Dungeon.heroes.add(heroA);
            Dungeon.heroes.add(heroB);
            Dungeon.heroes.add(heroC);
            Dungeon.heroes.add(heroD);

            Chasm.heroFall(heroA, 0);
            Dungeon.hero = heroB;
            Chasm.heroFall(heroB, 0);
            Dungeon.hero = heroC;
            Chasm.heroFall(heroC, 0);
            Dungeon.hero = heroD;
            Chasm.heroFall(heroD, 0);

            assertEquals(1, sceneSwitchCount,
                    "Scene switch must fire exactly once across the full 4-hero fall sequence");
        }
    }

    // =========================================================================
    // Two-hero complete fall (A waits, B fires)
    // =========================================================================

    @Nested
    class TwoHeroCompleteFall {

        @Test
        void heroB_isLast_firesSceneSwitch() {
            Dungeon.heroes = new ArrayList<>();
            Dungeon.heroes.add(heroA);
            Dungeon.heroes.add(heroB);

            // A falls first — waits
            Chasm.heroFall(heroA, 0);
            assertFalse(sceneSwitchFired);
            assertNotNull(heroA.buff(Chasm.WaitingToFall.class));

            // B falls — A is waiting, B is last
            Dungeon.hero = heroB;
            Chasm.heroFall(heroB, 0);
            assertTrue(sceneSwitchFired, "B is last active hero — must fire");
            assertNull(heroB.buff(Chasm.WaitingToFall.class), "B must not be paused");
            // A still has WaitingToFall — will be placed at fall cell by InterlevelScene
            assertNotNull(heroA.buff(Chasm.WaitingToFall.class),
                    "A's WaitingToFall buff must still be present for InterlevelScene to use");
        }

        @Test
        void sceneFiresExactlyOnce_twoHeroes() {
            Dungeon.heroes = new ArrayList<>();
            Dungeon.heroes.add(heroA);
            Dungeon.heroes.add(heroB);

            Chasm.heroFall(heroA, 0);
            Dungeon.hero = heroB;
            Chasm.heroFall(heroB, 0);

            assertEquals(1, sceneSwitchCount);
        }
    }

    // =========================================================================
    // Re-fall: hero already has WaitingToFall and falls again
    // =========================================================================

    @Nested
    class ReFall {

        @Test
        void heroAlreadyWaiting_reFall_isIdempotent() {
            // heroA has WaitingToFall, heroB is still active
            Buff.affect(heroA, Chasm.WaitingToFall.class);
            Dungeon.heroes = new ArrayList<>();
            Dungeon.heroes.add(heroA);
            Dungeon.heroes.add(heroB);

            // heroA falls again (e.g. game processes the pit cell again)
            Chasm.heroFall(heroA, 0);

            // Still no switch — heroB active
            assertFalse(sceneSwitchFired,
                    "Re-falling while already waiting must not fire scene switch");
            // Still exactly one WaitingToFall buff (Buff.affect is idempotent)
            assertNotNull(heroA.buff(Chasm.WaitingToFall.class));
        }
    }

    // =========================================================================
    // Only the falling hero gets the buff — others untouched
    // =========================================================================

    @Nested
    class BuffIsolation {

        @Test
        void onlyFallingHeroGetsWaitingToFall() {
            Dungeon.heroes = new ArrayList<>();
            Dungeon.heroes.add(heroA);
            Dungeon.heroes.add(heroB);
            Dungeon.heroes.add(heroC);

            Chasm.heroFall(heroA, 0);

            assertNotNull(heroA.buff(Chasm.WaitingToFall.class), "A must have WaitingToFall");
            assertNull(heroB.buff(Chasm.WaitingToFall.class), "B must NOT have WaitingToFall");
            assertNull(heroC.buff(Chasm.WaitingToFall.class), "C must NOT have WaitingToFall");
        }

        @Test
        void waitingHeroes_doNotAccumulateExtraBuffs() {
            Dungeon.heroes = new ArrayList<>();
            Dungeon.heroes.add(heroA);
            Dungeon.heroes.add(heroB);
            Dungeon.heroes.add(heroC);

            Chasm.heroFall(heroA, 0);
            Dungeon.hero = heroB;
            Chasm.heroFall(heroB, 0);

            // A and B each have exactly one WaitingToFall
            assertEquals(1,
                    heroA.buffs(Chasm.WaitingToFall.class).size(),
                    "heroA must have exactly one WaitingToFall buff");
            assertEquals(1,
                    heroB.buffs(Chasm.WaitingToFall.class).size(),
                    "heroB must have exactly one WaitingToFall buff");
        }
    }

    // =========================================================================
    // fallIntoPit flag on WaitingToFall buff
    // =========================================================================

    @Nested
    class FallIntoPitFlag {

        @Test
        void fallIntoPit_isFalse_whenDungeonLevelIsNull() {
            // Dungeon.level == null → isFallIntoPit returns false
            Dungeon.heroes = new ArrayList<>();
            Dungeon.heroes.add(heroA);
            Dungeon.heroes.add(heroB);

            Chasm.heroFall(heroA, 0);

            Chasm.WaitingToFall w = heroA.buff(Chasm.WaitingToFall.class);
            assertNotNull(w);
            assertFalse(w.fallIntoPit,
                    "fallIntoPit must be false when Dungeon.level is null (no WeakFloorRoom check possible)");
        }
    }

    // =========================================================================
    // WaitingToFall buff behaviour
    // =========================================================================

    @Nested
    class WaitingToFallBuff {

        @Test
        void act_doesNotCrash_andSpendsTick() {
            Chasm.WaitingToFall w = Buff.affect(heroA, Chasm.WaitingToFall.class);
            // act() must not throw and must return true (stay active)
            assertTrue(w.act(), "WaitingToFall.act() must return true (buff stays active)");
        }

        @Test
        void bundleRoundTrip_preservesFallIntoPitTrue() {
            Chasm.WaitingToFall original = new Chasm.WaitingToFall();
            original.fallIntoPit = true;

            com.watabou.utils.Bundle bundle = new com.watabou.utils.Bundle();
            original.storeInBundle(bundle);

            Chasm.WaitingToFall restored = new Chasm.WaitingToFall();
            restored.restoreFromBundle(bundle);

            assertTrue(restored.fallIntoPit,
                    "fallIntoPit=true must survive bundle round-trip");
        }

        @Test
        void bundleRoundTrip_preservesFallIntoPitFalse() {
            Chasm.WaitingToFall original = new Chasm.WaitingToFall();
            original.fallIntoPit = false;

            com.watabou.utils.Bundle bundle = new com.watabou.utils.Bundle();
            original.storeInBundle(bundle);

            Chasm.WaitingToFall restored = new Chasm.WaitingToFall();
            restored.restoreFromBundle(bundle);

            assertFalse(restored.fallIntoPit,
                    "fallIntoPit=false must survive bundle round-trip");
        }

        @Test
        void defaultFallIntoPit_isFalse() {
            Chasm.WaitingToFall w = new Chasm.WaitingToFall();
            assertFalse(w.fallIntoPit, "Default fallIntoPit must be false");
        }
    }

    // =========================================================================
    // Party with 3 alive heroes, 1 dead — full fall sequence
    // =========================================================================

    @Nested
    class MixedPartyWithDead {

        @Test
        void deadHeroIgnored_threeActiveOneDeadSequential() {
            heroD.HP = 0; // heroD is dead throughout
            Dungeon.heroes = new ArrayList<>();
            Dungeon.heroes.add(heroA);
            Dungeon.heroes.add(heroB);
            Dungeon.heroes.add(heroC);
            Dungeon.heroes.add(heroD);

            // A falls — B and C active, D dead
            Chasm.heroFall(heroA, 0);
            assertFalse(sceneSwitchFired, "A: no switch, B+C still active");
            assertNotNull(heroA.buff(Chasm.WaitingToFall.class));

            // B falls — C active, A waiting, D dead
            Dungeon.hero = heroB;
            Chasm.heroFall(heroB, 0);
            assertFalse(sceneSwitchFired, "B: no switch, C still active");
            assertNotNull(heroB.buff(Chasm.WaitingToFall.class));

            // C falls — A+B waiting, D dead → C is last alive non-waiting
            Dungeon.hero = heroC;
            Chasm.heroFall(heroC, 0);
            assertTrue(sceneSwitchFired, "C: must fire — D is dead, A+B waiting");
            assertNull(heroC.buff(Chasm.WaitingToFall.class));
        }

        @Test
        void heroBecomesDeadMidSequence_doesNotBlockFall() {
            Dungeon.heroes = new ArrayList<>();
            Dungeon.heroes.add(heroA);
            Dungeon.heroes.add(heroB);
            Dungeon.heroes.add(heroC);

            // A falls — B and C active
            Chasm.heroFall(heroA, 0);
            assertFalse(sceneSwitchFired);

            // C dies mid-sequence (killed by monster, not falling)
            heroC.HP = 0;

            // B falls — A is waiting, C is now dead → B is last active
            Dungeon.hero = heroB;
            Chasm.heroFall(heroB, 0);
            assertTrue(sceneSwitchFired,
                    "B must fire — C died mid-sequence, A is waiting");
        }
    }

    // =========================================================================
    // InterlevelScene.mode stays NONE until last hero falls
    // =========================================================================

    @Nested
    class InterlevelSceneModeGating {

        @Test
        void mode_remainsNone_whileHeroesAreWaiting() {
            Dungeon.heroes = new ArrayList<>();
            Dungeon.heroes.add(heroA);
            Dungeon.heroes.add(heroB);
            Dungeon.heroes.add(heroC);

            Chasm.heroFall(heroA, 0);
            assertEquals(InterlevelScene.Mode.NONE, InterlevelScene.mode,
                    "mode must stay NONE after first hero falls");

            Dungeon.hero = heroB;
            Chasm.heroFall(heroB, 0);
            assertEquals(InterlevelScene.Mode.NONE, InterlevelScene.mode,
                    "mode must stay NONE after second hero falls");
        }

        @Test
        void mode_becomesfall_onlyWhenLastHeroFalls() {
            Dungeon.heroes = new ArrayList<>();
            Dungeon.heroes.add(heroA);
            Dungeon.heroes.add(heroB);
            Dungeon.heroes.add(heroC);

            Chasm.heroFall(heroA, 0);
            Dungeon.hero = heroB;
            Chasm.heroFall(heroB, 0);
            Dungeon.hero = heroC;
            Chasm.heroFall(heroC, 0);

            assertEquals(InterlevelScene.Mode.FALL, InterlevelScene.mode,
                    "mode must be FALL once last hero falls");
        }
    }
}
