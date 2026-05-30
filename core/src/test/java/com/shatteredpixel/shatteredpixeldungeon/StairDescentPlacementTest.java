package com.shatteredpixel.shatteredpixeldungeon;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.features.LevelTransition;
import com.watabou.utils.PathFinder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Dungeon.placeHeroesNearEntrance() — the multiplayer hero
 * placement logic that runs when a party descends stairs.
 *
 * Bug: the original loop used Actor.findChar(candidate) to detect occupied
 * cells, but Actor.init() hasn't run yet at placement time so findChar()
 * always returned null. Every hero in the loop saw every cell as free and
 * all landed on the SAME first passable neighbour of the entrance.
 *
 * Fix: track placed positions in a local HashSet so each hero claims a
 * distinct adjacent cell.
 *
 * Layout of the 5×5 TestLevel used by most tests:
 *
 *   # # # # #       W=5, H=5, length=25
 *   # . . . #       entrance = cell 12 (centre)
 *   # . E . #       all non-border cells passable (cells 6-8, 11-13, 16-18)
 *   # . . . #
 *   # # # # #
 */
class StairDescentPlacementTest {

    static final int W = 5;
    static final int H = 5;
    static final int ENTRANCE = 12; // centre cell

    static class TestLevel extends Level {
        TestLevel() {
            width  = W;
            height = H;
            length = W * H;

            map      = new int[length];
            pit      = new boolean[length];
            passable = new boolean[length];
            solid    = new boolean[length];
            avoid    = new boolean[length];
            water    = new boolean[length];
            visited  = new boolean[length];
            mapped   = new boolean[length];
            heroFOV  = new boolean[length];

            transitions  = new ArrayList<>();
            mobs         = new java.util.HashSet<>();
            heaps        = new com.watabou.utils.SparseArray<>();
            blobs        = new java.util.HashMap<>();
            plants       = new com.watabou.utils.SparseArray<>();
            traps        = new com.watabou.utils.SparseArray<>();
            customTiles  = new ArrayList<>();
            customWalls  = new ArrayList<>();

            // Mark all inner cells (non-border) as passable
            for (int y = 1; y < H - 1; y++) {
                for (int x = 1; x < W - 1; x++) {
                    passable[y * W + x] = true;
                }
            }

            PathFinder.setMapSize(W, H);
        }

        @Override protected boolean build()    { return true; }
        @Override protected void createMobs()  {}
        @Override protected void createItems() {}
    }

    private TestLevel level;

    @BeforeEach
    void setUp() {
        level = new TestLevel();
        Dungeon.level = level;
    }

