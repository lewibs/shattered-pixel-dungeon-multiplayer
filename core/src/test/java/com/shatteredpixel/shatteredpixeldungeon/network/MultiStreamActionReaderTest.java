package com.shatteredpixel.shatteredpixeldungeon.network;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroAction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Flow 4: multiStreamActionReader
 * EC-6.7
 */
class MultiStreamActionReaderTest {

    private Hero hostHero;
    private Hero hero1;
    private Hero hero2;

    @BeforeEach
    void setUp() {
        hostHero = new Hero();
        hero1 = new Hero();
        hero2 = new Hero();
        Dungeon.heroes = new ArrayList<>();
        Dungeon.heroes.add(hostHero); // index 0 — local host hero
        Dungeon.heroes.add(hero1);   // index 1 — client 1 → reads from ins.get(0)
        Dungeon.heroes.add(hero2);   // index 2 — client 2 → reads from ins.get(1)
        Dungeon.hero = hostHero;

        NetworkManager.lanMode = false;
        NetworkManager.setIsHostForTesting(false);
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
     * EC-6.7: Hero at index 1 (hero1) reads from ins.get(0).
     * Send an ACTION packet on stream 0 and verify hero1 receives it within 2 seconds.
     */
    @Test
    void threePlayerGame_hero1ReceivesFromStream0() throws Exception {
        // Plan path: hero1-reads-from-stream0
        // Arrange: piped stream for ins.get(0), one dead stream for ins.get(1)
        PipedOutputStream pipe0Out = new PipedOutputStream();
        PipedInputStream pipe0In = new PipedInputStream(pipe0Out, 256);
        DataInputStream stream0 = new DataInputStream(pipe0In);
        DataOutputStream stream0Writer = new DataOutputStream(pipe0Out);

        // Dead stream for slot 1 (hero2's stream)
        PipedOutputStream pipe1Out = new PipedOutputStream();
        PipedInputStream pipe1In = new PipedInputStream(pipe1Out, 256);
        DataInputStream stream1 = new DataInputStream(pipe1In);

        // Inject two host streams: ins.get(0) for hero1, ins.get(1) for hero2
        NetworkManager.setIsHostForTesting(true);
        // We need to inject two streams into ins; use the existing test seam to inject the first
        // and a second injection for the pair
        NetworkManager.injectHostStreamsForTesting(stream0, new DataOutputStream(new ByteArrayOutputStream()));
        // Add second stream manually — need a second seam
        // Workaround: inject stream1 as a second element of ins
        NetworkManager.setClientInputStreamForTesting(stream1); // this replaces ins[0] but we need two

        // Actually, the cleanest approach: test via per-stream guard rather than real pipe
        // Hero index 1 should use streamIndex = heroIndex-1 = 0; verify per-stream guard is set
        NetworkManager.lanMode = true;

        // Reset and inject properly: two streams
        NetworkManager.injectHostStreamsForTesting(stream0, new DataOutputStream(new ByteArrayOutputStream()));
        // We need to add stream1 for hero2. Use clearClientInputStreamsForTesting + manual add
        // For this test, just verify the correct stream index calculation
        int heroIndex1 = Dungeon.heroes.indexOf(hero1); // should be 1
        int expectedStreamIndex = heroIndex1 - 1; // should be 0
        assertEquals(1, heroIndex1, "hero1 should be at index 1 in Dungeon.heroes");
        assertEquals(0, expectedStreamIndex, "hero1 should read from ins.get(0)");

        // Start receiver for hero1
        NetworkManager.receiveActionAsync(hero1);

        // Wait a bit, then send ACTION on stream0
        Thread.sleep(50);
        stream0Writer.writeByte(NetworkManager.PacketType.ACTION);
        stream0Writer.writeInt(0); // heroId
        stream0Writer.writeByte(NetworkManager.ActionType.MOVE);
        stream0Writer.writeInt(42); // targetPos
        stream0Writer.flush();

        // Wait for hero1 to receive action
        CountDownLatch received = new CountDownLatch(1);
        Thread watcher = new Thread(() -> {
            long deadline = System.currentTimeMillis() + 2000;
            while (System.currentTimeMillis() < deadline) {
                synchronized (hero1.lanActionLock) {
                    if (hero1.curAction != null) {
                        received.countDown();
                        return;
                    }
                }
                try { Thread.sleep(10); } catch (InterruptedException e) { return; }
            }
        });
        watcher.start();

        boolean gotAction = received.await(2500, TimeUnit.MILLISECONDS);
        NetworkManager.lanMode = false;
        watcher.interrupt();

        assertTrue(gotAction, "hero1 should receive ACTION within 2 seconds from ins.get(0)");
        assertNotNull(hero1.curAction, "hero1.curAction should be set");
        assertTrue(hero1.curAction instanceof HeroAction.Move,
                "Action should be a Move, got: " + (hero1.curAction != null ? hero1.curAction.getClass().getSimpleName() : "null"));
    }

    /**
     * EC-6.7: Hero at index 2 (hero2) reads from ins.get(1).
     * Verify correct stream index is computed.
     */
    @Test
    void threePlayerGame_hero2ReceivesFromStream1() throws Exception {
        // Plan path: hero2-reads-from-stream1
        // Verify index computation: hero2 at Dungeon.heroes index 2 → streamIndex = 2-1 = 1
        int heroIndex2 = Dungeon.heroes.indexOf(hero2); // should be 2
        int expectedStreamIndex = heroIndex2 - 1; // should be 1
        assertEquals(2, heroIndex2, "hero2 should be at index 2 in Dungeon.heroes");
        assertEquals(1, expectedStreamIndex, "hero2 should read from ins.get(1)");

        // Arrange: two piped streams
        PipedOutputStream pipe0Out = new PipedOutputStream();
        PipedInputStream pipe0In = new PipedInputStream(pipe0Out, 256);
        DataInputStream stream0 = new DataInputStream(pipe0In);

        PipedOutputStream pipe1Out = new PipedOutputStream();
        PipedInputStream pipe1In = new PipedInputStream(pipe1Out, 256);
        DataInputStream stream1 = new DataInputStream(pipe1In);
        DataOutputStream stream1Writer = new DataOutputStream(pipe1Out);

        NetworkManager.setIsHostForTesting(true);
        NetworkManager.lanMode = true;

        // Inject stream0 for hero1 and stream1 for hero2
        // Use test seam to add both streams
        NetworkManager.injectHostStreamsForTesting(stream0, new DataOutputStream(new ByteArrayOutputStream()));
        // We need stream1 in ins.get(1) — add it via the extra seam
        NetworkManager.addPeerForTesting(null, stream1, new DataOutputStream(new ByteArrayOutputStream()));

        // Start receiver for hero2
        NetworkManager.receiveActionAsync(hero2);

        // Send ACTION on stream1 (the correct stream for hero2)
        Thread.sleep(50);
        stream1Writer.writeByte(NetworkManager.PacketType.ACTION);
        stream1Writer.writeInt(0);
        stream1Writer.writeByte(NetworkManager.ActionType.MOVE);
        stream1Writer.writeInt(77);
        stream1Writer.flush();

        // Wait for hero2 to receive action
        CountDownLatch received = new CountDownLatch(1);
        Thread watcher = new Thread(() -> {
            long deadline = System.currentTimeMillis() + 2000;
            while (System.currentTimeMillis() < deadline) {
                synchronized (hero2.lanActionLock) {
                    if (hero2.curAction != null) {
                        received.countDown();
                        return;
                    }
                }
                try { Thread.sleep(10); } catch (InterruptedException e) { return; }
            }
        });
        watcher.start();

        boolean gotAction = received.await(2500, TimeUnit.MILLISECONDS);
        NetworkManager.lanMode = false;
        watcher.interrupt();

        assertTrue(gotAction, "hero2 should receive ACTION within 2 seconds from ins.get(1)");
        assertNotNull(hero2.curAction, "hero2.curAction should be set");
    }

    /**
     * EC-6.7: Per-stream guard allows both hero1 and hero2 readers to be active simultaneously.
     * The old global guard would block the second reader.
     */
    @Test
    void perStreamGuard_allowsConcurrentReaders() throws Exception {
        // Plan path: per-stream-concurrent-readers
        // Arrange: two streams — one blocked (no data), one blocked (no data)
        PipedOutputStream pipe0Out = new PipedOutputStream();
        PipedInputStream pipe0In = new PipedInputStream(pipe0Out, 256);
        DataInputStream stream0 = new DataInputStream(pipe0In);

        PipedOutputStream pipe1Out = new PipedOutputStream();
        PipedInputStream pipe1In = new PipedInputStream(pipe1Out, 256);
        DataInputStream stream1 = new DataInputStream(pipe1In);

        NetworkManager.setIsHostForTesting(true);
        NetworkManager.lanMode = true;

        NetworkManager.injectHostStreamsForTesting(stream0, new DataOutputStream(new ByteArrayOutputStream()));
        NetworkManager.addPeerForTesting(null, stream1, new DataOutputStream(new ByteArrayOutputStream()));

        // Start both readers
        NetworkManager.receiveActionAsync(hero1);
        Thread.sleep(30); // let reader1 start
        NetworkManager.receiveActionAsync(hero2);
        Thread.sleep(30); // let reader2 start

        // Both per-stream guards should be set (streams 0 and 1)
        boolean stream0GuardSet = NetworkManager.isActionReaderRunningForStream(0);
        boolean stream1GuardSet = NetworkManager.isActionReaderRunningForStream(1);

        // Cleanup
        NetworkManager.lanMode = false;
        pipe0Out.close();
        pipe1Out.close();

        assertTrue(stream0GuardSet, "Per-stream guard for stream 0 should be active (hero1's reader running)");
        assertTrue(stream1GuardSet, "Per-stream guard for stream 1 should be active (hero2's reader running)");
    }
}
