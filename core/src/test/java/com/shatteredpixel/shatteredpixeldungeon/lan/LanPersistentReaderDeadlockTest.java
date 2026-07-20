package com.shatteredpixel.shatteredpixeldungeon.lan;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.network.NetworkManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Timeout;

import java.io.*;
import java.net.*;
import java.util.ArrayList;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Historical context: the old per-turn reader could deliver turn-(N+1)'s
 * packet into curAction while turn N was still processing; ready() then wiped
 * it and the game froze ("few moves then freeze").
 *
 * v3 leader-sequenced commit protocol: readers are PERSISTENT (one per
 * socket) and every sequenced op is queued in the hero's per-hero inbox
 * (hero.lanActionInbox), so early / back-to-back / buffered packets can never
 * be lost or wiped. These tests pin down those invariants:
 *
 *  - ops sent before the previous one is consumed are queued, not lost
 *  - repeated ensureGameplayReaders calls never spawn duplicate readers
 *  - long sequences of back-to-back ops all arrive, in order
 */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class LanPersistentReaderDeadlockTest {

    private ServerSocket serverSocket;
    private Socket       hostSideSocket;
    private Socket       clientSocket;

    private DataInputStream  hostIn;
    private DataOutputStream hostOut;
    private DataInputStream  clientIn;
    private DataOutputStream clientOut;

    private Hero localHero;
    private Hero remoteHero;

    // Per-client contiguous request counter (v3 leader dedupes by clientSeq)
    private int clientSeq = 0;

    @BeforeEach
    void setUp() throws IOException {
        serverSocket = new ServerSocket(0);
        int port = serverSocket.getLocalPort();
        Future<Socket> serverSide = Executors.newSingleThreadExecutor()
                .submit(() -> serverSocket.accept());
        clientSocket = new Socket("127.0.0.1", port);
        try {
            hostSideSocket = serverSide.get(2, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
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

    void clientSendsMove(int targetPos) throws IOException {
        LanTestProtocol.writeActionRequest(clientOut, ++clientSeq, (byte) 0, targetPos);
    }

    /**
     * Simulates what Hero.act() does for a remote hero in v3:
     * - calls receiveActionAsync() (persistent readers, idempotent)
     * - waits on lanActionLock until a commit is in the inbox
     * - consumes and returns the commit (ready() no longer wipes anything —
     *   the inbox is only drained at execution time)
     */
    NetworkManager.Commit simulateRemoteAct(long maxWaitMs) throws InterruptedException {
        synchronized (remoteHero.lanActionLock) {
            NetworkManager.receiveActionAsync(remoteHero);
            long deadline = System.currentTimeMillis() + maxWaitMs;
            while (remoteHero.lanActionInbox.isEmpty() && NetworkManager.lanMode) {
                long rem = deadline - System.currentTimeMillis();
                if (rem <= 0) break;
                remoteHero.lanActionLock.wait(rem);
            }
            return remoteHero.lanActionInbox.pollFirst();
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

    // =========================================================================
    // Invariant 1: Pre-sent turn-2 op is never lost
    //
    // Scenario: remote sends BOTH turn-1 and turn-2 ops back-to-back before
    // the host has consumed turn-1. With the old per-turn reader, turn-2's
    // packet was read into curAction and then wiped by ready() — deadlock.
    // v3: both commits queue in the per-hero inbox and are consumed in order.
    // =========================================================================

    @Test
    void preSentTurnTwoPacket_notLostWhenReadyClears() throws Exception {
        // Client sends BOTH ops before the host processes either
        clientSendsMove(10); // turn 1 op
        clientSendsMove(20); // turn 2 op — arrives while turn 1 still processing
        Thread.sleep(20);    // let both frames reach the TCP receive buffer

        // --- Turn 1 ---
        NetworkManager.Commit turn1 = simulateRemoteAct(2000);
        assertNotNull(turn1, "Turn 1 op must be received");
        assertEquals(10, turn1.targetPos, "Turn 1: wrong destination");

        // --- Turn 2 ---
        // The pre-sent op must be waiting in the inbox — nothing may wipe it.
        NetworkManager.Commit turn2 = simulateRemoteAct(2000);
        assertNotNull(turn2,
                "Turn 2 op must be received. " +
                "If null: the early-delivered commit was lost instead of queued in " +
                "the per-hero inbox. This is the recurring LAN freeze.");
        assertEquals(20, turn2.targetPos, "Turn 2: wrong destination");
    }

    // =========================================================================
    // Invariant 2: readers are a per-stream singleton — repeated
    // ensureGameplayReaders / receiveActionAsync calls spawn no duplicates,
    // and turn 2 still arrives (v3 replacement for the dead per-turn
    // actionReaderRunning guard-reset mechanic)
    // =========================================================================

    @Test
    void guardResetsAfterActionDelivered_newReaderStartsForTurn2() throws Exception {
        // Turn 1: send + receive
        clientSendsMove(11);
        NetworkManager.Commit turn1 = simulateRemoteAct(2000);
        assertNotNull(turn1, "Turn 1 must be received");
        assertEquals(11, turn1.targetPos);

        // The persistent reader must still be running, and calling
        // receiveActionAsync again for the next turn must NOT spawn a duplicate.
        int before = countNetReaderThreads();
        assertTrue(before >= 1, "Persistent reader must still be alive after turn 1");
        for (int i = 0; i < 5; i++) {
            NetworkManager.receiveActionAsync(remoteHero);
        }
        int after = countNetReaderThreads();
        assertTrue(after <= before,
                "Repeated receiveActionAsync must not spawn duplicate net-reader threads"
                        + " (before=" + before + " after=" + after + ")");

        // Turn 2: the same persistent reader serves it
        clientSendsMove(22);
        NetworkManager.Commit turn2 = simulateRemoteAct(2000);
        assertNotNull(turn2,
                "Turn 2 must be received by the persistent reader. " +
                "If null: the reader died after one op, deadlock.");
        assertEquals(22, turn2.targetPos);
    }

    // =========================================================================
    // Invariant 3: Five-turn alternating sequence (the reported "few moves
    // then freeze") — every sequential commit is delivered
    // =========================================================================

    @Test
    void fiveTurns_neverFreeze() throws Exception {
        int[] positions = {10, 20, 30, 40, 50};
        for (int i = 0; i < positions.length; i++) {
            clientSendsMove(positions[i]);
            NetworkManager.Commit c = simulateRemoteAct(2000);
            assertNotNull(c, "Turn " + (i + 1) + " must not freeze");
            assertEquals(positions[i], c.targetPos,
                    "Turn " + (i + 1) + " wrong position");
        }
    }

    // =========================================================================
    // Invariant 4: Remote sends op while previous is being processed
    //
    // Models the real-world case: network latency is low, remote player is
    // fast. Remote player's turn-N+1 op arrives before host finishes turn-N.
    // =========================================================================

    @Test
    void fastRemotePlayer_actionArrivesEarly_neverLost() throws Exception {
        // Turn 1: send op, consume it
        clientSendsMove(1);
        NetworkManager.Commit turn1 = simulateRemoteAct(2000);
        assertNotNull(turn1, "Fast remote: turn 1 must be received");

        // Remote sends turn-2 op while host is "still processing" turn 1
        // (simulated by sending before we call simulateRemoteAct for turn 2)
        clientSendsMove(2);
        Thread.sleep(10); // frame in flight, reader will queue it immediately

        // Turn 2 must still be receivable
        NetworkManager.Commit turn2 = simulateRemoteAct(2000);
        assertNotNull(turn2, "Fast remote: turn 2 must not be lost even if delivered early");
        assertEquals(2, turn2.targetPos);
    }

    // =========================================================================
    // Invariant 5: Ten rapid back-to-back turns without freeze
    // =========================================================================

    @Test
    void tenRapidTurns_noFreeze() throws Exception {
        for (int i = 1; i <= 10; i++) {
            clientSendsMove(i * 7);
            NetworkManager.Commit c = simulateRemoteAct(2000);
            assertNotNull(c, "Rapid turn " + i + " must not freeze");
            assertEquals(i * 7, c.targetPos,
                    "Rapid turn " + i + " wrong position");
        }
    }
}
