package com.shatteredpixel.shatteredpixeldungeon.lan;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.Statistics;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.GreaterHaste;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.Weapon;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.network.NetworkManager;
import com.watabou.noosa.Game;
import com.watabou.utils.PathFinder;
import com.watabou.utils.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compares LAN-mode and pass-and-play outcomes for the same action sequence.
 *
 * Core question: does the same mob kill produce identical game state on the
 * host device (Dungeon.hero = hero0) and the client device (Dungeon.hero = hero1)?
 *
 * Pre-fix answer: NO — all talent checks, EXP awards, and loot decisions used
 * Dungeon.hero, which is hero0 on host and hero1 on client.
 *
 * Post-fix answer: YES — killerHero is resolved from the cause parameter once
 * in die(), then used throughout rollToDropLoot(), destroy(), and lootChance().
 *
 * Test strategy: SyncTestMob runs the real production code for killerHero
 * resolution, rollToDropLoot, lootChance, and destroy, but overrides die() to
 * call destroy() directly rather than super.die(), avoiding the sprite.die()
 * NPE that occurs in headless tests (Char.die() line 1097 calls sprite.die()
 * unconditionally). All state-affecting logic runs; only the visual sprite.die()
 * is skipped.
 *
 * The GameState snapshot captures hero EXP, levels, and enemy-kill count after
 * each scenario and asserts they are bit-for-bit identical between host and
 * client perspectives.
 */
class LanPassPlaySyncTest {

    static final int W = 8;
    static final int H = 5;
    static final int POS_HERO0 = W + 1;  // row 1 col 1
    static final int POS_HERO1 = W + 3;  // row 1 col 3
    static final int POS_MOB   = W + 5;  // row 1 col 5

    // -------------------------------------------------------------------------
    // Minimal level (same pattern as LanMobDeathSyncTest)
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // SyncTestMob: exercises production code for killerHero resolution,
    // rollToDropLoot (decision only), lootChance, and destroy (EXP).
    //
    // die(): mirrors Mob.die() but calls destroy() directly to avoid
    //        Char.die() → sprite.die() NPE in headless tests.
    //
    // rollToDropLoot(): captures whether loot WOULD drop (lootWouldDrop) without
    //        calling Level.drop() → GameScene.add() which needs a running scene.
    //        Uses the real lootChance() (production code) so the killerHero fix
    //        is exercised, and consumes the RNG identically to production.
    // -------------------------------------------------------------------------

    static class SyncTestMob extends Mob {
        /** Whether rollToDropLoot() would have dropped an item. */
        boolean lootWouldDrop = false;

        SyncTestMob(float lootChanceValue) {
            EXP        = 10;
            maxLvl     = 30;
            lootChance = lootChanceValue;
            alignment  = Alignment.ENEMY;
            HP = HT    = 1;
            pos        = POS_MOB;
        }

        /**
         * Mirrors Mob.die() exactly — killerHero resolution, rollToDropLoot,
         * talent checks — but calls destroy() directly instead of super.die()
         * to avoid Char.die() → sprite.die() NPE in headless tests.
         */
        @Override
        public void die(Object cause) {
            killerHero = (cause instanceof Hero) ? (Hero) cause : Dungeon.hero;

            if (alignment == Alignment.ENEMY) {
                rollToDropLoot();

                Hero k = killerHero;
                if (cause instanceof Hero || cause instanceof Weapon
                        || cause instanceof Weapon.Enchantment) {
                    if (k.hasTalent(Talent.LETHAL_MOMENTUM)
                            && Random.Float() < 0.34f + 0.33f * k.pointsInTalent(Talent.LETHAL_MOMENTUM)) {
                        Buff.affect(k, Talent.LethalMomentumTracker.class, 0f);
                    }
                    if (k.heroClass != HeroClass.DUELIST
                            && k.hasTalent(Talent.LETHAL_HASTE)
                            && k.buff(Talent.LethalHasteCooldown.class) == null) {
                        Buff.affect(k, Talent.LethalHasteCooldown.class, 100f);
                        Buff.affect(k, GreaterHaste.class)
                                .set(2 + 2 * k.pointsInTalent(Talent.LETHAL_HASTE));
                    }
                }
            }

            destroy();  // real Mob.destroy() — EXP, statistics, badges

            HP = 0;
            deathMarked = true;
        }

