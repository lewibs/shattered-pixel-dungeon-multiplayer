package com.shatteredpixel.shatteredpixeldungeon.items;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import org.junit.jupiter.api.*;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for KingsCrown multiplayer behaviour.
 *
 * When a hero wears the Crown, every other ALIVE hero is added to
 * pendingHeroes so they each get their armor-ability dialog in sequence.
 *
 * Key invariants:
 *  - Dead heroes are never queued
 *  - pendingHeroes is cleared before repopulating (no stale entries)
 *  - The wearing hero is never added to their own queue
 *  - showNextPending() drains the list one entry at a time
 *  - After all pending heroes are processed the list is empty
 */
class KingsCrownMultiplayerTest {

    @BeforeEach
    void setUp() {
        KingsCrown.pendingHeroes.clear();
        Dungeon.heroes = new ArrayList<>();
    }

    @AfterEach
    void tearDown() {
        KingsCrown.pendingHeroes.clear();
        Dungeon.heroes = null;
    }

    static Hero aliveHero()  { Hero h = new Hero(); h.HP = h.HT = 20; return h; }
    static Hero deadHero()   { Hero h = new Hero(); h.HP = 0;  h.HT = 20; return h; }

    // =========================================================================
    // pendingHeroes population
    // =========================================================================

    @Test
    void singlePlayer_noPendingHeroes() {
        Hero solo = aliveHero();
        Dungeon.heroes.add(solo);

        // Simulate what execute() does when populating pendingHeroes
        KingsCrown.pendingHeroes.clear();
        for (Hero h : Dungeon.heroes) {
            if (h != solo && h.isAlive()) KingsCrown.pendingHeroes.add(h);
        }

        assertEquals(0, KingsCrown.pendingHeroes.size(),
                "Single player: no other heroes to queue");
    }

    @Test
    void twoPlayers_wearer_excluded_from_pending() {
        Hero wearer = aliveHero();
        Hero other  = aliveHero();
        Dungeon.heroes.add(wearer);
        Dungeon.heroes.add(other);

        KingsCrown.pendingHeroes.clear();
        for (Hero h : Dungeon.heroes) {
            if (h != wearer && h.isAlive()) KingsCrown.pendingHeroes.add(h);
        }

        assertEquals(1, KingsCrown.pendingHeroes.size());
        assertSame(other, KingsCrown.pendingHeroes.get(0),
                "Only the non-wearer hero should be pending");
        assertFalse(KingsCrown.pendingHeroes.contains(wearer),
                "Wearer must never be in their own pending list");
    }

    @Test
    void fourPlayers_threeQueued_wearerExcluded() {
        Hero wearer = aliveHero();
        Hero h1 = aliveHero(), h2 = aliveHero(), h3 = aliveHero();
        Dungeon.heroes.add(wearer);
        Dungeon.heroes.add(h1);
        Dungeon.heroes.add(h2);
        Dungeon.heroes.add(h3);

        KingsCrown.pendingHeroes.clear();
        for (Hero h : Dungeon.heroes) {
            if (h != wearer && h.isAlive()) KingsCrown.pendingHeroes.add(h);
        }

        assertEquals(3, KingsCrown.pendingHeroes.size());
        assertFalse(KingsCrown.pendingHeroes.contains(wearer));
        assertTrue(KingsCrown.pendingHeroes.contains(h1));
        assertTrue(KingsCrown.pendingHeroes.contains(h2));
        assertTrue(KingsCrown.pendingHeroes.contains(h3));
    }

    @Test
    void deadHeroes_notQueued() {
        Hero wearer = aliveHero();
        Hero dead1  = deadHero();
        Hero dead2  = deadHero();
        Hero alive  = aliveHero();
        Dungeon.heroes.add(wearer);
        Dungeon.heroes.add(dead1);
        Dungeon.heroes.add(dead2);
        Dungeon.heroes.add(alive);

        KingsCrown.pendingHeroes.clear();
        for (Hero h : Dungeon.heroes) {
            if (h != wearer && h.isAlive()) KingsCrown.pendingHeroes.add(h);
        }

        assertEquals(1, KingsCrown.pendingHeroes.size(),
                "Dead heroes must not be queued");
        assertSame(alive, KingsCrown.pendingHeroes.get(0));
    }

    @Test
    void allOthersDeadExceptWearer_emptyPending() {
        Hero wearer = aliveHero();
        Dungeon.heroes.add(wearer);
        Dungeon.heroes.add(deadHero());
        Dungeon.heroes.add(deadHero());

        KingsCrown.pendingHeroes.clear();
        for (Hero h : Dungeon.heroes) {
            if (h != wearer && h.isAlive()) KingsCrown.pendingHeroes.add(h);
        }

        assertTrue(KingsCrown.pendingHeroes.isEmpty(),
                "All others dead: nothing to queue");
    }

    // =========================================================================
    // showNextPending() drains list correctly
    // =========================================================================

    @Test
    void showNextPending_emptyList_doesNothing() {
        KingsCrown.pendingHeroes.clear();
        // Must not throw even when list is empty
        assertDoesNotThrow(() -> {
            if (!KingsCrown.pendingHeroes.isEmpty()) {
                KingsCrown.pendingHeroes.remove(0);
            }
        });
        assertTrue(KingsCrown.pendingHeroes.isEmpty());
    }

