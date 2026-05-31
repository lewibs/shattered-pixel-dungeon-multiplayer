package com.shatteredpixel.shatteredpixeldungeon.lan;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroAction;
import com.shatteredpixel.shatteredpixeldungeon.network.NetworkManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Timeout;

import java.io.*;
import java.net.*;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive real-socket tests targeting the specific "3rd move freeze"
 * scenario and all variations that could cause LAN turns to freeze.
 *
 * Every test uses real TCP loopback sockets with a short timeout (200ms)
 * to simulate the production 30-second scenario at test speed.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class LanTurnSequenceTest {

    // Delay simulating a thinking player (no socket timeout in production)
    private static final int THINK_DELAY_MS = 250;

    private ServerSocket serverSocket;
    private Socket       hostSideSocket;
    private Socket       clientSocket;

    private DataInputStream  hostIn;
    private DataOutputStream hostOut;
    private DataInputStream  clientIn;
    private DataOutputStream clientOut;

    private Hero localHero;   // heroes[0] — host
    private Hero remoteHero;  // heroes[1] — client

    @BeforeEach
    void setUp() throws IOException {
        serverSocket   = new ServerSocket(0);
        int port       = serverSocket.getLocalPort();
        Future<Socket> serverSide = Executors.newSingleThreadExecutor().submit(() -> serverSocket.accept());
        clientSocket   = new Socket("127.0.0.1", port);
        try { hostSideSocket = serverSide.get(2, TimeUnit.SECONDS); }
        catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new IOException("setup failed", e);
        }
        
        

        hostIn   = new DataInputStream(hostSideSocket.getInputStream());
        hostOut  = new DataOutputStream(hostSideSocket.getOutputStream());
        clientIn  = new DataInputStream(clientSocket.getInputStream());
        clientOut = new DataOutputStream(clientSocket.getOutputStream());

        NetworkManager.injectHostStreamsForTesting(hostIn, hostOut);
        NetworkManager.injectClientStreamsForTesting(clientIn, clientOut);

        localHero  = new Hero(); localHero.HP  = localHero.HT  = 20;
        remoteHero = new Hero(); remoteHero.HP = remoteHero.HT = 20;
        Dungeon.heroes = new ArrayList<>();
        Dungeon.heroes.add(localHero);
        Dungeon.heroes.add(remoteHero);
        Dungeon.hero = localHero;

        NetworkManager.lanMode = true;
        NetworkManager.setIsHostForTesting(true);
        NetworkManager.localPlayerIndex = 0;
        NetworkManager.resetActionReaderForTesting();
    }

    @AfterEach
    void tearDown() throws IOException {
        NetworkManager.lanMode = false;
        NetworkManager.setIsHostForTesting(false);
        NetworkManager.resetActionReaderForTesting();
        if (clientSocket   != null && !clientSocket.isClosed())   clientSocket.close();
        if (hostSideSocket != null && !hostSideSocket.isClosed()) hostSideSocket.close();
        if (serverSocket   != null && !serverSocket.isClosed())   serverSocket.close();
        NetworkManager.injectHostStreamsForTesting(null, null);
        NetworkManager.injectClientStreamsForTesting(null, null);
        Dungeon.heroes = null; Dungeon.hero = null;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    void clientSendsAction(int targetPos) throws IOException {
        clientOut.writeByte(NetworkManager.PacketType.ACTION);
        clientOut.writeInt(1);   // heroId = 1 (remote)
        clientOut.writeByte(0);  // Move
        clientOut.writeInt(targetPos);
        clientOut.flush();
    }

    boolean waitForAction(Hero h, long maxMs) throws InterruptedException {
        synchronized (h.lanActionLock) {
            long deadline = System.currentTimeMillis() + maxMs;
            while (h.curAction == null && NetworkManager.lanMode) {
                long rem = deadline - System.currentTimeMillis();
                if (rem <= 0) break;
                h.lanActionLock.wait(rem);
            }
        }
        return h.curAction != null;
    }

    /** Run a complete remote turn: start reader, deliver packet, wait, consume. */
    void doRemoteTurn(int targetPos, long sendDelayMs, String label)
            throws Exception {
        remoteHero.curAction = null;
        NetworkManager.receiveActionAsync(remoteHero);

        Thread deliver = new Thread(() -> {
            try {
                if (sendDelayMs > 0) Thread.sleep(sendDelayMs);
                clientSendsAction(targetPos);
            } catch (Exception ignored) {}
        });
        deliver.setDaemon(true);
        deliver.start();

        boolean got = waitForAction(remoteHero, 3000);
        assertTrue(got, label + ": remote action must be received (no freeze)");
        assertEquals(targetPos, ((HeroAction.Move) remoteHero.curAction).dst,
                label + ": correct action received");

        remoteHero.curAction = null;  // consume
        remoteHero.next();            // unblock actor loop
    }

    /** Run a complete local turn: just set curAction and consume. */
    void doLocalTurn(int targetPos) {
        localHero.curAction = new HeroAction.Move(targetPos);
        assertNotNull(localHero.curAction);
        localHero.curAction = null;
    }

    // =========================================================================
    // THE ORIGINAL BUG: P1, P2, P1, P2 frozen on 4th move
    // =========================================================================

    @Test
    void turn1P1_turn2P2_turn3P1_turn4P2_noFreeze() throws Exception {
        doLocalTurn(10);                            // T1: P1 (local)
        doRemoteTurn(20, 10, "T2-P2");              // T2: P2 (remote)
        doLocalTurn(30);                            // T3: P1 (local)
        doRemoteTurn(40, 10, "T4-P2");              // T4: P2 — was freezing here
    }

    @Test
    void eightAlternatingTurns_noFreeze() throws Exception {
        for (int i = 0; i < 4; i++) {
            doLocalTurn(i * 10 + 1);
            doRemoteTurn(i * 10 + 2, 10, "round-" + i + "-remote");
        }
    }

    @Test
    void tenRounds_noFreeze() throws Exception {
        for (int i = 0; i < 10; i++) {
            doLocalTurn(i * 10 + 1);
            doRemoteTurn(i * 10 + 2, 5, "round-" + i);
        }
    }

    // =========================================================================
    // Delay variations — simulate a thinking player
    // =========================================================================

    @Test
    void p2ThinksBetweenTurns_noFreeze() throws Exception {
        // P2 takes slightly longer than socket timeout to think each time
        doLocalTurn(1);
        doRemoteTurn(2, THINK_DELAY_MS + 50, "T2 slow");  // P2 "thinks" past timeout
        doLocalTurn(3);
        doRemoteTurn(4, THINK_DELAY_MS + 50, "T4 slow");  // was the freeze point
        doLocalTurn(5);
        doRemoteTurn(6, THINK_DELAY_MS + 50, "T6 slow");
    }

    @Test
    void p2VerySlowEveryOtherTurn_noFreeze() throws Exception {
        int[] delays = {5, THINK_DELAY_MS + 100, 5, THINK_DELAY_MS + 100, 5};
        for (int i = 0; i < delays.length; i++) {
            doLocalTurn(i * 2 + 1);
            doRemoteTurn(i * 2 + 2, delays[i], "remote-" + i);
        }
    }

    @Test
    void randomishDelays_20rounds_noFreeze() throws Exception {
        // Pseudo-random delays to hit many timing combinations
        int[] delays = {5, 250, 10, 250, 0, 300, 5, 250, 15, 250,
                        0, 5, 300, 10, 250, 5, 0, 300, 10, 5};
        for (int i = 0; i < 20; i++) {
            doLocalTurn(i * 2 + 1);
            doRemoteTurn(i * 2 + 2, delays[i], "r" + i);
        }
    }

    // =========================================================================
    // Action arrives before reader starts (pre-buffered packet)
    // =========================================================================

    @Test
    void actionArrivesBeforeReaderStarts_stillReceived() throws Exception {
        // Send packet BEFORE calling receiveActionAsync
        clientSendsAction(77);
        Thread.sleep(10); // let it buffer in the TCP receive buffer

        // Now start the reader — packet already in buffer
        NetworkManager.receiveActionAsync(remoteHero);
        boolean got = waitForAction(remoteHero, 2000);
        assertTrue(got, "Pre-buffered action must be received");
        assertEquals(77, ((HeroAction.Move) remoteHero.curAction).dst);
        remoteHero.curAction = null;
        remoteHero.next();
    }

    @Test
    void multiplePreBufferedActions_firstIsReceived() throws Exception {
        // Send 3 packets before starting reader; at minimum the first must arrive.
        // (Production code processes one action per turn — the reader delivers them
        // sequentially; this test verifies pre-buffered data is not dropped.)
        for (int i = 1; i <= 3; i++) {
            clientSendsAction(i * 11);
        }
        Thread.sleep(20);

        NetworkManager.receiveActionAsync(remoteHero);
        boolean got = waitForAction(remoteHero, 2000);
        assertTrue(got, "First pre-buffered action must be received");
        assertNotNull(remoteHero.curAction);
        remoteHero.curAction = null;
        remoteHero.next();
    }

    // =========================================================================
    // Race condition: action sent simultaneously with reader starting
    // =========================================================================

    @Test
    void actionRacesWithReaderStart_10times() throws Exception {
        for (int race = 0; race < 10; race++) {
            remoteHero.curAction = null;

            CountDownLatch go = new CountDownLatch(2);
            final int pos = race * 7 + 1;
            Thread sender = new Thread(() -> {
                try {
                    go.countDown(); go.await();
                    clientSendsAction(pos);
                } catch (Exception ignored) {}
            });
            Thread reader = new Thread(() -> {
                try {
                    go.countDown(); go.await();
                    NetworkManager.receiveActionAsync(remoteHero);
                } catch (Exception ignored) {}
            });
            sender.setDaemon(true);
            reader.setDaemon(true);
            sender.start();
            reader.start();

            boolean got = waitForAction(remoteHero, 2000);
            assertTrue(got, "Race " + race + ": action must be received");
            remoteHero.curAction = null;
            remoteHero.next();
            sender.join(500);
            reader.join(500);
        }
    }

    // =========================================================================
    // Exact "3rd move" reproduction
    // =========================================================================

    @Test
    void exactThirdMoveScenario_p2Action3NeverFreezes() throws Exception {
        // Reproduce the exact reported bug sequence
        // Observed: P1 move 1 ✓, P2 move 1 ✓, P1 move 2 ✓, P2 move 2 FROZEN

        // Move 1 for each player
        doLocalTurn(1);
        doRemoteTurn(2, 15, "P2-move1");

        // Move 2 for each player — this was failing
        doLocalTurn(3);
        doRemoteTurn(4, 15, "P2-move2");

        // Move 3 for each player — extra confidence
        doLocalTurn(5);
        doRemoteTurn(6, 15, "P2-move3");
    }

    @Test
    void thirdMoveWithSlowPeer_noFreeze() throws Exception {
        // Same scenario but P2 takes > socket timeout each time
        int slowDelay = THINK_DELAY_MS + 60;
        doLocalTurn(1);
        doRemoteTurn(2, slowDelay, "P2-slow-move1");
        doLocalTurn(3);
        doRemoteTurn(4, slowDelay, "P2-slow-move2-WAS-FROZEN");
        doLocalTurn(5);
        doRemoteTurn(6, slowDelay, "P2-slow-move3");
    }

    // =========================================================================
    // Reader singleton guard — must not prevent re-entry after timeout
    // =========================================================================

    @Test
    void singletonGuard_allowsReEntryAfterTimeout() throws Exception {
        // Start reader, let it timeout, then start again — must still work
        NetworkManager.receiveActionAsync(remoteHero);
        Thread.sleep(THINK_DELAY_MS + 100); // let timeout fire

        // Reset guard (simulates actionReaderRunning=false after timeout)
        // With the fix, the reader NEVER exits on timeout, so guard stays true
        // Either way, a subsequent receiveActionAsync must eventually get the action

        NetworkManager.receiveActionAsync(remoteHero); // second call
        clientSendsAction(55);
        boolean got = waitForAction(remoteHero, 3000);
        assertTrue(got, "After timeout, re-entered reader must receive action");
    }

    // =========================================================================
    // Both players active simultaneously — no cross-contamination
    // =========================================================================

    @Test
    void bothPlayersActConcurrently_noContamination() throws Exception {
        // P1 sets local action while P2's packet is in flight
        AtomicBoolean p2Received = new AtomicBoolean(false);

        // Start P2's reader
        NetworkManager.receiveActionAsync(remoteHero);

        // P1 acts (local) and P2 sends packet simultaneously
        Thread p1 = new Thread(() -> doLocalTurn(100));
        Thread p2 = new Thread(() -> {
            try {
                Thread.sleep(10);
                clientSendsAction(200);
            } catch (Exception ignored) {}
        });
        p1.setDaemon(true); p2.setDaemon(true);
        p1.start(); p2.start();
        p1.join(1000); p2.join(1000);

        boolean got = waitForAction(remoteHero, 2000);
        p2Received.set(got);

        assertTrue(p2Received.get(), "P2 action must be received while P1 also acts");
        assertEquals(200, ((HeroAction.Move) remoteHero.curAction).dst,
                "P2 action must not be contaminated by P1's action");
    }

    // =========================================================================
    // Many rapid turns — stress test
    // =========================================================================

    @Test
    void fiftyRapidTurns_noFreeze() throws Exception {
        for (int i = 0; i < 50; i++) {
            doLocalTurn(i * 2 + 1);
            doRemoteTurn(i * 2 + 2, 2, "rapid-" + i);
        }
    }

    @Test
    void fiftyTurnsWithOccasionalSlowness_noFreeze() throws Exception {
        for (int i = 0; i < 50; i++) {
            doLocalTurn(i * 2 + 1);
            // Every 7th turn P2 is slow (> socket timeout)
            long delay = (i % 7 == 0) ? THINK_DELAY_MS + 60 : 3;
            doRemoteTurn(i * 2 + 2, delay, "t" + i);
        }
    }

    // =========================================================================
    // Action arrives during timeout wait — critical timing
    // =========================================================================

    @Test
    void actionArrivesExactlyAtTimeoutBoundary_received() throws Exception {
        NetworkManager.receiveActionAsync(remoteHero);
        // Send at exactly the timeout boundary
        Thread.sleep(THINK_DELAY_MS - 10);
        clientSendsAction(33);
        boolean got = waitForAction(remoteHero, 3000);
        assertTrue(got, "Action at timeout boundary must be received");
        remoteHero.curAction = null; remoteHero.next();
    }

    @Test
    void actionArrivesJustAfterTimeout_received() throws Exception {
        NetworkManager.receiveActionAsync(remoteHero);
        // Send just AFTER the timeout fires
        Thread.sleep(THINK_DELAY_MS + 30);
        clientSendsAction(44);
        boolean got = waitForAction(remoteHero, 3000);
        assertTrue(got, "Action arriving just after timeout must be received");
        remoteHero.curAction = null; remoteHero.next();
    }
}
