package com.shatteredpixel.shatteredpixeldungeon.lan;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroAction;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.network.NetworkManager;
import com.watabou.utils.PathFinder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reproduces and verifies the fix for the LAN FOV interrupt freeze.
 *
 * Root cause (Hero.java line 909):
 *   fieldOfView = Dungeon.level.heroFOV;
 *
 * All heroes share the same heroFOV array reference. Dungeon.observe() only
 * updates heroFOV for Dungeon.hero (always hero[0] on P1's device). When
 * hero[1]'s act() runs:
 *
 *   1. Line 909: hero[1].fieldOfView = heroFOV  (hero[0]'s view — WRONG)
 *   2. Line 918: Dungeon.observe() updates heroFOV from hero[0]'s position
 *   3. Line 925: checkVisibleMobs() uses hero[1].fieldOfView == heroFOV
 *
 * If a mob is visible from hero[0]'s position but NOT from hero[1]'s position:
 *   fieldOfView[mob.pos] == true for hero[1] (wrong — hero[0]'s view)
 *   Mob not in hero[1].visibleEnemies → newMob = true
 *   interrupt() fires → curAction = null
 *   Hero[1] re-enters lanActionLock.wait() for P2's already-sent packet → DEADLOCK
 *
 * Fix: inside checkVisibleMobs(), for LAN remote heroes, recompute fieldOfView
 * from the hero's own position before checking visibility. This ensures only
 * mobs the hero genuinely encounters trigger interrupt().
 *
 * Level layout (10x5):
 *
 *   # # # # # # # # # #
 *   # . . . . . . . . #
 *   # H . M . W . R . #   H=hero0(21), M=mob(23), W=wall(25), R=hero1(27)
 *   # . . . . . . . . #
 *   # # # # # # # # # #
 *
 * hero0 at pos 21 (left of wall) — can see mob at 23.
 * Wall at pos 25 blocks LOS from hero1 to mob.
 * hero1 at pos 27 (right of wall) — cannot see mob at 23.
 *
 * heroFOV reflects hero0's view: heroFOV[23]=true (mob visible from hero0).
 *
 * BUG: hero1.fieldOfView = heroFOV → checkVisibleMobs sees mob → interrupt → curAction=null
 * FIX: checkVisibleMobs recomputes hero1's FOV → wall blocks mob → no interrupt → curAction preserved
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class LanFovInterruptFreezeTest {

    static final int W = 10;
    static final int H = 5;

    // Row 2 (y=2): cells 20-29
    static final int POS_HERO0 = 21;  // col 1 — left of wall
    static final int POS_MOB   = 23;  // col 3 — visible from hero0, blocked from hero1
    static final int POS_WALL  = 25;  // col 5 — LOS blocker
    static final int POS_HERO1 = 27;  // col 7 — right of wall

    static class MinimalLevel extends Level {
        MinimalLevel() {
            width       = W;
            height      = H;
            length      = W * H;
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

            transitions = new ArrayList<>();
            mobs        = new java.util.HashSet<>();
            heaps       = new com.watabou.utils.SparseArray<>();
            blobs       = new java.util.HashMap<>();
            plants      = new com.watabou.utils.SparseArray<>();
            traps       = new com.watabou.utils.SparseArray<>();
            customTiles = new ArrayList<>();
            customWalls = new ArrayList<>();

            // Inner cells passable and transparent
            for (int y = 1; y < H - 1; y++) {
                for (int x = 1; x < W - 1; x++) {
                    int cell = y * W + x;
                    passable[cell]    = true;
                    losBlocking[cell] = false;
                }
            }
            // Border cells block LOS
            for (int i = 0; i < length; i++) {
                if (!passable[i]) losBlocking[i] = true;
            }
            // Wall at POS_WALL blocks LOS from hero1 to mob
            passable[POS_WALL]    = false;
            solid[POS_WALL]       = true;
            losBlocking[POS_WALL] = true;

            PathFinder.setMapSize(W, H);
        }

        @Override protected boolean build()    { return true; }
        @Override protected void createMobs()  {}
        @Override protected void createItems() {}
        @Override public int entrance()        { return POS_HERO0; }
    }

    private Hero hero0;
    private Hero hero1;
    private Rat  mob;

    @BeforeEach
    void setUp() {
        PathFinder.setMapSize(W, H);

        hero0 = new Hero(); hero0.HP = hero0.HT = 20;
        hero1 = new Hero(); hero1.HP = hero1.HT = 20;

        Dungeon.heroes = new ArrayList<>();
        Dungeon.heroes.add(hero0);
        Dungeon.heroes.add(hero1);

        Dungeon.hero = hero0;
        NetworkManager.lanMode = true;
        NetworkManager.localPlayerIndex = 0;
        NetworkManager.setIsHostForTesting(true);

        Dungeon.level = new MinimalLevel();

        hero0.pos = POS_HERO0;
        hero1.pos = POS_HERO1;

        mob = new Rat();
        mob.pos = POS_MOB;
        // Mob.alignment = ENEMY by default

        Dungeon.level.mobs.add(mob);

        // heroFOV = hero0's view: mob at POS_MOB is visible
        Dungeon.level.heroFOV[POS_MOB]   = true;
        Dungeon.level.heroFOV[POS_HERO0] = true;
        // POS_HERO1 area is NOT in heroFOV (wall blocks hero0's view to the right too)

        // hero1 has a pending action (already received from P2)
        hero1.curAction = new HeroAction.Move(POS_HERO1 - 1);

        Actor.clear();
    }

    @AfterEach
    void tearDown() {
        NetworkManager.lanMode = false;
        NetworkManager.setIsHostForTesting(false);
        NetworkManager.localPlayerIndex = 0;
        Dungeon.heroes = null;
        Dungeon.hero   = null;
        Dungeon.level  = null;
        Actor.clear();
    }

    // =========================================================================
    // Helper: call checkVisibleMobs() safely in tests.
    //
    // Sets Dungeon.hero=null before the call so QuickSlotButton.autoAim returns
    // -1 immediately — avoiding NPE from TargetHealthIndicator.instance being
    // null in test environments. Restores Dungeon.hero after the call.
    // =========================================================================

    private void callCheckVisibleMobs(Hero h) {
        Dungeon.hero = null;
        h.checkVisibleMobs();
        Dungeon.hero = hero0;
    }

    // =========================================================================
    // Bug documentation: raw mechanics of the wrong-FOV interrupt.
    //
    // When the LAN fix in checkVisibleMobs() is bypassed (lanMode=false),
    // assigning heroFOV to hero1 causes checkVisibleMobs() to see the mob and
    // fire interrupt(). This permanently demonstrates the root cause.
    //
    // This test ALWAYS PASSES (it asserts the bug mechanics work as expected
    // even after the fix — the fix is gated on lanMode, so disabling lanMode
    // exposes the raw bug path).
    // =========================================================================

    @Test
    void bugMechanics_sharedHeroFovCausesInterruptWhenLanModeDisabled() {
        assertNotNull(hero1.curAction, "Precondition: hero1 has a pending action");

        // Assign heroFOV (hero0's view) to hero1 — the root cause
        hero1.fieldOfView = Dungeon.level.heroFOV;
        assertTrue(hero1.fieldOfView[POS_MOB],
                "heroFOV shows mob at POS_MOB (visible from hero0)");

        // Disable LAN mode to bypass the fix (fix is gated on NetworkManager.lanMode)
        NetworkManager.lanMode = false;

        // checkVisibleMobs() now uses the wrong heroFOV — sees mob as new — interrupt fires
        callCheckVisibleMobs(hero1);

        NetworkManager.lanMode = true; // restore

        // interrupt() cleared curAction — demonstrating the root cause
        assertNull(hero1.curAction,
                "BUG: interrupt() fires when hero1 uses hero0's heroFOV. " +
                "Hero1 would re-enter the LAN wait for a packet P2 already sent → deadlock.");
    }

    // =========================================================================
    // FAILING test (before fix) / PASSING test (after fix):
    //
    // In LAN mode, when hero1 is a remote hero, checkVisibleMobs() MUST NOT
    // fire interrupt() due to a mob visible in hero0's heroFOV but not hero1's.
    //
    // Without the fix: checkVisibleMobs() uses hero1.fieldOfView = heroFOV
    // (hero0's view) → mob visible → interrupt() → curAction=null → ASSERTION FAILS.
    //
    // With the fix: checkVisibleMobs() recomputes hero1's FOV from its position.
    // Wall at POS_WALL blocks mob from hero1's view → no interrupt → ASSERTION PASSES.
    // =========================================================================

    @Test
    void fix_lanRemoteHeroCheckVisibleMobsDoesNotInterruptWithWrongFov() {
        assertNotNull(hero1.curAction, "Precondition: hero1 has a pending action");

        // Simulate the buggy line 909 in Hero.act(): all heroes get heroFOV reference
        // (hero0's view). In LAN mode with the fix, checkVisibleMobs() should override
        // this with a per-hero FOV before checking visibility.
        hero1.fieldOfView = Dungeon.level.heroFOV;

        // heroFOV[POS_MOB] = true — mob appears visible through hero0's eyes
        assertTrue(hero1.fieldOfView[POS_MOB],
                "Precondition: heroFOV (wrongly assigned to hero1) shows mob at POS_MOB");

        // Call checkVisibleMobs() in LAN mode.
        // WITH THE FIX: the fix recomputes hero1's FOV from pos POS_HERO1.
        //   Wall at POS_WALL blocks LOS to mob at POS_MOB → fieldOfView[POS_MOB]=false
        //   → no interrupt → curAction preserved.
        // WITHOUT THE FIX: heroFOV is used directly → mob visible → interrupt() fires
        //   → curAction=null → assertion FAILS.
        callCheckVisibleMobs(hero1);

        // This assertion FAILS before the fix is applied.
        // The fix in checkVisibleMobs() recomputes hero1's per-hero FOV so the wall
        // blocks the mob, preventing the spurious interrupt.
        assertNotNull(hero1.curAction,
                "FAILS before fix: hero1.curAction was cleared by interrupt() because hero1 " +
                "used hero0's heroFOV and saw a mob that wasn't in its visibleEnemies. " +
                "Fix: checkVisibleMobs() recomputes per-hero FOV for remote heroes in LAN mode.");
    }

    // =========================================================================
    // Regression: LAN deadlock condition after spurious interrupt.
    //
    // When checkVisibleMobs() fires interrupt() for a remote hero that already
    // received its action packet:
    //   curAction = null → hero1 re-enters lanActionLock.wait()
    //   P2 already sent the packet and won't resend → permanent deadlock
    //
    // This test uses lanMode=false to bypass the fix and show the deadlock path.
    // It ALWAYS PASSES (documents that the bug creates the deadlock condition).
    // =========================================================================

    @Test
    void bugMechanics_afterInterrupt_remoteHeroEntersDeadlockWait() {
        // Bypass fix (lanMode=false) to trigger the bug
        NetworkManager.lanMode = false;
        hero1.fieldOfView = Dungeon.level.heroFOV;
        callCheckVisibleMobs(hero1);
        NetworkManager.lanMode = true;

        assertNull(hero1.curAction, "interrupt() cleared curAction (bug confirmed)");

        // LAN guard in Hero.act(): if (curAction == null) → receiveActionAsync() → wait
        // This check confirms the deadlock condition:
        boolean wouldDeadlock;
        synchronized (hero1.lanActionLock) {
            wouldDeadlock = (hero1.curAction == null && NetworkManager.lanMode);
        }
        assertTrue(wouldDeadlock,
                "BUG: curAction==null + lanMode==true → hero1 would wait for packet P2 " +
                "already sent and will not resend → permanent deadlock.");
    }

    // =========================================================================
    // Fix verification: with per-hero FOV, no deadlock.
    //
    // After the fix, checkVisibleMobs() does NOT fire interrupt() for hero1
    // (mob blocked by wall from hero1's position). curAction is preserved.
    // The LAN wait guard (curAction == null) is false → no deadlock.
    // =========================================================================

    @Test
    void fix_perHeroFov_noInterrupt_noDeadlock() {
        assertNotNull(hero1.curAction, "Precondition: hero1 has a pending action");

        // Simulate buggy line 909 — assign heroFOV to hero1
        hero1.fieldOfView = Dungeon.level.heroFOV;

        // Call checkVisibleMobs() in LAN mode — fix recomputes hero1's FOV
        callCheckVisibleMobs(hero1);

        assertNotNull(hero1.curAction,
                "FIX: curAction preserved after checkVisibleMobs(). " +
                "Per-hero FOV recomputation blocks mob from hero1's view (wall at POS_WALL), " +
                "preventing spurious interrupt().");

        boolean wouldDeadlock;
        synchronized (hero1.lanActionLock) {
            wouldDeadlock = (hero1.curAction == null && NetworkManager.lanMode);
        }
        assertFalse(wouldDeadlock,
                "FIX: curAction != null → hero1 does NOT enter lanActionLock.wait() → no deadlock.");
    }

    // =========================================================================
    // Verify wall actually blocks LOS from hero1 to mob.
    //
    // Sanity check: confirms that the level geometry is correct and that
    // updateFieldOfView for hero1 at POS_HERO1 does NOT show the mob at POS_MOB
    // (the wall at POS_WALL blocks the line of sight).
    // =========================================================================

    @Test
    void levelGeometry_wallBlocksMobFromHero1Pos() {
        boolean[] hero1FOV = new boolean[Dungeon.level.length()];
        Dungeon.level.updateFieldOfView(hero1, hero1FOV);

        assertFalse(hero1FOV[POS_MOB],
                "Level geometry: wall at POS_WALL must block LOS from hero1(POS_HERO1) " +
                "to mob(POS_MOB). Fix depends on this property for correctness.");
        assertTrue(hero1FOV[POS_HERO1],
                "hero1 must be able to see its own cell.");
        assertFalse(hero1FOV[POS_HERO0],
                "hero1 must NOT see hero0's position (wall blocks that direction too).");
    }

    // =========================================================================
    // Verify heroFOV shows mob (hero0's view).
    //
    // Confirms that heroFOV[POS_MOB]=true (mob visible from hero0) — the
    // precondition that triggers the bug when hero1 uses the wrong FOV.
    // =========================================================================

    @Test
    void levelGeometry_heroFovShowsMobFromHero0Pos() {
        assertTrue(Dungeon.level.heroFOV[POS_MOB],
                "heroFOV must show mob at POS_MOB (visible from hero0 at POS_HERO0). " +
                "This is the precondition that triggers the bug.");
    }
}
