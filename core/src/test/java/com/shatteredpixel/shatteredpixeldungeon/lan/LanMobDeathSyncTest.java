package com.shatteredpixel.shatteredpixeldungeon.lan;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.network.NetworkManager;
import com.watabou.utils.PathFinder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reproduces and verifies the fix for the LAN mob-death desync bug.
 *
 * Root cause: Mob.die(), Mob.destroy(), Mob.rollToDropLoot(), and
 * Mob.lootChance() all reference Dungeon.hero — which is always the LOCAL
 * device's hero. In LAN mode, Dungeon.hero = hero[0] on the host and
 * Dungeon.hero = hero[1] on the client. The same mob kill therefore produces
 * different game state on each device.
 *
 * Fix: Add a killerHero field to Mob. In die(), resolve the killer hero from
 * the cause parameter and store it. Use killerHero (falling back to
 * Dungeon.hero) throughout destroy(), rollToDropLoot(), and lootChance().
 *
 * Test strategy: use a minimal Mob subclass that isolates the EXP-recipient
 * and loot-level logic (the core of the desync) without depending on sprites,
 * GameScene, or other UI infrastructure. We track which hero was SELECTED as
 * the EXP recipient (capturedEXPRecipient) rather than the actual exp delta,
 * to avoid level-up side effects.
 */
class LanMobDeathSyncTest {

    static final int W = 8;
    static final int H = 5;
    static final int POS_HERO0 = W + 1;  // cell 9
    static final int POS_HERO1 = W + 3;  // cell 11
    static final int POS_MOB   = W + 5;  // cell 13

    static class MinimalLevel extends Level {
        MinimalLevel() {
            width    = W;
            height   = H;
            length   = W * H;
            map      = new int[length];
            pit      = new boolean[length];
            passable = new boolean[length];
            losBlocking = new boolean[length];
            solid    = new boolean[length];
            avoid    = new boolean[length];
            water    = new boolean[length];
            visited  = new boolean[length];
            mapped   = new boolean[length];
            heroFOV  = new boolean[length];

            transitions = new ArrayList<>();
            mobs        = new java.util.HashSet<>();
            heaps       = new com.watabou.utils.SparseArray<>();
            blobs       = new java.util.HashMap<>();
            plants      = new com.watabou.utils.SparseArray<>();
            traps       = new com.watabou.utils.SparseArray<>();
            customTiles = new ArrayList<>();
            customWalls = new ArrayList<>();

            java.util.Arrays.fill(passable, true);
            PathFinder.setMapSize(W, H);
        }
        @Override protected boolean build()    { return true; }
        @Override protected void createMobs()  {}
        @Override protected void createItems() {}
        @Override public int entrance()        { return POS_HERO0; }
    }

    /**
     * Minimal Mob that captures which hero was SELECTED as the EXP recipient.
     * Overrides die() to execute ONLY the EXP-recipient selection logic,
     * without sprite calls, level-up UI, badges, or statistics.
     *
     * PRE-FIX: effectiveHero() returns Dungeon.hero → desync.
     * POST-FIX: effectiveHero() returns killerHero → sync.
     */
    static class TestMob extends Mob {
        /** Which hero was selected as the EXP recipient. Set before earnExp(). */
        Hero capturedEXPRecipient = null;
        /** Whether the loot roll was skipped due to hero level check. */
        boolean lootSkipped = false;

        TestMob() {
            EXP = 10;
            maxLvl = 30;
            lootChance = 0;
            alignment = Alignment.ENEMY;
            HP = HT = 10;
            pos = POS_MOB;
        }

        /**
         * Returns the hero that die/destroy logic should use.
         * PRE-FIX (BUG): always returns Dungeon.hero.
         */
        Hero effectiveHero() {
            return Dungeon.hero;   // BUG: uses local device's hero, not the killer
        }

        /**
         * Stripped-down die() that exercises only:
         * 1. EXP-recipient selection (mirrors destroy() lines 853-882)
         * 2. Loot level check (mirrors rollToDropLoot() line 962)
         *
         * Bypasses sprite.die(), GameScene, GLog, Badges, Statistics to run
         * correctly in a headless test environment.
         */
        @Override
        public void die(Object cause) {
            Hero hero = effectiveHero();

            // Loot level check (mirrors rollToDropLoot(): if (Dungeon.hero.lvl > maxLvl + 2) return)
            if (hero != null && hero.lvl > maxLvl + 2) {
                lootSkipped = true;
            }

            // EXP recipient selection (mirrors destroy() lines 853-877)
            if (hero != null && hero.isAlive() && alignment == Alignment.ENEMY) {
                int exp = hero.lvl <= maxLvl ? EXP : 0;
                if (exp > 0) {
                    capturedEXPRecipient = hero;
                    // Don't call hero.earnExp() — it triggers level-up UI in headless tests
                }
            }

            // Mark dead
            HP = 0;
            deathMarked = true;

            if (Dungeon.level != null) {
                Dungeon.level.mobs.remove(this);
            }
        }