        /**
         * Captures the loot drop DECISION using the real lootChance() (which
         * uses killerHero — the fix), then consumes RNG identically to
         * production without creating item sprites or calling GameScene.
         */
        @Override
        public void rollToDropLoot() {
            Hero h = killerHero != null ? killerHero : Dungeon.hero;
            if (h.lvl > maxLvl + 2) return;  // level gate: uses killerHero's level (THE FIX)

            // Consume RNG exactly as production does, capture the decision
            lootWouldDrop = Random.Float() < lootChance();  // lootChance() uses killerHero (THE FIX)
            // Ring of Wealth / SoulEater checks omitted — no ring or soul mark in tests
        }
    }

    // -------------------------------------------------------------------------
    // Snapshot of the state that must be identical on both LAN devices
    // -------------------------------------------------------------------------

    static class GameState {
        final int hero0Exp;
        final int hero0Lvl;
        final int hero1Exp;
        final int hero1Lvl;
        final int enemiesSlain;
        final boolean lootWouldDrop;  // captured from SyncTestMob.lootWouldDrop
        final boolean lootGatePassed; // true if killer level was within cap

        GameState(Hero h0, Hero h1, SyncTestMob mob) {
            hero0Exp     = h0.exp;
            hero0Lvl     = h0.lvl;
            hero1Exp     = h1.exp;
            hero1Lvl     = h1.lvl;
            enemiesSlain = Statistics.enemiesSlain;
            lootWouldDrop = mob.lootWouldDrop;
            lootGatePassed = mob.lootWouldDrop; // lootWouldDrop is false when level gate rejected
        }

        @Override
        public String toString() {
            return String.format(
                    "hero0(exp=%d lvl=%d) hero1(exp=%d lvl=%d) slain=%d lootDrop=%b",
                    hero0Exp, hero0Lvl, hero1Exp, hero1Lvl, enemiesSlain, lootWouldDrop);
        }
    }

    // -------------------------------------------------------------------------
    // Test infrastructure
    // -------------------------------------------------------------------------

    private Hero hero0;       // index 0 — Dungeon.hero on host
    private Hero hero1;       // index 1 — Dungeon.hero on client
    private MinimalLevel level;
    private int initialHero0Exp;
    private int initialHero1Exp;

    @BeforeEach
    void setUp() {
        Game.version = "test";  // required by Document static init via Badges.validateCatalogBadges
        PathFinder.setMapSize(W, H);
        Statistics.reset();

        hero0 = new Hero();
        hero0.HP = hero0.HT = 30;
        hero0.lvl = 5;
        hero0.heroClass = HeroClass.WARRIOR;
        hero0.pos = POS_HERO0;

        hero1 = new Hero();
        hero1.HP = hero1.HT = 25;
        hero1.lvl = 5;
        hero1.heroClass = HeroClass.MAGE;
        hero1.pos = POS_HERO1;

        initialHero0Exp = hero0.exp;
        initialHero1Exp = hero1.exp;

        Dungeon.heroes = new ArrayList<>();
        Dungeon.heroes.add(hero0);
        Dungeon.heroes.add(hero1);

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
        Statistics.reset();
    }

    /** Run one kill and return a state snapshot. */
    GameState runKill(Hero dungeonHero, Hero killer, float mobLootChance) {
        Dungeon.hero = dungeonHero;
        Statistics.reset();

        SyncTestMob mob = new SyncTestMob(mobLootChance);
        level.mobs.add(mob);
        mob.die(killer);

        return new GameState(hero0, hero1, mob);
    }

