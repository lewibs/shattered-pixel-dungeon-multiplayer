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
 * Integration tests using REAL TCP sockets to simulate two devices.
 *
 * These tests wire actual DataInputStream/DataOutputStream into NetworkManager
 * and call receiveActionAsync() exactly as production code does, exercising the
 * real persistent per-socket reader threads.
 *
 * v3 leader-sequenced commit protocol: this device is the LEADER. The remote
 * player (player 1) submits REQUEST frames; the leader sequences each into a
 * COMMIT delivered to remoteHero.lanActionInbox. The reader has no read
 * timeout — a quiet stream must never kill it.
 */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class LanRealSocketTest {

    // Simulated "think time" delay — no actual socket timeout set
    // (production uses no read timeout, with periodic PING for disconnect detection)
    private static final int THINK_DELAY_MS = 200;

    private ServerSocket serverSocket;
    private Socket hostSideSocket;   // host's view of the client connection
    private Socket clientSocket;     // client's socket

    private DataInputStream  hostIn;
    private DataOutputStream hostOut;
    private DataInputStream  clientIn;
    private DataOutputStream clientOut;

    private Hero localHero;   // heroes[0]  — local on this device (host)
    private Hero remoteHero;  // heroes[1]  — remote (client's hero)

    // Per-client contiguous request counter (v3 leader dedupes by clientSeq)
    private int clientSeq = 0;

    @BeforeEach
    void setUp() throws IOException {
        // Create loopback socket pair
        serverSocket = new ServerSocket(0); // OS picks free port
        int port = serverSocket.getLocalPort();

        // Connect client in background (accept blocks until connected)
        Future<Socket> serverSide = Executors.newSingleThreadExecutor()
                .submit(() -> serverSocket.accept());

        clientSocket = new Socket("127.0.0.1", port);
        try { hostSideSocket = serverSide.get(2, TimeUnit.SECONDS); }
        catch (InterruptedException | ExecutionException | TimeoutException e) { throw new IOException("setup failed", e); }

        // No socket timeout — matches production

        hostIn   = new DataInputStream(hostSideSocket.getInputStream());
        hostOut  = new DataOutputStream(hostSideSocket.getOutputStream());
        clientIn  = new DataInputStream(clientSocket.getInputStream());
        clientOut = new DataOutputStream(clientSocket.getOutputStream());

        // Wire into NetworkManager — host perspective
        NetworkManager.injectHostStreamsForTesting(hostIn, hostOut);
        NetworkManager.injectClientStreamsForTesting(clientIn, clientOut);

        // Heroes
        localHero  = new Hero(); localHero.HP  = localHero.HT  = 20;
        remoteHero = new Hero(); remoteHero.HP = remoteHero.HT = 20;
        Dungeon.heroes = new ArrayList<>();
        Dungeon.heroes.add(localHero);
        Dungeon.heroes.add(remoteHero);
        Dungeon.hero = localHero;

        NetworkManager.lanMode = true;
        NetworkManager.setIsHostForTesting(true);     // host reads from ins.get(0)
        NetworkManager.localPlayerIndex = 0;          // hero[0] is local (host)
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
        if (clientSocket    != null && !clientSocket.isClosed())    clientSocket.close();
        if (hostSideSocket  != null && !hostSideSocket.isClosed())  hostSideSocket.close();
        if (serverSocket    != null && !serverSocket.isClosed())    serverSocket.close();
        NetworkManager.injectHostStreamsForTesting(null, null);
        NetworkManager.injectClientStreamsForTesting(null, null);
        Dungeon.heroes = null;
        Dungeon.hero   = null;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Client submits a Move op to the leader (simulates peer moving). heroId is
     *  implicit in v3 — the leader stamps the player index from the socket slot. */
    void clientSendsAction(int heroId, int targetPos) throws IOException {
        LanTestProtocol.writeActionRequest(clientOut, ++clientSeq, (byte) 0, targetPos);
    }

    /** Run the remote-hero wait as Hero.act() does it: wait for an inbox commit. */
    boolean runRemoteWait(Hero h, long maxMs) throws InterruptedException {
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

    NetworkManager.Commit takeCommit(Hero h) {
        synchronized (h.lanActionLock) {
            return h.lanActionInbox.pollFirst();
        }
    }

    // =========================================================================
    // Test 1: immediate delivery — baseline (should always pass)
    // =========================================================================

    @Test
    void immediateDelivery_receivesAction() throws Exception {
        NetworkManager.receiveActionAsync(remoteHero);

        // Send action immediately
        clientSendsAction(1, 42);

        boolean got = runRemoteWait(remoteHero, 2000);
        assertTrue(got, "Immediate delivery must be received");
        NetworkManager.Commit c = takeCommit(remoteHero);
        assertNotNull(c);
        assertEquals(42, c.targetPos);
    }

    // =========================================================================
    // Test 2: action arrives AFTER a long quiet period
    //
    // With the old per-turn reader: the reader got SocketTimeoutException,
    // broke its loop, and the action was lost — permanent freeze.
    // v3: the persistent reader has no read timeout and must stay alive.
    // =========================================================================

    @Test
    void actionAfterSocketTimeout_mustNotFreezeGame() throws Exception {
        // Start the real readers (use the actual hostIn socket)
        NetworkManager.receiveActionAsync(remoteHero);

        // Wait longer than the old socket timeout used to be
        Thread.sleep(THINK_DELAY_MS + 100);

        // NOW send the action — with broken code the reader is dead, so this is lost
        clientSendsAction(1, 99);

        // Game must still receive the action within 2s
        boolean got = runRemoteWait(remoteHero, 2000);
        assertTrue(got,
                "Action arriving AFTER a quiet period must still be received. " +
                "If this fails, the reader died on the quiet stream and " +
                "the game is permanently frozen — this is the real-world deadlock.");
        assertEquals(99, takeCommit(remoteHero).targetPos);
    }

    // =========================================================================
    // Test 3: multiple quiet periods then action
    // =========================================================================

    @Test
    void threeTimeoutsThenAction_mustStillReceive() throws Exception {
        NetworkManager.receiveActionAsync(remoteHero);

        // Stay quiet for 3 × the old timeout period
        Thread.sleep((THINK_DELAY_MS + 50) * 3);

        // Send action after the long quiet period
        clientSendsAction(1, 7);

        boolean got = runRemoteWait(remoteHero, 2000);
        assertTrue(got,
                "After a long quiet period the reader must still be alive and receive the action");
        assertEquals(7, takeCommit(remoteHero).targetPos);
    }

    // =========================================================================
    // Test 4: full two-player round trip — P1 acts, P2 acts, P1 acts
    // =========================================================================

    @Test
    void threeRoundRoundTrip_noFreeze() throws Exception {
        // Round 1: local hero (P1) acts — just sets curAction directly
        localHero.curAction = new HeroAction.Move(1);
        assertNotNull(localHero.curAction);
        localHero.curAction = null; // consumed

        // Round 2: remote hero (P2) acts — must receive via socket
        NetworkManager.receiveActionAsync(remoteHero);
        Thread.sleep(20);
        clientSendsAction(1, 2);
        assertTrue(runRemoteWait(remoteHero, 2000), "Round 2: remote must receive action");
        assertEquals(2, takeCommit(remoteHero).targetPos);

        // Round 3: local hero acts again
        localHero.curAction = new HeroAction.Move(3);
        assertNotNull(localHero.curAction, "Round 3: local must still be able to act");
        localHero.curAction = null;
    }

    // =========================================================================
    // Test 5: slow peer (think time > old socket timeout) across multiple rounds
    // =========================================================================

    @Test
    void slowPeer_multipleRounds_noFreeze() throws Exception {
        for (int round = 0; round < 3; round++) {
            NetworkManager.receiveActionAsync(remoteHero);

            // Peer "thinks" for longer than the old socket timeout
            Thread.sleep(THINK_DELAY_MS + 80);

            // Then sends action
            clientSendsAction(1, round * 10);

            boolean got = runRemoteWait(remoteHero, 2000);
            assertTrue(got,
                    "Slow peer round " + round + ": action must be received even after think time > socket timeout");
            assertEquals(round * 10, takeCommit(remoteHero).targetPos);
        }
    }

    // =========================================================================
    // Test 6: repeated ensureGameplayReaders calls are idempotent — no duplicate
    // readers, and the (single) persistent reader keeps receiving
    // =========================================================================

    @Test
    void readerSurvivesTimeout_singletonGuardResets() throws Exception {
        // Start readers
        NetworkManager.receiveActionAsync(remoteHero);
        int before = countNetReaderThreads();
        assertTrue(before >= 1, "A persistent net-reader thread must be running");

        // Long quiet period (the old code's timeout would fire here)
        Thread.sleep(THINK_DELAY_MS + 100);

        // Calling receiveActionAsync again must be a safe no-op: the SAME
        // persistent reader keeps serving, no duplicate thread is spawned.
        NetworkManager.receiveActionAsync(remoteHero);
        int after = countNetReaderThreads();
        assertTrue(after <= before,
                "Repeated receiveActionAsync must not spawn duplicate net-reader threads"
                        + " (before=" + before + " after=" + after + ")");

        clientSendsAction(1, 55);
        boolean got = runRemoteWait(remoteHero, 2000);
        assertTrue(got, "After repeated ensure calls, action must be receivable");
        assertEquals(55, takeCommit(remoteHero).targetPos);
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
}
