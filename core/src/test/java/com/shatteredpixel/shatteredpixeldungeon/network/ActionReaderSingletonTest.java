package com.shatteredpixel.shatteredpixeldungeon.network;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the duplicate-reader-thread bug:
 *
 * BUG: receiveActionAsync() spawned a new persistent reader thread on every
 * call but never checked whether one was already running. On every turn where
 * the remote hero had curAction == null, Hero.act() called receiveActionAsync()
 * again, creating a second (third, etc.) thread competing to read bytes from
 * the same DataInputStream. The resulting stream corruption manifested as
 * "Expected HASH packet, got 101 ('e')" errors and a frozen black-square level.
 *
 * FIX: actionReaderRunning flag must be set true before the thread starts and
 * checked at the top of receiveActionAsync(); a second call while the first
 * thread is active must be a no-op.
 */
class ActionReaderSingletonTest {

    private Hero remoteHero;

    @BeforeEach
    void setUp() {
        remoteHero = new Hero();
        Dungeon.heroes = new ArrayList<>();
        Dungeon.heroes.add(new Hero()); // local
        Dungeon.heroes.add(remoteHero);
        Dungeon.hero = Dungeon.heroes.get(0);

        NetworkManager.lanMode = false;
        NetworkManager.setIsHostForTesting(false);
        // Reset singleton guard so each test starts fresh
        NetworkManager.resetActionReaderForTesting();
    }

    @AfterEach
    void tearDown() {
        NetworkManager.lanMode = false;
        NetworkManager.setIsHostForTesting(false);
        NetworkManager.resetActionReaderForTesting();
        Dungeon.hero = null;
        Dungeon.heroes = null;
    }

    /**
     * CONTRACT: When lanMode is false, receiveActionAsync must be a no-op —
     * the flag must remain false so the guard does not erroneously block a
     * future real start.
     */
    @Test
    void soloMode_doesNotSetRunningFlag() {
        NetworkManager.lanMode = false;
        NetworkManager.receiveActionAsync(remoteHero);

        assertFalse(NetworkManager.isActionReaderRunning(),
                "actionReaderRunning must remain false in solo mode — " +
                "no reader thread should be started, and the flag must not be polluted");
    }

    /**
     * CONTRACT: The first call to receiveActionAsync() in LAN mode must set
     * actionReaderRunning = true before the thread starts (so a concurrent
     * second call sees the flag immediately).
     */
    @Test
    void firstCall_setsRunningFlag() {
        NetworkManager.lanMode = true;
        // We don't have a real socket, so the thread will fail immediately —
        // but the flag must be set synchronously before the thread is launched.
        NetworkManager.receiveActionAsync(remoteHero);

        assertTrue(NetworkManager.isActionReaderRunning(),
                "actionReaderRunning must be true immediately after the first call — " +
                "the flag must be set before Thread.start() so concurrent callers see it");

        // Cleanup: turn off lanMode so the thread (if it somehow got a stream) exits
        NetworkManager.lanMode = false;
    }

    /**
     * CONTRACT: A second call to receiveActionAsync() while the first thread is
     * active must be a no-op — it must NOT spawn a second reader thread.
     *
     * This is the core regression: previously, every Hero.act() invocation with
     * curAction == null spawned an additional reader thread, causing concurrent
     * reads that corrupted the packet stream.
     */
    @Test
    void secondCall_isNoOpWhenAlreadyRunning() throws InterruptedException {
        // Manually set the flag to simulate an already-running reader
        NetworkManager.lanMode = true;
        NetworkManager.setActionReaderRunningForTesting(true);

        // Count how many threads named "net-reader-action" are spawned
        AtomicInteger threadsBefore = countActionReaderThreads();

        // Second call — should be a no-op because the flag is already true
        NetworkManager.receiveActionAsync(remoteHero);

        // Give any erroneously-spawned thread time to appear
        Thread.sleep(50);

        AtomicInteger threadsAfter = countActionReaderThreads();

        assertEquals(threadsBefore.get(), threadsAfter.get(),
                "receiveActionAsync must NOT spawn a second thread when actionReaderRunning=true — " +
                "duplicate reader threads corrupt the packet stream (bytes from one thread's read " +
                "appear as packet-type bytes for the other, producing 'Expected HASH, got 101' errors)");

        NetworkManager.lanMode = false;
    }

    // --------------------------------------------------------------------------
    // Helpers
    // --------------------------------------------------------------------------

    private AtomicInteger countActionReaderThreads() {
        AtomicInteger count = new AtomicInteger(0);
        Thread.getAllStackTraces().keySet().forEach(t -> {
            if ("net-reader-action".equals(t.getName()) && t.isAlive()) {
                count.incrementAndGet();
            }
        });
        return count;
    }
}