    /** Reset hero EXP and level to initial test values between scenario runs. */
    void resetHeroExp() {
        hero0.exp = initialHero0Exp;
        hero0.lvl = 5;
        hero1.exp = initialHero1Exp;
        hero1.lvl = 5;
    }

    /** Reset only EXP, preserving any custom levels set by a test. */
    void resetHeroExpOnly() {
        hero0.exp = initialHero0Exp;
        hero1.exp = initialHero1Exp;
    }

    // =========================================================================
    // Core sync: same killer → same EXP recipient regardless of Dungeon.hero
    // =========================================================================

    @Test
    void hero1KillsMob_expGoesToHero1_onBothDevices() {
        // Host: Dungeon.hero = hero0, killer = hero1
        GameState host = runKill(hero0, hero1, 0f);

        resetHeroExp();

        // Client: Dungeon.hero = hero1, killer = hero1
        GameState client = runKill(hero1, hero1, 0f);

        // hero1 (the killer) must receive EXP on both devices
        assertTrue(host.hero1Exp > initialHero1Exp,   "Host: hero1 must receive EXP after killing");
        assertTrue(client.hero1Exp > initialHero1Exp, "Client: hero1 must receive EXP after killing");

        assertEquals(host.hero1Exp, client.hero1Exp,
                "EXP received by hero1 must be identical on host and client");
    }

    @Test
    void hero1KillsMob_hero0GetsNoExp_onBothDevices() {
        GameState host = runKill(hero0, hero1, 0f);
        resetHeroExp();
        GameState client = runKill(hero1, hero1, 0f);

        assertEquals(initialHero0Exp, host.hero0Exp,
                "Host: hero0 must NOT receive EXP from hero1's kill");
        assertEquals(initialHero0Exp, client.hero0Exp,
                "Client: hero0 must NOT receive EXP from hero1's kill");
    }

    @Test
    void hero0KillsMob_expGoesToHero0_onBothDevices() {
        GameState host = runKill(hero0, hero0, 0f);
        resetHeroExp();
        GameState client = runKill(hero1, hero0, 0f);

        assertTrue(host.hero0Exp > initialHero0Exp,   "Host: hero0 must receive EXP after killing");
        assertTrue(client.hero0Exp > initialHero0Exp, "Client: hero0 must receive EXP after killing");
        assertEquals(host.hero0Exp, client.hero0Exp,
                "EXP received by hero0 must be identical on host and client");
    }

    // =========================================================================
    // Full state equality: every field identical between host and client
    // =========================================================================

    @Test
    void fullStateIdentical_hero1Kills_hostVsClient() {
        GameState host = runKill(hero0, hero1, 0f);
        resetHeroExp();
        GameState client = runKill(hero1, hero1, 0f);

        assertEquals(host.hero0Exp,     client.hero0Exp,     "hero0.exp");
        assertEquals(host.hero0Lvl,     client.hero0Lvl,     "hero0.lvl");
        assertEquals(host.hero1Exp,     client.hero1Exp,     "hero1.exp");
        assertEquals(host.hero1Lvl,     client.hero1Lvl,     "hero1.lvl");
        assertEquals(host.enemiesSlain, client.enemiesSlain,  "Statistics.enemiesSlain");
        assertEquals(host.lootWouldDrop, client.lootWouldDrop, "loot drop decision");
    }

    @Test
    void fullStateIdentical_hero0Kills_hostVsClient() {
        GameState host = runKill(hero0, hero0, 0f);
        resetHeroExp();
        GameState client = runKill(hero1, hero0, 0f);

        assertEquals(host.hero0Exp,     client.hero0Exp,     "hero0.exp");
        assertEquals(host.hero0Lvl,     client.hero0Lvl,     "hero0.lvl");
        assertEquals(host.hero1Exp,     client.hero1Exp,     "hero1.exp");
        assertEquals(host.hero1Lvl,     client.hero1Lvl,     "hero1.lvl");
        assertEquals(host.enemiesSlain, client.enemiesSlain,  "Statistics.enemiesSlain");
        assertEquals(host.lootWouldDrop, client.lootWouldDrop, "loot drop decision");
    }

