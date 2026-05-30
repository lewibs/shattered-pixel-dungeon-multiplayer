package com.shatteredpixel.shatteredpixeldungeon.network;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroAction;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.watabou.noosa.Game;
import com.watabou.utils.Reflection;
import com.watabou.utils.Signal;

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
    public static String playerName = "Player";  // session-only, not persisted

    // Instance fields
    private static ServerSocket serverSocket = null;
    private static List<Socket> peerSockets = new ArrayList<>();
    private static List<DataInputStream> ins = new ArrayList<>();
    private static List<DataOutputStream> outs = new ArrayList<>();
    private static boolean isHost = false;
    private static int connectedPlayerCount = 0;
    private static String[] playerNames = new String[4];  // stores player names by index

    // Single socket for client mode
    private static Socket clientSocket = null;
    private static DataInputStream clientIn = null;
    private static DataOutputStream clientOut = null;

    // Socket timeout for all read operations (30 seconds)
    private static final int SOCKET_TIMEOUT_MS = 30000;

    // UDP discovery port
    public static final int UDP_DISCOVERY_PORT = 7778;

    // Disconnect event signal
    public static class PeerDisconnected {
        public Hero hero;

        public PeerDisconnected(Hero hero) {
            this.hero = hero;
        }
    }

    public static Signal<PeerDisconnected> peerDisconnectSignal = new Signal<>();

    // Getter for host status
    public static boolean isHostMode() {
        return isHost;
    }

    // Callback invoked on the host when a new client connects (called on accept thread)
    public interface OnPlayerJoined {
        void call(int playerIndex, int totalPlayers);
    }
    public static OnPlayerJoined onPlayerJoined = null;

    // Callback invoked when an item is identified on a peer device
    public static Runnable onItemIdentified = null;
    public static String lastIdentifiedClass = null;

    // Callbacks for CLASS_CLAIMED/UNCLAIMED received from peers (used to update HeroSelectScene UI)
    public interface OnClassUpdate { void call(int playerIndex, HeroClass heroClass); }
    public static OnClassUpdate onClassClaimedReceived   = null;
    public static OnClassUpdate onClassUnclaimedReceived = null;

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
        public static final byte ITEM_IDENTIFIED = 14;
        public static final byte NAME_ANNOUNCE    = 15; // client → host immediately on connect
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
            playerNames[0] = playerName; // store the host's name at index 0

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

            // Immediately announce our name to the host so it can include it in PLAYER_JOINED
            clientOut.writeByte(PacketType.NAME_ANNOUNCE);
            clientOut.writeUTF(playerName);
            clientOut.flush();

            GLog.p("Connected to host at %s:%d as \"%s\"", ip, port, playerName);
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
     * Opens a new ServerSocket on port 7777 and resumes UDP discovery broadcast.
     * Called when "Open Rejoin Room" is tapped - allows disconnected hero slot to be claimed by a new player.
     */
    public static void openRejoinRoom() throws IOException {
        try {
            // Clear previous host state
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
            peerSockets.clear();
            ins.clear();
            outs.clear();

            // Re-open ServerSocket
            serverSocket = new ServerSocket(7777);
            lanMode = true;
            isHost = true;
            gameStarted = false;

            // Start accepting clients in background
            new Thread(() -> acceptClientsLoop(), "network-accept-loop").start();

            // Resume UDP discovery broadcast
            String roomName = "Rejoin Game";
            int currentPlayers = Dungeon.heroes != null ? Dungeon.heroes.size() : 1;
            startDiscoveryBroadcast(roomName, currentPlayers);

            GLog.p("Rejoin room opened on port 7777");
        } catch (IOException e) {
            GLog.n("Failed to open rejoin room: %s", e.getMessage());
            lanMode = false;
            throw e;
        }
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
            DataInputStream in = null;
            try {
                in = isHost ? ins.get(0) : clientIn; // Simplified for now

                while (lanMode && remoteHero != null) {
                    try {
                        byte type = in.readByte();

                        if (type == PacketType.ACTION) {
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
                        } else if (type == PacketType.ITEM_IDENTIFIED) {
                            String className = in.readUTF();
                            Game.runOnRenderThread(() -> {
                                try {
                                    Class<?> cls = Class.forName(className);
                                    Item item = (Item) Reflection.newInstance(cls);
                                    if (item != null) item.identify(false);
                                } catch (Exception e) {
                                    GLog.w("Could not apply remote identification: %s", e.getMessage());
                                }
                            });
                        } else if (type == PacketType.CLASS_CLAIMED) {
                            int pidx = in.readInt();
                            byte ord = in.readByte();
                            HeroClass cls = ordinalToHeroClass(ord);
                            if (onClassClaimedReceived != null)
                                onClassClaimedReceived.call(pidx, cls);
                        } else if (type == PacketType.CLASS_UNCLAIMED) {
                            int pidx = in.readInt();
                            byte ord = in.readByte();
                            HeroClass cls = ordinalToHeroClass(ord);
                            if (onClassUnclaimedReceived != null)
                                onClassUnclaimedReceived.call(pidx, cls);
                        }
                    } catch (IOException e) {
                        if (!Thread.currentThread().isInterrupted()) {
                            GLog.w("Peer disconnected: %s", e.getClass().getSimpleName());
                            // Dispatch disconnect signal
                            peerDisconnectSignal.dispatch(new PeerDisconnected(remoteHero));
                            // Wake actor thread
                            synchronized (Actor.class) {
                                Actor.class.notifyAll();
                            }
                        }
                        break;
                    }
                }
            } catch (Exception e) {
                if (e instanceof InterruptedIOException) {
                    Thread.currentThread().interrupt();
                }
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
     * Sends an item identification packet to broadcast that an item has been identified.
     * The class name is sent so peers can instantiate and identify the same item.
     */
    public static void sendItemIdentified(String itemClassName) {
        if (!lanMode) return;
        try {
            if (isHost) {
                for (DataOutputStream out : outs) {
                    out.writeByte(PacketType.ITEM_IDENTIFIED);
                    out.writeUTF(itemClassName);
                    out.flush();
                }
            } else if (clientOut != null) {
                clientOut.writeByte(PacketType.ITEM_IDENTIFIED);
                clientOut.writeUTF(itemClassName);
                clientOut.flush();
            }
        } catch (IOException e) {
            GLog.w("Failed to send item identification: %s", e.getMessage());
        }
    }

    /**
     * Receives a hash packet from the peer.
     * Blocking call with timeout set on the socket.
     */
    public static HashPacket receiveHash(Hero associatedHero) throws IOException {
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
        } catch (IOException e) {
            if (!Thread.currentThread().isInterrupted()) {
                GLog.w("Hash receive failed: %s - peer may have disconnected", e.getClass().getSimpleName());
                // Dispatch disconnect signal
                if (associatedHero != null) {
                    peerDisconnectSignal.dispatch(new PeerDisconnected(associatedHero));
                    // Wake actor thread
                    synchronized (Actor.class) {
                        Actor.class.notifyAll();
                    }
                }
            }
            throw e;
        }
    }

    /**
     * Sends a resync bundle (serialized dungeon state) to the peer.
     * Called by host when a desync is detected.
     */
    public static void sendResyncBundle(byte[] bytes) throws IOException {
        if (!lanMode) return;

        try {
            if (isHost) {
                for (DataOutputStream out : outs) {
                    out.writeByte(PacketType.RESUME_HANDSHAKE);
                    out.writeInt(bytes.length);
                    out.write(bytes);
                    out.flush();
                }
            } else {
                if (clientOut != null) {
                    clientOut.writeByte(PacketType.RESUME_HANDSHAKE);
                    clientOut.writeInt(bytes.length);
                    clientOut.write(bytes);
                    clientOut.flush();
                }
            }
        } catch (IOException e) {
            GLog.n("Failed to send resync bundle: %s", e.getMessage());
            throw e;
        }
    }

    /**
     * Receives a resync bundle from the peer.
     * Blocking call that waits for the full bundle.
     */
    public static byte[] receiveResyncBundle() throws IOException {
        if (!lanMode) return null;

        try {
            DataInputStream in = isHost ? ins.get(0) : clientIn;
            byte type = in.readByte();
            if (type != PacketType.RESUME_HANDSHAKE) {
                throw new IOException("Expected RESUME_HANDSHAKE packet, got " + type);
            }
            int len = in.readInt();
            byte[] bytes = new byte[len];
            in.readFully(bytes);
            return bytes;
        } catch (SocketTimeoutException e) {
            GLog.w("Resync bundle receive timeout - peer may have disconnected");
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
                int newPlayerIndex = connectedPlayerCount - 1;

                // Read the client's name announcement (sent immediately on connect)
                String clientName = "Player " + (newPlayerIndex + 1); // fallback
                try {
                    clientSocket.setSoTimeout(2000); // short timeout for name read
                    byte nameType = in.readByte();
                    if (nameType == PacketType.NAME_ANNOUNCE) {
                        String announced = in.readUTF().trim();
                        if (!announced.isEmpty()) clientName = announced;
                    }
                } catch (IOException e) {
                    GLog.w("Could not read client name, using fallback");
                } finally {
                    clientSocket.setSoTimeout(SOCKET_TIMEOUT_MS); // restore normal timeout
                }
                playerNames[newPlayerIndex] = clientName;

                GLog.p("Client \"%s\" connected, total players: %d", clientName, connectedPlayerCount);

                // Tell the new client their assigned index and name, then broadcast to all
                for (int i = 0; i < outs.size(); i++) {
                    DataOutputStream dest = outs.get(i);
                    dest.writeByte(PacketType.PLAYER_JOINED);
                    dest.writeInt(newPlayerIndex);
                    dest.writeInt(connectedPlayerCount);
                    dest.writeUTF(clientName);
                    dest.flush();
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
     * Returns the array of player names by index.
     */
    public static String[] getPlayerNames() {
        return playerNames;
    }

    /**
     * Sets the player name for a given index.
     */
    public static void setPlayerName(int index, String name) {
        if (index >= 0 && index < playerNames.length) {
            playerNames[index] = name != null ? name : "Player";
        }
    }

    /**
     * Gets the player name for a given index.
     */
    public static String getPlayerName(int index) {
        if (index >= 0 && index < playerNames.length && playerNames[index] != null) {
            return playerNames[index];
        }
        return "Player";
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
    private static HeroClass ordinalToHeroClass(byte ord) {
        HeroClass[] vals = HeroClass.values();
        return (ord >= 0 && ord < vals.length) ? vals[ord] : null;
    }

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
        playerNames = new String[4];  // reset player names array
    }

    // Test seam — override sendAction for unit tests (null = use real network)
    public static java.util.function.BiConsumer<HeroAction, Integer> sendActionOverride = null;

    // Test seam — override host flag for unit tests
    public static void setIsHostForTesting(boolean host) { isHost = host; }

    // Test seam — fire sendAction check without touching sockets
    public static void sendActionIfLocal(HeroAction action, int heroId) {
        if (!lanMode) return;
        if (sendActionOverride != null) { sendActionOverride.accept(action, heroId); return; }
        sendAction(action, heroId);
    }

    // Test seam — whether a remote hero's act() should block waiting for a packet
    public static boolean shouldBlockForRemoteAction(Hero hero) {
        return lanMode && hero != Dungeon.hero && hero.curAction == null;
    }

    /**
     * Encodes a HeroAction into a byte action type for the wire protocol.
     * Package-private so tests can verify round-trip symmetry.
     */
    static byte encodeAction(HeroAction action) {
        if (action == null)                          return ActionType.REST;
        if (action instanceof HeroAction.Move)       return ActionType.MOVE;
        if (action instanceof HeroAction.Attack)     return ActionType.ATTACK;
        if (action instanceof HeroAction.Interact)   return ActionType.INTERACT;
        if (action instanceof HeroAction.PickUp)     return ActionType.PICKUP;
        if (action instanceof HeroAction.OpenChest)  return ActionType.OPEN_CHEST;
        if (action instanceof HeroAction.Unlock)     return ActionType.UNLOCK;
        if (action instanceof HeroAction.LvlTransition) return ActionType.LVL_TRANSITION;
        if (action instanceof HeroAction.Buy)        return ActionType.BUY;
        if (action instanceof HeroAction.Mine)       return ActionType.MINE;
        if (action instanceof HeroAction.Alchemy)    return ActionType.ALCHEMY;
        return ActionType.REST;
    }

    /**
     * Extracts the target position from a HeroAction for the wire protocol.
     * Package-private so tests can verify round-trip symmetry.
     */
    static int getTargetPos(HeroAction action) {
        if (action instanceof HeroAction.Attack) {
            HeroAction.Attack a = (HeroAction.Attack) action;
            return a.target != null ? a.target.pos : a.dst;
        }
        if (action instanceof HeroAction.Interact) {
            HeroAction.Interact i = (HeroAction.Interact) action;
            return i.ch != null ? i.ch.pos : i.dst;
        }
        return action != null ? action.dst : 0;
    }

    /**
     * Decodes a wire protocol byte + position back into a HeroAction.
     * For Attack/Interact the receiver looks up the Char by position at simulation time.
     * Package-private so tests can verify round-trip symmetry.
     */
    static HeroAction decodeAction(byte actionType, int targetPos) {
        if (actionType == ActionType.MOVE)           return new HeroAction.Move(targetPos);
        if (actionType == ActionType.ATTACK)         { HeroAction.Attack a = new HeroAction.Attack(null); a.dst = targetPos; return a; }
        if (actionType == ActionType.INTERACT)       { HeroAction.Interact i = new HeroAction.Interact(null); i.dst = targetPos; return i; }
        if (actionType == ActionType.PICKUP)         return new HeroAction.PickUp(targetPos);
        if (actionType == ActionType.OPEN_CHEST)     return new HeroAction.OpenChest(targetPos);
        if (actionType == ActionType.UNLOCK)         return new HeroAction.Unlock(targetPos);
        if (actionType == ActionType.LVL_TRANSITION) return new HeroAction.LvlTransition(targetPos);
        if (actionType == ActionType.BUY)            return new HeroAction.Buy(targetPos);
        if (actionType == ActionType.MINE)           return new HeroAction.Mine(targetPos);
        if (actionType == ActionType.ALCHEMY)        return new HeroAction.Alchemy(targetPos);
        if (actionType == ActionType.REST)           return null;
        return null;
    }

    // Keep old private names as delegators so sendAction/receiveActionAsync still compile
    private static byte encodeHeroAction(HeroAction action) { return encodeAction(action); }
    private static int  getActionTargetPos(HeroAction action) { return getTargetPos(action); }
    private static HeroAction decodeHeroAction(byte t, int p) { return decodeAction(t, p); }

    // Hero selection and handshake coordination
    private static volatile boolean heroReadyReceived = false;
    private static volatile int heroReadyCount = 0;
    private static volatile HeroClass[] collectedClasses = null;
    private static volatile boolean handshakeReceived = false;
    private static volatile HandshakePayload handshakePayload = null;

    /**
     * Client: Sends HERO_READY packet with the chosen hero class.
     */
    public static void sendHeroReady(HeroClass heroClass) {
        if (!lanMode) return;

        try {
            byte classOrdinal = (byte) heroClass.ordinal();

            if (isHost) {
                for (DataOutputStream out : outs) {
                    out.writeByte(PacketType.HERO_READY);
                    out.writeByte(classOrdinal);
                    out.flush();
                }
            } else {
                if (clientOut != null) {
                    clientOut.writeByte(PacketType.HERO_READY);
                    clientOut.writeByte(classOrdinal);
                    clientOut.flush();
                }
            }
            GLog.p("HERO_READY sent: %s", heroClass.name());
        } catch (IOException e) {
            GLog.n("Failed to send HERO_READY: %s", e.getMessage());
        }
    }

    /**
     * Host: Broadcasts HANDSHAKE packet with seed and class list.
     */
    public static void sendHandshake(long seed, HeroClass[] classes) {
        if (!lanMode || !isHost) return;

        try {
            for (DataOutputStream out : outs) {
                out.writeByte(PacketType.HANDSHAKE);
                out.writeLong(seed);
                out.writeInt(classes.length);
                for (HeroClass cls : classes) {
                    out.writeByte(cls.ordinal());
                }
                out.flush();
            }
            GLog.p("HANDSHAKE sent: %d players, seed %d", classes.length, seed);
        } catch (IOException e) {
            GLog.n("Failed to send HANDSHAKE: %s", e.getMessage());
        }
    }

    /**
     * Client: Sends CLASS_CLAIMED packet.
     */
    public static void sendClassClaimed(int playerIndex, HeroClass heroClass) {
        if (!lanMode) return;

        try {
            byte classOrdinal = (byte) heroClass.ordinal();

            if (isHost) {
                for (DataOutputStream out : outs) {
                    out.writeByte(PacketType.CLASS_CLAIMED);
                    out.writeInt(playerIndex);
                    out.writeByte(classOrdinal);
                    out.flush();
                }
            } else {
                if (clientOut != null) {
                    clientOut.writeByte(PacketType.CLASS_CLAIMED);
                    clientOut.writeInt(playerIndex);
                    clientOut.writeByte(classOrdinal);
                    clientOut.flush();
                }
            }
        } catch (IOException e) {
            GLog.n("Failed to send CLASS_CLAIMED: %s", e.getMessage());
        }
    }

    /**
     * Client: Sends CLASS_UNCLAIMED packet.
     */
    public static void sendClassUnclaimed(int playerIndex, HeroClass heroClass) {
        if (!lanMode) return;

        try {
            byte classOrdinal = (byte) heroClass.ordinal();

            if (isHost) {
                for (DataOutputStream out : outs) {
                    out.writeByte(PacketType.CLASS_UNCLAIMED);
                    out.writeInt(playerIndex);
                    out.writeByte(classOrdinal);
                    out.flush();
                }
            } else {
                if (clientOut != null) {
                    clientOut.writeByte(PacketType.CLASS_UNCLAIMED);
                    clientOut.writeInt(playerIndex);
                    clientOut.writeByte(classOrdinal);
                    clientOut.flush();
                }
            }
        } catch (IOException e) {
            GLog.n("Failed to send CLASS_UNCLAIMED: %s", e.getMessage());
        }
    }

    /**
     * Host: Sends CLASS_REJECTED packet to a player.
     */
    public static void sendClassRejected(int playerIndex) {
        if (!lanMode || !isHost) return;

        try {
            if (playerIndex >= 0 && playerIndex < outs.size()) {
                DataOutputStream out = outs.get(playerIndex);
                out.writeByte(PacketType.CLASS_REJECTED);
                out.writeInt(playerIndex);
                out.flush();
            }
        } catch (IOException e) {
            GLog.n("Failed to send CLASS_REJECTED: %s", e.getMessage());
        }
    }

    /**
     * Host: Starts a background thread to collect HERO_READY packets from all players.
     * Once all are received, caller should call sendHandshake().
     */
    public static void waitForAllHeroReady(int playerCount) {
        if (!lanMode || !isHost) return;

        heroReadyCount = 0;
        collectedClasses = new HeroClass[playerCount];
        heroReadyReceived = false;

        new Thread(() -> {
            try {
                // Host is player 0, has already sent their class
                heroReadyCount = 1;

                // Expect (playerCount - 1) HERO_READY packets from clients
                for (int i = 1; i < playerCount; i++) {
                    if (i - 1 < ins.size()) {
                        DataInputStream in = ins.get(i - 1);
                        byte type = in.readByte();
                        if (type == PacketType.HERO_READY) {
                            byte classOrdinal = in.readByte();
                            collectedClasses[i] = HeroClass.values()[classOrdinal];
                            heroReadyCount++;
                        }
                    }
                }

                heroReadyReceived = true;
                synchronized (NetworkManager.class) {
                    NetworkManager.class.notifyAll();
                }
            } catch (IOException e) {
                GLog.n("Error waiting for HERO_READY: %s", e.getMessage());
            }
        }, "net-wait-hero-ready").start();
    }

    /**
     * Client: Starts a background thread to wait for HANDSHAKE packet.
     */
    public static void waitForHandshake() {
        if (!lanMode || isHost) return;

        handshakeReceived = false;
        handshakePayload = null;

        new Thread(() -> {
            try {
                DataInputStream in = clientIn;
                byte type = in.readByte();
                if (type == PacketType.HANDSHAKE) {
                    long seed = in.readLong();
                    int playerCount = in.readInt();
                    HeroClass[] heroClasses = new HeroClass[playerCount];
                    for (int i = 0; i < playerCount; i++) {
                        byte classOrdinal = in.readByte();
                        heroClasses[i] = HeroClass.values()[classOrdinal];
                    }
                    handshakePayload = new HandshakePayload(seed, playerCount, heroClasses);
                    handshakeReceived = true;
                    GLog.p("Handshake received — %d players, seed %d", playerCount, seed);
                    synchronized (NetworkManager.class) {
                        NetworkManager.class.notifyAll();
                    }
                }
            } catch (SocketTimeoutException e) {
                GLog.w("HANDSHAKE timeout - peer disconnected");
            } catch (IOException e) {
                GLog.n("Error waiting for HANDSHAKE: %s", e.getMessage());
            }
        }, "net-wait-handshake").start();
    }

    /**
     * Returns true if all HERO_READY packets have been received (host only).
     */
    public static boolean isHeroReadyReceived() {
        return heroReadyReceived;
    }

    /**
     * Returns the collected hero classes in order (host only).
     */
    public static HeroClass[] getCollectedClasses() {
        return collectedClasses;
    }

    /**
     * Returns true if HANDSHAKE has been received (client only).
     */
    public static boolean isHandshakeReceived() {
        return handshakeReceived;
    }

    /**
     * Returns the handshake payload (client only).
     */
    public static HandshakePayload getHandshakePayload() {
        return handshakePayload;
    }

    /**
     * Data holder for handshake packets.
     */
    public static class HandshakePayload {
        public long seed;
        public int playerCount;
        public HeroClass[] heroClasses;

        public HandshakePayload(long seed, int playerCount, HeroClass[] heroClasses) {
            this.seed = seed;
            this.playerCount = playerCount;
            this.heroClasses = heroClasses;
        }
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

    /**
     * Data holder for save lobby info packets (resume mode).
     */
    public static class SaveLobbyInfo {
        public int playerCount;
        public String[] heroNames;
        public HeroClass[] heroClasses;
        public int[] heroHP;

        public SaveLobbyInfo(int playerCount, String[] heroNames, HeroClass[] heroClasses, int[] heroHP) {
            this.playerCount = playerCount;
            this.heroNames = heroNames;
            this.heroClasses = heroClasses;
            this.heroHP = heroHP;
        }
    }

    // For resume mode: holds the received save lobby info
    private static SaveLobbyInfo saveLobbyInfo = null;

    /**
     * Host only: send SAVE_LOBBY_INFO to a client (resume mode).
     */
    public static void sendSaveLobbyInfo(DataOutputStream out, SaveLobbyInfo info) throws IOException {
        out.writeByte(PacketType.SAVE_LOBBY_INFO);
        out.writeInt(info.playerCount);
        out.writeInt(info.heroNames.length);
        for (String name : info.heroNames) {
            out.writeUTF(name);
        }
        for (HeroClass cls : info.heroClasses) {
            out.writeUTF(cls.name());
        }
        for (int hp : info.heroHP) {
            out.writeInt(hp);
        }
        out.flush();
    }

    /**
     * Client only: retrieve the received SAVE_LOBBY_INFO.
     */
    public static SaveLobbyInfo getSaveLobbyInfo() {
        return saveLobbyInfo;
    }

    /**
     * Host only: broadcast RESUME_START to all clients.
     */
    public static void broadcastResumeStart(int playerCount) throws IOException {
        for (DataOutputStream out : outs) {
            out.writeByte(PacketType.RESUME_START);
            out.writeInt(playerCount);
            out.flush();
        }
    }

    /**
     * Host only: broadcast RESUME_HANDSHAKE with bundle bytes and hero assignments.
     */
    public static void broadcastResumeHandshake(byte[] bundleBytes, int[] heroAssignments) throws IOException {
        for (DataOutputStream out : outs) {
            out.writeByte(PacketType.RESUME_HANDSHAKE);
            out.writeInt(bundleBytes.length);
            out.write(bundleBytes);
            out.writeInt(heroAssignments.length);
            for (int idx : heroAssignments) {
                out.writeInt(idx);
            }
            out.flush();
        }
    }

    /**
     * Host only: wait for HERO_CLAIM packets from all clients (non-host players).
     * Returns an array where index i contains the claimed hero index for player i.
     */
    public static int[] waitForHeroClaims(int clientCount) throws IOException {
        int[] claims = new int[clientCount];
        for (int i = 0; i < clientCount; i++) {
            claims[i] = -1; // -1 = not claimed yet
        }

        // Read HERO_CLAIM from each client in turn
        for (int i = 0; i < clientCount; i++) {
            byte packetType = ins.get(i).readByte();
            if (packetType == PacketType.HERO_CLAIM) {
                int heroIndex = ins.get(i).readInt();
                claims[i] = heroIndex;
            }
        }

        return claims;
    }

    /**
     * Client only: wait for SAVE_LOBBY_INFO from host (resume mode).
     * Starts a background thread that reads the packet and stores the info.
     */
    public static void waitForSaveLobbyInfo() {
        new Thread(() -> {
            try {
                DataInputStream in = clientIn;
                byte type = in.readByte();
                if (type == PacketType.SAVE_LOBBY_INFO) {
                    int playerCount = in.readInt();
                    int nameCount = in.readInt();
                    String[] heroNames = new String[nameCount];
                    for (int i = 0; i < nameCount; i++) {
                        heroNames[i] = in.readUTF();
                    }
                    HeroClass[] heroClasses = new HeroClass[nameCount];
                    for (int i = 0; i < nameCount; i++) {
                        String className = in.readUTF();
                        heroClasses[i] = HeroClass.valueOf(className);
                    }
                    int[] heroHP = new int[nameCount];
                    for (int i = 0; i < nameCount; i++) {
                        heroHP[i] = in.readInt();
                    }
                    saveLobbyInfo = new SaveLobbyInfo(playerCount, heroNames, heroClasses, heroHP);
                    GLog.p("Save lobby info received — %d heroes", playerCount);
                    synchronized (NetworkManager.class) {
                        NetworkManager.class.notifyAll();
                    }
                }
            } catch (SocketTimeoutException e) {
                GLog.w("SAVE_LOBBY_INFO timeout - peer disconnected");
            } catch (IOException e) {
                GLog.n("Error waiting for SAVE_LOBBY_INFO: %s", e.getMessage());
            }
        }, "net-wait-save-lobby-info").start();
    }
}
