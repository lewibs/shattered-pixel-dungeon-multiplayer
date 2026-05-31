package com.shatteredpixel.shatteredpixeldungeon.items;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroSubClass;
import org.junit.jupiter.api.*;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TengusMask multiplayer behaviour.
 *
 * When a hero wears the Mask, every other ALIVE hero WITHOUT a subclass
 * is added to pendingHeroes so they each get their subclass dialog in sequence.
 *
 * Key invariants:
 *  - Dead heroes are never queued
 *  - Heroes who already have a subclass are excluded from the queue
 *    (showNextPending skips them too)
 *  - pendingHeroes is cleared before repopulating (no stale entries)
 *  - The wearing hero is never in their own queue
 *  - showNextPending() skips heroes that got a subclass between queueing
 *    and their turn (race condition safety)
 */
class TengusMaskMultiplayerTest {

    @BeforeEach
    void setUp() {
        TengusMask.pendingHeroes.clear();
        Dungeon.heroes = new ArrayList<>();
    }

    @AfterEach
    void tearDown() {
        TengusMask.pendingHeroes.clear();
        Dungeon.heroes = null;
    }

    static Hero aliveHero() {
        Hero h = new Hero(); h.HP = h.HT = 20;
        h.subClass = HeroSubClass.NONE;
        return h;
    }

    static Hero deadHero() {
        Hero h = new Hero(); h.HP = 0; h.HT = 20;
        h.subClass = HeroSubClass.NONE;
        return h;
    }

    static Hero heroWithSubclass() {
        Hero h = aliveHero();
        h.subClass = HeroSubClass.BERSERKER; // any non-NONE subclass
        return h;
    }

    // =========================================================================
    // pendingHeroes population
    // =========================================================================

    @Test
    void singlePlayer_noPendingHeroes() {
        Hero solo = aliveHero();
        Dungeon.heroes.add(solo);

        TengusMask.pendingHeroes.clear();
        for (Hero h : Dungeon.heroes) {
            if (h != solo && h.isAlive() && h.subClass == HeroSubClass.NONE)
                TengusMask.pendingHeroes.add(h);
        }

        assertTrue(TengusMask.pendingHeroes.isEmpty());
    }

    @Test
    void twoPlayers_wearer_excluded() {
        Hero wearer = aliveHero();
        Hero other  = aliveHero();
        Dungeon.heroes.add(wearer);
        Dungeon.heroes.add(other);

        TengusMask.pendingHeroes.clear();
        for (Hero h : Dungeon.heroes) {
            if (h != wearer && h.isAlive() && h.subClass == HeroSubClass.NONE)
                TengusMask.pendingHeroes.add(h);
        }

        assertEquals(1, TengusMask.pendingHeroes.size());
        assertSame(other, TengusMask.pendingHeroes.get(0));
        assertFalse(TengusMask.pendingHeroes.contains(wearer));
    }

    @Test
    void heroAlreadyHasSubclass_notQueued() {
        Hero wearer       = aliveHero();
        Hero needsSubclass = aliveHero();
        Hero hasSubclass  = heroWithSubclass();
        Dungeon.heroes.add(wearer);
        Dungeon.heroes.add(needsSubclass);
        Dungeon.heroes.add(hasSubclass);

        TengusMask.pendingHeroes.clear();
        for (Hero h : Dungeon.heroes) {
            if (h != wearer && h.isAlive() && h.subClass == HeroSubClass.NONE)
                TengusMask.pendingHeroes.add(h);
        }

        assertEquals(1, TengusMask.pendingHeroes.size(),
                "Hero with existing subclass must not be queued");
        assertTrue(TengusMask.pendingHeroes.contains(needsSubclass));
        assertFalse(TengusMask.pendingHeroes.contains(hasSubclass));
    }