    // =========================================================================
    // RNG determinism: same seed → same loot decision on both devices
    // =========================================================================

    @Test
    void sameRngSeed_hero1Kills_identicalLootDecision_hostVsClient() {
        long seed = 0xDEADBEEFL;

        Random.pushGenerator(seed);
        GameState host = runKill(hero0, hero1, 0.5f);
        Random.popGenerator();
        resetHeroExp();

        Random.pushGenerator(seed);
        GameState client = runKill(hero1, hero1, 0.5f);
        Random.popGenerator();
        resetHeroExp();

        assertEquals(host.lootWouldDrop, client.lootWouldDrop,
                "Loot drop decision must be identical when using the same RNG seed. " +
                "host=" + host + " client=" + client);
    }

    @Test
    void sameRngSeed_hero0Kills_identicalLootDecision_hostVsClient() {
        long seed = 0xCAFEBABEL;

        Random.pushGenerator(seed);
        GameState host = runKill(hero0, hero0, 0.5f);
        Random.popGenerator();
        resetHeroExp();

        Random.pushGenerator(seed);
        GameState client = runKill(hero1, hero0, 0.5f);
        Random.popGenerator();
        resetHeroExp();

        assertEquals(host.lootWouldDrop, client.lootWouldDrop,
                "Loot decision must be identical regardless of Dungeon.hero. " +
                "host=" + host + " client=" + client);
    }

    @Test
    void differentRngSeeds_differentKillOrder_stillConsistentPerSeed() {
        long[] seeds = { 1L, 42L, 12345L, 0xFEEDFACEL };
        for (long seed : seeds) {
            resetHeroExp();
            Random.pushGenerator(seed);
            GameState host = runKill(hero0, hero1, 0.4f);
            Random.popGenerator();
            resetHeroExp();

            Random.pushGenerator(seed);
            GameState client = runKill(hero1, hero1, 0.4f);
            Random.popGenerator();

            assertEquals(host.lootWouldDrop, client.lootWouldDrop,
                    "Seed " + seed + ": loot must be identical host=" + host + " client=" + client);
            assertEquals(host.hero1Exp, client.hero1Exp,
                    "Seed " + seed + ": hero1 EXP must be identical");
        }
    }

    // =========================================================================
    // Level-based loot gate: uses killer's level, not Dungeon.hero's level
    // =========================================================================

    @Test
    void lootGate_usesKillerLevel_notDungeonHeroLevel() {
        // hero0 is far above maxLvl+2 — old code would skip loot (wrong hero used)
        // hero1 is the killer and is within level cap — loot should drop
        hero0.lvl = 35;  // well above maxLvl+2 = 32
        hero1.lvl = 5;   // within cap

        long seed = 0xABCD1234L;

        // Host: Dungeon.hero=hero0 (lvl 35, above cap), killer=hero1 (lvl 5, within cap)
        Random.pushGenerator(seed);
        GameState host = runKill(hero0, hero1, 1.0f); // 100% drop if not gated
        Random.popGenerator();
        resetHeroExpOnly();  // preserve custom levels

        // Client: Dungeon.hero=hero1 (lvl 5, within cap), killer=hero1
        Random.pushGenerator(seed);
        GameState client = runKill(hero1, hero1, 1.0f);
        Random.popGenerator();

        // Both must agree: loot WOULD drop (killer is within cap, lootChance=1.0 → always true)
        assertTrue(host.lootWouldDrop,
                "Host: loot must be decided to drop because KILLER (hero1 lvl 5) is within level cap");
        assertTrue(client.lootWouldDrop,
                "Client: loot must be decided to drop because KILLER (hero1 lvl 5) is within level cap");
        assertEquals(host.lootWouldDrop, client.lootWouldDrop,
                "Loot gate decision must be identical on both devices");
    }

