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
 * Tests for the CLIENT (P2) perspective in a 2-player LAN game.
 *
 * All tests in LanRealSocketTest and LanTurnSequenceTest simulate the HOST
 * perspective (isHost=true, localPlayerIndex=0, reading from ins.get(0)).
 * This file tests the CLIENT perspective:
 *   - isHost = false
 *   - localPlayerIndex = 1  (heroes[1] is P2's local hero)
 *   - heroes[0] is the remote hero (P1's)
 *   - The reader for heroes[0] reads from clientIn
 *   - The host sends ACTION via hostOut → arrives at clientIn
 *
 * Regression: "P1 moves, P1's screen updates correctly but P2's screen does
 * not update. Then both players get stuck waiting for their turn — a
 * permanent freeze."
 */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class LanClientPerspectiveTest {

    private ServerSocket     serverSocket;
    private Socket           hostSideSocket;
    private Socket           clientSocket;

    private DataInputStream  hostIn;
    private DataOutputStream hostOut;
    private DataInputStream  clientIn;
    private DataOutputStream clientOut;

    /** heroes[0] = P1's hero (remote on P2's device) */
    private Hero p1Hero;
    /** heroes[1] = P2's hero (local on P2's device) */
    private Hero p2Hero;

    @BeforeEach
    void setUp() throws IOException {
        serverSocket = new ServerSocket(0);
        int port = serverSocket.getLocalPort();
        Future<Socket> serverSide = Executors.newSingleThreadExecutor()
                .submit(() -> serverSocket.accept());

        clientSocket = new Socket("127.0.0.1", port);
        try { hostSideSocket = serverSide.get(2, TimeUnit.SECONDS); }
        catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new IOException("setup failed", e);
        }

        // Set a timeout on hostSideSocket so raw hostIn.readByte() calls in tests
        // don't block forever if the client unexpectedly doesn't write.
        hostSideSocket.setSoTimeout(5000);

        hostIn   = new DataInputStream(hostSideSocket.getInputStream());
        hostOut  = new DataOutputStream(hostSideSocket.getOutputStream());
        clientIn  = new DataInputStream(clientSocket.getInputStream());
        clientOut = new DataOutputStream(clientSocket.getOutputStream());

        // Wire streams into NetworkManager — CLIENT perspective
        NetworkManager.injectHostStreamsForTesting(hostIn, hostOut);
        NetworkManager.injectClientStreamsForTesting(clientIn, clientOut);

        // Heroes: P1 is heroes[0] (remote), P2 is heroes[1] (local)
        p1Hero = new Hero(); p1Hero.HP = p1Hero.HT = 20;
        p2Hero = new Hero(); p2Hero.HP = p2Hero.HT = 20;
        Dungeon.heroes = new ArrayList<>();
        Dungeon.heroes.add(p1Hero);
        Dungeon.heroes.add(p2Hero);

        // CLIENT perspective: P2 is local
        Dungeon.hero = p2Hero;
        NetworkManager.lanMode = true;
        NetworkManager.setIsHostForTesting(false);  // ← CLIENT, not host
        NetworkManager.localPlayerIndex = 1;         // ← P2 is player 1 (index 1)
        NetworkManager.resetActionReaderForTesting();
        NetworkManager.resetCommitProtocolState();
    }

    @AfterEach
    void tearDown() throws IOException {
        NetworkManager.lanMode = false;
        NetworkManager.setIsHostForTesting(false);
        NetworkManager.resetActionReaderForTesting();
        NetworkManager.resetCommitProtocolState();
        NetworkManager.localPlayerIndex = 0;
        // Close sockets first to interrupt any blocking readByte() in reader threads
        if (clientSocket   != null && !clientSocket.isClosed())   clientSocket.close();
        if (hostSideSocket != null && !hostSideSocket.isClosed()) hostSideSocket.close();
        if (serverSocket   != null && !serverSocket.isClosed())   serverSocket.close();
        NetworkManager.injectHostStreamsForTesting(null, null);
        NetworkManager.injectClientStreamsForTesting(null, null);
        Dungeon.heroes = null;
        Dungeon.hero   = null;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Simulate the HOST (P1) sending an ACTION packet to P2 (client).
     * In production: host calls sendAction() → writes to outs.get(0) = hostOut.
     * Here: we write directly to hostOut to simulate that.
     */
    /** globalSeq for COMMITs the fake leader sends down to this client. */
    private int hostCommitSeq = 0;

    void hostSendsAction(int heroId, int targetPos) throws IOException {
        LanTestProtocol.writeActionCommit(hostOut, ++hostCommitSeq, heroId, 0,
                NetworkManager.ActionType.MOVE, targetPos);
    }

    /**
     * Run the remote-hero wait exactly as Hero.act() does it on P2's device:
     * block until the committed op lands in p1Hero's inbox, then decode it into
     * curAction (decode is deferred to execution time in v3).
     */
    boolean runP2WaitForP1(long maxMs) throws InterruptedException {
        synchronized (p1Hero.lanActionLock) {
            long deadline = System.currentTimeMillis() + maxMs;
            while (p1Hero.lanActionInbox.isEmpty() && p1Hero.curAction == null && NetworkManager.lanMode) {
                long rem = deadline - System.currentTimeMillis();
                if (rem <= 0) break;
                p1Hero.lanActionLock.wait(rem);
            }
            if (p1Hero.curAction == null) {
                NetworkManager.Commit c = p1Hero.lanActionInbox.pollFirst();
                if (c != null) p1Hero.curAction = NetworkManager.decodeAction(c.actionType, c.targetPos);
            }
            return p1Hero.curAction != null;
        }
    }

    // =========================================================================
    // Test 1: CLIENT immediately receives HOST's action (baseline)
    //
    // Verifies the client-side reader correctly uses clientIn (not ins.get(0)).
    // =========================================================================

    @Test
    void client_immediatelyReceivesHostAction() throws Exception {
        // P2's device: start reader for P1's hero (remote)
        NetworkManager.receiveActionAsync(p1Hero);

        // P1 (host) sends ACTION — writes to hostOut → arrives at clientIn
        hostSendsAction(0, 42);

        boolean got = runP2WaitForP1(2000);
        assertTrue(got,
                "P2 must receive P1's action. If this fails, the client-side " +
                "reader is using the wrong stream (ins.get(0) instead of clientIn).");
        assertNotNull(p1Hero.curAction);
        assertEquals(42, ((HeroAction.Move) p1Hero.curAction).dst);
    }

    // =========================================================================
    // Test 2: CLIENT receives HOST's action after a delay (simulates P1 thinking)
    //
    // Regression test for: "P1 moves, P2's screen doesn't update".
    // =========================================================================

    @Test
    void client_receivesHostActionAfterDelay() throws Exception {
        NetworkManager.receiveActionAsync(p1Hero);

        // P1 takes 300ms to make a move
        Thread.sleep(300);
        hostSendsAction(0, 77);

        boolean got = runP2WaitForP1(3000);
        assertTrue(got,
                "P2 must receive P1's action even after a 300ms delay. " +
                "Freeze = P2 is stuck in lanActionLock.wait() forever.");
        assertEquals(77, ((HeroAction.Move) p1Hero.curAction).dst);
    }

    // =========================================================================
    // Test 3: Full symmetric round-trip — P1 acts then P2 acts then P1 acts
    //
    // This is the core 2-player turn sequence from BOTH perspectives.
    // Previous tests only covered the HOST receiving CLIENT's action.
    // This covers the CLIENT receiving the HOST's action.
    // =========================================================================

    @Test
    void symmetric_p1ActsThenP2ActsThenP1Acts() throws Exception {
        // -------- Round 1: P1 acts, P2 must receive --------
        p1Hero.curAction = null;
        NetworkManager.receiveActionAsync(p1Hero);

        Thread.sleep(20);
        hostSendsAction(0, 1); // P1 sends move to cell 1

        boolean got1 = runP2WaitForP1(2000);
        assertTrue(got1, "Round 1: P2 must receive P1's action");
        assertEquals(1, ((HeroAction.Move) p1Hero.curAction).dst);
        p1Hero.curAction = null;

        // -------- Round 2: P2 acts (local action) --------
        p2Hero.curAction = new HeroAction.Move(2);
        // P2 sends its op to the leader as a REQUEST via clientOut
        LanTestProtocol.writeActionRequest(clientOut, 1, NetworkManager.ActionType.MOVE, 2);
        // Verify P2 knows its curAction is set
        assertNotNull(p2Hero.curAction, "P2 must have its own action ready");
        p2Hero.curAction = null; // consumed

        // -------- Round 3: P1 acts again, P2 must receive --------
        p1Hero.curAction = null;
        NetworkManager.receiveActionAsync(p1Hero); // idempotent — persistent reader keeps running

        Thread.sleep(20);
        hostSendsAction(0, 3); // P1 sends move to cell 3

        boolean got3 = runP2WaitForP1(2000);
        assertTrue(got3, "Round 3: P2 must receive P1's second action (no freeze on 3rd move)");
        assertEquals(3, ((HeroAction.Move) p1Hero.curAction).dst);
    }

    // =========================================================================
    // Test 4: CLIENT reader must survive multiple rounds without being replaced
    //
    // The reader thread loops continuously. After the first action is received
    // (and curAction is set), the reader loops back and waits for the NEXT
    // readByte(). This verifies that re-entry via receiveActionAsync is idempotent.
    // =========================================================================

    @Test
    void client_readerSurvivesMultipleRounds_noFreeze() throws Exception {
        for (int round = 0; round < 5; round++) {
            p1Hero.curAction = null;
            NetworkManager.receiveActionAsync(p1Hero); // idempotent after first call

            Thread.sleep(30 + round * 20L);
            hostSendsAction(0, round * 10 + 1);

            boolean got = runP2WaitForP1(2000);
            assertTrue(got, "Client round " + round + ": P2 must receive P1's action");
            assertEquals(round * 10 + 1, ((HeroAction.Move) p1Hero.curAction).dst);
        }
    }

    // =========================================================================
    // Test 5: HOST's action arrives before P2 starts waiting (pre-buffered)
    //
    // P1 sends ACTION before P2 calls receiveActionAsync. The packet sits in
    // the TCP buffer. When P2's reader starts, it must drain the buffer.
    // =========================================================================

    @Test
    void client_actionArrivesBeforeReaderStarts_stillReceived() throws Exception {
        // P1 sends action BEFORE P2 starts its reader
        hostSendsAction(0, 99);
        Thread.sleep(10); // give TCP time to buffer

        // NOW P2 starts the reader
        p1Hero.curAction = null;
        NetworkManager.receiveActionAsync(p1Hero);

        boolean got = runP2WaitForP1(2000);
        assertTrue(got,
                "P2 must receive a pre-buffered action (sent before reader started). " +
                "If this fails, the reader is not reading buffered data from clientIn.");
        assertEquals(99, ((HeroAction.Move) p1Hero.curAction).dst);
    }

    // =========================================================================
    // Test 6: HOST sends PING then ACTION — client reader must handle PING
    //
    // The host's ping sender may inject PING packets between turns. The client
    // reader must skip PINGs and continue waiting for the ACTION.
    // =========================================================================

    @Test
    void client_pingBeforeAction_readerSkipsPing() throws Exception {
        NetworkManager.receiveActionAsync(p1Hero);

        // P1 sends several PINGs (keepalive) then the real action
        for (int i = 0; i < 3; i++) {
            hostOut.writeByte(NetworkManager.PacketType.PING);
            hostOut.flush();
            Thread.sleep(10);
        }
        hostSendsAction(0, 55);

        boolean got = runP2WaitForP1(2000);
        assertTrue(got, "P2 must receive P1's action even after interleaved PINGs");
        assertEquals(55, ((HeroAction.Move) p1Hero.curAction).dst);
    }

    // =========================================================================
    // Test 7: Symmetric full freeze detection — the EXACT reported bug scenario
    //
    // P1 (host) moves first. Both devices must see the effect. P2 must
    // receive P1's action. Then P2 acts and P1 must receive P2's action.
    // If either direction fails, both players freeze.
    // =========================================================================

    @Test
    void fullFreeze_p1MovesP2SeesMoveP2MovesP1Sees() throws Exception {
        // --- P2's device: waiting for P1's action ---
        p1Hero.curAction = null;
        NetworkManager.receiveActionAsync(p1Hero);

        // P1 moves (host sends ACTION)
        Thread.sleep(50);
        hostSendsAction(0, 10);

        // P2 must see P1's move
        boolean p2sawP1 = runP2WaitForP1(3000);
        assertTrue(p2sawP1,
                "BUG: P2's screen did not update after P1 moved. " +
                "P2's reader for P1's hero failed to receive the ACTION packet from clientIn.");

        p1Hero.curAction = null;

        // --- P2 now makes their own move (sends to the leader) ---
        p2Hero.curAction = new HeroAction.Move(20);
        // v3: P2's device sends a REQUEST to the leader; the leader infers the
        // owning player from the stream, so a REQUEST carries no heroId field.
        // Hold clientOutLock so this manual write doesn't interleave with the
        // reader thread's ACK writes to the same stream.
        synchronized (NetworkManager.clientOutLockForTesting()) {
            LanTestProtocol.writeActionRequest(clientOut, 1, NetworkManager.ActionType.MOVE, 20);
        }

        // The leader (P1's device) reads P2's REQUEST from ins.get(0) = hostIn.
        // We verify by reading it directly here — skipping the ACK/PING frames the
        // client emits upstream after applying the round-1 commit.
        byte type;
        while (true) {
            type = hostIn.readByte(); // blocking read with 15s timeout from @Timeout
            if (type == NetworkManager.PacketType.ACK) { hostIn.readInt(); continue; }
            if (type == NetworkManager.PacketType.PING) { continue; }
            break;
        }
        assertEquals(NetworkManager.PacketType.REQUEST, type,
                "The leader must receive a REQUEST from P2.");
        hostIn.readInt(); // clientSeq
        byte inner    = hostIn.readByte();
        byte actType  = hostIn.readByte();
        int targetPos = hostIn.readInt();
        assertEquals(NetworkManager.InnerOp.ACTION, inner, "inner op must be ACTION");
        assertEquals(0, actType, "action type must be MOVE (0)");
        assertEquals(20, targetPos, "target pos must match P2's move");
    }
}