    @AfterEach
    void tearDown() {
        Dungeon.level  = null;
        Dungeon.heroes = null;
        Dungeon.hero   = null;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static ArrayList<Hero> party(int count) {
        ArrayList<Hero> heroes = new ArrayList<>();
        for (int i = 0; i < count; i++) heroes.add(new Hero());
        return heroes;
    }

    /** Chebyshev distance on a W-wide grid */
    private static int dist(int a, int b) {
        int ax = a % W, ay = a / W;
        int bx = b % W, by = b / W;
        return Math.max(Math.abs(ax - bx), Math.abs(ay - by));
    }

    // -------------------------------------------------------------------------
    // Single hero — no-op (loop body never executes for i=1..N with N=1)
    // -------------------------------------------------------------------------

    @Nested
    class SingleHero {

        @Test
        void singleHero_nothingPlaced_hero0Unchanged() {
            ArrayList<Hero> heroes = party(1);
            heroes.get(0).pos = ENTRANCE;
            Dungeon.placeHeroesNearEntrance(level, ENTRANCE, heroes);
            assertEquals(ENTRANCE, heroes.get(0).pos,
                    "Single hero must stay at entrance — no other heroes to place");
        }
    }

    // -------------------------------------------------------------------------
    // Two heroes
    // -------------------------------------------------------------------------

    @Nested
    class TwoHeroes {

        @Test
        void hero1_placedAdjacentToEntrance() {
            ArrayList<Hero> heroes = party(2);
            heroes.get(0).pos = ENTRANCE;
            Dungeon.placeHeroesNearEntrance(level, ENTRANCE, heroes);
            assertEquals(1, dist(ENTRANCE, heroes.get(1).pos),
                    "hero[1] must be adjacent (distance 1) to the entrance");
        }

        @Test
        void hero0_notMovedFromEntrance() {
            ArrayList<Hero> heroes = party(2);
            heroes.get(0).pos = ENTRANCE;
            Dungeon.placeHeroesNearEntrance(level, ENTRANCE, heroes);
            assertEquals(ENTRANCE, heroes.get(0).pos,
                    "hero[0] must remain at the entrance cell");
        }

        @Test
        void heroes_doNotShareCell() {
            ArrayList<Hero> heroes = party(2);
            heroes.get(0).pos = ENTRANCE;
            Dungeon.placeHeroesNearEntrance(level, ENTRANCE, heroes);
            assertNotEquals(heroes.get(0).pos, heroes.get(1).pos,
                    "hero[0] and hero[1] must be on different cells");
        }

        @Test
        void hero1_onPassableCell() {
            ArrayList<Hero> heroes = party(2);
            heroes.get(0).pos = ENTRANCE;
            Dungeon.placeHeroesNearEntrance(level, ENTRANCE, heroes);
            assertTrue(level.passable[heroes.get(1).pos],
                    "hero[1] must land on a passable cell");
        }
    }

    // -------------------------------------------------------------------------
    // Three heroes — regression for the original bug
    // -------------------------------------------------------------------------

    @Nested
    class ThreeHeroes {

        @Test
        void allHeroes_onDistinctCells() {
            ArrayList<Hero> heroes = party(3);
            heroes.get(0).pos = ENTRANCE;
            Dungeon.placeHeroesNearEntrance(level, ENTRANCE, heroes);

            HashSet<Integer> positions = new HashSet<>();
            for (Hero h : heroes) positions.add(h.pos);

            assertEquals(3, positions.size(),
                    "All 3 heroes must be on distinct cells (original bug: all landed at same cell)");
        }

        @Test
        void heroes1and2_adjacentToEntrance() {
            ArrayList<Hero> heroes = party(3);
            heroes.get(0).pos = ENTRANCE;
            Dungeon.placeHeroesNearEntrance(level, ENTRANCE, heroes);

            assertEquals(1, dist(ENTRANCE, heroes.get(1).pos),
                    "hero[1] must be adjacent to entrance");
            assertEquals(1, dist(ENTRANCE, heroes.get(2).pos),
                    "hero[2] must be adjacent to entrance");
        }

        @Test
        void allHeroes_onPassableCells() {
            ArrayList<Hero> heroes = party(3);
            heroes.get(0).pos = ENTRANCE;
            Dungeon.placeHeroesNearEntrance(level, ENTRANCE, heroes);

            for (int i = 0; i < heroes.size(); i++) {
                assertTrue(level.passable[heroes.get(i).pos],
                        "hero[" + i + "] must be on a passable cell");
            }
        }
    }

    // -------------------------------------------------------------------------
    // Four heroes — full party
    // -------------------------------------------------------------------------

    @Nested
    class FourHeroes {

        @Test
        void allFourHeroes_onDistinctCells() {
            ArrayList<Hero> heroes = party(4);
            heroes.get(0).pos = ENTRANCE;
            Dungeon.placeHeroesNearEntrance(level, ENTRANCE, heroes);

            HashSet<Integer> positions = new HashSet<>();
            for (Hero h : heroes) positions.add(h.pos);

            assertEquals(4, positions.size(),
                    "All 4 heroes must be on distinct cells");
        }

        @Test
        void heroes1to3_adjacentToEntrance() {
            ArrayList<Hero> heroes = party(4);
            heroes.get(0).pos = ENTRANCE;
            Dungeon.placeHeroesNearEntrance(level, ENTRANCE, heroes);

            for (int i = 1; i < heroes.size(); i++) {
                assertEquals(1, dist(ENTRANCE, heroes.get(i).pos),
                        "hero[" + i + "] must be adjacent to entrance");
            }
        }

        @Test
        void allFourHeroes_onPassableCells() {
            ArrayList<Hero> heroes = party(4);
            heroes.get(0).pos = ENTRANCE;
            Dungeon.placeHeroesNearEntrance(level, ENTRANCE, heroes);

            for (int i = 0; i < heroes.size(); i++) {
                assertTrue(level.passable[heroes.get(i).pos],
                        "hero[" + i + "] must be on a passable cell");
            }
        }
    }

    // -------------------------------------------------------------------------
    // Constrained layout — some neighbours are walls
    // -------------------------------------------------------------------------

    @Nested
    class ConstrainedLayout {

        @Test
        void singlePassableNeighbour_extraHeroesStackAtEntrance() {
            // Block all neighbours except one (cell to the right = ENTRANCE+1)
            java.util.Arrays.fill(level.passable, false);
            level.passable[ENTRANCE]     = true;
            level.passable[ENTRANCE + 1] = true;
            // ENTRANCE - 1 = left, blocked; all others blocked

            ArrayList<Hero> heroes = party(3);
            heroes.get(0).pos = ENTRANCE;
            Dungeon.placeHeroesNearEntrance(level, ENTRANCE, heroes);

            // hero[1] takes the one free neighbour
            assertEquals(ENTRANCE + 1, heroes.get(1).pos);
            // hero[2] has no free neighbour → falls back to entrance
            assertEquals(ENTRANCE, heroes.get(2).pos);
            // hero[0] is still at entrance
            assertEquals(ENTRANCE, heroes.get(0).pos);
        }

        @Test
        void entranceAtCorner_heroesStayWithinBounds() {
            // Entrance at cell 6 (top-left inner corner of 5×5 grid)
            int cornerEntrance = 6; // x=1, y=1
            ArrayList<Hero> heroes = party(4);
            heroes.get(0).pos = cornerEntrance;
            Dungeon.placeHeroesNearEntrance(level, cornerEntrance, heroes);

            for (Hero h : heroes) {
                assertTrue(h.pos >= 0 && h.pos < level.length(),
                        "Hero position must be within level bounds");
                assertTrue(level.passable[h.pos],
                        "Hero must be on a passable cell");
            }
        }
    }

    // -------------------------------------------------------------------------
    // Original far-apart behavior — what happened before heroesNeedInitialPlacement
    // was set on each stair descent (commit ad0ac2749 fixed this)
    // -------------------------------------------------------------------------

    @Nested
    class OriginalFarApartBehavior {

        @Test
        void withoutPlacement_heroesKeepStalePositions() {
            // Before ad0ac2749, heroesNeedInitialPlacement was never set on stair
            // descent. Heroes kept their positions from the OLD floor. Those same
            // cell indices on the NEW floor map to completely different locations —
            // often far from the stairs and sometimes off-passable terrain.
            ArrayList<Hero> heroes = party(2);
            heroes.get(0).pos = ENTRANCE;
            heroes.get(1).pos = 24; // far corner of the 5x5 grid — stale "old floor" pos

            // NOT calling placeHeroesNearEntrance — simulates the old broken path
            // hero[1] keeps its stale position, far from the entrance

            int distanceApart = dist(heroes.get(0).pos, heroes.get(1).pos);
            assertTrue(distanceApart > 1,
                    "Without placement, heroes retain stale old-floor positions " +
                    "and are far apart (distance=" + distanceApart + ")");
        }

        @Test
        void withPlacement_heroesAreNearEntrance() {
            // After ad0ac2749 + the stacking fix: heroes land on distinct adjacent cells
            ArrayList<Hero> heroes = party(2);
            heroes.get(0).pos = ENTRANCE;
            heroes.get(1).pos = 24; // starts at stale far position

            Dungeon.placeHeroesNearEntrance(level, ENTRANCE, heroes);

            // Now hero[1] is adjacent to the entrance, not at the stale position
            assertEquals(1, dist(ENTRANCE, heroes.get(1).pos),
                    "After placement, hero[1] must be adjacent to entrance regardless of prior position");
            assertNotEquals(24, heroes.get(1).pos,
                    "Stale position must have been overwritten");
        }

        @Test
        void withPlacement_threeHeroesAllNearEntrance() {
            // Simulates the full party scenario: each hero starts at a stale
            // far-corner position and ends up adjacent to the entrance
            ArrayList<Hero> heroes = party(3);
            heroes.get(0).pos = ENTRANCE;
            heroes.get(1).pos = 0;  // top-left corner — stale
            heroes.get(2).pos = 24; // bottom-right corner — stale

            Dungeon.placeHeroesNearEntrance(level, ENTRANCE, heroes);

            for (int i = 1; i < heroes.size(); i++) {
                assertEquals(1, dist(ENTRANCE, heroes.get(i).pos),
                        "hero[" + i + "] must be adjacent to entrance after placement");
            }
        }
    }

    // -------------------------------------------------------------------------
    // Regression — proves the stacking bug (all heroes at same cell)
    // -------------------------------------------------------------------------

    @Nested
    class Regression {

        @Test
        void originalBug_threeHeroesMustNotAllLandOnSameCell() {
            // Before the fix: every hero saw all candidates as free because
            // Actor.findChar() always returned null (Actor.init() not yet called).
            // All heroes landed on the same first passable neighbour of the entrance.
            ArrayList<Hero> heroes = party(3);
            heroes.get(0).pos = ENTRANCE;
            Dungeon.placeHeroesNearEntrance(level, ENTRANCE, heroes);

            int p1 = heroes.get(1).pos;
            int p2 = heroes.get(2).pos;

            assertNotEquals(p1, p2,
                    "REGRESSION: hero[1] and hero[2] must NOT share a cell. " +
                    "Before the fix they both landed at cell " + p1 + ".");
        }

        @Test
        void originalBug_fourHeroesMustNotAllLandOnSameCell() {
            ArrayList<Hero> heroes = party(4);
            heroes.get(0).pos = ENTRANCE;
            Dungeon.placeHeroesNearEntrance(level, ENTRANCE, heroes);

            HashSet<Integer> distinctCells = new HashSet<>();
            for (int i = 1; i < heroes.size(); i++) distinctCells.add(heroes.get(i).pos);

            assertEquals(3, distinctCells.size(),
                    "REGRESSION: heroes[1..3] must each be on a distinct cell. " +
                    "Before the fix all three landed on the same cell.");
        }
    }
}
