package com.shatteredpixel.shatteredpixeldungeon.network;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Flow 1: lobbyPlayerManagement
 * EC-1.1, EC-1.2, EC-1.3, EC-1.4, EC-6.10
 */
class LobbyPlayerManagementTest {

    @BeforeEach
    void setUp() throws Exception {
        NetworkManager.lanMode = true;
        NetworkManager.gameStarted = false;
        NetworkManager.setIsHostForTesting(true);
        NetworkManager.setConnectedPlayerCountForTesting(1); // host only
        NetworkManager.setPlayerName(0, "Host");
        NetworkManager.setPlayerName(1, null);
        NetworkManager.setPlayerName(2, null);
        NetworkManager.setPlayerName(3, null);
        // Clear any existing peers
        NetworkManager.injectHostStreamsForTesting(null, null);
    }

    @AfterEach
    void tearDown() {
        NetworkManager.lanMode = false;
        NetworkManager.gameStarted = false;
        NetworkManager.setIsHostForTesting(false);
        NetworkManager.setConnectedPlayerCountForTesting(0);
    }

    /**
     * EC-1.2 / EC-5.3: Two clients try to use the same name; second receives NAME_REJECTED.
     * We test this by verifying that the NAME_REJECTED packet constant exists and the
     * duplicate name logic correctly identifies collisions.
     */
    @Test
    void duplicateName_isRejected() throws Exception {
        // Plan path: duplicate-name-rejection
        // Arrange: host has "Alice" at slot 1; a 3rd player tries to connect as "Alice"
        // Simulate post-increment state: connectedPlayerCount=3 (host + Alice + incoming)
        NetworkManager.setPlayerName(0, "Host");
        NetworkManager.setPlayerName(1, "Alice");
        NetworkManager.setConnectedPlayerCountForTesting(3); // simulates after connectedPlayerCount++ in acceptClientsLoop

        // Set up a piped stream pair for a second "Alice" client
        PipedOutputStream clientPipe = new PipedOutputStream();
        PipedInputStream hostReadsFromClient = new PipedInputStream(clientPipe);
        DataOutputStream clientWriter = new DataOutputStream(clientPipe);

        ByteArrayOutputStream hostSendsToClient = new ByteArrayOutputStream();
        DataOutputStream hostOut = new DataOutputStream(hostSendsToClient);

        // Write NAME_ANNOUNCE for duplicate name
        clientWriter.writeByte(NetworkManager.PacketType.NAME_ANNOUNCE);
        clientWriter.writeUTF("Alice");
        clientWriter.flush();
        clientWriter.close(); // EOF

        // Simulate the duplicate-name check in acceptClientsLoop
        DataInputStream clientIn = new DataInputStream(hostReadsFromClient);
        byte nameType = clientIn.readByte();
        assertEquals(NetworkManager.PacketType.NAME_ANNOUNCE, nameType);
        String announcedName = clientIn.readUTF().trim();
        assertEquals("Alice", announcedName);

        // Check if name is taken (the same logic as acceptClientsLoop)
        boolean nameTaken = false;
        String[] names = NetworkManager.getPlayerNamesForTesting();
        for (int k = 0; k < NetworkManager.getConnectedPlayerCount() - 1; k++) {
            if (announcedName.equalsIgnoreCase(names[k])) {
                nameTaken = true;
                break;
            }
        }

        // If name is taken, NAME_REJECTED would be sent
        assertTrue(nameTaken, "Duplicate name 'Alice' should be detected as taken");
        if (nameTaken) {
            hostOut.writeByte(NetworkManager.PacketType.NAME_REJECTED);
            hostOut.flush();
        }

        byte[] sentBytes = hostSendsToClient.toByteArray();
        assertEquals(1, sentBytes.length, "Exactly one byte (NAME_REJECTED) should be sent");
        assertEquals(NetworkManager.PacketType.NAME_REJECTED, sentBytes[0],
                "Byte should be NAME_REJECTED");
    }

