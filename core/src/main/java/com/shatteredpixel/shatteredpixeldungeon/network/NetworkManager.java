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
import java.util.concurrent.TimeUnit;

/**
 * NetworkManager handles all TCP socket communication for LAN multiplayer.
 * It provides host/client mode initialization, action packet transmission,
 * hash synchronization, and remote hero action reception.
 */
public class NetworkManager {

    // LAN_DEBUG — filter with: adb logcat -s LAN_DEBUG
    private static final String LAN_TAG = "LAN_DEBUG";
    private static java.lang.reflect.Method androidLog = null;
    private static boolean androidLogChecked = false;

    public static void lanLog(String fmt, Object... args) {
        String msg = args.length == 0 ? fmt : String.format(fmt, args);
        try { GLog.i(LAN_TAG + " | " + msg); } catch (Throwable ignored) {}
        if (!androidLogChecked) {
            androidLogChecked = true;
            try { Class<?> c = Class.forName("android.util.Log");
                  androidLog = c.getMethod("d", String.class, String.class);
            } catch (Throwable ignored) {}
        }
        if (androidLog != null) try { androidLog.invoke(null, LAN_TAG, msg); } catch (Throwable ignored) {}
    }

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

    // Per-stream write locks for EC-6.1 — one lock per entry in outs
    private static List<Object> outLocks = new ArrayList<>();
    // Write lock for client mode (EC-6.1)
    private static final Object clientOutLock = new Object();

    // Per-stream reader running guards for EC-6.7 (keyed by heroIndex - 1)
    // Size 4 = MAX_PLAYERS; cannot reference MAX_PLAYERS here due to forward reference
    private static volatile boolean[] actionReaderRunningPerStream = new boolean[4];

    // Socket timeout for all read operations (30 seconds)
    // No read timeout on gameplay sockets — a player may take as long as they
    // want to make a move. Disconnect detection is handled by periodic PING writes.
    private static final int SOCKET_TIMEOUT_MS = 0; // 0 = no timeout
    private static final int PING_INTERVAL_MS  = 5000; // send PING every 5 seconds

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

    // Protocol version for HANDSHAKE version negotiation (EC-6.5)
    public static final int PROTOCOL_VERSION = 1;

    // Maximum players supported (EC-6.3)
    public static final int MAX_PLAYERS = 4;

    // Desync detection signal (EC-6.4)
    public static class DesyncDetected {
        public long localHash;
        public long remoteHash;
        public DesyncDetected(long localHash, long remoteHash) {
            this.localHash = localHash;
            this.remoteHash = remoteHash;
        }
    }
    public static Signal<DesyncDetected> desyncDetectedSignal = new Signal<>();

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
        public static final byte PING             = 16; // heartbeat — silently ignored by reader
        public static final byte PLAYER_LEFT      = 17; // host → clients: player removed from lobby
        public static final byte NAME_REJECTED    = 18; // host → client: duplicate name
        public static final byte KICK             = 19; // host → client: kicked by host
        public static final byte HOST_DISCONNECTED = 20; // host → clients: host leaving lobby
        public static final byte STATE_HASH       = 21; // host → clients: state hash for desync detection
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
            lanLog("sendAction | heroId=%d actionType=%d pos=%d isHost=%b outs=%d clientOut=%b",
                    heroId, actionType, targetPos, isHost, outs.size(), clientOut != null);

