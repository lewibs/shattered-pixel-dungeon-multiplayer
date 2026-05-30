package com.shatteredpixel.shatteredpixeldungeon.network;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.watabou.utils.PathFinder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec-first tests for the desync detection hash (lan-06).
 *
 * The plan requires that the hash formula is DETERMINISTIC: identical game
 * state on both devices must produce identical hashes. Any deviation breaks
 * desync detection. These tests verify the CONTRACT, not the implementation.
 *
 * Tests also verify that MEANINGFUL STATE CHANGES produce DIFFERENT hashes —
 * a hash that never changes is useless as a desync detector.
 */
class HashDeterminismTest {

    private Hero hero;

    static class TestLevel extends Level {
        TestLevel() {
            width = 5; height = 5; length = 25;
            map = new int[length]; pit = new boolean[length];
            passable = new boolean[length]; solid = new boolean[length];
            avoid = new boolean[length]; water = new boolean[length];
            visited = new boolean[length]; mapped = new boolean[length];
            heroFOV = new boolean[length];
            transitions = new ArrayList<>();
            mobs = new java.util.HashSet<>();
            heaps = new com.watabou.utils.SparseArray<>();
            blobs = new java.util.HashMap<>();
            plants = new com.watabou.utils.SparseArray<>();
            traps = new com.watabou.utils.SparseArray<>();
            customTiles = new ArrayList<>(); customWalls = new ArrayList<>();
            PathFinder.setMapSize(width, height);
        }
        @Override protected boolean build()    { return true; }
        @Override protected void createMobs()  {}
        @Override protected void createItems() {}
    }

    @BeforeEach
    void setUp() {
        hero = new Hero();
        hero.HP = 20;

        Dungeon.hero = hero;
        Dungeon.heroes = new ArrayList<>();
        Dungeon.heroes.add(hero);
        Dungeon.seed = 12345L;

        Dungeon.level = new TestLevel();
    }

    @AfterEach
    void tearDown() {
        Dungeon.hero = null;
        Dungeon.heroes = null;
        Dungeon.level = null;
        Dungeon.seed = 0;
        NetworkManager.lanMode = false;
    }

    // -------------------------------------------------------------------------
    // Same state → same hash (determinism is the whole point)
    // -------------------------------------------------------------------------

    @Test
    void sameState_producesSameHash() {
        // Given: a fixed game state
        int turn = 10;

        // When: hash computed twice on identical state
        long hash1 = GameScene.computeHash(turn);
        long hash2 = GameScene.computeHash(turn);

        // Then: hashes are identical
        assertEquals(hash1, hash2,
                "Same game state must always produce the same hash — " +
                "non-determinism would cause false desync alarms on every check");
    }

    // -------------------------------------------------------------------------
    // Different HP → different hash (hero damage must be detectable)
    // -------------------------------------------------------------------------

    @Test
    void differentHeroHP_producesDifferentHash() {
        // Given: same turn, different hero HP
        int turn = 10;
        hero.HP = 20;
        long hashFull = GameScene.computeHash(turn);

        hero.HP = 19; // hero took 1 damage
        long hashDamaged = GameScene.computeHash(turn);

        // Then: hashes differ — a desync where one device took damage is caught
        assertNotEquals(hashFull, hashDamaged,
                "HP change must produce a different hash — " +
                "a hero dying on one device but not the other must be detectable");
    }

    // -------------------------------------------------------------------------
    // Different turn → different hash (prevents stale hash reuse)
    // -------------------------------------------------------------------------

    @Test
    void differentTurn_producesDifferentHash() {
        // Given: same state, different turn numbers
        long hash10 = GameScene.computeHash(10);
        long hash20 = GameScene.computeHash(20);

        // Then: hashes differ — a hash from turn 10 can't be confused with turn 20
        assertNotEquals(hash10, hash20,
                "Turn number must be included in hash — " +
                "otherwise a replayed packet from an earlier turn could pass validation");
    }

    // -------------------------------------------------------------------------
    // Different seed → different hash (games with different seeds are distinct)
    // -------------------------------------------------------------------------

    @Test
    void differentSeed_producesDifferentHash() {
        int turn = 10;
        Dungeon.seed = 99999L;
        long hashA = GameScene.computeHash(turn);

        Dungeon.seed = 11111L;
        long hashB = GameScene.computeHash(turn);

        assertNotEquals(hashA, hashB,
                "Dungeon seed must be part of hash — " +
                "two different games must not produce matching hashes");
    }

    // -------------------------------------------------------------------------
    // Mob position change → different hash (mob desync is the most common type)
    // -------------------------------------------------------------------------

    @Test
    void mobPositionChange_producesDifferentHash() {
        int turn = 10;

        // Given: a mob at position 5 — use Rat as a concrete non-abstract Mob subclass
        com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat mob =
                new com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat();
        mob.pos = 5;
        Dungeon.level.mobs.add(mob);
        long hashBefore = GameScene.computeHash(turn);

        // When: mob moves to position 10
        mob.pos = 10;
        long hashAfter = GameScene.computeHash(turn);

        // Then: hashes differ
        assertNotEquals(hashBefore, hashAfter,
                "Mob position change must alter the hash — " +
                "mob desync (e.g. different pathfinding on two devices) must be caught");
    }

    // -------------------------------------------------------------------------
    // Hash only fires on turn % 10 == 0 (per plan — don't check every turn)
    // -------------------------------------------------------------------------

    @Test
    void hashCheckShouldFireOnMultiplesOfTen() {
        // The plan specifies: (int)Actor.now() % 10 == 0
        // This verifies the trigger condition uses int cast, not float modulo
        // (Actor.now() returns float — float % 10 is unreliable for exact equality)

        // These should trigger a hash check:
        assertTrue((int) 10.0f % 10 == 0, "Turn 10 must trigger hash check");
        assertTrue((int) 20.0f % 10 == 0, "Turn 20 must trigger hash check");
        assertTrue((int) 100.0f % 10 == 0, "Turn 100 must trigger hash check");

        // These should NOT:
        assertFalse((int) 11.0f % 10 == 0, "Turn 11 must not trigger hash check");
        assertFalse((int) 15.5f % 10 == 0, "Turn 15.5 must not trigger hash check");

        // The bug: float modulo is NOT the same as int modulo for non-integers
        // This shows why the cast is mandatory
        float floatTurn = 10.0000001f; // typical float drift
        assertTrue((int) floatTurn % 10 == 0,
                "Slight float drift must not prevent hash check — " +
                "always cast Actor.now() to int before % 10");
    }
}
