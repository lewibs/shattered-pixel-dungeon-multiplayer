package com.shatteredpixel.shatteredpixeldungeon.network;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroAction;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * NetworkManager handles all TCP socket communication for LAN multiplayer.
 * It provides host/client mode initialization, action packet transmission,
 * hash synchronization, and remote hero action reception.
 */
public class NetworkManager {
    // Static flags and configuration
    public static boolean lanMode = false;
    public static int localPlayerIndex = 0;
    public static boolean gameStarted = false;

    // Instance fields
    private static ServerSocket serverSocket = null;
    private static List<Socket> peerSockets = new ArrayList<>();
    private static List<DataInputStream> ins = new ArrayList<>();
    private static List<DataOutputStream> outs = new ArrayList<>();
    private static boolean isHost = false;
    private static int connectedPlayerCount = 0;

    // Single socket for client mode
    private static Socket clientSocket = null;
    private static DataInputStream clientIn = null;
    private static DataOutputStream clientOut = null;

    // Socket timeout for all read operations (30 seconds)
    private static final int SOCKET_TIMEOUT_MS = 30000;

    // UDP discovery port
    public static final int UDP_DISCOVERY_PORT = 7778;

    // Callback invoked on the host when a new client connects (called on accept thread)
    public interface OnPlayerJoined {
        void call(int playerIndex, int totalPlayers);
    }
    public static OnPlayerJoined onPlayerJoined = null;

    // Packet type constants
    public static class PacketType {
        public static final byte ACTION = 1;
        public static final byte HASH = 2;
        public static final byte HANDSHAKE = 3;
        public static final byte PLAYER_JOINED = 4;
        public static final byte START = 5;
        public static final byte HERO_READY = 6;
        public static final byte SAVE_LOBBY_INFO = 7;
        public static final byte RESUME_START = 8;
        public static final byte HERO_CLAIM = 9;
        public static final byte RESUME_HANDSHAKE = 10;
        public static final byte CLASS_CLAIMED = 11;
        public static final byte CLASS_REJECTED = 12;
        public static final byte CLASS_UNCLAIMED = 13;
    }

    // HeroAction type constants for serialization
    public static class ActionType {
        public static final byte MOVE = 0;
        public static final byte ATTACK = 1;
        public static final byte INTERACT = 2;
        public static final byte PICKUP = 3;
        public static final byte OPEN_CHEST = 4;
        public static final byte UNLOCK = 5;
        public static final byte LVL_TRANSITION = 6;
        public static final byte BUY = 7;
        public static final byte MINE = 8;
        public static final byte ALCHEMY = 9;
        public static final byte REST = 10;
    }

    /**
     * Host: Opens a ServerSocket on the given port and begins accepting client connections.
     * Sets lanMode=true and isHost=true.
     */
    public static void hostGame(int port) throws IOException {
        try {
            serverSocket = new ServerSocket(port);
            lanMode = true;
            isHost = true;
            localPlayerIndex = 0;
            connectedPlayerCount = 1; // Host counts as player 0

            // Start accepting clients in a background thread
            new Thread(() -> acceptClientsLoop(), "network-accept-loop").start();

            GLog.p("Hosting game on port %d", port);
        } catch (IOException e) {
            GLog.n("Failed to host game: %s", e.getMessage());
            lanMode = false;
            throw e;
        }
    }

    /**
     * Client: Connects to a host at the given IP and port.
     * Sets lanMode=true and isHost=false.
     */
    public static void joinGame(String ip, int port) throws IOException {
        try {
            clientSocket = new Socket(ip, port);
            clientSocket.setSoTimeout(SOCKET_TIMEOUT_MS);

            clientIn = new DataInputStream(clientSocket.getInputStream());
            clientOut = new DataOutputStream(clientSocket.getOutputStream());

            lanMode = true;
            isHost = false;

            GLog.p("Connected to host at %s:%d", ip, port);
        } catch (IOException e) {
            GLog.n("Failed to connect to host: %s", e.getMessage());
            lanMode = false;
            cleanup();
            throw e;
        }
    }

    /**
     * Closes all sockets and streams, resets lanMode to false.
     */
    public static void disconnect() {
        lanMode = false;
        cleanup();
        GLog.w("Disconnected from network");
    }

