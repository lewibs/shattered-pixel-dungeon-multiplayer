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
 * and call receiveActionAsync() exactly as production code does, triggering
 * real socket behaviour including SocketTimeoutException.
 *
 * Goal: find tests that ACTUALLY FAIL (freeze/deadlock), then add the fix.
 */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class LanRealSocketTest {

    // Simulated "think time" delay — no actual socket timeout set
    // (production uses THINK_DELAY_MS=0 with periodic PING for disconnect detection)
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

        // No socket timeout — matches production (THINK_DELAY_MS=0)

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
        NetworkManager.resetActionReaderForTesting(); // reset singleton guard
    }

    @AfterEach
    void tearDown() throws IOException {
        NetworkManager.lanMode = false;
        NetworkManager.setIsHostForTesting(false);
        NetworkManager.resetActionReaderForTesting();
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

    /** Write an ACTION packet from the client to the host (simulates peer moving). */
    void clientSendsAction(int heroId, int targetPos) throws IOException {
        clientOut.writeByte(NetworkManager.PacketType.ACTION);
        clientOut.writeInt(heroId);
        clientOut.writeByte(0); // Move action type
        clientOut.writeInt(targetPos);
        clientOut.flush();
    }

    /** Run the remote-hero wait as Hero.act() does it. */
    boolean runRemoteWait(Hero h, long maxMs) throws InterruptedException {
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
        assertNotNull(remoteHero.curAction);
    }

    // =========================================================================
    // Test 2: action arrives AFTER socket timeout fires
    //
    // With current code: reader gets SocketTimeoutException → breaks loop →
    // nobody ever sets curAction → game freezes.
    // This test SHOULD FAIL with the unfixed code.
    // =========================================================================

    @Test
    void actionAfterSocketTimeout_mustNotFreezeGame() throws Exception {
        // Start the real reader (uses actual hostIn socket)
        NetworkManager.receiveActionAsync(remoteHero);

        // Wait longer than THINK_DELAY_MS so the reader's readByte() times out
        Thread.sleep(THINK_DELAY_MS + 100);

        // NOW send the action — with broken code the reader is dead, so this is lost
        clientSendsAction(1, 99);

        // Game must still receive the action within 2s
        boolean got = runRemoteWait(remoteHero, 2000);
        assertTrue(got,
                "Action arriving AFTER socket timeout must still be received. " +
                "If this fails, the reader exited on SocketTimeoutException and " +
                "the game is permanently frozen — this is the real-world deadlock.");
    }

    // =========================================================================
    // Test 3: multiple timeouts then action
    // =========================================================================

    @Test
    void threeTimeoutsThenAction_mustStillReceive() throws Exception {
        NetworkManager.receiveActionAsync(remoteHero);

        // Let the reader time out 3 times (3 × THINK_DELAY_MS)
        Thread.sleep((THINK_DELAY_MS + 50) * 3);

        // Send action after multiple timeouts
        clientSendsAction(1, 7);

        boolean got = runRemoteWait(remoteHero, 2000);
        assertTrue(got,
                "After 3 socket timeouts the reader must still be alive and receive the action");
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
        remoteHero.curAction = null;
        remoteHero.next();

        // Round 3: local hero acts again
        localHero.curAction = new HeroAction.Move(3);
        assertNotNull(localHero.curAction, "Round 3: local must still be able to act");
        localHero.curAction = null;
    }

    // =========================================================================
    // Test 5: slow peer (think time > socket timeout) across multiple rounds
    // =========================================================================

    @Test
    void slowPeer_multipleRounds_noFreeze() throws Exception {
        for (int round = 0; round < 3; round++) {
            remoteHero.curAction = null;
            NetworkManager.receiveActionAsync(remoteHero);

            // Peer "thinks" for longer than socket timeout
            Thread.sleep(THINK_DELAY_MS + 80);

            // Then sends action
            clientSendsAction(1, round * 10);

            boolean got = runRemoteWait(remoteHero, 2000);
            assertTrue(got,
                    "Slow peer round " + round + ": action must be received even after think time > socket timeout");
            remoteHero.curAction = null;
            remoteHero.next();
        }
    }

    // =========================================================================
    // Test 6: reader must survive disconnect then reconnect path
    // =========================================================================

    @Test
    void readerSurvivesTimeout_singletonGuardResets() throws Exception {
        // Start reader
        NetworkManager.receiveActionAsync(remoteHero);

        // Wait for timeout to fire
        Thread.sleep(THINK_DELAY_MS + 100);

        // actionReaderRunning should be false again so a new reader can start
        // (if the reader broke on timeout and reset the flag)
        // OR it should still be true (if the reader continued instead of breaking)
        // Either way, calling receiveActionAsync again must work
        NetworkManager.receiveActionAsync(remoteHero); // second call — must not be a no-op forever

        clientSendsAction(1, 55);
        boolean got = runRemoteWait(remoteHero, 2000);
        assertTrue(got, "After reader restart, action must be receivable");
    }
}
