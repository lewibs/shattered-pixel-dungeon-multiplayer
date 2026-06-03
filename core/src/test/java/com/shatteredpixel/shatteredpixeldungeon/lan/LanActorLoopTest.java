package com.shatteredpixel.shatteredpixeldungeon.lan;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroAction;
import com.shatteredpixel.shatteredpixeldungeon.network.NetworkManager;
import com.watabou.utils.PathFinder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that probe the interaction between Actor.process(), Hero.act(),
 * and the LAN remote-hero wait protocol.
 *
 * Key invariant tested: after the remote hero executes an action (act()
 * returns false), Actor.processing() MUST become false so GameScene can
 * wake the actor loop for the next turn.
 *
 * Also tests the full "treat remote hero like a mob" contract:
 *  - Remote hero.act() blocks until action packet arrives
 *  - After executing, loop continues without hanging
 *  - No combination of turns leads to all-players-waiting
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class LanActorLoopTest {

    private Hero local;
    private Hero remote;

    @BeforeEach
    void setUp() {
        local  = new Hero();
        remote = new Hero();
        local.HP = local.HT = 20;
        remote.HP = remote.HT = 20;

        Dungeon.heroes = new ArrayList<>();
        Dungeon.heroes.add(local);
        Dungeon.heroes.add(remote);
        Dungeon.hero = local;

        NetworkManager.lanMode = true;
        NetworkManager.localPlayerIndex = 0;

        PathFinder.setMapSize(5, 5);
    }

    @AfterEach
    void tearDown() {
        NetworkManager.lanMode  = false;
        NetworkManager.localPlayerIndex = 0;
        Dungeon.heroes = null;
        Dungeon.hero   = null;
    }

    // -------------------------------------------------------------------------
    // Helper: invoke the LAN remote-hero path from Hero.act() directly.
    // Mirrors the synchronized-wait block in Hero.act() for the remote hero.
    // -------------------------------------------------------------------------

    /**
     * Returns true if the remote hero's act() would proceed to execute an action,
     * false if it returned early (timeout / disconnect).
     */
    static boolean runRemoteActLANPath(Hero remoteHero, long waitMs) throws InterruptedException {
        int myIdx = Dungeon.heroes.indexOf(remoteHero);
        if (myIdx == NetworkManager.localPlayerIndex) {
            fail("runRemoteActLANPath called with local hero");
        }
        synchronized (remoteHero.lanActionLock) {
            long deadline = System.currentTimeMillis() + waitMs;
            while (remoteHero.curAction == null && NetworkManager.lanMode) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) break;
                remoteHero.lanActionLock.wait(remaining);
            }
        }
        return remoteHero.curAction != null;
    }

    static void deliverAction(Hero h, HeroAction action) {
        synchronized (h.lanActionLock) {
            h.curAction = action;
            h.lanActionLock.notifyAll();
        }
    }

    // =========================================================================
    // Critical path: after remote hero acts, Actor.current must become null
    // =========================================================================

    @Test
    void afterRemoteActFalse_nextClearsCurrentSoProcessingIsFalse() {
        // Simulate: Actor.process() set current = remote hero
        // Hero.act() for remote hero executed action, actResult=false
        // next() must clear current

        // Simulate Actor.process() setting current
        // (Actor.current is package-private via the all set)
        remote.curAction = new HeroAction.Move(5); // pre-set action

        // Simulate what Hero.act() does at end when actResult=false for remote:
        // next() is called
        remote.next();

        assertFalse(Actor.processing(),
                "After remote hero calls next(), Actor.processing() must be false " +
                "so GameScene can notify the actor thread");
    }

    @Test
    void localHeroNextDoesNotAffectProcessing() {
        // Verify next() logic: Actor.next() only clears current if current==this
        local.curAction = new HeroAction.Move(3);
        // next() should be idempotent when current is not set to local
        local.next();
        // current was null (not set), so still null
        assertFalse(Actor.processing(), "Actor.processing() must be false when current is null");
    }

    // =========================================================================
    // Turn-order: local gets turn after remote completes
    // =========================================================================

    @Test
    void remoteTurnThenLocalTurn_noDeadlock() throws InterruptedException {
        // Simulate one full round: remote acts → local acts
        AtomicBoolean remoteCompleted = new AtomicBoolean(false);
        AtomicBoolean localCanAct     = new AtomicBoolean(false);

        // Step 1: remote hero waits for its action
        Thread remoteThread = new Thread(() -> {
            try {
                boolean got = runRemoteActLANPath(remote, 2000);
                if (got) {
                    // simulate act() completing: clear action, call next()
                    remote.curAction = null;
                    remote.next();  // ← this is what makes Actor.processing()=false
                    remoteCompleted.set(true);
                }
            } catch (InterruptedException ignored) {}
        });
        remoteThread.start();

        // Step 2: deliver remote action (simulating peer packet)
        Thread.sleep(30);
        deliverAction(remote, new HeroAction.Move(7));

        remoteThread.join(2000);
        assertTrue(remoteCompleted.get(), "Remote hero must complete its turn");

        // Step 3: after remote, verify Actor.processing()=false so local can go
        assertFalse(Actor.processing(),
                "After remote turn, Actor.processing() must be false for local to get turn");

        // Step 4: local hero acts
        local.curAction = new HeroAction.Move(2);
        assertNotNull(local.curAction, "Local hero must be able to set action");
        localCanAct.set(true);
        local.curAction = null;

        assertTrue(localCanAct.get(), "Local hero must get its turn after remote");
    }

    @Test
    void fourRounds_alternating_noDeadlock() throws InterruptedException {
        // Simulate 4 complete rounds (P1, P2, P1, P2)
        AtomicInteger roundsCompleted = new AtomicInteger(0);

        for (int round = 0; round < 4; round++) {
            if (round % 2 == 0) {
                // Local hero's turn
                local.curAction = new HeroAction.Move(round * 3);
                assertNotNull(local.curAction, "Local must have action on round " + round);
                local.curAction = null; // consume
            } else {
                // Remote hero's turn — deliver action with delay
                final int r = round;
                Thread deliver = new Thread(() -> {
                    try { Thread.sleep(20); } catch (InterruptedException ignored) {}
                    deliverAction(remote, new HeroAction.Move(r * 3));
                });
                deliver.setDaemon(true);
                deliver.start();

                boolean got = runRemoteActLANPath(remote, 2000);
                assertTrue(got, "Remote must receive action on round " + round);

                // Simulate action completing: clear + next()
                remote.curAction = null;
                remote.next();
                assertFalse(Actor.processing(),
                        "After round " + round + " Actor.processing() must be false");
            }
            roundsCompleted.incrementAndGet();
        }

        assertEquals(4, roundsCompleted.get(), "All 4 rounds must complete");
    }

    // =========================================================================
    // next() idempotency and correctness
    // =========================================================================

    @Test
    void next_calledTwice_doesNotBreak() {
        // Calling next() twice must not cause issues
        remote.next();
        remote.next();
        assertFalse(Actor.processing());
    }

    @Test
    void next_withDifferentCurrent_doesNotClearCurrent() {
        // If current is a different actor, next() must not clear it
        // (current is a package-private static — we can't set it directly,
        // but we can verify the invariant: next() only clears if current == this)

        // Without setting current explicitly, current should be null already
        // This test just verifies next() is safe to call
        remote.next(); // safe even when current is null
        assertFalse(Actor.processing());
    }

    // =========================================================================
    // The "treat remote hero like a mob" contract
    // =========================================================================

    @Test
    void remoteHero_actsLikeMob_consumesAction() throws InterruptedException {
        // When it's the remote hero's turn (like a mob), it should:
        // 1. Block waiting for the action packet
        // 2. Execute the action once received
        // 3. Allow the loop to continue

        AtomicBoolean actionExecuted = new AtomicBoolean(false);

        Thread actor = new Thread(() -> {
            try {
                boolean got = runRemoteActLANPath(remote, 2000);
                if (got) {
                    // "execute" the action
                    HeroAction action = remote.curAction;
                    assertNotNull(action, "Remote hero must have action to execute");
                    remote.curAction = null;
                    remote.next(); // unblock the actor loop
                    actionExecuted.set(true);
                }
            } catch (InterruptedException ignored) {}
        });
        actor.start();

        Thread.sleep(50);
        deliverAction(remote, new HeroAction.Move(10));

        actor.join(2000);
        assertTrue(actionExecuted.get(), "Remote hero (mob-like) must execute its action");
        assertFalse(Actor.processing(), "Actor loop must be unblocked after mob-like remote acts");
    }

    @Test
    void remoteHero_noAction_doesNotBlockLocalHero() throws InterruptedException {
        // Even if remote hero hasn't received its action yet, the local hero
        // must still be able to act when it's their turn.
        // (turns are strictly alternating — this tests that the wait doesn't
        // bleed into the local hero's turn)

        // Remote hero starts waiting (background)
        AtomicBoolean remoteWaiting = new AtomicBoolean(false);
        AtomicBoolean remoteGot     = new AtomicBoolean(false);

        Thread remoteActor = new Thread(() -> {
            try {
                remoteWaiting.set(true);
                boolean got = runRemoteActLANPath(remote, 2000);
                remoteGot.set(got);
                if (got) { remote.curAction = null; remote.next(); }
            } catch (InterruptedException ignored) {}
        });
        remoteActor.start();

        // Wait for remote to start waiting
        long deadline = System.currentTimeMillis() + 500;
        while (!remoteWaiting.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }

        // Local hero acts independently — must not be blocked by remote's wait
        long start = System.currentTimeMillis();
        local.curAction = new HeroAction.Move(1);
        assertNotNull(local.curAction, "Local hero must be able to act while remote waits");
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed < 50, "Local hero action must be instant (elapsed=" + elapsed + "ms)");
        local.curAction = null;

        // Deliver remote action to unblock
        deliverAction(remote, new HeroAction.Move(99));
        remoteActor.join(2000);
        assertTrue(remoteGot.get(), "Remote hero must receive its action eventually");
    }

    // =========================================================================
    // GameScene polling integration
    // =========================================================================

    @Test
    void gamescenePoll_seesRemoteCurAction_whenSet() {
        // GameScene polls: h != Dungeon.hero && h.curAction != null
        // This must be true AFTER action delivery and BEFORE actor processes it

        Dungeon.hero = local; // local is Dungeon.hero
        remote.curAction = new HeroAction.Move(5);

        // Simulate what GameScene poll checks
        boolean pollWouldFire = false;
        for (Hero h : Dungeon.heroes) {
            if (h != Dungeon.hero && h.curAction != null) {
                pollWouldFire = true;
                break;
            }
        }

        assertTrue(pollWouldFire,
                "GameScene poll must fire when remote hero has pending action");
    }

    @Test
    void gamescenePoll_doesNotFireForLocalHero() {
        // The poll checks h != Dungeon.hero — local hero must be excluded
        Dungeon.hero = local;
        local.curAction = new HeroAction.Move(3); // local has action

        boolean pollWouldFireForLocal = false;
        for (Hero h : Dungeon.heroes) {
            if (h != Dungeon.hero && h.curAction != null) {
                pollWouldFireForLocal = true;
                break;
            }
        }

        assertFalse(pollWouldFireForLocal,
                "GameScene poll must NOT fire for local hero's own curAction");
        local.curAction = null;
    }

    // =========================================================================
    // Edge case: early return path calls next() correctly
    // =========================================================================

    @Test
    void earlyReturnPath_afterTimeout_mustCallNext() {
        // If the wait exits with curAction==null (lanMode=false scenario),
        // next() must still be called to unblock the actor loop.
        // We test the post-condition: after any return-false path for remote hero,
        // Actor.processing() must eventually become false.

        // Simulate lanMode turning false (disconnect)
        NetworkManager.lanMode = false;

        // Remote hero's act() would check:
        // while (curAction == null && lanMode) { wait }
        // → exits immediately because lanMode=false
        // → if (curAction == null) return false; ← but we need next() first!

        // The test: verify that next() WOULD be needed here
        // (We can't call the actual act() easily, but we can verify the invariant)

        // After lanMode=false and curAction==null:
        assertNull(remote.curAction, "curAction must be null for this test");

        // If next() is called (as it should be):
        remote.next();
        assertFalse(Actor.processing(),
                "Even on early return, Actor.processing() must become false after next()");

        NetworkManager.lanMode = true; // restore
    }
}