    /**
     * Sends an action to all connected peers.
     * Serializes the HeroAction into an ACTION packet and writes to all output streams.
     */
    public static void sendAction(HeroAction action, int heroId) {
        if (!lanMode) return;

        try {
            byte actionType = encodeHeroAction(action);
            int targetPos = getActionTargetPos(action);

            if (isHost) {
                // Host sends to all connected clients
                for (DataOutputStream out : outs) {
                    out.writeByte(PacketType.ACTION);
                    out.writeInt(heroId);
                    out.writeByte(actionType);
                    out.writeInt(targetPos);
                    out.flush();
                }
            } else {
                // Client sends to host
                if (clientOut != null) {
                    clientOut.writeByte(PacketType.ACTION);
                    clientOut.writeInt(heroId);
                    clientOut.writeByte(actionType);
                    clientOut.writeInt(targetPos);
                    clientOut.flush();
                }
            }
        } catch (IOException e) {
            GLog.n("Failed to send action: %s", e.getMessage());
        }
    }

    /**
     * Starts a background thread to receive actions from a remote hero.
     * Reads ACTION packets and updates the hero's curAction, then notifies the Actor thread.
     */
    public static void receiveActionAsync(Hero remoteHero) {
        if (!lanMode || remoteHero == null) return;

        new Thread(() -> {
            try {
                DataInputStream in = isHost ? ins.get(0) : clientIn; // Simplified for now

                while (lanMode && remoteHero != null) {
                    try {
                        byte type = in.readByte();
                        if (type != PacketType.ACTION) continue;

                        int heroId = in.readInt();
                        byte actionType = in.readByte();
                        int targetPos = in.readInt();

                        // Decode and set the action
                        HeroAction decodedAction = decodeHeroAction(actionType, targetPos);
                        if (decodedAction != null) {
                            remoteHero.curAction = decodedAction;

                            // Notify actor thread that new action is available
                            synchronized (Actor.class) {
                                Actor.class.notifyAll();
                            }
                        }
                    } catch (SocketTimeoutException e) {
                        GLog.w("Peer disconnected (timeout)");
                        // Signal peer disconnection
                        lanMode = false;
                        break;
                    }
                }
            } catch (InterruptedIOException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                GLog.w("Error receiving action: %s", e.getMessage());
            }
        }, "net-reader-action").start();
    }

    /**
     * Sends a hash value for turn synchronization.
     * Called periodically (every 10 turns) to ensure game state consistency.
     */
    public static void sendHash(long hash, int turn) {
        if (!lanMode) return;

        try {
            if (isHost) {
                for (DataOutputStream out : outs) {
                    out.writeByte(PacketType.HASH);
                    out.writeInt(turn);
                    out.writeLong(hash);
                    out.flush();
                }
            } else {
                if (clientOut != null) {
                    clientOut.writeByte(PacketType.HASH);
                    clientOut.writeInt(turn);
                    clientOut.writeLong(hash);
                    clientOut.flush();
                }
            }
        } catch (IOException e) {
            GLog.n("Failed to send hash: %s", e.getMessage());
        }
    }

    /**
     * Receives a hash packet from the peer.
     * Blocking call with timeout set on the socket.
     */
    public static HashPacket receiveHash() throws IOException {
        if (!lanMode) return null;

        try {
            DataInputStream in = isHost ? ins.get(0) : clientIn;
            byte type = in.readByte();
            if (type != PacketType.HASH) {
                throw new IOException("Expected HASH packet, got " + type);
            }
            int turn = in.readInt();
            long hash = in.readLong();
            return new HashPacket(turn, hash);
        } catch (SocketTimeoutException e) {
            GLog.w("Hash receive timeout - peer may have disconnected");
            throw e;
        }
    }

    /**
     * Internal: Accepts client connections in a loop (host mode only).
     */
    private static void acceptClientsLoop() {
        while (lanMode && isHost && serverSocket != null) {
            try {
                Socket clientSocket = serverSocket.accept();
                clientSocket.setSoTimeout(SOCKET_TIMEOUT_MS);

                DataInputStream in = new DataInputStream(clientSocket.getInputStream());
                DataOutputStream out = new DataOutputStream(clientSocket.getOutputStream());

                peerSockets.add(clientSocket);
                ins.add(in);
                outs.add(out);

                connectedPlayerCount++;

                GLog.p("Client connected, total players: %d", connectedPlayerCount);

                // Send PLAYER_JOINED packet to new client
                out.writeByte(PacketType.PLAYER_JOINED);
                out.writeInt(connectedPlayerCount - 1); // playerIndex (0-based)
                out.writeInt(connectedPlayerCount);
                out.flush();

                // Broadcast updated player count to all clients
                for (DataOutputStream clientOut : outs) {
                    clientOut.writeByte(PacketType.PLAYER_JOINED);
                    clientOut.writeInt(connectedPlayerCount - 1);
                    clientOut.writeInt(connectedPlayerCount);
                    clientOut.flush();
                }

                // Notify host lobby scene that a player has joined
                if (onPlayerJoined != null) {
                    onPlayerJoined.call(connectedPlayerCount - 1, connectedPlayerCount);
                }
            } catch (IOException e) {
                if (lanMode) {
                    GLog.n("Error accepting client: %s", e.getMessage());
                }
            }
        }
    }

