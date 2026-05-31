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
 * Tests that PING packets work correctly and that removing the read timeout
 * does not break anything.
 *
 * Without the timeout=0 change: a player who thinks for > 30s gets
 * "Peer disconnected: SocketTimeoutException" and the game freezes.
 *
 * With timeout=0 + PING: the read blocks indefinitely; PING keeps the
 * connection alive and detects real disconnects via write failure.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class LanPingHeartbeatTest {

    private ServerSocket serverSocket;
    private Socket       hostSideSocket;
    private Socket       clientSocket;
    private DataInputStream  hostIn;
    private DataOutputStream hostOut;
    private DataInputStream  clientIn;
    private DataOutputStream clientOut;
    private Hero localHero;
    private Hero remoteHero;

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
        // NO socket timeout — that is the fix
        // hostSideSocket.setSoTimeout(0) is already the default
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

    void sendPing() throws IOException {
        clientOut.writeByte(NetworkManager.PacketType.PING);
        clientOut.flush();
    }

    void sendAction(int pos) throws IOException {
        clientOut.writeByte(NetworkManager.PacketType.ACTION);
        clientOut.writeInt(1);
        clientOut.writeByte(0);
        clientOut.writeInt(pos);
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

    // =========================================================================
    // PING packets are silently ignored — action still received after PINGs
    // =========================================================================

    @Test
    void pingsBeforeAction_actionStillReceived() throws Exception {
        NetworkManager.receiveActionAsync(remoteHero);

        // Send several PINGs before the real action
        for (int i = 0; i < 5; i++) {
            sendPing();
        }
        sendAction(42);

        boolean got = waitForAction(remoteHero, 2000);
        assertTrue(got, "Action must be received after PING packets");
        assertEquals(42, ((HeroAction.Move) remoteHero.curAction).dst);
    }

    @Test
    void pingsInterleavedBetweenTurns_allActionsReceived() throws Exception {
        for (int turn = 0; turn < 5; turn++) {
            remoteHero.curAction = null;
            NetworkManager.receiveActionAsync(remoteHero);

            // Each turn: send 3 pings then the action
            sendPing(); sendPing(); sendPing();
            sendAction(turn * 10 + 1);

            boolean got = waitForAction(remoteHero, 2000);
            assertTrue(got, "Turn " + turn + " action must arrive after PINGs");
            assertEquals(turn * 10 + 1, ((HeroAction.Move) remoteHero.curAction).dst);
            remoteHero.curAction = null;
            remoteHero.next();
        }
    }

    @Test
    void manyPingsThenAction_noConfusion() throws Exception {
        NetworkManager.receiveActionAsync(remoteHero);

        // Flood with pings
        for (int i = 0; i < 50; i++) {
            sendPing();
        }
        sendAction(99);

        boolean got = waitForAction(remoteHero, 2000);
        assertTrue(got, "Action must be received after 50 PINGs");
        assertEquals(99, ((HeroAction.Move) remoteHero.curAction).dst);
    }

    // =========================================================================
    // No timeout = no SocketTimeoutException even with long think time
    // Simulate by sending delayed action WITHOUT setting a socket timeout
    // =========================================================================

    @Test
    void noSocketTimeout_longThinkTime_noFreeze() throws Exception {
        // With no socket timeout the read blocks indefinitely.
        // Verify: action arrives after 500ms (simulating thinking) without any exception.
        NetworkManager.receiveActionAsync(remoteHero);

        Thread.sleep(500); // "thinking" — no timeout fires
        sendAction(77);

        boolean got = waitForAction(remoteHero, 3000);
        assertTrue(got, "Long think time must not freeze (no socket timeout)");
    }

    @Test
    void socketTimeoutIsZero_configuredCorrectly() {
        // Verify the production constant is 0 (no timeout)
        // If this fails the timeout was accidentally re-enabled
        assertEquals(0, getSoTimeoutMs(),
                "SOCKET_TIMEOUT_MS must be 0 — read timeout must be disabled. " +
                "A non-zero timeout causes SocketTimeoutException when peer thinks " +
                "longer than the timeout, permanently freezing the game.");
    }

    // Reflection to read the private constant for verification
    private int getSoTimeoutMs() {
        try {
            java.lang.reflect.Field f = NetworkManager.class.getDeclaredField("SOCKET_TIMEOUT_MS");
            f.setAccessible(true);
            return (int) f.get(null);
        } catch (Exception e) {
            fail("Could not read SOCKET_TIMEOUT_MS: " + e.getMessage());
            return -1;
        }
    }

    // =========================================================================
    // PING packet type exists and has a valid value
    // =========================================================================

    @Test
    void pingPacketType_defined() {
        // PING must be defined and not conflict with other packet types
        byte ping = NetworkManager.PacketType.PING;
        assertNotEquals(NetworkManager.PacketType.ACTION,         ping);
        assertNotEquals(NetworkManager.PacketType.HANDSHAKE,      ping);
        assertNotEquals(NetworkManager.PacketType.PLAYER_JOINED,  ping);
        assertNotEquals(NetworkManager.PacketType.START,          ping);
        assertNotEquals(NetworkManager.PacketType.ITEM_IDENTIFIED, ping);
        assertNotEquals(NetworkManager.PacketType.NAME_ANNOUNCE,  ping);
    }

    // =========================================================================
    // Full turn sequence with periodic pings simulating production behaviour
    // =========================================================================

    @Test
    void tenRoundsWithPings_allActionsReceived() throws Exception {
        // Simulate production: ping every 5s, action when player moves
        // We compress timing: ping every 20ms
        for (int round = 0; round < 10; round++) {
            remoteHero.curAction = null;
            NetworkManager.receiveActionAsync(remoteHero);

            // Send a ping (keepalive) then the actual action
            sendPing();
            Thread.sleep(10);
            sendAction(round * 3 + 1);

            boolean got = waitForAction(remoteHero, 2000);
            assertTrue(got, "Round " + round + " must not freeze");
            remoteHero.curAction = null;
            remoteHero.next();
        }
    }
}