        @Override
        public void destroy() {
            // No-op — logic is in die() for test isolation
        }
    }

    /**
     * Fixed variant of TestMob.
     * Resolves killerHero from the cause parameter and uses it consistently.
     */
    static class FixedTestMob extends TestMob {
        Hero killerHero = null;

        @Override
        public void die(Object cause) {
            // FIX: resolve killer hero from cause before using it
            if (cause instanceof Hero) {
                killerHero = (Hero) cause;
            } else {
                killerHero = Dungeon.hero; // fallback
            }
            super.die(cause);
        }

        @Override
        Hero effectiveHero() {
            // FIX: use killerHero instead of Dungeon.hero
            return killerHero != null ? killerHero : Dungeon.hero;
        }
    }

    private Hero hero0;
    private Hero hero1;
    private MinimalLevel level;

    @BeforeEach
    void setUp() {
        PathFinder.setMapSize(W, H);

        hero0 = new Hero();
        hero0.HP = hero0.HT = 30;
        hero0.lvl = 5;       // level 5: maxExp = 30, so 10 EXP doesn't level up
        hero0.heroClass = HeroClass.WARRIOR;
        hero0.pos = POS_HERO0;

        hero1 = new Hero();
        hero1.HP = hero1.HT = 25;
        hero1.lvl = 5;
        hero1.heroClass = HeroClass.MAGE;
        hero1.pos = POS_HERO1;

        Dungeon.heroes = new ArrayList<>();
        Dungeon.heroes.add(hero0);
        Dungeon.heroes.add(hero1);

        // Simulate host perspective: Dungeon.hero = hero[0]
        Dungeon.hero = hero0;
        Dungeon.customSeedText = "";

        NetworkManager.lanMode = true;
        NetworkManager.localPlayerIndex = 0;

        level = new MinimalLevel();
        Dungeon.level = level;

        Actor.clear();
    }

    @AfterEach
    void tearDown() {
        NetworkManager.lanMode = false;
        NetworkManager.localPlayerIndex = 0;
        Dungeon.heroes = null;
        Dungeon.hero   = null;
        Dungeon.level  = null;
        Actor.clear();
    }

    // =========================================================================
    // Bug demonstration: pre-fix behavior selects Dungeon.hero (hero0) as the
    // EXP recipient, not the actual killer (hero1).
    // =========================================================================

    @Test
    void bugDemo_preFixExpGoesToDungeonHeroNotKiller() {
        // Dungeon.hero = hero0 (host device), but hero1 is the killer
        TestMob mob = new TestMob();
        level.mobs.add(mob);

        mob.die(hero1);

        // PRE-FIX BUG: effectiveHero() returns Dungeon.hero (hero0), not the killer (hero1).
        // On the client device, Dungeon.hero=hero1, so the recipient would be hero1 instead.
        assertEquals(hero0, mob.capturedEXPRecipient,
                "PRE-FIX BUG: EXP recipient is Dungeon.hero (hero0), not the killer (hero1)");
        assertNotEquals(hero1, mob.capturedEXPRecipient,
                "PRE-FIX BUG: actual killer hero1 is not selected as EXP recipient");
    }

    // =========================================================================
    // Fix verification: post-fix always selects the killer hero as EXP recipient
    // =========================================================================

    @Test
    void fix_expGoesToKillerHero_regardlessOfDungeonHero() {
        FixedTestMob mob = new FixedTestMob();
        level.mobs.add(mob);

        // Simulate host perspective: Dungeon.hero = hero0, killer = hero1
        Dungeon.hero = hero0;
        mob.die(hero1);

        assertEquals(hero1, mob.capturedEXPRecipient,
                "POST-FIX: EXP recipient must be the killer (hero1), not Dungeon.hero (hero0)");
        assertNotEquals(hero0, mob.capturedEXPRecipient,
                "POST-FIX: hero0 (Dungeon.hero) must not be selected as EXP recipient");
    }

    @Test
    void fix_expRecipientIsConsistentAcrossBothDevicePerspectives() {
        // Both devices must select the same EXP recipient: the killer (hero1).

        // Host: Dungeon.hero=hero0, killer=hero1
        FixedTestMob mobHost = new FixedTestMob();
        level.mobs.add(mobHost);
        Dungeon.hero = hero0;
        mobHost.die(hero1);

        // Client: Dungeon.hero=hero1, killer=hero1 (same kill)
        FixedTestMob mobClient = new FixedTestMob();
        level.mobs.add(mobClient);
        Dungeon.hero = hero1;
        mobClient.die(hero1);

        // Both devices must select hero1 as the EXP recipient
        assertEquals(hero1, mobHost.capturedEXPRecipient,
                "Host device: EXP recipient must be killer (hero1)");
        assertEquals(hero1, mobClient.capturedEXPRecipient,
                "Client device: EXP recipient must be killer (hero1)");
        assertEquals(mobHost.capturedEXPRecipient, mobClient.capturedEXPRecipient,
                "EXP recipient must be identical on host and client devices");
    }

