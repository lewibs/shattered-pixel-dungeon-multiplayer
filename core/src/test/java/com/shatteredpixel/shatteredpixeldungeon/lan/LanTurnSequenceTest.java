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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive real-socket tests targeting the specific "3rd move freeze"
 * scenario and all variations that could cause LAN turns to freeze.
 *
 * v3 leader-sequenced commit protocol: this device is the LEADER (host,
 * localPlayerIndex=0). The remote player (player 1) submits ops as REQUEST
 * frames on the client socket; the leader sequences them and dispatches the
 * resulting COMMIT into remoteHero.lanActionInbox (and echoes the COMMIT back
 * down the socket — the tests simply leave those bytes in the client's TCP
 * receive buffer). "Action received" now means "commit landed in the inbox".
 *
 * Every test uses real TCP loopback sockets.
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

    // Per-client contiguous request counter (v3 leader dedupes by clientSeq)
    private int clientSeq = 0;

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
        NetworkManager.resetCommitProtocolState();
    }

    @AfterEach
    void tearDown() throws IOException {
        NetworkManager.lanMode = false;
        NetworkManager.setIsHostForTesting(false);
        NetworkManager.localPlayerIndex = 0;
        NetworkManager.resetActionReaderForTesting();
        NetworkManager.resetCommitProtocolState();
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

    /** Client (player 1) submits a Move op — v3 REQUEST frame with contiguous clientSeq. */
    void clientSendsAction(int targetPos) throws IOException {
        LanTestProtocol.writeActionRequest(clientOut, ++clientSeq, (byte) 0, targetPos);
    }

    /** Waits until a commit for h lands in its per-hero inbox. */
    boolean waitForAction(Hero h, long maxMs) throws InterruptedException {
        synchronized (h.lanActionLock) {
            long deadline = System.currentTimeMillis() + maxMs;
            while (h.lanActionInbox.isEmpty() && NetworkManager.lanMode) {
                long rem = deadline - System.currentTimeMillis();
                if (rem <= 0) break;
                h.lanActionLock.wait(rem);
            }
            return !h.lanActionInbox.isEmpty();
        }
    }

    /** Consumes the next committed op from the hero's inbox (as Hero.act() does). */
    NetworkManager.Commit takeCommit(Hero h) {
        synchronized (h.lanActionLock) {
            return h.lanActionInbox.pollFirst();
        }
    }

    /** Counts live persistent gameplay reader threads. */
    static int countNetReaderThreads() {
        Thread[] all = new Thread[Thread.activeCount() * 2 + 16];
        int n = Thread.enumerate(all);
        int count = 0;
        for (int i = 0; i < n; i++) {
            if (all[i] != null && all[i].isAlive() && all[i].getName().startsWith("net-reader-")) count++;
        }
        return count;
    }

    /** Run a complete remote turn: ensure readers, deliver request, wait, consume commit. */
    void doRemoteTurn(int targetPos, long sendDelayMs, String label)
            throws Exception {
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
        deliver.join(1000); // keeps clientSeq increments serialized across turns
        assertTrue(got, label + ": remote action must be received (no freeze)");
        NetworkManager.Commit c = takeCommit(remoteHero);
        assertNotNull(c, label + ": commit must be in the inbox");
        assertEquals(targetPos, c.targetPos, label + ": correct action received");
        assertEquals(1, c.player, label + ": commit owned by remote player");
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
        // P2 takes slightly longer than the old socket timeout to think each time
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
    // Request arrives before readers start (pre-buffered packet)
    // =========================================================================

    @Test
    void actionArrivesBeforeReaderStarts_stillReceived() throws Exception {
        // Send request BEFORE the persistent readers are running
        clientSendsAction(77);
        Thread.sleep(10); // let it buffer in the TCP receive buffer

        // Now start the readers — request already in buffer
        NetworkManager.receiveActionAsync(remoteHero);
        boolean got = waitForAction(remoteHero, 2000);
        assertTrue(got, "Pre-buffered action must be received");
        NetworkManager.Commit c = takeCommit(remoteHero);
        assertEquals(77, c.targetPos);
    }

    @Test
    void multiplePreBufferedActions_firstIsReceived() throws Exception {
        // Send 3 requests before starting readers. v3: the per-hero inbox makes
        // early/buffered packets safe — ALL of them must be sequenced and
        // delivered, in order, none lost.
        for (int i = 1; i <= 3; i++) {
            clientSendsAction(i * 11);
        }
        Thread.sleep(20);

        NetworkManager.receiveActionAsync(remoteHero);
        for (int i = 1; i <= 3; i++) {
            boolean got = waitForAction(remoteHero, 2000);
            assertTrue(got, "Pre-buffered action " + i + " must be received");
            NetworkManager.Commit c = takeCommit(remoteHero);
            assertNotNull(c);
            assertEquals(i * 11, c.targetPos, "Pre-buffered actions must arrive in order");
        }
    }

    // =========================================================================
    // Race condition: request sent simultaneously with readers starting
    // =========================================================================

    @Test
    void actionRacesWithReaderStart_10times() throws Exception {
        for (int race = 0; race < 10; race++) {
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
            NetworkManager.Commit c = takeCommit(remoteHero);
            assertEquals(pos, c.targetPos, "Race " + race + ": correct action");
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
        // Same scenario but P2 takes > the old socket timeout each time
        int slowDelay = THINK_DELAY_MS + 60;
        doLocalTurn(1);
        doRemoteTurn(2, slowDelay, "P2-slow-move1");
        doLocalTurn(3);
        doRemoteTurn(4, slowDelay, "P2-slow-move2-WAS-FROZEN");
        doLocalTurn(5);
        doRemoteTurn(6, slowDelay, "P2-slow-move3");
    }

    // =========================================================================
    // Reader lifecycle — repeated ensure calls spawn no duplicates, and the
    // persistent reader keeps receiving after quiet periods (v3 equivalent of
    // the old "singleton guard allows re-entry after timeout" test)
    // =========================================================================

    @Test
    void singletonGuard_allowsReEntryAfterTimeout() throws Exception {
        // Start readers, let the connection sit idle, then call again — the
        // persistent reader must still be the SAME single thread and must
        // still deliver the next action.
        NetworkManager.receiveActionAsync(remoteHero);
        int before = countNetReaderThreads();
        assertTrue(before >= 1, "A persistent net-reader thread must be running");

        Thread.sleep(THINK_DELAY_MS + 100); // idle period (old code timed out here)

        for (int i = 0; i < 5; i++) {
            NetworkManager.receiveActionAsync(remoteHero); // repeated calls
        }
        int after = countNetReaderThreads();
        assertTrue(after <= before,
                "Repeated receiveActionAsync must not spawn duplicate net-reader threads"
                        + " (before=" + before + " after=" + after + ")");

        clientSendsAction(55);
        boolean got = waitForAction(remoteHero, 3000);
        assertTrue(got, "After idle period, persistent reader must still receive the action");
        assertEquals(55, takeCommit(remoteHero).targetPos);
    }

    // =========================================================================
    // Both players active simultaneously — no cross-contamination
    // =========================================================================

    @Test
    void bothPlayersActConcurrently_noContamination() throws Exception {
        // Start P2's reader
        NetworkManager.receiveActionAsync(remoteHero);

        // P1 acts (local) and P2 sends its request simultaneously
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
        assertTrue(got, "P2 action must be received while P1 also acts");
        NetworkManager.Commit c = takeCommit(remoteHero);
        assertEquals(200, c.targetPos,
                "P2 action must not be contaminated by P1's action");
        assertEquals(1, c.player, "commit must be owned by P2");
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
            // Every 7th turn P2 is slow (> old socket timeout)
            long delay = (i % 7 == 0) ? THINK_DELAY_MS + 60 : 3;
            doRemoteTurn(i * 2 + 2, delay, "t" + i);
        }
    }

    // =========================================================================
    // Action arrives during a quiet period — critical timing
    // =========================================================================

    @Test
    void actionArrivesExactlyAtTimeoutBoundary_received() throws Exception {
        NetworkManager.receiveActionAsync(remoteHero);
        // Send at exactly the old timeout boundary
        Thread.sleep(THINK_DELAY_MS - 10);
        clientSendsAction(33);
        boolean got = waitForAction(remoteHero, 3000);
        assertTrue(got, "Action at timeout boundary must be received");
        assertEquals(33, takeCommit(remoteHero).targetPos);
    }

    @Test
    void actionArrivesJustAfterTimeout_received() throws Exception {
        NetworkManager.receiveActionAsync(remoteHero);
        // Send just AFTER the old timeout would have fired
        Thread.sleep(THINK_DELAY_MS + 30);
        clientSendsAction(44);
        boolean got = waitForAction(remoteHero, 3000);
        assertTrue(got, "Action arriving just after timeout must be received");
        assertEquals(44, takeCommit(remoteHero).targetPos);
    }
}