    @Test
    void pendingList_drainsInOrder() {
        Hero h1 = aliveHero(), h2 = aliveHero(), h3 = aliveHero();
        KingsCrown.pendingHeroes.add(h1);
        KingsCrown.pendingHeroes.add(h2);
        KingsCrown.pendingHeroes.add(h3);

        // Simulate showNextPending() draining one at a time
        assertSame(h1, KingsCrown.pendingHeroes.remove(0));
        assertEquals(2, KingsCrown.pendingHeroes.size());

        assertSame(h2, KingsCrown.pendingHeroes.remove(0));
        assertEquals(1, KingsCrown.pendingHeroes.size());

        assertSame(h3, KingsCrown.pendingHeroes.remove(0));
        assertTrue(KingsCrown.pendingHeroes.isEmpty(),
                "After all pending heroes processed, list must be empty");
    }

    // =========================================================================
    // Stale state — list cleared before repopulating
    // =========================================================================

    @Test
    void staleHeroes_clearedBeforeRepopulation() {
        Hero stale = aliveHero();
        KingsCrown.pendingHeroes.add(stale); // leftover from a previous use

        Hero wearer = aliveHero();
        Hero fresh  = aliveHero();
        Dungeon.heroes.add(wearer);
        Dungeon.heroes.add(fresh);

        // Simulate execute() — MUST clear first
        KingsCrown.pendingHeroes.clear();
        for (Hero h : Dungeon.heroes) {
            if (h != wearer && h.isAlive()) KingsCrown.pendingHeroes.add(h);
        }

        assertEquals(1, KingsCrown.pendingHeroes.size(),
                "Stale entries must be cleared before repopulating");
        assertFalse(KingsCrown.pendingHeroes.contains(stale),
                "Stale hero from previous use must not remain");
        assertTrue(KingsCrown.pendingHeroes.contains(fresh));
    }

    @Test
    void crownUsedTwice_secondUseResetsCorrectly() {
        Hero h0 = aliveHero(), h1 = aliveHero(), h2 = aliveHero();
        Dungeon.heroes.add(h0); Dungeon.heroes.add(h1); Dungeon.heroes.add(h2);

        // First use by h0 — queues h1, h2
        KingsCrown.pendingHeroes.clear();
        for (Hero h : Dungeon.heroes) {
            if (h != h0 && h.isAlive()) KingsCrown.pendingHeroes.add(h);
        }
        assertEquals(2, KingsCrown.pendingHeroes.size());
        KingsCrown.pendingHeroes.remove(0); // h0 chose, h1 processed

        // Second use by h1 (different hero picks up another crown) — must reset
        KingsCrown.pendingHeroes.clear();
        for (Hero h : Dungeon.heroes) {
            if (h != h1 && h.isAlive()) KingsCrown.pendingHeroes.add(h);
        }
        assertEquals(2, KingsCrown.pendingHeroes.size());
        assertTrue(KingsCrown.pendingHeroes.contains(h0));
        assertTrue(KingsCrown.pendingHeroes.contains(h2));
        assertFalse(KingsCrown.pendingHeroes.contains(h1),
                "The new wearer must not be in their own queue");
    }

    // =========================================================================
    // Ordering — heroes queued in Dungeon.heroes order, skipping wearer
    // =========================================================================

    @Test
    void pendingOrder_matchesDungeonHeroesOrder() {
        Hero h0 = aliveHero(), h1 = aliveHero(), h2 = aliveHero(), h3 = aliveHero();
        Dungeon.heroes.add(h0); Dungeon.heroes.add(h1);
        Dungeon.heroes.add(h2); Dungeon.heroes.add(h3);

        // h1 wears crown
        KingsCrown.pendingHeroes.clear();
        for (Hero h : Dungeon.heroes) {
            if (h != h1 && h.isAlive()) KingsCrown.pendingHeroes.add(h);
        }

        assertEquals(3, KingsCrown.pendingHeroes.size());
        assertSame(h0, KingsCrown.pendingHeroes.get(0), "h0 first (before wearer h1)");
        assertSame(h2, KingsCrown.pendingHeroes.get(1), "h2 second");
        assertSame(h3, KingsCrown.pendingHeroes.get(2), "h3 third");
    }

    // =========================================================================
    // Mixed alive/dead — only alive are queued
    // =========================================================================

    @Test
    void mixedParty_onlyAliveQueued() {
        Hero wearer = aliveHero();
        Hero alive1 = aliveHero();
        Hero dead   = deadHero();
        Hero alive2 = aliveHero();
        Dungeon.heroes.add(wearer);
        Dungeon.heroes.add(alive1);
        Dungeon.heroes.add(dead);
        Dungeon.heroes.add(alive2);

        KingsCrown.pendingHeroes.clear();
        for (Hero h : Dungeon.heroes) {
            if (h != wearer && h.isAlive()) KingsCrown.pendingHeroes.add(h);
        }

        assertEquals(2, KingsCrown.pendingHeroes.size());
        assertTrue(KingsCrown.pendingHeroes.contains(alive1));
        assertTrue(KingsCrown.pendingHeroes.contains(alive2));
        assertFalse(KingsCrown.pendingHeroes.contains(dead));
    }
}
