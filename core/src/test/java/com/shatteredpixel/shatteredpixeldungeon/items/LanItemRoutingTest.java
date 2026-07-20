package com.shatteredpixel.shatteredpixeldungeon.items;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.Statistics;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.spells.DivineSense;
import com.shatteredpixel.shatteredpixeldungeon.items.wands.WandOfMagicMissile;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.network.NetworkManager;
import com.watabou.noosa.Game;
import com.watabou.utils.PathFinder;
import com.watabou.utils.Random;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LAN routing tests that need package access to Item.curUser / Item.thrower.
 *
 * Bug A (double execution): when a queued UseItem execution prompts for a cell
 * on the REMOTE device, the parked thrower listener used to cast() directly on
 * CELL_CHOICE — and then the owner's USE_ITEM_AT broadcast executed the same
 * throw AGAIN. The remote side of a re-queueing listener must be a no-op; the
 * broadcast action is the single source of truth.
 *
 * Bug B (wrong hero): Wand.wandUsed() applied talent procs (Divine Sense,
 * Empowered Strike, Excess Charge...) to Dungeon.hero — the DEVICE-LOCAL hero —
 * instead of curUser, the hero who actually zapped. Buffs landed on different
 * heroes on different devices.
 */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class LanItemRoutingTest {

    static final int W = 10, H = 6;

    static class FlatLevel extends Level {
        FlatLevel() {
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
            PathFinder.setMapSize(W, H);
        }
        @Override protected boolean build()    { return true; }
        @Override protected void createMobs()  {}
        @Override protected void createItems() {}
        @Override public int entrance()        { return W + 1; }
        @Override public void updateFieldOfView(Char c, boolean[] fov) {
            Arrays.fill(fov, true);
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

    /** Item that records whether cast() actually executed. */
    static class ProbeItem extends Item {
        int casts = 0;
        ProbeItem() { stackable = false; }
        @Override public void cast(Hero user, int dst) { casts++; }
    }

    TestHero hero0, hero1;

    @BeforeEach
    void setUp() {
        Game.version = "test";
        PathFinder.setMapSize(W, H);
        Statistics.reset();
        Random.resetGenerators();
        Random.unbindSimGenerator();

        hero0 = new TestHero(HeroClass.WARRIOR, W + 1);
        hero1 = new TestHero(HeroClass.MAGE, W + 5);

        Dungeon.heroes = new ArrayList<>();
        Dungeon.heroes.add(hero0);
        Dungeon.heroes.add(hero1);
        Dungeon.hero = hero0;
        Dungeon.level = new FlatLevel();

        Actor.clear();
        Actor.addDelayed(hero0, 0);
        Actor.addDelayed(hero1, 0);

        NetworkManager.lanMode = true;
        NetworkManager.setIsHostForTesting(false);
        NetworkManager.localPlayerIndex = 0;   // hero0 local, hero1 remote
        NetworkManager.sendActionOverride = (a, h) -> {};
    }

    @AfterEach
    void tearDown() {
        NetworkManager.sendActionOverride = null;
        NetworkManager.lanMode = false;
        NetworkManager.localPlayerIndex = 0;
        Dungeon.heroes = null;
        Dungeon.hero = null;
        Dungeon.level = null;
        Actor.clear();
        Statistics.reset();
    }

    // -------------------------------------------------------------------------
    // Bug A — remote-side cell prompt must not execute the throw directly:
    // the owner's USE_ITEM_AT broadcast will execute it.
    // -------------------------------------------------------------------------
    @Test
    void thrower_onRemoteHero_doesNotCastDirectly() {
        ProbeItem probe = new ProbeItem();
        probe.collect(hero1.belongings.backpack);
        probe.setCurrent(hero1);   // hero1 is remote on this device

        Item.thrower.onSelect(3 * W + 3);

        assertEquals(0, probe.casts,
                "remote-side prompt resolution must not cast() — the owner's " +
                "USE_ITEM_AT broadcast executes the throw; casting here runs it twice");
    }

    @Test
    void thrower_onLocalHero_queuesExactlyOneAction() {
        ProbeItem probe = new ProbeItem();
        probe.collect(hero0.belongings.backpack);
        probe.setCurrent(hero0);

        Item.thrower.onSelect(3 * W + 3);

        assertEquals(0, probe.casts, "local side should queue, not cast inline");
        assertNotNull(hero0.curAction, "local side must queue the throw action");
    }

    // -------------------------------------------------------------------------
    // Bug B — wandUsed() talent procs must target curUser (the zapping hero),
    // not Dungeon.hero (the device-local hero).
    // -------------------------------------------------------------------------
    @Test
    void wandUsed_appliesTalentProcsToZappingHero_notDeviceLocalHero() {
        // hero1 (remote on this device) has Divine Sense; hero0 does not
        LinkedHashMap<Talent, Integer> tier = new LinkedHashMap<>();
        tier.put(Talent.DIVINE_SENSE, 1);
        hero1.talents.add(tier);

        WandOfMagicMissile wand = new WandOfMagicMissile();
        wand.setCurrent(hero1);   // hero1 zaps
        wand.wandUsed();

        assertNotNull(hero1.buff(DivineSense.DivineSenseTracker.class),
                "the zapping hero has Divine Sense — the proc must land on them " +
                "regardless of which hero is device-local");
        assertNull(hero0.buff(DivineSense.DivineSenseTracker.class),
                "the device-local hero without the talent must not get the proc");
    }
}