    /**
     * Returns true if this device is the LAN host.
     */
    public static boolean isHost() {
        return isHost;
    }

    /**
     * Returns the number of connected players (including host).
     */
    public static int getConnectedPlayerCount() {
        return connectedPlayerCount;
    }

    /**
     * Returns the client input stream (for client-side lobby packet reading).
     */
    public static DataInputStream getClientInput() {
        return clientIn;
    }

    /**
     * Host: Broadcasts a START packet to all connected clients and marks the game as started.
     */
    public static void sendStart(int playerCount, long seed) throws IOException {
        for (DataOutputStream out : outs) {
            out.writeByte(PacketType.START);
            out.writeInt(playerCount);
            out.writeLong(seed);
            out.flush();
        }
        gameStarted = true;
        GLog.p("START sent: playerCount=%d seed=%d", playerCount, seed);
    }

    /**
     * Host: Starts broadcasting UDP discovery packets on port 7778 every 2 seconds.
     * Stops when lanMode becomes false or gameStarted becomes true.
     *
     * @param roomName       human-readable room label shown in LanRoomListScene
     * @param currentPlayers number of players currently in the lobby
     */
    public static void startDiscoveryBroadcast(final String roomName, final int currentPlayers) {
        new Thread(() -> {
            DatagramSocket udp = null;
            try {
                udp = new DatagramSocket();
                udp.setBroadcast(true);
                InetAddress broadcast = InetAddress.getByName("255.255.255.255");
                while (lanMode && !gameStarted) {
                    byte[] data = buildDiscoveryPacket(roomName, currentPlayers);
                    DatagramPacket pkt = new DatagramPacket(data, data.length, broadcast, UDP_DISCOVERY_PORT);
                    udp.send(pkt);
                    Thread.sleep(2000);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                if (lanMode) GLog.n("UDP broadcast error: %s", e.getMessage());
            } finally {
                if (udp != null && !udp.isClosed()) udp.close();
            }
        }, "udp-broadcast").start();
    }

    /**
     * Builds a pipe-delimited UDP discovery packet payload.
     * Format: "SPD-MP|hostIP|tcpPort|roomName|currentPlayers|maxPlayers"
     */
    private static byte[] buildDiscoveryPacket(String roomName, int currentPlayers) {
        String payload = "SPD-MP" + "|" + getLocalIP() + "|7777|" + roomName + "|"
                + currentPlayers + "|4";
        return payload.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Returns the first non-loopback IPv4 address of this device,
     * or "unknown" if none is found.
     */
    public static String getLocalIP() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;
                Enumeration<InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (SocketException e) {
            GLog.n("Error getting local IP: %s", e.getMessage());
        }
        return "unknown";
    }

    /**
     * Internal: Cleanup all sockets and streams.
     */
    private static void cleanup() {
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            // Ignore
        }

        for (Socket s : peerSockets) {
            try {
                if (s != null && !s.isClosed()) {
                    s.close();
                }
            } catch (IOException e) {
                // Ignore
            }
        }

        try {
            if (clientSocket != null && !clientSocket.isClosed()) {
                clientSocket.close();
            }
        } catch (IOException e) {
            // Ignore
        }

        peerSockets.clear();
        ins.clear();
        outs.clear();
        serverSocket = null;
        clientSocket = null;
        clientIn = null;
        clientOut = null;
        isHost = false;
        connectedPlayerCount = 0;
        gameStarted = false;
        onPlayerJoined = null;
    }

    /**
     * Internal: Encodes a HeroAction into a byte action type.
     */
    private static byte encodeHeroAction(HeroAction action) {
        // This is a simplified implementation
        // In practice, we'd check the actual HeroAction type
        if (action == null) {
            return ActionType.REST;
        }
        // Default to REST if we can't determine the type
        return ActionType.REST;
    }

    /**
     * Internal: Gets the target position from a HeroAction.
     */
    private static int getActionTargetPos(HeroAction action) {
        if (action == null) {
            return 0;
        }
        // Default to 0 if we can't determine the target
        return 0;
    }

    /**
     * Internal: Decodes a byte action type and target position into a HeroAction.
     */
    private static HeroAction decodeHeroAction(byte actionType, int targetPos) {
        // This would need to be implemented based on the actual HeroAction types
        // For now, return null (remote hero will handle it)
        return null;
    }

    /**
     * Simple data holder for hash packets.
     */
    public static class HashPacket {
        public int turn;
        public long hash;

        public HashPacket(int turn, long hash) {
            this.turn = turn;
            this.hash = hash;
        }
    }
}