            if (isHost) {
                for (int i = 0; i < outs.size(); i++) {
                    DataOutputStream out = outs.get(i);
                    Object lock = i < outLocks.size() ? outLocks.get(i) : out;
                    synchronized (lock) {
                        out.writeByte(PacketType.ACTION);
                        out.writeInt(heroId);
                        out.writeByte(actionType);
                        out.writeInt(targetPos);
                        out.flush();
                    }
                }
                lanLog("sendAction | sent to %d clients", outs.size());
            } else {
                if (clientOut != null) {
                    synchronized (clientOutLock) {
                        clientOut.writeByte(PacketType.ACTION);
                        clientOut.writeInt(heroId);
                        clientOut.writeByte(actionType);
                        clientOut.writeInt(targetPos);
                        clientOut.flush();
                    }
                    lanLog("sendAction | sent to host");
                } else {
                    lanLog("sendAction | WARN clientOut is null — packet NOT sent!");
                }
            }
        } catch (IOException e) {
            GLog.n("Failed to send action: %s", e.getMessage());
            lanLog("sendAction | IOException: %s", e.getMessage());
        }
    }

    /**
     * Starts a background thread to receive actions from a remote hero.
     * Reads ACTION packets and updates the hero's curAction, then notifies the Actor thread.
     *
     * Only one reader thread is ever active at a time (enforced by actionReaderRunning).
     * Re-entrant calls while the thread is running are no-ops.
     */
    public static void receiveActionAsync(Hero remoteHero) {
        if (!lanMode || remoteHero == null) return;

        // EC-6.7: per-stream guard keyed by heroIndex - 1 for 3+ player games.
        // Each remote hero gets its own reader thread on its own stream; the old
        // global singleton guard prevented concurrent readers for hero[1] and hero[2].
        int heroIndex = (Dungeon.heroes != null) ? Dungeon.heroes.indexOf(remoteHero) : 0;
        int streamIndex = isHost ? Math.max(0, heroIndex - 1) : 0; // ins.get(0) for hero[1], ins.get(1) for hero[2]

        synchronized (NetworkManager.class) {
            if (streamIndex >= 0 && streamIndex < actionReaderRunningPerStream.length) {
                if (actionReaderRunningPerStream[streamIndex]) {
                    lanLog("receiveActionAsync | guard true stream=%d heroIdx=%d — no-op", streamIndex, heroIndex);
                    return;
                }
                actionReaderRunningPerStream[streamIndex] = true;
            } else {
                if (actionReaderRunning) {
                    lanLog("receiveActionAsync | global guard true heroIdx=%d — no-op", heroIndex);
                    return;
                }
                actionReaderRunning = true;
            }
        }

        lanLog("receiveActionAsync | reader starting heroIdx=%d stream=%d isHost=%b", heroIndex, streamIndex, isHost);
        final int finalStreamIndex = streamIndex;

        new Thread(() -> {
            try {
                DataInputStream in;
                if (isHost) {
                    in = (finalStreamIndex < ins.size()) ? ins.get(finalStreamIndex) : null;
                } else {
                    in = clientIn;
                }
                if (in == null) {
                    lanLog("receiveActionAsync | ERROR stream null stream=%d", finalStreamIndex);
                    return;
                }

                lanLog("receiveActionAsync | waiting on readByte stream=%d", finalStreamIndex);
                while (lanMode && remoteHero != null) {
                    try {
                        byte type = in.readByte();
                        lanLog("receiveActionAsync | got packet type=%d stream=%d", type, finalStreamIndex);
                        if (type == PacketType.ACTION) {
                            int heroId = in.readInt();
                            byte actionType = in.readByte();
                            int targetPos = in.readInt();

                            HeroAction decodedAction = decodeHeroAction(actionType, targetPos);
                            lanLog("receiveActionAsync | ACTION heroId=%d actionType=%d pos=%d decoded=%s",
                                    heroId, actionType, targetPos, decodedAction != null ? decodedAction.getClass().getSimpleName() : "null");
                            synchronized (remoteHero.lanActionLock) {
                                if (decodedAction != null) {
                                    remoteHero.curAction = decodedAction;
                                }
                                remoteHero.lanActionLock.notifyAll();
                                lanLog("receiveActionAsync | notifyAll fired curAction=%s",
                                        remoteHero.curAction != null ? remoteHero.curAction.getClass().getSimpleName() : "null");
                            }
                            break;
                        } else if (type == PacketType.STATE_HASH) {
                            // EC-6.4: desync detection
                            int turn = in.readInt();
                            long remoteHash = in.readLong();
                            long localHash = computeStateHash();
                            if (localHash != remoteHash) {
                                GLog.n("DESYNC DETECTED at turn %d: local=%d remote=%d", turn, localHash, remoteHash);
                                desyncDetectedSignal.dispatch(new DesyncDetected(localHash, remoteHash));
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
                        } else if (type == PacketType.PING) {
                            // Heartbeat — silently discard, just proves the connection is alive
                        }
                    } catch (IOException e) {
                        if (!Thread.currentThread().isInterrupted()) {
                            GLog.w("Peer disconnected: %s", e.getClass().getSimpleName());
                            peerDisconnectSignal.dispatch(new PeerDisconnected(remoteHero));
                            com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene.notifyActorThread();
                        }
                        break;
                    }
                }
            } catch (Exception e) {
                if (e instanceof InterruptedIOException) {
                    Thread.currentThread().interrupt();
                }
            } finally {
                synchronized (NetworkManager.class) {
                    if (finalStreamIndex >= 0 && finalStreamIndex < actionReaderRunningPerStream.length) {
                        actionReaderRunningPerStream[finalStreamIndex] = false;
                    }
                    actionReaderRunning = false;
                }
                lanLog("receiveActionAsync | guard reset stream=%d — reader done", finalStreamIndex);
            }
        }, "net-reader-action").start();
    }

    /**
     * Starts a background thread that sends a PING packet to all peers every
     * PING_INTERVAL_MS milliseconds. This keeps NAT/firewall entries alive and
     * provides write-based disconnect detection: if the peer is gone the write
     * throws IOException, which is caught and triggers the disconnect signal.
     *
     * Call once after gameplay begins. Stops automatically when lanMode=false.
     */
    public static void startPingSender() {
        if (!lanMode) return;
        new Thread(() -> {
            while (lanMode) {
                try {
                    Thread.sleep(PING_INTERVAL_MS);
                    if (!lanMode) break;
                    if (isHost) {
                        for (int i = 0; i < outs.size(); i++) {
                            DataOutputStream out = outs.get(i);
                            Object lock = i < outLocks.size() ? outLocks.get(i) : out;
                            try {
                                synchronized (lock) {
                                    out.writeByte(PacketType.PING);
                                    out.flush();
                                }
                            } catch (IOException e) {
                                // EC-6.2: ping write failure fires peerDisconnectSignal for the affected hero
                                if (lanMode) {
                                    try { GLog.w("Ping failed for client %d — firing disconnect signal: %s", i, e.getMessage()); } catch (Throwable ignored) {}
                                    Hero disconnectedHero = null;
                                    if (Dungeon.heroes != null && (i + 1) < Dungeon.heroes.size()) {
                                        disconnectedHero = Dungeon.heroes.get(i + 1);
                                    }
                                    peerDisconnectSignal.dispatch(new PeerDisconnected(disconnectedHero));
                                    try { com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene.notifyActorThread(); } catch (Throwable ignored) {}
                                }
                                break;
                            }
                        }
                    } else if (clientOut != null) {
                        synchronized (clientOutLock) {
                            clientOut.writeByte(PacketType.PING);
                            clientOut.flush();
                        }
                    }
                } catch (IOException e) {
                    if (lanMode) GLog.w("Ping failed — peer disconnected: %s", e.getMessage());
                    break;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "net-ping-sender").start();
    }

    /**
     * Sends an item identification packet to broadcast that an item has been identified.
     * The class name is sent so peers can instantiate and identify the same item.
     */
    public static void sendItemIdentified(String itemClassName) {
        if (!lanMode) return;
        try {
            if (isHost) {
                for (int i = 0; i < outs.size(); i++) {
                    DataOutputStream out = outs.get(i);
                    Object lock = i < outLocks.size() ? outLocks.get(i) : out;
                    synchronized (lock) {
                        out.writeByte(PacketType.ITEM_IDENTIFIED);
                        out.writeUTF(itemClassName);
                        out.flush();
                    }
                }
            } else if (clientOut != null) {
                synchronized (clientOutLock) {
                    clientOut.writeByte(PacketType.ITEM_IDENTIFIED);
                    clientOut.writeUTF(itemClassName);
                    clientOut.flush();
                }
            }
        } catch (IOException e) {
            GLog.w("Failed to send item identification: %s", e.getMessage());
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
                outLocks.add(new Object()); // EC-6.1: per-stream write lock

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

                // EC-1.2 / EC-5.3: reject duplicate names
                boolean nameTaken = false;
                for (int k = 0; k < connectedPlayerCount - 1; k++) { // check slots 0..newPlayerIndex-1
                    if (clientName.equalsIgnoreCase(playerNames[k])) {
                        nameTaken = true;
                        break;
                    }
                }
                if (nameTaken) {
                    GLog.w("Rejected client with duplicate name: %s", clientName);
                    try {
                        out.writeByte(PacketType.NAME_REJECTED);
                        out.flush();
                    } catch (IOException ignored) {}
                    // Roll back the add
                    peerSockets.remove(peerSockets.size() - 1);
                    ins.remove(ins.size() - 1);
                    outs.remove(outs.size() - 1);
                    if (!outLocks.isEmpty()) outLocks.remove(outLocks.size() - 1);
                    connectedPlayerCount--;
                    try { clientSocket.close(); } catch (IOException ignored) {}
                    continue;
                }

                playerNames[newPlayerIndex] = clientName;

                GLog.p("Client \"%s\" connected, total players: %d", clientName, connectedPlayerCount);

                // Tell the new client their assigned index and name, then broadcast to all
                for (int i = 0; i < outs.size(); i++) {
                    DataOutputStream dest = outs.get(i);
                    dest.writeByte(PacketType.PLAYER_JOINED);
                    dest.writeInt(newPlayerIndex);
                    dest.writeInt(connectedPlayerCount);
                    // Include ALL current player names so clients always know the host's name
                    dest.writeInt(connectedPlayerCount);
                    for (int k = 0; k < connectedPlayerCount; k++) {
                        String n = playerNames[k];
                        dest.writeUTF(n != null ? n : "Player " + (k + 1));
                    }
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
     * EC-1.1 / EC-6.10: Removes a connected client by index (0-indexed in peerSockets/ins/outs).
     * Closes socket, removes from lists, decrements connectedPlayerCount, compacts indices,
     * broadcasts PLAYER_LEFT to remaining clients, and calls onPlayerJoined callback.
     */
    public static synchronized void removeClient(int index) {
        if (index < 0 || index >= peerSockets.size()) return;

        // Close the socket (null-safe for testing)
        try {
            Socket s = peerSockets.get(index);
            if (s != null && !s.isClosed()) s.close();
        } catch (Exception ignored) {}

        // Remove from lists
        peerSockets.remove(index);
        ins.remove(index);
        DataOutputStream removedOut = outs.remove(index);
        if (index < outLocks.size()) outLocks.remove(index);

        // Shift playerNames down (slot 0 = host, index+1 corresponds to socket index)
        int playerSlot = index + 1; // peerSockets[0] = player at slot 1
        for (int k = playerSlot; k < playerNames.length - 1; k++) {
            playerNames[k] = playerNames[k + 1];
        }
        playerNames[playerNames.length - 1] = null;

        connectedPlayerCount--;

        // Broadcast PLAYER_LEFT to remaining clients
        for (int i = 0; i < outs.size(); i++) {
            DataOutputStream dest = outs.get(i);
            Object lock = i < outLocks.size() ? outLocks.get(i) : dest;
            try {
                synchronized (lock) {
                    dest.writeByte(PacketType.PLAYER_LEFT);
                    dest.writeInt(connectedPlayerCount);
                    dest.writeInt(connectedPlayerCount);
                    for (int k = 0; k < connectedPlayerCount; k++) {
                        String n = playerNames[k];
                        dest.writeUTF(n != null ? n : "Player " + (k + 1));
                    }
                    dest.flush();
                }
            } catch (IOException ignored) {}
        }

        // Notify host lobby UI
        if (onPlayerJoined != null) {
            onPlayerJoined.call(-1, connectedPlayerCount);
        }

        try { GLog.p("Removed client at index %d, connectedPlayerCount=%d", index, connectedPlayerCount); } catch (Throwable ignored) {}
    }

    /**
     * EC-1.3: Host kicks a player at the given player slot (1-indexed).
     * Sends KICK packet to the target, then calls removeClient().
     */
    public static void kickPlayer(int playerSlot) {
        if (!isHost) return;
        int socketIndex = playerSlot - 1; // peerSockets is 0-indexed, playerSlot 1 = first client
        if (socketIndex < 0 || socketIndex >= outs.size()) return;

        DataOutputStream out = outs.get(socketIndex);
        Object lock = socketIndex < outLocks.size() ? outLocks.get(socketIndex) : out;
        try {
            synchronized (lock) {
                out.writeByte(PacketType.KICK);
                out.flush();
            }
        } catch (IOException e) {
            try { GLog.w("Failed to send KICK to player %d: %s", playerSlot, e.getMessage()); } catch (Throwable ignored) {}
        }

        removeClient(socketIndex);
    }

    /**
     * EC-1.4: Host broadcasts HOST_DISCONNECTED to all connected clients (lobby phase only).
     */
    public static void broadcastHostDisconnected() {
        if (!isHost) return;
        for (int i = 0; i < outs.size(); i++) {
            DataOutputStream out = outs.get(i);
            Object lock = i < outLocks.size() ? outLocks.get(i) : out;
            try {
                synchronized (lock) {
                    out.writeByte(PacketType.HOST_DISCONNECTED);
                    out.flush();
                }
            } catch (IOException ignored) {}
        }
    }

    /**
     * EC-6.4: Sends a STATE_HASH packet to all peers for desync detection.
     * Called by Host every 10 turns.
     */
    public static void sendStateHash(int turn, long hash) {
        if (!lanMode || !isHost) return;
        try {
            for (int i = 0; i < outs.size(); i++) {
                DataOutputStream out = outs.get(i);
                Object lock = i < outLocks.size() ? outLocks.get(i) : out;
                synchronized (lock) {
                    out.writeByte(PacketType.STATE_HASH);
                    out.writeInt(turn);
                    out.writeLong(hash);
                    out.flush();
                }
            }
        } catch (IOException e) {
            GLog.w("Failed to send state hash: %s", e.getMessage());
        }
    }

    /**
     * EC-6.4: Computes a lightweight state hash from level map XOR hero positions XOR hero HP.
     */
    public static long computeStateHash() {
        long hash = 0L;
        try {
            if (Dungeon.level != null && Dungeon.level.map != null) {
                for (int tile : Dungeon.level.map) {
                    hash = hash * 31 + tile;
                }
            }
            if (Dungeon.heroes != null) {
                for (Hero h : Dungeon.heroes) {
                    if (h != null) {
                        hash ^= (long) h.pos * 0x9e3779b97f4a7c15L;
                        hash ^= (long) h.HP * 0x6c62272e07bb0142L;
                    }
                }
            }
        } catch (Exception e) {
            // Ignore — hash remains partial
        }
        return hash;
    }

    /**
     * Returns the client input stream (for client-side lobby packet reading).
     */
    public static DataInputStream getClientInput() {
        return clientIn;
    }

    /**
     * EC-6.8: Public accessor for clientIn — eliminates reflection in WndHeroClaim.
     */
    public static DataInputStream getClientIn() {
        return clientIn;
    }

    /**
     * EC-6.8: Public accessor for clientOut — eliminates reflection in WndHeroClaim.
     */
    public static DataOutputStream getClientOut() {
        return clientOut;
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
        outLocks.clear(); // EC-6.1: clear per-stream write locks
        serverSocket = null;
        clientSocket = null;
        clientIn = null;
        clientOut = null;
        isHost = false;
        connectedPlayerCount = 0;
        gameStarted = false;
        onPlayerJoined = null;
        playerNames = new String[4];  // reset player names array
        localPlayerIndex = 0;
        actionReaderRunningPerStream = new boolean[MAX_PLAYERS]; // EC-6.7: reset per-stream guards

    }

    // Test seam — expose connectedPlayerCount for assertions
    public static int getConnectedPlayerCountForTesting() { return connectedPlayerCount; }

    // Test seam — set connectedPlayerCount directly for setup
    public static void setConnectedPlayerCountForTesting(int count) { connectedPlayerCount = count; }

    // Test seam — expose playerNames for assertions
    public static String[] getPlayerNamesForTesting() { return playerNames; }

    // Test seam — directly add a socket/stream pair to the host lists (simulates acceptClientsLoop)
    public static void addPeerForTesting(Socket socket, DataInputStream in, DataOutputStream out) {
        peerSockets.add(socket);
        ins.add(in);
        outs.add(out);
        outLocks.add(new Object());
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

    // Test seams for actionReaderRunning guard
    public static boolean isActionReaderRunning() {
        if (actionReaderRunning) return true;
        // Also check per-stream guards (EC-6.7)
        for (boolean b : actionReaderRunningPerStream) {
            if (b) return true;
        }
        return false;
    }
    public static void setActionReaderRunningForTesting(boolean running) { actionReaderRunning = running; }
    public static void resetActionReaderForTesting() {
        actionReaderRunning = false;
        actionReaderRunningPerStream = new boolean[MAX_PLAYERS];
    }
    // EC-6.7: per-stream reader guard test seams
    public static boolean isActionReaderRunningForStream(int streamIndex) {
        if (streamIndex >= 0 && streamIndex < actionReaderRunningPerStream.length) {
            return actionReaderRunningPerStream[streamIndex];
        }
        return false;
    }
    public static void setActionReaderRunningForStreamTesting(int streamIndex, boolean running) {
        if (streamIndex >= 0 && streamIndex < actionReaderRunningPerStream.length) {
            actionReaderRunningPerStream[streamIndex] = running;
        }
    }
    // EC-6.1: inject outLocks for testing
    public static void injectOutLocksForTesting(List<Object> locks) {
        outLocks = locks;
    }

    // Test seams — inject real socket streams so integration tests can use real TCP
    public static void injectHostStreamsForTesting(DataInputStream in, DataOutputStream out) {
        ins = new java.util.ArrayList<>();
        if (in  != null) ins.add(in);
        outs = new java.util.ArrayList<>();
        if (out != null) outs.add(out);
    }
    public static void injectClientStreamsForTesting(DataInputStream in, DataOutputStream out) {
        clientIn  = in;
        clientOut = out;
    }

    // Test seam — reset hero-ready coordination state between tests
    public static void resetHeroReadyStateForTesting() {
        synchronized (NetworkManager.class) {
            heroReadyCount = 0;
            heroReadyReceived = false;
            collectedClasses = null;
        }
    }

    // Test seam — expose heroReadyCount for race-condition assertions
    public static int getHeroReadyCountForTesting() {
        synchronized (NetworkManager.class) {
            return heroReadyCount;
        }
    }

    // Test seam — inject a mock DataInputStream for the first client slot
    public static void setClientInputStreamForTesting(java.io.DataInputStream in) {
        ins.clear();
        ins.add(in);
    }

    // Test seam — clear all client input streams
    public static void clearClientInputStreamsForTesting() {
        ins.clear();
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
        if (actionType == ActionType.ATTACK) {
            com.shatteredpixel.shatteredpixeldungeon.actors.Char target =
                com.shatteredpixel.shatteredpixeldungeon.actors.Actor.findChar(targetPos);
            HeroAction.Attack a = new HeroAction.Attack(target);
            a.dst = targetPos;
            return a;
        }
        if (actionType == ActionType.INTERACT) {
            com.shatteredpixel.shatteredpixeldungeon.actors.Char ch =
                com.shatteredpixel.shatteredpixeldungeon.actors.Actor.findChar(targetPos);
            HeroAction.Interact i = new HeroAction.Interact(ch);
            i.dst = targetPos;
            return i;
        }
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
     * Guard flag: prevents more than one net-reader-action thread from starting.
     * The reader thread loops continuously and must not be duplicated.
     */
    private static volatile boolean actionReaderRunning = false;

    /**
     * Client: Sends HERO_READY packet with the chosen hero class.
     */
    public static void sendHeroReady(HeroClass heroClass) {
        if (!lanMode) return;

        try {
            byte classOrdinal = (byte) heroClass.ordinal();

            if (isHost) {
                // Host readiness is tracked locally in waitForAllHeroReady (heroReadyCount=1).
                // Do NOT broadcast HERO_READY to clients — they are not expecting it and it
                // would corrupt the waitForHandshake stream reader on the client side.
                GLog.p("HERO_READY (host, local only): %s", heroClass.name());
            } else {
                if (clientOut != null) {
                    clientOut.writeByte(PacketType.HERO_READY);
                    clientOut.writeByte(classOrdinal);
                    clientOut.flush();
                    GLog.p("HERO_READY sent to host: %s", heroClass.name());
                }
            }
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
            for (int i = 0; i < outs.size(); i++) {
                DataOutputStream out = outs.get(i);
                Object lock = i < outLocks.size() ? outLocks.get(i) : out;
                synchronized (lock) {
                    out.writeByte(PacketType.HANDSHAKE);
                    out.writeInt(PROTOCOL_VERSION); // EC-6.5: version field before seed
                    out.writeLong(seed);
                    out.writeInt(classes.length);
                    for (HeroClass cls : classes) {
                        out.writeByte(cls.ordinal());
                    }
                    out.flush();
                }
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
                for (int i = 0; i < outs.size(); i++) {
                    DataOutputStream out = outs.get(i);
                    Object lock = i < outLocks.size() ? outLocks.get(i) : out;
                    synchronized (lock) {
                        out.writeByte(PacketType.CLASS_CLAIMED);
                        out.writeInt(playerIndex);
                        out.writeByte(classOrdinal);
                        out.flush();
                    }
                }
            } else {
                if (clientOut != null) {
                    synchronized (clientOutLock) {
                        clientOut.writeByte(PacketType.CLASS_CLAIMED);
                        clientOut.writeInt(playerIndex);
                        clientOut.writeByte(classOrdinal);
                        clientOut.flush();
                    }
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
                for (int i = 0; i < outs.size(); i++) {
                    DataOutputStream out = outs.get(i);
                    Object lock = i < outLocks.size() ? outLocks.get(i) : out;
                    synchronized (lock) {
                        out.writeByte(PacketType.CLASS_UNCLAIMED);
                        out.writeInt(playerIndex);
                        out.writeByte(classOrdinal);
                        out.flush();
                    }
                }
            } else {
                if (clientOut != null) {
                    synchronized (clientOutLock) {
                        clientOut.writeByte(PacketType.CLASS_UNCLAIMED);
                        clientOut.writeInt(playerIndex);
                        clientOut.writeByte(classOrdinal);
                        clientOut.flush();
                    }
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

        collectedClasses = new HeroClass[playerCount];
        // Initialize heroReadyCount=1 BEFORE starting reader threads so that if a client
        // sends HERO_READY before the main thread reaches the synchronized block below,
        // the increment from 1→playerCount fires heroReadyReceived correctly.
        synchronized (NetworkManager.class) {
            heroReadyCount = 1;
            heroReadyReceived = (playerCount == 1);
        }

        // One reader thread per client — handles CLASS_CLAIMED/UNCLAIMED interleaved
        // with HERO_READY so the host relays class reservations during selection
        for (int clientIdx = 0; clientIdx < playerCount - 1; clientIdx++) {
            final int ci = clientIdx;
            final int playerSlot = ci + 1; // slot 0 = host, slots 1..N = clients
            new Thread(() -> {
                try {
                    DataInputStream in = ins.get(ci);
                    // Read packets until we get HERO_READY from this client
                    while (true) {
                        byte type = in.readByte();
                        if (type == PacketType.HERO_READY) {
                            byte classOrdinal = in.readByte();
                            HeroClass cls = HeroClass.values()[classOrdinal];
                            collectedClasses[playerSlot] = cls;
                            // Broadcast permanent lock to all others so their UI updates
                            broadcastClassClaimed(playerSlot, cls);
                            if (onClassClaimedReceived != null)
                                onClassClaimedReceived.call(playerSlot, cls);
                            synchronized (NetworkManager.class) {
                                heroReadyCount++;
                                if (heroReadyCount >= playerCount) {
                                    heroReadyReceived = true;
                                    NetworkManager.class.notifyAll();
                                }
                            }
                            break; // done with this client
                        } else if (type == PacketType.CLASS_CLAIMED) {
                            int pidx = in.readInt();
                            byte ord = in.readByte();
                            HeroClass cls = ordinalToHeroClass(ord);
                            // Relay to all other clients
                            for (int j = 0; j < outs.size(); j++) {
                                if (j != ci) {
                                    try {
                                        outs.get(j).writeByte(PacketType.CLASS_CLAIMED);
                                        outs.get(j).writeInt(pidx);
                                        outs.get(j).writeByte(ord);
                                        outs.get(j).flush();
                                    } catch (IOException ignored) {}
                                }
                            }
                            if (onClassClaimedReceived != null)
                                onClassClaimedReceived.call(pidx, cls);
                        } else if (type == PacketType.CLASS_UNCLAIMED) {
                            int pidx = in.readInt();
                            byte ord = in.readByte();
                            HeroClass cls = ordinalToHeroClass(ord);
                            for (int j = 0; j < outs.size(); j++) {
                                if (j != ci) {
                                    try {
                                        outs.get(j).writeByte(PacketType.CLASS_UNCLAIMED);
                                        outs.get(j).writeInt(pidx);
                                        outs.get(j).writeByte(ord);
                                        outs.get(j).flush();
                                    } catch (IOException ignored) {}
                                }
                            }
                            if (onClassUnclaimedReceived != null)
                                onClassUnclaimedReceived.call(pidx, cls);
                        }
                        // skip unknown packet types and keep reading
                    }
                } catch (IOException e) {
                    GLog.n("Error in hero-ready reader for slot %d: %s", playerSlot, e.getMessage());
                }
            }, "net-hero-ready-" + ci).start();
        }

    }

    /** Broadcasts CLASS_CLAIMED for a confirmed player to all clients (host side). */
    private static void broadcastClassClaimed(int playerIdx, HeroClass cls) {
        byte ord = (byte) cls.ordinal();
        for (DataOutputStream out : outs) {
            try {
                out.writeByte(PacketType.CLASS_CLAIMED);
                out.writeInt(playerIdx);
                out.writeByte(ord);
                out.flush();
            } catch (IOException ignored) {}
        }
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
                while (true) { // keep reading until HANDSHAKE arrives
                    byte type = in.readByte();
                    if (type == PacketType.HANDSHAKE) {
                    // EC-6.5: read and validate protocol version before seed
                    int remoteVersion = in.readInt();
                    if (remoteVersion != PROTOCOL_VERSION) {
                        throw new IOException("Protocol version mismatch: remote=" + remoteVersion + " local=" + PROTOCOL_VERSION);
                    }
                    long seed = in.readLong();
                    int playerCount = in.readInt();
                    // EC-6.3: validate playerCount range
                    if (playerCount < 1 || playerCount > MAX_PLAYERS) {
                        throw new IOException("Invalid playerCount: " + playerCount);
                    }
                    HeroClass[] heroClasses = new HeroClass[playerCount];
                    for (int i = 0; i < playerCount; i++) {
                        byte classOrdinal = in.readByte();
                        // EC-6.3: validate classOrdinal range
                        if (classOrdinal < 0 || classOrdinal >= HeroClass.values().length) {
                            throw new IOException("Invalid classOrdinal: " + classOrdinal);
                        }
                        heroClasses[i] = HeroClass.values()[classOrdinal];
                    }
                    handshakePayload = new HandshakePayload(seed, playerCount, heroClasses);
                    handshakeReceived = true;
                    GLog.p("Handshake received — %d players, seed %d", playerCount, seed);
                    synchronized (NetworkManager.class) {
                        NetworkManager.class.notifyAll();
                    }
                    break; // done
                    } else if (type == PacketType.CLASS_CLAIMED) {
                        int pidx = in.readInt();
                        byte ord = in.readByte();
                        HeroClass cls = ordinalToHeroClass(ord);
                        if (onClassClaimedReceived != null) onClassClaimedReceived.call(pidx, cls);
                    } else if (type == PacketType.CLASS_UNCLAIMED) {
                        int pidx = in.readInt();
                        byte ord = in.readByte();
                        HeroClass cls = ordinalToHeroClass(ord);
                        if (onClassUnclaimedReceived != null) onClassUnclaimedReceived.call(pidx, cls);
                    }
                    // unknown packet — keep reading
                } // end while
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