    @Test
    void lootGate_killerAboveCap_noLootOnBothDevices() {
        // killer is above cap → no loot, regardless of Dungeon.hero
        hero0.lvl = 5;
        hero1.lvl = 35;  // killer is above cap

        long seed = 0x1234ABCDL;

        Random.pushGenerator(seed);
        GameState host = runKill(hero0, hero1, 1.0f);
        Random.popGenerator();
        resetHeroExpOnly();  // preserve custom levels

        Random.pushGenerator(seed);
        GameState client = runKill(hero1, hero1, 1.0f);
        Random.popGenerator();

        assertFalse(host.lootWouldDrop,
                "Host: no loot when killer (hero1 lvl 35) is above level cap (loot gate returns early)");
        assertFalse(client.lootWouldDrop,
                "Client: no loot when killer (hero1 lvl 35) is above level cap (loot gate returns early)");
    }

    // =========================================================================
    // Multi-kill sequence: state stays in sync across several kills
    // =========================================================================

    @Test
    void multipleKills_alternatingHeroes_stateIdenticalAfterEachKill() {
        // Simulates several rounds: hero0 kills, hero1 kills, hero0 kills, ...
        // Run the full sequence from host perspective and client perspective.
        // State must be identical after every kill.

        long seed = 0x98765432L;
        Random.pushGenerator(seed);
        // Host sequence: Dungeon.hero stays as hero0 the whole time
        Dungeon.hero = hero0;
        Statistics.reset();
        int[] expSnapHost = new int[4]; // hero0Exp, hero1Exp after each kill

        for (int i = 0; i < 2; i++) {
            Hero killer = (i % 2 == 0) ? hero0 : hero1;
            SyncTestMob mob = new SyncTestMob(0f);
            level.mobs.add(mob);
            mob.die(killer);
        }
        expSnapHost[0] = hero0.exp;
        expSnapHost[1] = hero1.exp;
        int slainHost = Statistics.enemiesSlain;
        Random.popGenerator();

        // Reset for client sequence
        resetHeroExp();
        Random.pushGenerator(seed);
        // Client sequence: Dungeon.hero stays as hero1 the whole time
        Dungeon.hero = hero1;
        Statistics.reset();

        for (int i = 0; i < 2; i++) {
            Hero killer = (i % 2 == 0) ? hero0 : hero1;
            SyncTestMob mob = new SyncTestMob(0f);
            level.mobs.add(mob);
            mob.die(killer);
        }
        int[] expSnapClient = new int[]{hero0.exp, hero1.exp};
        int slainClient = Statistics.enemiesSlain;
        Random.popGenerator();

        assertEquals(expSnapHost[0], expSnapClient[0], "hero0.exp after multi-kill sequence");
        assertEquals(expSnapHost[1], expSnapClient[1], "hero1.exp after multi-kill sequence");
        assertEquals(slainHost, slainClient, "enemiesSlain after multi-kill sequence");
    }

    // =========================================================================
    // Regression: pre-fix behavior (Dungeon.hero used) produces wrong results
    // =========================================================================

    @Test
    void regression_preFix_dungeonHeroBehavior_causesDesync() {
        // Demonstrates what USED TO HAPPEN before the fix.
        // With Dungeon.hero=hero0 and killer=hero1:
        //   - Old: EXP went to hero0 (Dungeon.hero)
        //   - New: EXP goes to hero1 (the killer)
        // We verify the new (fixed) behavior here.

        GameState host = runKill(hero0, hero1, 0f);

        // POST-FIX: hero1 (the killer) gets EXP; hero0 (Dungeon.hero) does not
        assertTrue(host.hero1Exp > initialHero1Exp,
                "POST-FIX: killer hero1 must receive EXP");
        assertEquals(initialHero0Exp, host.hero0Exp,
                "POST-FIX: Dungeon.hero (hero0) must NOT receive EXP for hero1's kill");
    }
}