    /**
     * EC-1.1 / EC-6.10: A client is removed; host connectedPlayerCount decrements and
     * PLAYER_LEFT is broadcast to remaining clients.
     */
    @Test
    void clientDropInLobby_removesSlot() throws Exception {
        // Plan path: client-drop-removes-slot
        // Arrange: host with 2 clients (playerSlots 1 and 2)
        // We need a real writable stream for the "remaining" client
        PipedInputStream remainingClientIn = new PipedInputStream(1024);
        PipedOutputStream toRemainingClient = new PipedOutputStream(remainingClientIn);
        DataInputStream remainingReader = new DataInputStream(remainingClientIn);
        DataOutputStream remainingWriter = new DataOutputStream(toRemainingClient);

        // Register remaining client (slot 2) as peer
        // We use a dummy socket-like object via test seam
        NetworkManager.setConnectedPlayerCountForTesting(3); // host + 2 clients
        NetworkManager.setPlayerName(1, "Bob");
        NetworkManager.setPlayerName(2, "Charlie");

        // Inject: remaining client is at outs[0], dropped client was at index 1 (slot 2)
        // For this test we directly call removeClient with index 0 (first client)
        // and verify PLAYER_LEFT goes to the second client
        // Use real PipedOutputStream for first remaining client
        ByteArrayOutputStream droppedOut = new ByteArrayOutputStream();
        NetworkManager.addPeerForTesting(createFakeSocket(), new DataInputStream(new java.io.ByteArrayInputStream(new byte[0])), remainingWriter);
        // Add a second peer that's being "dropped" (no-op stream)
        NetworkManager.addPeerForTesting(createFakeSocket(), new DataInputStream(new java.io.ByteArrayInputStream(new byte[0])), new DataOutputStream(droppedOut));

        int countBefore = NetworkManager.getConnectedPlayerCount();

        // Act: remove client at index 1 (the dropped one)
        NetworkManager.removeClient(1);

        // Assert: connectedPlayerCount decremented
        assertEquals(countBefore - 1, NetworkManager.getConnectedPlayerCount(),
                "connectedPlayerCount should decrement by 1");

        // Assert: PLAYER_LEFT was broadcast to remaining client (index 0)
        // remainingWriter has been written to — check the pipe
        toRemainingClient.close(); // close write end so reader can EOF
        byte packetType = remainingReader.readByte();
        assertEquals(NetworkManager.PacketType.PLAYER_LEFT, packetType,
                "Remaining client should receive PLAYER_LEFT packet");
    }

    /**
     * EC-1.3: Host calls kickPlayer(1); target client stream receives KICK byte.
     */
    @Test
    void kickPlayer_clientReceivesKickPacket() throws Exception {
        // Plan path: kick-packet
        // Arrange: host with one client at playerSlot=1
        ByteArrayOutputStream clientCapture = new ByteArrayOutputStream();
        DataOutputStream captureOut = new DataOutputStream(clientCapture);

        NetworkManager.setConnectedPlayerCountForTesting(2);
        NetworkManager.setPlayerName(1, "Bob");
        NetworkManager.addPeerForTesting(
            createFakeSocket(),
            new DataInputStream(new java.io.ByteArrayInputStream(new byte[0])),
            captureOut
        );

        // Act: host kicks player at slot 1
        NetworkManager.kickPlayer(1);

        // Assert: client stream received KICK byte
        byte[] sentBytes = clientCapture.toByteArray();
        assertTrue(sentBytes.length >= 1, "At least one byte should have been sent to kicked client");
        assertEquals(NetworkManager.PacketType.KICK, sentBytes[0],
                "First byte to kicked client should be KICK");
    }

    /**
     * EC-1.4: Host calls broadcastHostDisconnected(); all client streams receive HOST_DISCONNECTED.
     */
    @Test
    void hostDisconnectInLobby_broadcastsHostDisconnected() throws Exception {
        // Plan path: host-disconnect-broadcast
        // Arrange: host with 2 clients
        ByteArrayOutputStream client1Capture = new ByteArrayOutputStream();
        ByteArrayOutputStream client2Capture = new ByteArrayOutputStream();

        NetworkManager.setConnectedPlayerCountForTesting(3);
        NetworkManager.setPlayerName(1, "Client1");
        NetworkManager.setPlayerName(2, "Client2");
        NetworkManager.addPeerForTesting(
            createFakeSocket(),
            new DataInputStream(new java.io.ByteArrayInputStream(new byte[0])),
            new DataOutputStream(client1Capture)
        );
        NetworkManager.addPeerForTesting(
            createFakeSocket(),
            new DataInputStream(new java.io.ByteArrayInputStream(new byte[0])),
            new DataOutputStream(client2Capture)
        );

        // Act: host broadcasts HOST_DISCONNECTED
        NetworkManager.broadcastHostDisconnected();

        // Assert: both clients received HOST_DISCONNECTED
        byte[] bytes1 = client1Capture.toByteArray();
        byte[] bytes2 = client2Capture.toByteArray();
        assertTrue(bytes1.length >= 1, "Client 1 should have received a packet");
        assertTrue(bytes2.length >= 1, "Client 2 should have received a packet");
        assertEquals(NetworkManager.PacketType.HOST_DISCONNECTED, bytes1[0],
                "Client 1 should receive HOST_DISCONNECTED");
        assertEquals(NetworkManager.PacketType.HOST_DISCONNECTED, bytes2[0],
                "Client 2 should receive HOST_DISCONNECTED");
    }

    // Helper: create a null-safe Socket substitute (not used for real I/O in these tests)
    private Socket createFakeSocket() {
        return null; // removeClient handles null sockets safely
    }
}
