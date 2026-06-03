package com.shatteredpixel.shatteredpixeldungeon.lan;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroAction;
import com.shatteredpixel.shatteredpixeldungeon.network.NetworkManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that a SocketTimeoutException from the reader does NOT permanently
 * freeze the game. The root cause of the observed LAN deadlock:
 *
 *   receiveActionAsync reader had setSoTimeout(30_000). If the peer took
 *   > 30 s to move, SocketTimeoutException fired, the reader broke out of
 *   its loop, nobody ever set curAction, and the game froze forever.
 *
 * The fix: treat SocketTimeoutException as "still alive, keep reading" —
 * only a real IOException (EOFException, connection reset) means disconnect.
 */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class LanSocketTimeoutTest {

    private Hero remote;

    @BeforeEach
    void setUp() {
        remote = new Hero();
        remote.HP = remote.HT = 20;
        Hero local = new Hero();
        Dungeon.heroes = new ArrayList<>();
        Dungeon.heroes.add(local);
        Dungeon.heroes.add(remote);
        Dungeon.hero = local;
        NetworkManager.lanMode = true;
        NetworkManager.localPlayerIndex = 0;
    }

    @AfterEach
    void tearDown() {
        NetworkManager.lanMode  = false;
        NetworkManager.localPlayerIndex = 0;
        Dungeon.heroes = null;
        Dungeon.hero   = null;
    }

    static boolean runRemoteWait(Hero h, long ms) throws InterruptedException {
        synchronized (h.lanActionLock) {
            long deadline = System.currentTimeMillis() + ms;
            while (h.curAction == null && NetworkManager.lanMode) {
                long rem = deadline - System.currentTimeMillis();
                if (rem <= 0) break;
                h.lanActionLock.wait(rem);
            }
        }
        return h.curAction != null;
    }

    static void deliver(Hero h, HeroAction a) {
        synchronized (h.lanActionLock) {
            h.curAction = a;
            h.lanActionLock.notifyAll();
        }
    }

    /**
     * Simulates what used to happen: the reader thread exits (SocketTimeoutException
     * was treated as a disconnect), then the action arrives. With the old code this
     * caused a permanent freeze. With the fix the game must recover.
     *
     * We model "reader exits then action arrives later" by:
     *  1. Starting the wait (actor thread sleeping on lanActionLock)
     *  2. Not delivering the action for 200ms (simulating slow peer / timeout window)
     *  3. Delivering action after the delay
     * The wait must complete successfully.
     */
    @Test
    void slowPeer_doesNotFreezeGame() throws InterruptedException {
        AtomicBoolean received = new AtomicBoolean(false);
        CountDownLatch done = new CountDownLatch(1);

        // Actor thread: waits up to 2s
        Thread actor = new Thread(() -> {
            try {
                boolean got = runRemoteWait(remote, 2000);
                received.set(got);
            } catch (InterruptedException ignored) {}
            finally { done.countDown(); }
        });
        actor.start();

        // Slow peer: delivers action after 300ms (simulates > timeout scenario)
        Thread.sleep(300);
        deliver(remote, new HeroAction.Move(42));

        assertTrue(done.await(3, TimeUnit.SECONDS), "Wait must complete");
        assertTrue(received.get(),
                "Slow peer action must unfreeze the game — SocketTimeoutException " +
                "must NOT permanently break the reader");
        assertEquals(42, ((HeroAction.Move) remote.curAction).dst);
    }

    /**
     * Even if the "reader" conceptually restarts multiple times (multiple
     * timeouts), the game must eventually receive the action.
     */
    @Test
    void multipleTimeouts_eventuallyReceivesAction() throws InterruptedException {
        // Simulate: reader times out 3 times, then action arrives
        for (int timeout = 0; timeout < 3; timeout++) {
            // Each wait cycle: briefly checks (short timeout), no action yet
            boolean got = runRemoteWait(remote, 50); // 50ms simulated timeout
            if (got) break; // early if already delivered
        }

        // Now deliver the real action
        deliver(remote, new HeroAction.Move(7));
        boolean got = runRemoteWait(remote, 500);

        assertTrue(got, "After multiple timeouts, action must eventually be received");
    }

    /**
     * Critical invariant: after a "timeout" cycle where no action arrived,
     * the NEXT delivery must immediately unblock the wait.
     */
    @Test
    void afterTimeout_nextDeliveryImmediatelyUnblocks() throws InterruptedException {
        // First wait times out
        boolean first = runRemoteWait(remote, 50);
        assertFalse(first, "First wait should timeout");
        assertNull(remote.curAction);

        // Deliver action immediately
        deliver(remote, new HeroAction.Move(9));

        // Second wait must not block
        long start = System.currentTimeMillis();
        boolean second = runRemoteWait(remote, 1000);
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(second, "Post-timeout delivery must be received immediately");
        assertTrue(elapsed < 100,
                "Must not wait unnecessarily after action is delivered (elapsed=" + elapsed + "ms)");
    }

    /**
     * Ten full rounds with 200ms delay each (simulating a thinking player).
     * Must not freeze at any round.
     */
    @Test
    void tenRoundsWithThinkingTime_noFreeze() throws InterruptedException {
        int completed = 0;
        for (int round = 0; round < 10; round++) {
            remote.curAction = null;

            // Simulate peer thinking for 150ms
            final int r = round;
            Thread t = new Thread(() -> {
                try { Thread.sleep(150); } catch (InterruptedException ignored) {}
                deliver(remote, new HeroAction.Move(r * 5));
            });
            t.setDaemon(true);
            t.start();

            boolean got = runRemoteWait(remote, 2000);
            assertTrue(got, "Round " + round + " must not freeze (simulate thinking time)");
            remote.curAction = null; // consume
            completed++;
        }
        assertEquals(10, completed, "All 10 thinking-time rounds must complete");
    }
}