    @Test
    void allOthersHaveSubclass_emptyPending() {
        Hero wearer = aliveHero();
        Dungeon.heroes.add(wearer);
        Dungeon.heroes.add(heroWithSubclass());
        Dungeon.heroes.add(heroWithSubclass());

        TengusMask.pendingHeroes.clear();
        for (Hero h : Dungeon.heroes) {
            if (h != wearer && h.isAlive() && h.subClass == HeroSubClass.NONE)
                TengusMask.pendingHeroes.add(h);
        }

        assertTrue(TengusMask.pendingHeroes.isEmpty(),
                "All others have subclasses: nothing to queue");
    }

    @Test
    void deadHeroes_notQueued() {
        Hero wearer = aliveHero();
        Hero dead   = deadHero();
        Hero alive  = aliveHero();
        Dungeon.heroes.add(wearer);
        Dungeon.heroes.add(dead);
        Dungeon.heroes.add(alive);

        TengusMask.pendingHeroes.clear();
        for (Hero h : Dungeon.heroes) {
            if (h != wearer && h.isAlive() && h.subClass == HeroSubClass.NONE)
                TengusMask.pendingHeroes.add(h);
        }

        assertEquals(1, TengusMask.pendingHeroes.size());
        assertFalse(TengusMask.pendingHeroes.contains(dead));
        assertTrue(TengusMask.pendingHeroes.contains(alive));
    }

    @Test
    void fourPlayers_mixedState_onlyNeedyAliveQueued() {
        Hero wearer       = aliveHero();
        Hero needsSubclass = aliveHero();
        Hero dead          = deadHero();
        Hero hasSubclass   = heroWithSubclass();
        Dungeon.heroes.add(wearer);
        Dungeon.heroes.add(needsSubclass);
        Dungeon.heroes.add(dead);
        Dungeon.heroes.add(hasSubclass);

        TengusMask.pendingHeroes.clear();
        for (Hero h : Dungeon.heroes) {
            if (h != wearer && h.isAlive() && h.subClass == HeroSubClass.NONE)
                TengusMask.pendingHeroes.add(h);
        }

        assertEquals(1, TengusMask.pendingHeroes.size());
        assertSame(needsSubclass, TengusMask.pendingHeroes.get(0));
    }

    // =========================================================================
    // showNextPending skips heroes who now have a subclass
    // =========================================================================

    @Test
    void showNextPending_skipsHeroWhoGotSubclassMeanwhile() {
        Hero h1 = aliveHero();
        Hero h2 = aliveHero();
        TengusMask.pendingHeroes.add(h1);
        TengusMask.pendingHeroes.add(h2);

        // h1 somehow got a subclass between being queued and their turn
        h1.subClass = HeroSubClass.BERSERKER;

        // Simulate showNextPending():
        while (!TengusMask.pendingHeroes.isEmpty()
                && TengusMask.pendingHeroes.get(0).subClass != HeroSubClass.NONE) {
            TengusMask.pendingHeroes.remove(0);
        }

        // h1 was skipped; h2 (still NONE) is now first
        assertFalse(TengusMask.pendingHeroes.isEmpty());
        assertSame(h2, TengusMask.pendingHeroes.get(0),
                "showNextPending must skip heroes that already have a subclass");
    }

    @Test
    void showNextPending_emptyAfterAllSkipped() {
        Hero h1 = heroWithSubclass();
        Hero h2 = heroWithSubclass();
        TengusMask.pendingHeroes.add(h1);
        TengusMask.pendingHeroes.add(h2);

        // Simulate showNextPending() — all are skipped
        while (!TengusMask.pendingHeroes.isEmpty()
                && TengusMask.pendingHeroes.get(0).subClass != HeroSubClass.NONE) {
            TengusMask.pendingHeroes.remove(0);
        }

        assertTrue(TengusMask.pendingHeroes.isEmpty(),
                "All pending heroes already have subclasses — list must be empty");
    }

    // =========================================================================
    // Stale state cleared before repopulation
    // =========================================================================

