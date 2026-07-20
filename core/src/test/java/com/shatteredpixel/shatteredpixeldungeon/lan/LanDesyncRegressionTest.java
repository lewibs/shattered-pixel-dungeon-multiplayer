package com.shatteredpixel.shatteredpixeldungeon.lan;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.Statistics;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Momentum;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroAction;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroSubClass;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.Weapon;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.melee.Shortsword;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.CursingTrap;
import com.shatteredpixel.shatteredpixeldungeon.network.NetworkManager;
import com.shatteredpixel.shatteredpixeldungeon.scenes.CellSelector;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndBag;
import com.watabou.noosa.Game;
import com.watabou.utils.PathFinder;
import com.watabou.utils.Random;
import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for LAN lockstep desync bugs found in the 2026-07 audit.
 * Each test documents one way real LAN play diverged while headless tests and
 * pass-and-play stayed healthy.
 *
 * The pattern throughout: simulate "device A" (localPlayerIndex=0, Dungeon.hero
 * = heroes[0]) and "device B" (Dungeon.hero = heroes[1]) against the SAME
 * simulation state, and assert the simulation-visible outcome is identical.
 */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class LanDesyncRegressionTest {

    static final int W = 12, H = 8;

    /** Flat level with a REAL (distance-based) FOV so per-hero FOV actually differs. */
    static class FovLevel extends Level {
        FovLevel() {
            width = W; height = H; length = W * H;
            map         = new int[length];
            pit         = new boolean[length];
            passable    = new boolean[length];
            losBlocking = new boolean[length];
            solid       = new boolean[length];
            avoid       = new boolean[length];
            water       = new boolean[length];
            visited     = new boolean[length];
            mapped      = new boolean[length];
            heroFOV     = new boolean[length];
            secret      = new boolean[length];
            transitions = new ArrayList<>();
            mobs        = new java.util.LinkedHashSet<>();
            heaps       = new com.watabou.utils.SparseArray<>();
            blobs       = new java.util.LinkedHashMap<>();
            plants      = new com.watabou.utils.SparseArray<>();
            traps       = new com.watabou.utils.SparseArray<>();
            customTiles = new ArrayList<>();
            customWalls = new ArrayList<>();
            Arrays.fill(passable, true);
            for (int i = 0; i < length; i++) {
                int row = i / W, col = i % W;
                if (row == 0 || row == H - 1 || col == 0 || col == W - 1) passable[i] = false;
            }
            PathFinder.setMapSize(W, H);
        }
        @Override protected boolean build()    { return true; }
        @Override protected void createMobs()  {}
        @Override protected void createItems() {}
        @Override public int entrance()        { return W + 1; }
        /** Simple deterministic FOV: everything within distance 2 of the char. */
        @Override public void updateFieldOfView(Char c, boolean[] fov) {
            Arrays.fill(fov, false);
            int cx = c.pos % W, cy = c.pos / W;
            for (int i = 0; i < length; i++) {
                int x = i % W, y = i / W;
                if (Math.max(Math.abs(x - cx), Math.abs(y - cy)) <= 2) fov[i] = true;
            }
        }
    }

    static class TestHero extends Hero {
        TestHero(HeroClass cls, int startPos) {
            heroClass = cls;
            HP = HT = 20;
            pos = startPos;
            actPriority = HERO_PRIO;
        }
        @Override protected boolean moveSprite(int from, int to) { return true; }
        @Override public void checkVisibleMobs() {}
    }

    TestHero hero0, hero1;
    FovLevel level;

    @BeforeEach
    void setUp() {
        Game.version = "test";
        PathFinder.setMapSize(W, H);
        Statistics.reset();
        Random.resetGenerators();
        Random.unbindSimGenerator();
        Random.unregisterSimThread();

        hero0 = new TestHero(HeroClass.WARRIOR, W + 1);      // top-left area
        hero1 = new TestHero(HeroClass.MAGE, 5 * W + 9);     // bottom-right area

        Dungeon.heroes = new ArrayList<>();
        Dungeon.heroes.add(hero0);
        Dungeon.heroes.add(hero1);
        Dungeon.customSeedText = "";
        Dungeon.depth = 5;

        level = new FovLevel();
        Dungeon.level = level;
        Dungeon.hero = hero0;

        Actor.clear();
        Actor.addDelayed(hero0, 0);
        Actor.addDelayed(hero1, 0);

        NetworkManager.lanMode = true;
        NetworkManager.setIsHostForTesting(false);
        NetworkManager.localPlayerIndex = 0;
        NetworkManager.sendActionOverride = (a, h) -> {};
    }

    @AfterEach
    void tearDown() {
        NetworkManager.sendActionOverride = null;
        NetworkManager.lanMode = false;
        NetworkManager.localPlayerIndex = 0;
        Random.unbindSimGenerator();
        Random.unregisterSimThread();
        Dungeon.heroes = null;
        Dungeon.hero = null;
        Dungeon.level = null;
        Actor.clear();
        Statistics.reset();
    }

    // -------------------------------------------------------------------------
    // Bug 1: heroFOV is computed from the device-local hero only, so every
    // sim-relevant read of heroFOV (Mimic wake-up, AoE scroll targeting,
    // Thief escape RNG loop...) evaluates differently on each device.
    // In LAN mode heroFOV must be identical no matter which hero is local.
    // -------------------------------------------------------------------------
    @Test
    void heroFOV_identicalRegardlessOfLocalHero_inLanMode() {
        // device A: local player is hero 0
        NetworkManager.localPlayerIndex = 0;
        Dungeon.hero = hero0;
        Dungeon.observe();
        boolean[] deviceA = level.heroFOV.clone();

        // device B: same sim state, but the local player is hero 1
        NetworkManager.localPlayerIndex = 1;
        Dungeon.hero = hero1;
        Dungeon.observe();
        boolean[] deviceB = level.heroFOV.clone();

        assertArrayEquals(deviceA, deviceB,
                "heroFOV must not depend on which hero is device-local in LAN mode — " +
                "sim code (Mimic wake, AoE scrolls, Thief escape) reads it");
    }

    // -------------------------------------------------------------------------
    // Bug 2: on the OWNING device, a mid-action prompt choice (scroll of
    // upgrade/transmutation target, chain cell...) resolves on the render
    // thread WITHOUT sim context, so its RNG comes from the unseeded base
    // generator. The remote device resolves the same choice in sim context.
    // Both sides must draw from the deterministic sim generator.
    // -------------------------------------------------------------------------
    @Test
    void itemChoiceWrapper_resolvesEffectWithSimRng() {
        Random.bindSimGenerator(12345L);
        long before = Random.getSimGeneratorState();

        WndBag.ItemSelector inner = new WndBag.ItemSelector() {
            @Override public String textPrompt() { return null; }
            @Override public boolean itemSelectable(Item item) { return true; }
            @Override public void onSelect(Item item) {
                Random.Int(1000); // stands in for the effect's gameplay RNG
            }
        };
        NetworkManager.wrapItemSelectorForLan(inner).onSelect(null);

        assertNotEquals(before, Random.getSimGeneratorState(),
                "owner-side prompt resolution must consume the deterministic sim RNG " +
                "(the remote device resolves the same choice in sim context)");
    }

    @Test
    void cellChoiceWrapper_resolvesEffectWithSimRng() {
        Random.bindSimGenerator(54321L);
        long before = Random.getSimGeneratorState();

        CellSelector.Listener inner = new CellSelector.Listener() {
            @Override public void onSelect(Integer cell) {
                Random.Int(1000);
            }
            @Override public String prompt() { return null; }
        };
        NetworkManager.wrapCellListenerForLan(inner).onSelect(3 * W + 3);

        assertNotEquals(before, Random.getSimGeneratorState(),
                "owner-side cell-prompt resolution must consume the deterministic sim RNG");
    }

    // -------------------------------------------------------------------------
    // Bug 3: Char.interact() position swap grants Momentum / calls busy() only
    // when the swapping hero is Dungeon.hero. When the acting hero is remote,
    // one device applies the buff and the other doesn't -> speed desync.
    // -------------------------------------------------------------------------
    @Test
    void positionSwap_grantsMomentumToActingHero_notDeviceLocalHero() {
        // hero1 is the REMOTE freerunner doing the swapping; hero0 is local
        hero1.subClass = HeroSubClass.FREERUNNER;
        hero1.pos = 3 * W + 3;

        Char ally = new Char() {
            {
                alignment = Alignment.ALLY;
                pos = 3 * W + 4; // adjacent to hero1
                HP = HT = 10;
            }
        };
        Actor.add(ally);

        assertTrue(ally.interact(hero1), "swap should succeed");
        assertNotNull(hero1.buff(Momentum.class),
                "freerunner Momentum must be granted to the hero who swapped, " +
                "even when that hero is not the device-local Dungeon.hero");
        assertNull(hero0.buff(Momentum.class),
                "the device-local hero must NOT receive the other hero's Momentum");
    }

    // -------------------------------------------------------------------------
    // Bug 4: direct-hero traps target Dungeon.hero instead of the char that
    // triggered them. Each device curses/affects ITS OWN local hero.
    // -------------------------------------------------------------------------
    @Test
    void cursingTrap_cursesSteppingHero_notDeviceLocalHero() {
        Weapon w0 = new Shortsword();
        Weapon w1 = new Shortsword();
        hero0.belongings.weapon = w0;
        hero1.belongings.weapon = w1;

        int trapPos = 4 * W + 6;
        hero1.pos = trapPos;      // hero 1 (remote) is standing on the trap
        hero0.pos = W + 1;        // local hero far away

        CursingTrap trap = new CursingTrap();
        trap.pos = trapPos;
        trap.activate();

        assertTrue(w1.cursed,
                "the trap must curse the hero standing on it (hero 1)");
        assertFalse(w0.cursed,
                "the trap must NOT curse the device-local hero who is nowhere near it");
    }

    // -------------------------------------------------------------------------
    // Bug 5: several UI entry points call item.execute(Dungeon.hero, ...)
    // directly on the render thread, bypassing the LAN action queue entirely
    // (no packet sent, wrong thread, unseeded RNG). All UI item execution must
    // route through Hero.executeOrQueue(...).
    // -------------------------------------------------------------------------
    @Test
    void uiEntryPoints_routeItemExecutionThroughLanQueue() throws Exception {
        String[] files = {
                "ui/RightClickMenu.java",
                "ui/Toolbar.java",
                "ui/InventoryPane.java",
                "windows/WndQuickBag.java",
        };
        Path srcRoot = findSrcRoot();
        List<String> offenders = new ArrayList<>();
        for (String f : files) {
            String src = new String(Files.readAllBytes(
                    srcRoot.resolve("com/shatteredpixel/shatteredpixeldungeon/" + f)));
            // strip comments to avoid false positives
            src = src.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
            if (src.matches("(?s).*\\.execute\\(\\s*Dungeon\\.hero.*")) {
                offenders.add(f);
            }
        }
        assertTrue(offenders.isEmpty(),
                "UI classes must not call item.execute(Dungeon.hero...) directly — " +
                "in LAN mode that bypasses the action queue (no broadcast, render-thread " +
                "RNG). Route through Hero.executeOrQueue(). Offenders: " + offenders);
    }

    private static Path findSrcRoot() {
        // test working dir is core/src/main/assets (see core/build.gradle)
        Path p = Paths.get("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve("core/src/main/java"))) {
            if (Files.exists(p.resolve("../java")) && p.endsWith("assets")) {
                return p.resolve("../java").normalize();
            }
            p = p.getParent();
        }
        assertNotNull(p, "could not locate core/src/main/java from " + Paths.get("").toAbsolutePath());
        return p.resolve("core/src/main/java");
    }
}
