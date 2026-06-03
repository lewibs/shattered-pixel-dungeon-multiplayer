package com.shatteredpixel.shatteredpixeldungeon.items;

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
 * Tests that item identification packets are correctly sent and received
 * over real TCP sockets, mirroring the production LAN path.
 *
 * Production flow:
 *   P1 uses potion → Potion.setKnown() → NetworkManager.sendItemIdentified(className)
 *                                        → ITEM_IDENTIFIED packet on wire
 *   P2 reader     → reads packet         → instantiates item → item.identify(false)
 *                                        → P2's handler now knows the item
 */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class ItemIdentificationNetworkTest {

    private ServerSocket     serverSocket;
    private Socket           hostSideSocket;
    private Socket           clientSocket;
    private DataInputStream  hostIn;
    private DataOutputStream hostOut;
    private DataInputStream  clientIn;
    private DataOutputStream clientOut;

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
        hostIn   = new DataInputStream(hostSideSocket.getInputStream());
        hostOut  = new DataOutputStream(hostSideSocket.getOutputStream());
        clientIn  = new DataInputStream(clientSocket.getInputStream());
        clientOut = new DataOutputStream(clientSocket.getOutputStream());

        NetworkManager.injectHostStreamsForTesting(hostIn, hostOut);
        NetworkManager.injectClientStreamsForTesting(clientIn, clientOut);
        NetworkManager.lanMode = true;
        NetworkManager.setIsHostForTesting(true);
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
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Read one ITEM_IDENTIFIED packet from the client's perspective (sent by host). */
    String readIdentificationPacket(DataInputStream in) throws IOException {
        byte type = in.readByte();
        assertEquals(NetworkManager.PacketType.ITEM_IDENTIFIED, type,
                "Packet type must be ITEM_IDENTIFIED");
        return in.readUTF();
    }

    // =========================================================================
    // Packet format — class name preserved exactly
    // =========================================================================

    @Test
    void sendItemIdentified_writesCorrectPacketType() throws Exception {
        String className = "com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfHealing";
        NetworkManager.sendItemIdentified(className);

        byte type = clientIn.readByte();
        assertEquals(NetworkManager.PacketType.ITEM_IDENTIFIED, type,
                "First byte must be ITEM_IDENTIFIED packet type");
    }

    @Test
    void sendItemIdentified_classNamePreservedExactly() throws Exception {
        String className = "com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfHealing";
        NetworkManager.sendItemIdentified(className);

        String received = readIdentificationPacket(clientIn);
        assertEquals(className, received,
                "Class name must be preserved exactly across the wire");
    }

    @Test
    void sendItemIdentified_scrollClassNamePreserved() throws Exception {
        String className = "com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfIdentify";
        NetworkManager.sendItemIdentified(className);

        String received = readIdentificationPacket(clientIn);
        assertEquals(className, received);
    }

    @Test
    void multipleItemsIdentified_allReceived() throws Exception {
        String[] items = {
            "com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfHealing",
            "com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfIdentify",
            "com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfStrength"
        };

        for (String name : items) {
            NetworkManager.sendItemIdentified(name);
        }

        for (String expected : items) {
            assertEquals(expected, readIdentificationPacket(clientIn),
                    "Each identified item must be received in order");
        }
    }

    @Test
    void sendItemIdentified_whenNotLanMode_doesNothing() throws Exception {
        NetworkManager.lanMode = false;
        NetworkManager.sendItemIdentified("com.example.SomeItem");

        // Set a short timeout — if a byte arrives it means a packet was wrongly sent
        clientSocket.setSoTimeout(100);
        try {
            clientIn.readByte();
            fail("No packet should be sent when lanMode=false");
        } catch (SocketTimeoutException e) {
            // Expected — no packet was sent
        } finally {
            clientSocket.setSoTimeout(0);
            NetworkManager.lanMode = true;
        }
    }

    // =========================================================================
    // Packet received — class name is round-tripped correctly for all item types
    // =========================================================================

    @Test
    void potionClassName_roundTrip() throws Exception {
        // Simulate the exact class names that appear in production
        String[] potionClasses = {
            "com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfHealing",
            "com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfStrength",
            "com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfMindVision",
            "com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfFrost",
            "com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfLiquidFlame",
        };

        for (String name : potionClasses) {
            NetworkManager.sendItemIdentified(name);
            assertEquals(name, readIdentificationPacket(clientIn),
                    "Potion class name must round-trip: " + name);
        }
    }

    @Test
    void scrollClassName_roundTrip() throws Exception {
        String[] scrollClasses = {
            "com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfIdentify",
            "com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfUpgrade",
            "com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfTeleportation",
            "com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfRemoveCurse",
            "com.shatteredpixel.shatteredpixeldungeon.items.scrolls.ScrollOfMirrorImage",
        };

        for (String name : scrollClasses) {
            NetworkManager.sendItemIdentified(name);
            assertEquals(name, readIdentificationPacket(clientIn),
                    "Scroll class name must round-trip: " + name);
        }
    }

    @Test
    void exoticScrollClassName_roundTrip() throws Exception {
        // Exotic scrolls share the same mechanism
        String className = "com.shatteredpixel.shatteredpixeldungeon.items.scrolls.exotic.ScrollOfAffection";
        NetworkManager.sendItemIdentified(className);
        assertEquals(className, readIdentificationPacket(clientIn),
                "Exotic scroll class name must round-trip");
    }

    @Test
    void exoticPotionClassName_roundTrip() throws Exception {
        String className = "com.shatteredpixel.shatteredpixeldungeon.items.potions.exotic.PotionOfAdrenalineSurge";
        NetworkManager.sendItemIdentified(className);
        assertEquals(className, readIdentificationPacket(clientIn));
    }

    // =========================================================================
    // Multiple identifications in rapid succession
    // =========================================================================

    @Test
    void rapidIdentification_10items_allReceived() throws Exception {
        ArrayList<String> sent = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String name = "com.example.Item" + i;
            sent.add(name);
            NetworkManager.sendItemIdentified(name);
        }

        for (String expected : sent) {
            assertEquals(expected, readIdentificationPacket(clientIn),
                    "Rapid identification: each item must be received");
        }
    }

    // =========================================================================
    // Client sends to host (symmetric test)
    // =========================================================================

    @Test
    void clientSendsIdentification_hostReceives() throws Exception {
        // Switch to client perspective
        NetworkManager.setIsHostForTesting(false);
        String className = "com.shatteredpixel.shatteredpixeldungeon.items.potions.PotionOfHealing";
        NetworkManager.sendItemIdentified(className);

        // Host reads from hostIn
        assertEquals(NetworkManager.PacketType.ITEM_IDENTIFIED, hostIn.readByte(),
                "Host must receive ITEM_IDENTIFIED from client");
        assertEquals(className, hostIn.readUTF(),
                "Host must receive correct class name from client");

        NetworkManager.setIsHostForTesting(true);
    }
}