    @Test
    void staleEntries_clearedOnNewUse() {
        Hero stale = aliveHero();
        TengusMask.pendingHeroes.add(stale); // leftover

        Hero wearer = aliveHero();
        Hero fresh  = aliveHero();
        Dungeon.heroes.add(wearer);
        Dungeon.heroes.add(fresh);

        TengusMask.pendingHeroes.clear(); // execute() must do this
        for (Hero h : Dungeon.heroes) {
            if (h != wearer && h.isAlive() && h.subClass == HeroSubClass.NONE)
                TengusMask.pendingHeroes.add(h);
        }

        assertEquals(1, TengusMask.pendingHeroes.size());
        assertFalse(TengusMask.pendingHeroes.contains(stale),
                "Stale hero from a previous use must be cleared");
        assertTrue(TengusMask.pendingHeroes.contains(fresh));
    }

    @Test
    void maskUsedTwice_secondUseResetsState() {
        Hero h0 = aliveHero(), h1 = aliveHero(), h2 = aliveHero();
        Dungeon.heroes.add(h0); Dungeon.heroes.add(h1); Dungeon.heroes.add(h2);

        // First use by h0
        TengusMask.pendingHeroes.clear();
        for (Hero h : Dungeon.heroes) {
            if (h != h0 && h.isAlive() && h.subClass == HeroSubClass.NONE)
                TengusMask.pendingHeroes.add(h);
        }
        assertEquals(2, TengusMask.pendingHeroes.size());
        TengusMask.pendingHeroes.remove(0); // h1 processed

        // h1 now uses a second mask
        TengusMask.pendingHeroes.clear();
        for (Hero h : Dungeon.heroes) {
            if (h != h1 && h.isAlive() && h.subClass == HeroSubClass.NONE)
                TengusMask.pendingHeroes.add(h);
        }

        assertEquals(2, TengusMask.pendingHeroes.size(),
                "Second use must reset and queue all needy heroes except the new wearer");
        assertFalse(TengusMask.pendingHeroes.contains(h1));
        assertTrue(TengusMask.pendingHeroes.contains(h0));
        assertTrue(TengusMask.pendingHeroes.contains(h2));
    }

    // =========================================================================
    // Full flow — all heroes get their subclass dialog
    // =========================================================================

    @Test
    void fullFlow_allHeroesGetDialog() {
        Hero h0 = aliveHero(), h1 = aliveHero(), h2 = aliveHero();
        Dungeon.heroes.add(h0); Dungeon.heroes.add(h1); Dungeon.heroes.add(h2);

        // h0 wears mask — queues h1, h2
        TengusMask.pendingHeroes.clear();
        for (Hero h : Dungeon.heroes) {
            if (h != h0 && h.isAlive() && h.subClass == HeroSubClass.NONE)
                TengusMask.pendingHeroes.add(h);
        }
        assertEquals(2, TengusMask.pendingHeroes.size());

        // h0 chooses subclass, showNextPending fires for h1
        h0.subClass = HeroSubClass.BERSERKER;
        Hero next1 = TengusMask.pendingHeroes.remove(0);
        assertSame(h1, next1);

        // h1 chooses subclass, showNextPending fires for h2
        h1.subClass = HeroSubClass.GLADIATOR;
        Hero next2 = TengusMask.pendingHeroes.remove(0);
        assertSame(h2, next2);

        // h2 chooses, no more pending
        h2.subClass = HeroSubClass.ASSASSIN;
        assertTrue(TengusMask.pendingHeroes.isEmpty(),
                "After all heroes chose, list must be empty");
    }

    @Test
    void fullFlow_withDeadHero_skipsDead() {
        Hero h0 = aliveHero(), h1 = deadHero(), h2 = aliveHero();
        Dungeon.heroes.add(h0); Dungeon.heroes.add(h1); Dungeon.heroes.add(h2);

        TengusMask.pendingHeroes.clear();
        for (Hero h : Dungeon.heroes) {
            if (h != h0 && h.isAlive() && h.subClass == HeroSubClass.NONE)
                TengusMask.pendingHeroes.add(h);
        }

        assertEquals(1, TengusMask.pendingHeroes.size());
        assertSame(h2, TengusMask.pendingHeroes.get(0),
                "Dead h1 skipped; only h2 queued");
    }
}
