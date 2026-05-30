package com.shatteredpixel.shatteredpixeldungeon.levels.features;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.scenes.InterlevelScene;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Chasm.heroFall() — the full method, not the extracted helper.
 *
 * Each test calls heroFall() directly and asserts the correct outcome:
 * - WaitingToFall buff attached (or not) on the falling hero
 * - Scene switch fired (or not), detected via sceneSwitchOverride
 * - InterlevelScene.mode set to FALL when the switch fires
 *
 * libGDX dependencies (Sample, Game.switchScene, GameScene.cellSelector) are
 * either null-guarded in production or intercepted via sceneSwitchOverride.
 */
class ChasmPitFallIntegrationTest {

    private boolean sceneSwitchFired;
    private Hero heroA;
    private Hero heroB;
    private Hero heroC;

    @BeforeEach
    void setUp() {
        heroA = new Hero();
        heroB = new Hero();
        heroC = new Hero();

        sceneSwitchFired = false;
        Chasm.sceneSwitchOverride = () -> sceneSwitchFired = true;

        // Level.beforeTransition() reads Dungeon.hero — point it at the falling hero by default
        Dungeon.hero = heroA;

        // Reset InterlevelScene mode so each test starts clean
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

    // -------------------------------------------------------------------------
    // Scenario 1: single player falls — immediate scene switch, no buff
    // -------------------------------------------------------------------------

    @Test
    void singlePlayer_immediateSceneSwitch() {
        // Given: only heroA in party
        Dungeon.heroes = new ArrayList<>();
        Dungeon.heroes.add(heroA);

        // When: heroA falls into a pit
        Chasm.heroFall(heroA, 0);

        // Then: scene switch fires immediately
        assertTrue(sceneSwitchFired,
                "Single player fall must trigger scene switch immediately");

        // And: heroA does NOT have WaitingToFall — she fell now
        assertNull(heroA.buff(Chasm.WaitingToFall.class),
                "Single player should not receive WaitingToFall buff");

        // And: InterlevelScene is set to FALL mode
        assertEquals(InterlevelScene.Mode.FALL, InterlevelScene.mode,
                "InterlevelScene.mode must be FALL after immediate fall");
    }

    // -------------------------------------------------------------------------
    // Scenario 2: two alive heroes, one falls — deferred (no scene switch yet)
    // -------------------------------------------------------------------------

    @Test
    void twoAliveHeroes_fallingHeroPausedNoBSceneSwitch() {
        // Given: heroA and heroB both alive in party
        Dungeon.heroes = new ArrayList<>();
        Dungeon.heroes.add(heroA);
        Dungeon.heroes.add(heroB);

        // When: heroA falls
        Chasm.heroFall(heroA, 0);

        // Then: scene switch is NOT fired — heroB still active
        assertFalse(sceneSwitchFired,
                "Scene switch must not fire while heroB is still active");

        // And: heroA receives WaitingToFall buff (she is paused)
        assertNotNull(heroA.buff(Chasm.WaitingToFall.class),
                "Falling hero must receive WaitingToFall buff when others are active");

        // And: InterlevelScene mode is unchanged
        assertEquals(InterlevelScene.Mode.NONE, InterlevelScene.mode,
                "InterlevelScene.mode must remain NONE — transition not yet triggered");
    }

    // -------------------------------------------------------------------------
    // Scenario 3: other hero is dead — immediate scene switch
    // -------------------------------------------------------------------------

    @Test
    void otherHeroDead_immediateSceneSwitch() {
        // Given: heroA alive, heroB dead
        heroB.HP = 0;
        Dungeon.heroes = new ArrayList<>();
        Dungeon.heroes.add(heroA);
        Dungeon.heroes.add(heroB);

        // When: heroA falls
        Chasm.heroFall(heroA, 0);

        // Then: scene switch fires — heroB is dead, no one to wait for
        assertTrue(sceneSwitchFired,
                "Scene switch must fire immediately when only other hero is dead");

        assertNull(heroA.buff(Chasm.WaitingToFall.class),
                "heroA must not be paused — she fell immediately");
    }

    // -------------------------------------------------------------------------
    // Scenario 4: sequential 3-hero fall — first two paused, third fires
    // -------------------------------------------------------------------------

    @Test
    void sequentialThreeHeroes_lastFallTriggersSwitch() {
        Dungeon.heroes = new ArrayList<>();
        Dungeon.heroes.add(heroA);
        Dungeon.heroes.add(heroB);
        Dungeon.heroes.add(heroC);

        // Step 1: heroA falls — B and C still active
        Chasm.heroFall(heroA, 0);
        assertFalse(sceneSwitchFired, "Step 1: scene switch must not fire yet");
        assertNotNull(heroA.buff(Chasm.WaitingToFall.class), "Step 1: heroA must be WaitingToFall");

        // Step 2: heroB falls — C still active, A is waiting
        Dungeon.hero = heroB;
        Chasm.heroFall(heroB, 0);
        assertFalse(sceneSwitchFired, "Step 2: scene switch must not fire yet");
        assertNotNull(heroB.buff(Chasm.WaitingToFall.class), "Step 2: heroB must be WaitingToFall");

        // Step 3: heroC falls — A and B both waiting, C is last
        Dungeon.hero = heroC;
        Chasm.heroFall(heroC, 0);
        assertTrue(sceneSwitchFired, "Step 3: scene switch must fire — heroC is last active hero");
        assertNull(heroC.buff(Chasm.WaitingToFall.class), "Step 3: heroC must not be paused — she triggered the fall");
        assertEquals(InterlevelScene.Mode.FALL, InterlevelScene.mode,
                "Step 3: InterlevelScene.mode must be FALL");
    }

    // -------------------------------------------------------------------------
    // Scenario 5: falling hero is dead — silent no-op
    // -------------------------------------------------------------------------

    @Test
    void deadHeroFalls_silentNoOp() {
        // Given: heroA is already dead
        heroA.HP = 0;
        Dungeon.heroes = new ArrayList<>();
        Dungeon.heroes.add(heroA);
        Dungeon.heroes.add(heroB);

        // When: heroA falls (e.g. knocked into pit while dead)
        Chasm.heroFall(heroA, 0);

        // Then: no scene switch, no buff
        assertFalse(sceneSwitchFired, "Dead hero fall must not trigger scene switch");
        assertNull(heroA.buff(Chasm.WaitingToFall.class), "Dead hero must not receive WaitingToFall buff");
    }

    // -------------------------------------------------------------------------
    // Scenario 5b: Hero.act() with WaitingToFall — turn skipped, no dialog loop
    // -------------------------------------------------------------------------

    @Test
    void waitingToFallHero_actSkipsTurn() {
        // Attach WaitingToFall to heroA
        Buff.affect(heroA, Chasm.WaitingToFall.class);

        // Record time before act()
        float timeBefore = heroA.cooldown();

        // act() should spend TICK and return false — no dialog, no input wait
        boolean result = heroA.act();

        assertFalse(result, "act() must return false when hero is WaitingToFall");
        assertTrue(heroA.cooldown() > timeBefore,
                "Hero must have spent time (advanced cooldown) so other actors can run");
        assertNull(heroA.curAction,
                "curAction must be null — hero is not waiting for player input");
    }

    @Test
    void waitingToFallHero_actNeverCallsActivate() {
        // Attach WaitingToFall to heroA. If activate() were called it would try to
        // pan the camera and refresh the inventory — we verify it isn't by checking
        // that Dungeon.hero is NOT changed (activate() sets Dungeon.hero = this).
        Dungeon.hero = heroB; // hero[0] is heroB, not heroA
        Buff.affect(heroA, Chasm.WaitingToFall.class);

        heroA.act();

        assertSame(heroB, Dungeon.hero,
                "act() must not call activate() — Dungeon.hero must remain unchanged");
    }

    // -------------------------------------------------------------------------
    // Scenario 6: two heroes, other already WaitingToFall — immediate switch
    // -------------------------------------------------------------------------

    @Test
    void otherHeroAlreadyWaiting_immediateSceneSwitch() {
        // Given: heroB already has WaitingToFall (fell earlier)
        Buff.affect(heroB, Chasm.WaitingToFall.class);
        Dungeon.heroes = new ArrayList<>();
        Dungeon.heroes.add(heroA);
        Dungeon.heroes.add(heroB);

        // When: heroA (last active) falls
        Chasm.heroFall(heroA, 0);

        // Then: scene switch fires — heroA was the last active hero
        assertTrue(sceneSwitchFired,
                "Scene switch must fire when heroA is the last non-waiting alive hero");
        assertNull(heroA.buff(Chasm.WaitingToFall.class),
                "heroA must not be paused — she triggered the fall directly");
    }
}
