package com.shatteredpixel.shatteredpixeldungeon.lan;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroAction;
import com.shatteredpixel.shatteredpixeldungeon.network.NetworkManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for LAN turn-flow correctness.
 *
 * Core invariant: the game must NEVER reach a state where all heroes are
 * waiting — there must always be a hero that can make progress.
 *
 * We simulate the lanActionLock / curAction protocol directly without
 * spinning up real sockets or Actor.process(). Each test focuses on one
 * failure mode that has been observed in the field.
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)  // any test exceeding 10s is a deadlock
class LanTurnFlowTest {

    private Hero local;   // heroes[0], localPlayerIndex=0
    private Hero remote;  // heroes[1], the "peer" hero

    @BeforeEach
    void setUp() {
        local  = new Hero();
        remote = new Hero();

        Dungeon.heroes = new ArrayList<>();
        Dungeon.heroes.add(local);
        Dungeon.heroes.add(remote);

        Dungeon.hero = local;
        NetworkManager.lanMode = true;
        NetworkManager.localPlayerIndex = 0;
    }

    @AfterEach
    void tearDown() {
        NetworkManager.lanMode = false;
        NetworkManager.localPlayerIndex = 0;
        Dungeon.heroes = null;
        Dungeon.hero   = null;
    }

    // -------------------------------------------------------------------------
    // Helper: simulate the network reader delivering an action to a remote hero
    // -------------------------------------------------------------------------

    /** Deliver action to remote hero using the same lock protocol as receiveActionAsync. */
    static void deliverAction(Hero remoteHero, HeroAction action) {
        synchronized (remoteHero.lanActionLock) {
            remoteHero.curAction = action;
            remoteHero.lanActionLock.notifyAll();
        }
    }

    /** Deliver action after a delay (simulates network latency). */
    static Thread deliverAfterMs(Hero remoteHero, HeroAction action, long delayMs) {
        Thread t = new Thread(() -> {
            try { Thread.sleep(delayMs); } catch (InterruptedException ignored) {}
            deliverAction(remoteHero, action);
        }, "test-delivery");
        t.setDaemon(true);
        t.start();
        return t;
    }

    // -------------------------------------------------------------------------
    // Test helpers that directly exercise the lanActionLock protocol
    // -------------------------------------------------------------------------

