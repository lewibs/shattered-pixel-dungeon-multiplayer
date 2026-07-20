package com.shatteredpixel.shatteredpixeldungeon.network;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the duplicate-reader-thread bug, rewritten for the v3
 * leader-sequenced commit protocol.
 *
 * OLD BUG (v2): receiveActionAsync() spawned a NEW per-turn reader thread on
 * every call. On every turn where the remote hero had curAction == null,
 * Hero.act() called it again, creating multiple threads competing to read the
 * same DataInputStream — stream corruption and a frozen level.
 *
 * v3 makes this structurally impossible: readers are PERSISTENT (one per
 * socket, tracked in readerCovered), started idempotently by
 * ensureGameplayReaders() / receiveActionAsync(). Repeated calls never spawn a
 * second reader on the same stream. These tests assert that guarantee directly
 * by counting live "net-reader-*" threads.
 */
class ActionReaderSingletonTest {

    private Hero remoteHero;
    private PipedOutputStream feed;

    @BeforeEach
    void setUp() {
        remoteHero = new Hero();
        Dungeon.heroes = new ArrayList<>();
        Dungeon.heroes.add(new Hero()); // local (player 0)
        Dungeon.heroes.add(remoteHero); // remote (player 1)
        Dungeon.hero = Dungeon.heroes.get(0);

        NetworkManager.lanMode = false;
        NetworkManager.setIsHostForTesting(false);
        NetworkManager.resetActionReaderForTesting();
        NetworkManager.resetCommitProtocolState();
    }

    @AfterEach
    void tearDown() throws Exception {
        NetworkManager.lanMode = false;
        if (feed != null) { try { feed.close(); } catch (Exception ignored) {} feed = null; }
        // give any reader blocked on readByte() a moment to see EOF and exit
        awaitNetReaderCount(0, 1000);
        NetworkManager.setIsHostForTesting(false);
        NetworkManager.resetActionReaderForTesting();
        NetworkManager.resetCommitProtocolState();
        NetworkManager.injectClientStreamsForTesting(null, null);
        Dungeon.hero = null;
        Dungeon.heroes = null;
    }

    /** Inject a live client input stream the test controls (writes via {@link #feed}). */
    private void injectClientPipe() throws Exception {
        feed = new PipedOutputStream();
        PipedInputStream pipe = new PipedInputStream(feed, 8192);
        NetworkManager.injectClientStreamsForTesting(
                new DataInputStream(pipe),
                new DataOutputStream(new java.io.ByteArrayOutputStream()));
    }

    /** CONTRACT: in solo mode receiveActionAsync must be a no-op — no reader starts. */
    @Test
    void soloMode_startsNoReader() throws Exception {
        NetworkManager.lanMode = false;
        int before = countNetReaderThreads();
        NetworkManager.receiveActionAsync(remoteHero);
        Thread.sleep(50);
        assertEquals(before, countNetReaderThreads(),
                "no reader thread may start when lanMode is false");
    }

    /** CONTRACT: the first LAN-mode call starts exactly one persistent reader. */
    @Test
    void firstCall_startsPersistentReader() throws Exception {
        NetworkManager.lanMode = true;
        injectClientPipe();

        int before = countNetReaderThreads();
        NetworkManager.receiveActionAsync(remoteHero);

        assertTrue(awaitNetReaderCount(before + 1, 2000),
                "exactly one persistent reader must start after the first call");
    }

    /** CONTRACT: repeated calls never spawn a duplicate reader on the same stream. */
    @Test
    void repeatedCalls_spawnNoDuplicateReaders() throws Exception {
        NetworkManager.lanMode = true;
        injectClientPipe();

        NetworkManager.receiveActionAsync(remoteHero);
        assertTrue(awaitNetReaderCount(1, 2000), "one reader after first call");
        int afterFirst = countNetReaderThreads();

        // These mirror Hero.act() calling receiveActionAsync() every remote turn.
        NetworkManager.receiveActionAsync(remoteHero);
        NetworkManager.receiveActionAsync(remoteHero);
        NetworkManager.ensureGameplayReaders();
        Thread.sleep(80);

        assertEquals(afterFirst, countNetReaderThreads(),
                "repeated receiveActionAsync()/ensureGameplayReaders() calls must not " +
                "spawn a second reader on the same stream (persistent-reader singleton)");
    }

    // --------------------------------------------------------------------------
    // Helpers
    // --------------------------------------------------------------------------

    private static int countNetReaderThreads() {
        int n = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.isAlive() && t.getName().startsWith("net-reader-")) n++;
        }
        return n;
    }

    private static boolean awaitNetReaderCount(int expected, long maxMs) {
        long deadline = System.currentTimeMillis() + maxMs;
        while (countNetReaderThreads() != expected && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(10); } catch (InterruptedException e) { return false; }
        }
        return countNetReaderThreads() == expected;
    }
}
