package com.shatteredpixel.shatteredpixeldungeon.network;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroAction;
import com.shatteredpixel.shatteredpixeldungeon.lan.LanTestProtocol;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Flow 4: multi-stream reader for 3+ player games (v3 leader-sequenced protocol).
 *
 * The leader runs ONE persistent reader per client stream. A stream at
 * ins.get(i) belongs to player slot i+1: a REQUEST arriving on it is sequenced
 * by the leader and dispatched (by the packet's player field) into
 * Dungeon.heroes.get(i+1)'s lanActionInbox. Both readers run concurrently — the
 * old global singleton guard that blocked the second reader is gone.
 */
class MultiStreamActionReaderTest {

    private Hero hostHero;
    private Hero hero1;
    private Hero hero2;

    private PipedOutputStream feed0, feed1;

    @BeforeEach
    void setUp() {
        hostHero = new Hero();
        hero1 = new Hero();
        hero2 = new Hero();
        Dungeon.heroes = new ArrayList<>();
        Dungeon.heroes.add(hostHero); // index 0 — local host hero (player 0)
        Dungeon.heroes.add(hero1);    // index 1 — client on stream 0 (player 1)
        Dungeon.heroes.add(hero2);    // index 2 — client on stream 1 (player 2)
        Dungeon.hero = hostHero;

        NetworkManager.lanMode = false;
        NetworkManager.setIsHostForTesting(false);
        NetworkManager.resetActionReaderForTesting();
        NetworkManager.resetCommitProtocolState();
    }

    @AfterEach
    void tearDown() {
        NetworkManager.lanMode = false;
        try { if (feed0 != null) feed0.close(); } catch (Exception ignored) {}
        try { if (feed1 != null) feed1.close(); } catch (Exception ignored) {}
        feed0 = feed1 = null;
        try { Thread.sleep(50); } catch (InterruptedException ignored) {}
        NetworkManager.setIsHostForTesting(false);
        NetworkManager.resetActionReaderForTesting();
        NetworkManager.resetCommitProtocolState();
        NetworkManager.injectHostStreamsForTesting(null, null);
        Dungeon.hero = null;
        Dungeon.heroes = null;
    }

    /** Inject two host input streams (slots 0 and 1) that the test feeds. */
    private void injectTwoStreams() throws Exception {
        feed0 = new PipedOutputStream();
        feed1 = new PipedOutputStream();
        DataInputStream s0 = new DataInputStream(new PipedInputStream(feed0, 512));
        DataInputStream s1 = new DataInputStream(new PipedInputStream(feed1, 512));
        NetworkManager.injectHostStreamsForTesting(s0, new DataOutputStream(new ByteArrayOutputStream()));
        NetworkManager.addPeerForTesting(null, s1, new DataOutputStream(new ByteArrayOutputStream()));
    }

    private static boolean awaitInbox(Hero h, long maxMs) throws InterruptedException {
        synchronized (h.lanActionLock) {
            long deadline = System.currentTimeMillis() + maxMs;
            while (h.lanActionInbox.isEmpty()) {
                long rem = deadline - System.currentTimeMillis();
                if (rem <= 0) break;
                h.lanActionLock.wait(rem);
            }
            return !h.lanActionInbox.isEmpty();
        }
    }

    private static int countNetReaderThreads() {
        int n = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.isAlive() && t.getName().startsWith("net-reader-")) n++;
        }
        return n;
    }

    /** A REQUEST on stream 0 is dispatched to hero1 (player 1). */
    @Test
    void threePlayerGame_hero1ReceivesFromStream0() throws Exception {
        NetworkManager.setIsHostForTesting(true);
        NetworkManager.lanMode = true;
        injectTwoStreams();

        NetworkManager.receiveActionAsync(hero1); // starts one reader per stream
        Thread.sleep(50);
        LanTestProtocol.writeActionRequest(new DataOutputStream(feed0), 1,
                NetworkManager.ActionType.MOVE, 42);

        assertTrue(awaitInbox(hero1, 2500), "hero1 must receive a commit from stream 0");
        NetworkManager.Commit c = hero1.lanActionInbox.peekFirst();
        assertNotNull(c);
        assertTrue(NetworkManager.decodeAction(c.actionType, c.targetPos) instanceof HeroAction.Move,
                "dispatched op must be a Move");
        assertEquals(42, NetworkManager.decodeAction(c.actionType, c.targetPos).dst);
        assertTrue(hero2.lanActionInbox.isEmpty(), "hero2 must not receive stream 0's op");
    }

    /** A REQUEST on stream 1 is dispatched to hero2 (player 2). */
    @Test
    void threePlayerGame_hero2ReceivesFromStream1() throws Exception {
        NetworkManager.setIsHostForTesting(true);
        NetworkManager.lanMode = true;
        injectTwoStreams();

        NetworkManager.receiveActionAsync(hero2);
        Thread.sleep(50);
        LanTestProtocol.writeActionRequest(new DataOutputStream(feed1), 1,
                NetworkManager.ActionType.MOVE, 77);

        assertTrue(awaitInbox(hero2, 2500), "hero2 must receive a commit from stream 1");
        NetworkManager.Commit c = hero2.lanActionInbox.peekFirst();
        assertNotNull(c);
        assertEquals(77, NetworkManager.decodeAction(c.actionType, c.targetPos).dst);
        assertTrue(hero1.lanActionInbox.isEmpty(), "hero1 must not receive stream 1's op");
    }

    /** Both client streams get their own persistent reader, running concurrently. */
    @Test
    void perStream_concurrentReaders() throws Exception {
        NetworkManager.setIsHostForTesting(true);
        NetworkManager.lanMode = true;
        injectTwoStreams();

        int before = countNetReaderThreads();
        NetworkManager.receiveActionAsync(hero1); // ensureGameplayReaders covers all ins streams
        Thread.sleep(80);

        assertEquals(before + 2, countNetReaderThreads(),
                "the leader must run one persistent reader per client stream, both concurrent " +
                "(the old global singleton guard would have allowed only one)");
    }
}