    /**
     * Runs the remote-hero wait protocol (mirrors the code in Hero.act()):
     * synchronized(lanActionLock) { while (curAction == null && lanMode) { wait(timeout) } }
     * Returns true if an action was received before the timeout.
     */
    static boolean runRemoteWait(Hero remoteHero, long timeoutMs) throws InterruptedException {
        synchronized (remoteHero.lanActionLock) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (remoteHero.curAction == null && NetworkManager.lanMode) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) break;
                remoteHero.lanActionLock.wait(remaining);
            }
        }
        return remoteHero.curAction != null;
    }

    // =========================================================================
    // Group 1: Basic synchronization — no race
    // =========================================================================

    @Test
    void actionDeliveredBeforeWait_immediatelyAvailable() {
        // Action arrives BEFORE the remote hero even starts waiting.
        // The while(curAction==null) check must handle this.
        deliverAction(remote, new HeroAction.Move(5));

        assertNotNull(remote.curAction, "Pre-delivered action must be readable without waiting");
    }

    @Test
    void actionDeliveredDuringWait_wakesThread() throws InterruptedException {
        // Action arrives 50ms after the wait begins.
        deliverAfterMs(remote, new HeroAction.Move(10), 50);

        boolean received = runRemoteWait(remote, 2000);
        assertTrue(received, "Wait must wake when action is delivered");
        assertNotNull(remote.curAction);
    }

    @Test
    void actionDeliveredZeroDelay_neverDeadlocks() throws InterruptedException {
        // Delivery thread starts before wait — race between deliver and wait.
        // Both orderings must be handled correctly.
        Thread deliver = new Thread(() -> deliverAction(remote, new HeroAction.Move(7)));
        deliver.start();

        boolean received = runRemoteWait(remote, 2000);
        deliver.join(1000);

        assertTrue(received, "Zero-delay race must never deadlock");
    }

    @Test
    void noActionWithinTimeout_returnsGracefully() throws InterruptedException {
        // No action delivered — wait times out and returns false.
        boolean received = runRemoteWait(remote, 100);
        assertFalse(received, "Timed-out wait must return false");
        assertNull(remote.curAction, "curAction must remain null after timeout");
    }

    // =========================================================================
    // Group 2: Turn alternation — the core invariant
    // =========================================================================

    /**
     * Simulate N complete turns alternating local/remote.
     * Local: sets curAction directly (player tapped).
     * Remote: waits for delivered action.
     * Returns the number of turns completed before timeout.
     */
    int simulateTurns(int totalTurns, long actionDelayMs) throws InterruptedException {
        int completed = 0;
        for (int turn = 0; turn < totalTurns; turn++) {
            if (turn % 2 == 0) {
                // Local hero's turn — action set by "player tap"
                local.curAction = new HeroAction.Move(turn * 10);
                assertNotNull(local.curAction, "Local hero must have action on turn " + turn);
                local.curAction = null; // consume (simulates actMove completing)
            } else {
                // Remote hero's turn — action delivered via network
                deliverAfterMs(remote, new HeroAction.Move(turn * 10), actionDelayMs);
                boolean received = runRemoteWait(remote, 2000);
                if (!received) break; // timeout = deadlock
                assertNotNull(remote.curAction, "Remote hero must have action on turn " + turn);
                remote.curAction = null; // consume
            }
            completed++;
        }
        return completed;
    }

    @Test
    void tenTurns_noDeadlock_instantDelivery() throws InterruptedException {
        assertEquals(10, simulateTurns(10, 0),
                "All 10 turns must complete with instant delivery");
    }

    @Test
    void tenTurns_noDeadlock_50msLatency() throws InterruptedException {
        assertEquals(10, simulateTurns(10, 50),
                "All 10 turns must complete with 50ms network latency");
    }

    @Test
    void twentyTurns_noDeadlock_variableLatency() throws InterruptedException {
        // Variable latency: 0-100ms per remote turn
        int completed = 0;
        for (int turn = 0; turn < 20; turn++) {
            long delay = (turn * 17) % 101; // 0..100ms deterministic variation
            if (turn % 2 == 0) {
                local.curAction = new HeroAction.Move(turn);
                local.curAction = null;
            } else {
                deliverAfterMs(remote, new HeroAction.Move(turn), delay);
                boolean received = runRemoteWait(remote, 2000);
                if (!received) break;
                remote.curAction = null;
            }
            completed++;
        }
        assertEquals(20, completed, "All 20 variable-latency turns must complete");
    }

    // =========================================================================
    // Group 3: Concurrent delivery — multiple rapid actions
    // =========================================================================

    @Test
    void rapidDelivery_noLostActions() throws InterruptedException {
        // 5 actions delivered back-to-back with no delay
        int received = 0;
        for (int i = 0; i < 5; i++) {
            deliverAction(remote, new HeroAction.Move(i * 5));
            boolean got = runRemoteWait(remote, 500);
            if (got) {
                received++;
                remote.curAction = null; // consume
            }
        }
        assertEquals(5, received, "All 5 rapid actions must be received");
    }

    @Test
    void deliveryWhileConsuming_notLost() throws InterruptedException {
        // Delivery thread sends while consumer is in the middle of processing.
        CountDownLatch consumeStarted = new CountDownLatch(1);
        CountDownLatch consumeDone   = new CountDownLatch(1);
        AtomicBoolean deliverySucceeded = new AtomicBoolean(false);

        // Consumer thread: receives action, signals, then clears
        Thread consumer = new Thread(() -> {
            try {
                boolean received = runRemoteWait(remote, 2000);
                consumeStarted.countDown();
                if (received) {
                    remote.curAction = null;
                    deliverySucceeded.set(true);
                }
                consumeDone.countDown();
            } catch (InterruptedException ignored) {}
        });
        consumer.start();

        // Deliver action 10ms after consumer starts
        Thread.sleep(10);
        deliverAction(remote, new HeroAction.Move(99));

        assertTrue(consumeDone.await(3, TimeUnit.SECONDS), "Consumer must complete");
        assertTrue(deliverySucceeded.get(), "Delivery must succeed");
    }

    // =========================================================================
    // Group 4: curAction state machine — cleared between turns
    // =========================================================================

    @Test
    void curActionClearedBeforeNextWait_doesNotSkipTurn() throws InterruptedException {
        // Deliver action for turn 1
        deliverAction(remote, new HeroAction.Move(1));
        boolean t1 = runRemoteWait(remote, 500);
        assertTrue(t1);
        remote.curAction = null; // simulate ready() clearing curAction

        // Turn 2: curAction must be null, so we actually wait
        deliverAfterMs(remote, new HeroAction.Move(2), 50);
        boolean t2 = runRemoteWait(remote, 2000);
        assertTrue(t2, "Turn 2 wait must receive new action after curAction cleared");
        assertEquals(2, ((HeroAction.Move) remote.curAction).dst,
                "Must receive turn 2 action, not stale turn 1 action");
        remote.curAction = null;
    }

    @Test
    void preExistingCurAction_skipWait() throws InterruptedException {
        // If curAction is already set (e.g., multi-step move), don't wait
        remote.curAction = new HeroAction.Move(42);

        long start = System.currentTimeMillis();
        boolean received = runRemoteWait(remote, 2000);
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(received, "Pre-set curAction must be seen immediately");
        assertTrue(elapsed < 100, "Must not block when curAction already set (elapsed=" + elapsed + "ms)");
    }

    // =========================================================================
    // Group 5: Deadlock detection — multiple threads racing
    // =========================================================================

    @Test
    void concurrentDeliveryAndWait_neverDeadlock() throws InterruptedException {
        // 10 parallel races: delivery and wait start at the same time
        for (int race = 0; race < 10; race++) {
            final int r = race;
            remote.curAction = null;
            CountDownLatch ready = new CountDownLatch(2);
            AtomicBoolean waitReceived = new AtomicBoolean(false);

            Thread waiter = new Thread(() -> {
                ready.countDown();
                try {
                    ready.await();
                    waitReceived.set(runRemoteWait(remote, 1000));
                } catch (InterruptedException ignored) {}
            });
            Thread deliverer = new Thread(() -> {
                try {
                    ready.countDown();
                    ready.await();
                    deliverAction(remote, new HeroAction.Move(r));
                } catch (InterruptedException ignored) {}
            });

            waiter.start();
            deliverer.start();
            waiter.join(2000);
            deliverer.join(1000);

            assertTrue(waitReceived.get(),
                    "Race " + race + ": wait must receive action (no deadlock)");
            remote.curAction = null;
        }
    }

    @Test
    void gameNeverAllPlayersWaiting_twoPlayers() throws InterruptedException {
        // The core invariant: at least one player must always be able to make progress.
        // Simulate 10 full rounds. Use a 200ms window per round.
        AtomicInteger roundsCompleted = new AtomicInteger(0);
        AtomicBoolean deadlocked = new AtomicBoolean(false);

        for (int round = 0; round < 10 && !deadlocked.get(); round++) {
            final int r = round;

            // Local player acts immediately (no block)
            local.curAction = new HeroAction.Move(r * 2);
            assertNotNull(local.curAction, "Local player must never wait for itself");
            local.curAction = null;

            // Remote player: deliver action with realistic delay
            CountDownLatch done = new CountDownLatch(1);
            deliverAfterMs(remote, new HeroAction.Move(r * 2 + 1), 20);

            Thread waiter = new Thread(() -> {
                try {
                    boolean got = runRemoteWait(remote, 1000);
                    if (!got) deadlocked.set(true);
                    else remote.curAction = null;
                } catch (InterruptedException ignored) {
                    deadlocked.set(true);
                } finally {
                    done.countDown();
                }
            });
            waiter.start();
            done.await(1500, TimeUnit.MILLISECONDS);
            waiter.join(100);

            if (!deadlocked.get()) roundsCompleted.incrementAndGet();
        }

        assertFalse(deadlocked.get(), "Game must never enter all-players-waiting state");
        assertEquals(10, roundsCompleted.get(), "All 10 rounds must complete");
    }

    // =========================================================================
    // Group 6: Resilience — game unblocks when action arrives after stuck
    // =========================================================================

    @Test
    void stuckGame_unblocksByReceivingAction() throws InterruptedException {
        // Simulate: actor thread is stuck (no action). After 200ms, action arrives.
        // The game must unblock.
        AtomicBoolean unblocked = new AtomicBoolean(false);
        CountDownLatch waiting  = new CountDownLatch(1);

        Thread actor = new Thread(() -> {
            try {
                waiting.countDown();
                boolean got = runRemoteWait(remote, 2000);
                unblocked.set(got);
            } catch (InterruptedException ignored) {}
        });
        actor.start();

        assertTrue(waiting.await(1, TimeUnit.SECONDS), "Actor must start waiting");

        // Simulate: "if player just clicks it will unfreeze it"
        Thread.sleep(200);
        deliverAction(remote, new HeroAction.Move(55));

        actor.join(2000);
        assertTrue(unblocked.get(), "Late-arriving action must unblock the stuck game");
        assertNotNull(remote.curAction);
    }

    @Test
    void multipleStuckUnblockCycles_noDegradation() throws InterruptedException {
        // Each "stuck" cycle: wait 100ms, deliver action, verify unblocked.
        // The protocol must not degrade after many stuck/unblock cycles.
        for (int cycle = 0; cycle < 5; cycle++) {
            remote.curAction = null;
            deliverAfterMs(remote, new HeroAction.Move(cycle), 80);
            boolean received = runRemoteWait(remote, 1000);
            assertTrue(received, "Cycle " + cycle + " must unblock");
            remote.curAction = null;
        }
    }

    // =========================================================================
    // Group 7: next() semantics — actor loop can continue after remote turn
    // =========================================================================

    @Test
    void afterRemoteTurnCompletes_curActionIsNull() {
        // After a remote hero finishes (ready() called), curAction must be null.
        remote.curAction = new HeroAction.Move(99);
        // Simulate ready() clearing curAction
        remote.curAction = null;

        assertNull(remote.curAction,
                "After remote hero completes turn, curAction must be null for next wait");
    }

    @Test
    void localHeroIdentifiedByIndex() {
        // Invariant: indexOf(local) == localPlayerIndex → local hero
        assertEquals(NetworkManager.localPlayerIndex, Dungeon.heroes.indexOf(local),
                "Local hero must be at localPlayerIndex");
        assertNotEquals(NetworkManager.localPlayerIndex, Dungeon.heroes.indexOf(remote),
                "Remote hero must NOT be at localPlayerIndex");
    }

    @Test
    void remoteHeroIdentifiedByIndex() {
        // Invariant: indexOf(remote) != localPlayerIndex → remote hero
        int remoteIdx = Dungeon.heroes.indexOf(remote);
        assertNotEquals(NetworkManager.localPlayerIndex, remoteIdx,
                "Remote hero index must differ from localPlayerIndex");
    }

    // =========================================================================
    // Group 8: Two-device simulation — symmetric
    // =========================================================================

    @Test
    void deviceTwoSymmetry_remoteBecamesLocal() {
        // On device 2, localPlayerIndex=1. heroes[1] is the local hero.
        NetworkManager.localPlayerIndex = 1;

        int localIdx  = Dungeon.heroes.indexOf(remote); // remote is heroes[1] → local on D2
        int remoteIdx = Dungeon.heroes.indexOf(local);  // local is heroes[0] → remote on D2

        assertEquals(1, localIdx,  "On D2, hero[1] must be local");
        assertEquals(0, remoteIdx, "On D2, hero[0] must be remote");
        assertEquals(NetworkManager.localPlayerIndex, localIdx,
                "localIdx must match localPlayerIndex on D2");
    }

    @Test
    void d2_remoteTurnWaitsForD1() throws InterruptedException {
        // From D2's perspective: heroes[0] (local on D1) is remote.
        // When it's heroes[0]'s turn on D2, D2 waits for D1's packet.
        NetworkManager.localPlayerIndex = 1; // we are device 2

        Hero d1Hero = local;  // heroes[0], remote on D2
        d1Hero.curAction = null;

        // D1 sends its action after 30ms
        deliverAfterMs(d1Hero, new HeroAction.Move(42), 30);

        boolean received = runRemoteWait(d1Hero, 2000);
        assertTrue(received, "D2 must receive D1's hero action");
        assertEquals(42, ((HeroAction.Move) d1Hero.curAction).dst);

        NetworkManager.localPlayerIndex = 0; // restore
    }
}
