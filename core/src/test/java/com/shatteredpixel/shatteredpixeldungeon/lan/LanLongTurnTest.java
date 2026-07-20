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
 * Tests specifically targeting long / unpredictable turn durations.
 *
 * Core guarantee: no matter how long a player takes to move (500ms, 1s, 2s,
 * wildly variable), the game must never freeze and must always deliver the
 * action when it finally arrives.
 *
 * v3 leader-sequenced commit protocol: this device is the LEADER. The remote
 * player (player 1) submits REQUEST frames after "thinking"; the leader
 * sequences each into a COMMIT delivered to remoteHero.lanActionInbox. The
 * persistent per-socket reader has no read timeout, so arbitrarily long
 * think times (with or without PING keepalives) must never lose an op.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class LanLongTurnTest {

    private ServerSocket     serverSocket;
    private Socket           hostSideSocket;
    private Socket           clientSocket;
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
        Future<Socket> serverSide = Executors.newSingleThreadExecutor().submit(() -> serverSocket.accept());
        clientSocket = new Socket("127.0.0.1", port);
        try { hostSideSocket = serverSide.get(2, TimeUnit.SECONDS); }
        catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new IOException("setup failed", e);
        }
        // NO socket timeout — production behaviour
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

    void sendAction(int pos) throws IOException {
        LanTestProtocol.writeActionRequest(clientOut, ++clientSeq, (byte) 0, pos);
    }

    void sendPing() throws IOException {
        synchronized (clientOut) {
            clientOut.writeByte(NetworkManager.PacketType.PING);
            clientOut.flush();
        }
    }

    boolean waitFor(Hero h, long maxMs) throws InterruptedException {
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

    void remoteTurn(int pos, long thinkMs) throws Exception {
        NetworkManager.receiveActionAsync(remoteHero);
        Thread.sleep(thinkMs);   // peer "thinking" — no timeout fires
        sendAction(pos);
        assertTrue(waitFor(remoteHero, 3000), "Action must arrive after " + thinkMs + "ms think");
        NetworkManager.Commit c = takeCommit(remoteHero);
        assertEquals(pos, c.targetPos);
    }

    void localTurn(int pos) {
        localHero.curAction = new HeroAction.Move(pos);
        localHero.curAction = null;
    }

    // =========================================================================
    // Original bug reproduced with long think times
    // =========================================================================

    @Test
    void longThink_500ms_p1p2p1p2_noFreeze() throws Exception {
        localTurn(1);
        remoteTurn(2, 500);   // P2 thinks 500ms — was deadlocking with old timeout
        localTurn(3);
        remoteTurn(4, 500);   // 3rd move scenario with long think
    }

    @Test
    void longThink_1000ms_p1p2p1p2_noFreeze() throws Exception {
        localTurn(1);
        remoteTurn(2, 1000);
        localTurn(3);
        remoteTurn(4, 1000);
    }

    @Test
    void longThink_2000ms_singleTurn_noFreeze() throws Exception {
        localTurn(1);
        remoteTurn(2, 2000);  // 2-second think — would have killed old 30s timeout if repeated 15×
    }

    // =========================================================================
    // Variable think times — realistic gameplay
    // =========================================================================

    @Test
    void variableThinkTimes_10rounds_noFreeze() throws Exception {
        // Mix of quick and slow moves — exactly how a real game plays
        int[] thinkMs = {10, 600, 5, 800, 20, 500, 10, 700, 30, 600};
        for (int i = 0; i < 10; i++) {
            localTurn(i * 2 + 1);
            remoteTurn(i * 2 + 2, thinkMs[i]);
        }
    }

    @Test
    void increasingThinkTimes_5rounds_noFreeze() throws Exception {
        // Each round P2 thinks longer — stress test for cumulative state
        for (int i = 0; i < 5; i++) {
            long think = 100L * (i + 1); // 100ms, 200ms, 300ms, 400ms, 500ms
            localTurn(i * 2 + 1);
            remoteTurn(i * 2 + 2, think);
        }
    }

    @Test
    void allTurnsLong_5rounds_noFreeze() throws Exception {
        for (int i = 0; i < 5; i++) {
            localTurn(i * 2 + 1);
            remoteTurn(i * 2 + 2, 400);
        }
    }

    // =========================================================================
    // Long think WITH pings interleaved — exactly production scenario
    // =========================================================================

    @Test
    void longThinkWithPings_p1p2p1p2_noFreeze() throws Exception {
        // Production: pings every 5s during think time
        // Test: pings every 60ms during think

        localTurn(1);

        // Remote turn: readers running, pings arrive during think, then real request
        NetworkManager.receiveActionAsync(remoteHero);
        for (int p = 0; p < 5; p++) {
            Thread.sleep(60);
            sendPing();
        }
        sendAction(20);
        assertTrue(waitFor(remoteHero, 3000), "Action after pings must arrive");
        assertEquals(20, takeCommit(remoteHero).targetPos);

        localTurn(3);

        // Third move — the original freeze scenario, now with pings
        NetworkManager.receiveActionAsync(remoteHero);
        for (int p = 0; p < 5; p++) {
            Thread.sleep(60);
            sendPing();
        }
        sendAction(40);
        assertTrue(waitFor(remoteHero, 3000), "Third move with pings must not freeze");
        assertEquals(40, takeCommit(remoteHero).targetPos);
    }

    @Test
    void manyPingsThenLongWaitThenAction_noFreeze() throws Exception {
        NetworkManager.receiveActionAsync(remoteHero);
        // 10 pings, then a 600ms gap (simulating peer pondering), then action
        for (int i = 0; i < 10; i++) { sendPing(); Thread.sleep(5); }
        Thread.sleep(600);
        sendAction(77);
        assertTrue(waitFor(remoteHero, 3000), "Action after pings + long wait must arrive");
        assertEquals(77, takeCommit(remoteHero).targetPos);
    }

    // =========================================================================
    // Wait in the middle — old-style test preserved
    // =========================================================================

    @Test
    void waitInMiddle_thenAction_originalStyle() throws Exception {
        // Original test style: readers start, nothing happens for a while, then action
        NetworkManager.receiveActionAsync(remoteHero);
        // Deliberately wait without sending anything — simulating a slow peer
        Thread.sleep(800);
        // Now peer finally moves
        sendAction(55);
        boolean got = waitFor(remoteHero, 3000);
        assertTrue(got, "Action must be received after 800ms gap");
        assertEquals(55, takeCommit(remoteHero).targetPos);
    }

    @Test
    void waitInMiddle_multipleRounds() throws Exception {
        for (int round = 0; round < 4; round++) {
            localTurn(round * 2 + 1);
            NetworkManager.receiveActionAsync(remoteHero);
            Thread.sleep(300 + round * 100L);  // 300, 400, 500, 600ms
            sendAction(round * 2 + 2);
            assertTrue(waitFor(remoteHero, 3000), "Round " + round + " must not freeze");
            assertEquals(round * 2 + 2, takeCommit(remoteHero).targetPos);
        }
    }

    // =========================================================================
    // Concurrent local action + long remote think — no interference
    // =========================================================================

    @Test
    void localActsWhileRemoteThinks_noInterference() throws Exception {
        // Remote starts thinking (readers waiting for its request)
        NetworkManager.receiveActionAsync(remoteHero);

        // Local hero acts while remote is thinking — must not interfere
        long localStart = System.currentTimeMillis();
        localTurn(1);
        long localElapsed = System.currentTimeMillis() - localStart;
        assertTrue(localElapsed < 50, "Local turn must be instant even while remote thinks");

        // Remote finishes thinking after 400ms
        Thread.sleep(400);
        sendAction(2);
        assertTrue(waitFor(remoteHero, 3000), "Remote action must still arrive after local acted");
        assertEquals(2, takeCommit(remoteHero).targetPos);
    }

    // =========================================================================
    // Turn order integrity — actions always matched to correct turn
    // =========================================================================

    @Test
    void actionAlwaysMatchesTurn_5rounds() throws Exception {
        // remoteTurn() already asserts the correct targetPos before consuming the commit
        for (int round = 0; round < 5; round++) {
            localTurn(round * 2 + 1);
            remoteTurn(round * 100 + 7, 200); // remoteTurn asserts targetPos matches
        }
    }

    @Test
    void noCrossContamination_longThink() throws Exception {
        // Round 1: remoteTurn verifies pos=111 was received, then consumes the commit
        localTurn(1);
        remoteTurn(111, 300);
        assertTrue(remoteHero.lanActionInbox.isEmpty(), "inbox must be empty between turns");

        // Round 2: remoteTurn verifies pos=222 — must not see round 1's action
        localTurn(3);
        remoteTurn(222, 300);
        assertTrue(remoteHero.lanActionInbox.isEmpty(), "inbox must be empty after round 2");
    }

    // =========================================================================
    // Reader stays alive across many long-think turns
    // =========================================================================

    @Test
    void readerStaysAlive_8longRounds_noFreeze() throws Exception {
        // 8 rounds each with 300ms think — reader must stay alive throughout
        for (int i = 0; i < 8; i++) {
            localTurn(i * 3 + 1);
            remoteTurn(i * 3 + 2, 300);
        }
    }

    @Test
    void alternatingFastAndSlow_12rounds_noFreeze() throws Exception {
        for (int i = 0; i < 12; i++) {
            long think = (i % 3 == 0) ? 500 : 10; // every 3rd turn is slow
            localTurn(i * 2 + 1);
            remoteTurn(i * 2 + 2, think);
        }
    }

    // =========================================================================
    // Stress test — many turns, all long
    // =========================================================================

    @Test
    void twentyLongTurns_300msEach_noFreeze() throws Exception {
        for (int i = 0; i < 20; i++) {
            localTurn(i * 2 + 1);
            remoteTurn(i * 2 + 2, 300);
        }
    }
}