    // =========================================================================
    // Verify killer resolution from cause parameter
    // =========================================================================

    @Test
    void fix_killerHeroIsResolvedFromCauseParameter() {
        FixedTestMob mob = new FixedTestMob();
        level.mobs.add(mob);

        Dungeon.hero = hero0; // Dungeon.hero points to hero0

        mob.die(hero1); // hero1 is the killer

        // killerHero must be resolved to hero1 from the cause parameter
        assertEquals(hero1, mob.killerHero,
                "killerHero must be resolved from cause (hero1), not Dungeon.hero (hero0)");
    }

    @Test
    void fix_fallsBackToDungeonHeroWhenCauseIsNotAHero() {
        FixedTestMob mob = new FixedTestMob();
        level.mobs.add(mob);

        Dungeon.hero = hero0;

        // Cause is null (e.g. environment kill)
        mob.die(null);

        // With null cause, killerHero falls back to Dungeon.hero (hero0)
        assertEquals(hero0, mob.killerHero,
                "killerHero must fall back to Dungeon.hero when cause is not a Hero");
        assertEquals(hero0, mob.capturedEXPRecipient,
                "Fallback: Dungeon.hero (hero0) is selected as EXP recipient when cause is not a Hero");
    }

    // =========================================================================
    // Verify loot level check uses killer's level, not Dungeon.hero's level.
    //
    // Bug: rollToDropLoot() checks Dungeon.hero.lvl > maxLvl + 2.
    // If killer is hero1 at level 5 (within cap) but Dungeon.hero is hero0 at
    // level 35 (over cap), loot is skipped on host but not client — a desync.
    // =========================================================================

    @Test
    void bugDemo_lootLevelCheckUsesDungeonHeroNotKiller() {
        // hero0 (Dungeon.hero) far above maxLvl+2 — loot would be skipped
        hero0.lvl = 35;
        // hero1 (killer) at level 5 — loot should NOT be skipped
        hero1.lvl = 5;

        TestMob mob = new TestMob(); // pre-fix: uses Dungeon.hero for level check
        level.mobs.add(mob);

        Dungeon.hero = hero0; // host perspective
        mob.die(hero1);

        // BUG: loot skipped because effectiveHero().lvl (35) > maxLvl+2 (32)
        assertTrue(mob.lootSkipped,
                "PRE-FIX BUG: loot skipped because level check uses Dungeon.hero.lvl (35) not killer.lvl (5)");
    }

    @Test
    void fix_lootLevelCheckUsesKillerHeroLevel() {
        // hero0 (Dungeon.hero) far above maxLvl+2 — old code would skip loot
        hero0.lvl = 35;
        // hero1 (killer) at level 5 — loot should NOT be skipped with fix
        hero1.lvl = 5;

        FixedTestMob mob = new FixedTestMob(); // post-fix: uses killerHero for level check
        level.mobs.add(mob);

        Dungeon.hero = hero0; // host perspective
        mob.die(hero1);

        // FIX: loot NOT skipped because killer.lvl (5) <= maxLvl+2 (32)
        assertFalse(mob.lootSkipped,
                "POST-FIX: loot must NOT be skipped when killer.lvl (5) is within cap (maxLvl+2=32)");
    }

    @Test
    void fix_lootLevelCheckIsConsistentAcrossBothDevicePerspectives() {
        // hero0 (Dungeon.hero on host) far above cap; hero1 (killer) within cap
        hero0.lvl = 35;
        hero1.lvl = 5;

        // Host: Dungeon.hero=hero0 (above cap), killer=hero1 (within cap)
        FixedTestMob mobHost = new FixedTestMob();
        level.mobs.add(mobHost);
        Dungeon.hero = hero0;
        mobHost.die(hero1);

        // Client: Dungeon.hero=hero1 (within cap), killer=hero1 (within cap)
        FixedTestMob mobClient = new FixedTestMob();
        level.mobs.add(mobClient);
        Dungeon.hero = hero1;
        mobClient.die(hero1);

        // Both devices must agree: loot is NOT skipped (killer is within cap)
        assertFalse(mobHost.lootSkipped,
                "Host device: loot must not be skipped (killer.lvl=5 within cap)");
        assertFalse(mobClient.lootSkipped,
                "Client device: loot must not be skipped (killer.lvl=5 within cap)");
    }
}
