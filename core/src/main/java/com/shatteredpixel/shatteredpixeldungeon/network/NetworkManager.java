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
    // v2: HANDSHAKE carries the host's challenge mask after the seed
    // v3: leader-sequenced commit protocol — gameplay ops flow as
    //     REQUEST → COMMIT with acks, dedup, retransmit and state checks
    public static final int PROTOCOL_VERSION = 3;

    // Challenge mask for the current LAN run. Host sets it from local settings when
    // sending the handshake; clients set it from the received handshake. Dungeon.init
    // reads this instead of local settings so every device runs the same rules.
    public static int lanChallenges = 0;

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
        public static final byte ITEM_CHOICE      = 22; // sender resolved an item prompt mid-action
        public static final byte CELL_CHOICE      = 23; // sender resolved a cell prompt mid-action
        public static final byte OPTION_CHOICE    = 24; // sender resolved an option dialog mid-action (-1 = cancelled)
        // Leader-sequenced commit protocol (v3)
        public static final byte REQUEST          = 25; // follower → leader: please sequence this op
        public static final byte COMMIT           = 26; // leader → followers: sequenced op, apply in order
        public static final byte ACK              = 27; // follower → leader: highest contiguous commit applied
        public static final byte STATE_CHECK      = 28; // leader → followers: leader's state hash at a commit's pre-execution point
        public static final byte SNAPSHOT_REQUEST = 29; // follower → leader: my state diverged, send authoritative state
        public static final byte SNAPSHOT         = 30; // leader → follower: full game+level bundle for resync
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
        public static final byte REST     = 10;
        public static final byte USE_ITEM = 11;
        public static final byte SEARCH   = 12;
        public static final byte USE_ITEM_AT = 13;
        public static final byte SHOP_BUY    = 14;
        public static final byte SHOP_SELL   = 15;
    }

    /**
     * Host: Opens a ServerSocket on the given port and begins accepting client connections.
     * Sets lanMode=true and isHost=true.
     */
    public static void hostGame(int port) throws IOException {
        try {
            serverSocket = new ServerSocket(port);
            resetCommitProtocolState();
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

            resetCommitProtocolState();
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
     * Sends a hero action through the leader-sequenced commit protocol.
     * On the leader this sequences and broadcasts the commit immediately; on a
     * follower it sends a REQUEST and BLOCKS until the leader's COMMIT echo
     * returns (pessimistic execution). Returns the commit's globalSeq, or -1
     * if the op could not be sequenced.
     */
    public static int sendAction(HeroAction action, int heroId) {
        if (!lanMode) return -1;
        byte actionType = encodeHeroAction(action);
        int targetPos = getActionTargetPos(action);
        lanLog("sendAction | heroId=%d actionType=%d pos=%d isHost=%b",
                heroId, actionType, targetPos, isHost);
        return submitLocalOp(InnerOp.ACTION, actionType, targetPos, null, true);
    }

    // ------------------------------------------------------------------
    // Interactive prompt sync (EC: identify/upgrade scrolls, chains, etc.)
    //
    // Some item effects prompt the player mid-execution (pick an item to
    // identify, pick a cell to chain to). In LAN games both devices execute
    // the same queued action; on the owning device the prompt shows normally
    // and the resolved choice is broadcast, while on remote devices the
    // prompt is suppressed and its listener parked here until the ITEM_CHOICE
    // / CELL_CHOICE packet arrives.
    // ------------------------------------------------------------------

    /** True while a REMOTE hero's queued item action is executing on this device. */
    public static volatile boolean remoteItemExecution = false;
    /** The remote hero whose action is executing (choice packets resolve against its belongings). */
    public static volatile Hero remoteExecutionHero = null;
    /** True while the LOCAL hero's queued item action is executing on this device. */
    public static volatile boolean localItemExecution = false;

    public static volatile com.shatteredpixel.shatteredpixeldungeon.windows.WndBag.ItemSelector pendingRemoteItemSelector = null;
    public static volatile com.shatteredpixel.shatteredpixeldungeon.scenes.CellSelector.Listener pendingRemoteCellListener = null;

    /** A parked option-dialog choice on a remote device (see LanChoiceWindow). */
    public interface LanChoiceHandler { void onLanChoice(int choice); }
    public static volatile LanChoiceHandler pendingRemoteOptionHandler = null;

    /** Sender: broadcast which option the player picked in a mid-action dialog (-1 = cancelled). */
    public static void sendOptionChoice(int index) {
        submitLocalOp(InnerOp.OPTION_CHOICE, (byte) 0, index, null, false);
    }

    /**
     * Owner-side resolution of a mid-action choice (item pick, cell pick, option
     * dialog). Runs the effect with the deterministic sim RNG and with
     * localItemExecution set, so any NESTED prompt the effect raises is wrapped
     * and broadcast too — mirroring how the remote reader resolves the same
     * choice under remoteItemExecution.
     */
    public static void resolveLocalChoice(Runnable effect) {
        boolean wasLocal = localItemExecution;
        localItemExecution = true;
        com.watabou.utils.Random.enterSimContext();
        try {
            effect.run();
        } finally {
            com.watabou.utils.Random.exitSimContext();
            localItemExecution = wasLocal;
        }
    }

    /** Sender: broadcast which item the player picked in a mid-action prompt (-1/-1 = cancelled). */
    public static void sendItemChoice(int bagOrdinal, int slot) {
        int payload = (bagOrdinal < 0 || slot < 0) ? -1 : ((bagOrdinal << 16) | (slot & 0xFFFF));
        submitLocalOp(InnerOp.ITEM_CHOICE, (byte) 0, payload, null, false);
    }

    /** Sender: broadcast which cell the player picked in a mid-action prompt (-1 = cancelled). */
    public static void sendCellChoice(int cell) {
        submitLocalOp(InnerOp.CELL_CHOICE, (byte) 0, cell, null, false);
    }

    /** Wraps a sender-side item selector so the resolved choice is broadcast before it is applied. */
    public static com.shatteredpixel.shatteredpixeldungeon.windows.WndBag.ItemSelector
            wrapItemSelectorForLan(final com.shatteredpixel.shatteredpixeldungeon.windows.WndBag.ItemSelector inner) {
        return new com.shatteredpixel.shatteredpixeldungeon.windows.WndBag.ItemSelector() {
            @Override public String textPrompt() { return inner.textPrompt(); }
            @Override public Class<? extends com.shatteredpixel.shatteredpixeldungeon.items.bags.Bag> preferredBag() { return inner.preferredBag(); }
            @Override public boolean hideAfterSelecting() { return inner.hideAfterSelecting(); }
            @Override public boolean itemSelectable(Item item) { return inner.itemSelectable(item); }
            @Override public void onSelect(Item item) {
                int bag = -1, slot = -1;
                if (item != null && Dungeon.hero != null) {
                    java.util.ArrayList<com.shatteredpixel.shatteredpixeldungeon.items.bags.Bag> bags = Dungeon.hero.belongings.getBags();
                    for (int b = 0; b < bags.size(); b++) {
                        int idx = bags.get(b).items.indexOf(item);
                        if (idx >= 0) { bag = b; slot = idx; break; }
                    }
                }
                sendItemChoice(bag, slot);
                // resolves sim effects on the render thread while the actor loop is
                // parked — sim RNG + nested-prompt wrapping, see resolveLocalChoice
                resolveLocalChoice(() -> inner.onSelect(item));
            }
        };
    }

    /** Wraps a sender-side cell listener so the resolved choice is broadcast before it is applied. */
    public static com.shatteredpixel.shatteredpixeldungeon.scenes.CellSelector.Listener
            wrapCellListenerForLan(final com.shatteredpixel.shatteredpixeldungeon.scenes.CellSelector.Listener inner) {
        return new com.shatteredpixel.shatteredpixeldungeon.scenes.CellSelector.Listener() {
            @Override public void onSelect(Integer cell) {
                sendCellChoice(cell == null ? -1 : cell);
                resolveLocalChoice(() -> inner.onSelect(cell));
            }
            @Override public String prompt() { return inner.prompt(); }
        };
    }

    /**
     * Remote-side resolution of a mid-action choice packet. The effect resolves
     * simulation state, so it needs the deterministic sim RNG, and it may raise
     * a NESTED prompt (e.g. a picked scroll that then asks for a target) — so
     * remoteItemExecution is set for its duration to park those prompts too.
     */
    private static void resolveRemoteChoice(Hero remoteHero, Runnable effect) {
        boolean wasRemote = remoteItemExecution;
        remoteItemExecution = true;
        remoteExecutionHero = remoteHero;
        com.watabou.utils.Random.enterSimContext();
        try {
            effect.run();
        } finally {
            com.watabou.utils.Random.exitSimContext();
            remoteItemExecution = wasRemote;
        }
        synchronized (remoteHero.lanActionLock) {
            remoteHero.lanActionLock.notifyAll();
        }
    }

    /**
     * Compatibility shim for the old per-turn reader API. The v3 protocol uses
     * one PERSISTENT reader thread per socket (see ensureGameplayReaders); this
     * method now just makes sure those readers are running. Idempotent.
     */
    public static void receiveActionAsync(Hero remoteHero) {
        if (!lanMode) return;
        ensureGameplayReaders();
    }

    // ==================================================================
    // Leader-sequenced commit protocol (protocol v3)
    //
    // Every gameplay op flows through the leader (host):
    //
    //   follower input : follower --REQUEST(clientSeq, op)--> leader
    //   leader         : stamps globalSeq --COMMIT--> ALL followers
    //                    (+ applies remote ops locally)
    //   follower       : applies commits in globalSeq order, sends ACK
    //
    // The op's owner does not execute until its own commit echo returns
    // (pessimistic execution). The leader keeps a ring buffer of recent
    // commits and retransmits when a follower's ACK stalls; followers
    // dedupe by globalSeq (stale commits are re-acked and dropped) and
    // the leader dedupes retransmitted requests by per-player clientSeq.
    //
    // Desync safety net: at the shared pre-execution sim point of every
    // ACTION commit each device computes a state hash. The leader
    // broadcasts its hash (STATE_CHECK); followers compare and, on
    // mismatch, request a full authoritative snapshot (SNAPSHOT) which
    // is applied by reloading the game — divergence self-heals.
    // ==================================================================

    /** Inner op kinds carried by REQUEST/COMMIT envelopes. */
    public static class InnerOp {
        public static final byte ACTION          = 0;
        public static final byte ITEM_CHOICE     = 1;
        public static final byte CELL_CHOICE     = 2;
        public static final byte OPTION_CHOICE   = 3;
        public static final byte ITEM_IDENTIFIED = 4;
    }

    /** One sequenced gameplay op. Immutable; also the ring-buffer entry. */
    public static class Commit {
        public final int globalSeq;   // leader-assigned total order
        public final int player;      // originating player index (== heroId)
        public final int clientSeq;   // originator's request counter (echo matching / dedup)
        public final byte inner;      // InnerOp.*
        public final byte actionType; // ActionType.* when inner == ACTION
        public final int targetPos;   // action target, or choice payload
        public final String utf;      // class name for ITEM_IDENTIFIED, else null

        public Commit(int globalSeq, int player, int clientSeq,
                      byte inner, byte actionType, int targetPos, String utf) {
            this.globalSeq = globalSeq;
            this.player = player;
            this.clientSeq = clientSeq;
            this.inner = inner;
            this.actionType = actionType;
            this.targetPos = targetPos;
            this.utf = utf;
        }
    }

    // --- leader state ---
    private static final Object seqLock = new Object();
    private static int globalSeqCounter = 0;
    private static final java.util.ArrayDeque<Commit> commitLog = new java.util.ArrayDeque<>();
    private static final int COMMIT_LOG_CAP = 1024;
    private static int[]  lastClientSeqSeen = new int[MAX_PLAYERS]; // request dedup, per player
    private static int[]  lastAckedSeq      = new int[MAX_PLAYERS];
    private static long[] lastAckAt         = new long[MAX_PLAYERS];
    private static long[] lastResendAt      = new long[MAX_PLAYERS];

    // --- follower state ---
    private static final Object applyLock = new Object();
    private static int lastAppliedSeq = 0;
    private static final java.util.TreeMap<Integer, Commit> reorderBuffer = new java.util.TreeMap<>();

    // --- this device's outgoing requests ---
    private static final Object requestLock = new Object();
    private static int clientSeqCounter = 0;
    private static final java.util.Map<Integer, PendingRequest> pendingRequests =
            java.util.Collections.synchronizedMap(new java.util.HashMap<Integer, PendingRequest>());

    /** How long a follower blocks waiting for its own commit echo before giving up. Test seam. */
    public static volatile int commitEchoTimeoutMs = 10000;
    private static final int REQUEST_RESEND_MS = 1000;
    private static final int COMMIT_RESEND_MS  = 1500;

    private static class PendingRequest {
        final int clientSeq;
        final byte inner, actionType;
        final int targetPos;
        final String utf;
        volatile long lastSentAt = 0;
        volatile int globalSeq = -1; // set by the commit echo

        PendingRequest(int clientSeq, byte inner, byte actionType, int targetPos, String utf) {
            this.clientSeq = clientSeq;
            this.inner = inner;
            this.actionType = actionType;
            this.targetPos = targetPos;
            this.utf = utf;
        }
    }

    // --- reader / retransmit lifecycle ---
    private static final java.util.Set<DataInputStream> readerCovered =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<DataInputStream, Boolean>());
    private static volatile boolean retransmitLoopRunning = false;

    // --- state-check bookkeeping (follower side) ---
    private static final Object hashLock = new Object();
    private static final int HASH_WINDOW = 256;
    private static final java.util.LinkedHashMap<Integer, Long> leaderHashes = new java.util.LinkedHashMap<>();
    private static final java.util.LinkedHashMap<Integer, Long> localHashes  = new java.util.LinkedHashMap<>();
    private static volatile long lastSnapshotRequestAt = 0;
    private static volatile boolean resyncInProgress = false;
    private static final int SNAPSHOT_REQUEST_COOLDOWN_MS = 5000;

    // --- choice stash: commit arrived before the local sim parked its prompt ---
    private static final Object choiceLock = new Object();
    private static Commit stashedItemChoice   = null;
    private static Commit stashedCellChoice   = null;
    private static Commit stashedOptionChoice = null;

    /**
     * Sequences one locally-originated op into the commit stream.
     * Leader: assigns a globalSeq and broadcasts immediately (never blocks).
     * Follower: sends a REQUEST; when block=true, parks the calling thread
     * until the leader's COMMIT echo arrives (pessimistic execution),
     * re-sending the request every REQUEST_RESEND_MS (the leader dedupes).
     * Returns the assigned globalSeq, or -1 if unsequenced.
     */
    private static int submitLocalOp(byte inner, byte actionType, int targetPos, String utf, boolean block) {
        if (!lanMode) return -1;

        // The echo gate can only release if a reader is consuming commits.
        ensureGameplayReaders();

        if (isHost) {
            Commit c = leaderSequence(localPlayerIndex, nextClientSeq(), inner, actionType, targetPos, utf);
            return c != null ? c.globalSeq : -1;
        }

        if (clientOut == null) {
            // No leader connection at all (offline follower / headless test):
            // never park the sim on an echo that cannot arrive.
            lanLog("submitLocalOp | no leader stream — op proceeds unsequenced (inner=%d)", inner);
            return -1;
        }

        PendingRequest pr = new PendingRequest(nextClientSeq(), inner, actionType, targetPos, utf);
        pendingRequests.put(pr.clientSeq, pr);
        sendRequestPacket(pr);

        if (!block) return -1;

        long deadline = System.currentTimeMillis() + commitEchoTimeoutMs;
        synchronized (pr) {
            while (lanMode && pr.globalSeq < 0 && System.currentTimeMillis() < deadline) {
                long wait = Math.min(REQUEST_RESEND_MS, deadline - System.currentTimeMillis());
                if (wait <= 0) break;
                try {
                    pr.wait(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (lanMode && pr.globalSeq < 0
                        && System.currentTimeMillis() - pr.lastSentAt >= REQUEST_RESEND_MS) {
                    sendRequestPacket(pr); // leader dedupes by clientSeq
                }
            }
        }
        if (pr.globalSeq < 0) {
            pendingRequests.remove(pr.clientSeq);
            lanLog("submitLocalOp | echo timeout clientSeq=%d inner=%d — proceeding unsequenced", pr.clientSeq, inner);
        }
        return pr.globalSeq;
    }

    private static int nextClientSeq() {
        synchronized (requestLock) {
            return ++clientSeqCounter;
        }
    }

    /** Leader: assign the next globalSeq, log it, broadcast it. Null if the request is a stale duplicate. */
    private static Commit leaderSequence(int player, int clientSeq, byte inner, byte actionType, int targetPos, String utf) {
        Commit c;
        synchronized (seqLock) {
            if (player >= 0 && player < lastClientSeqSeen.length && player != localPlayerIndex) {
                if (clientSeq <= lastClientSeqSeen[player]) {
                    lanLog("leaderSequence | dup request player=%d clientSeq=%d — dropped", player, clientSeq);
                    return null;
                }
                lastClientSeqSeen[player] = clientSeq;
            }
            c = new Commit(++globalSeqCounter, player, clientSeq, inner, actionType, targetPos, utf);
            commitLog.addLast(c);
            if (commitLog.size() > COMMIT_LOG_CAP) commitLog.removeFirst();
        }
        broadcastCommit(c);
        return c;
    }

    private static void broadcastCommit(Commit c) {
        for (int i = 0; i < outs.size(); i++) {
            DataOutputStream out = outs.get(i);
            Object lock = i < outLocks.size() ? outLocks.get(i) : out;
            try {
                writeCommitTo(out, lock, c);
            } catch (IOException e) {
                // Dead stream: the ping sender / reader will fire the disconnect signal.
                lanLog("broadcastCommit | write failed stream=%d seq=%d: %s", i, c.globalSeq, e.getMessage());
            }
        }
    }

    private static void writeCommitTo(DataOutputStream out, Object lock, Commit c) throws IOException {
        synchronized (lock) {
            out.writeByte(PacketType.COMMIT);
            out.writeInt(c.globalSeq);
            out.writeInt(c.player);
            out.writeInt(c.clientSeq);
            out.writeByte(c.inner);
            out.writeByte(c.actionType);
            out.writeInt(c.targetPos);
            if (c.inner == InnerOp.ITEM_IDENTIFIED) out.writeUTF(c.utf != null ? c.utf : "");
            out.flush();
        }
    }

    private static void sendRequestPacket(PendingRequest pr) {
        DataOutputStream out = clientOut;
        if (out == null) {
            lanLog("sendRequestPacket | WARN clientOut is null — request NOT sent!");
            return;
        }
        try {
            synchronized (clientOutLock) {
                out.writeByte(PacketType.REQUEST);
                out.writeInt(pr.clientSeq);
                out.writeByte(pr.inner);
                out.writeByte(pr.actionType);
                out.writeInt(pr.targetPos);
                if (pr.inner == InnerOp.ITEM_IDENTIFIED) out.writeUTF(pr.utf != null ? pr.utf : "");
                out.flush();
            }
            pr.lastSentAt = System.currentTimeMillis();
        } catch (IOException e) {
            GLog.w("Failed to send request: %s", e.getMessage());
        }
    }

    /**
     * Starts the persistent gameplay readers (one per socket) and the
     * retransmit loop. Idempotent — safe to call every remote hero turn.
     */
    public static synchronized void ensureGameplayReaders() {
        if (!lanMode) return;
        if (isHost) {
            for (int i = 0; i < ins.size(); i++) {
                final DataInputStream in = ins.get(i);
                if (in == null || !readerCovered.add(in)) continue;
                final int slot = i + 1; // stream i belongs to player slot i+1
                lastAckAt[slot] = System.currentTimeMillis();
                new Thread(() -> gameplayReaderLoop(in, slot), "net-reader-p" + slot).start();
            }
        } else {
            final DataInputStream in = clientIn;
            if (in != null && readerCovered.add(in)) {
                new Thread(() -> gameplayReaderLoop(in, -1), "net-reader-leader").start();
            }
        }
        startRetransmitLoop();
    }

    /**
     * The persistent per-socket reader. On the leader, playerSlot is the
     * connected player's index (reads REQUEST/ACK); on a follower it is -1
     * (reads COMMIT/STATE_CHECK/SNAPSHOT).
     */
    private static void gameplayReaderLoop(DataInputStream in, int playerSlot) {
        lanLog("gameplayReader | started slot=%d isHost=%b", playerSlot, isHost);
        try {
            while (lanMode) {
                byte type;
                try {
                    type = in.readByte();
                } catch (IOException e) {
                    handleReaderDisconnect(playerSlot, e);
                    break;
                }
                try {
                    if (type == PacketType.REQUEST && playerSlot > 0) {
                        int clientSeq = in.readInt();
                        byte inner = in.readByte();
                        byte actionType = in.readByte();
                        int targetPos = in.readInt();
                        String utf = (inner == InnerOp.ITEM_IDENTIFIED) ? in.readUTF() : null;
                        Commit c = leaderSequence(playerSlot, clientSeq, inner, actionType, targetPos, utf);
                        if (c != null) dispatchCommit(c); // apply the remote op on the leader too

                    } else if (type == PacketType.COMMIT) {
                        int globalSeq = in.readInt();
                        int player = in.readInt();
                        int clientSeq = in.readInt();
                        byte inner = in.readByte();
                        byte actionType = in.readByte();
                        int targetPos = in.readInt();
                        String utf = (inner == InnerOp.ITEM_IDENTIFIED) ? in.readUTF() : null;
                        onCommitReceived(new Commit(globalSeq, player, clientSeq, inner, actionType, targetPos, utf));

                    } else if (type == PacketType.ACK && playerSlot > 0) {
                        int acked = in.readInt();
                        synchronized (seqLock) {
                            if (acked > lastAckedSeq[playerSlot]) lastAckedSeq[playerSlot] = acked;
                            lastAckAt[playerSlot] = System.currentTimeMillis();
                        }

                    } else if (type == PacketType.STATE_CHECK) {
                        int seq = in.readInt();
                        long hash = in.readLong();
                        onStateCheckReceived(seq, hash);

                    } else if (type == PacketType.SNAPSHOT_REQUEST && playerSlot > 0) {
                        lanLog("gameplayReader | SNAPSHOT_REQUEST from player %d", playerSlot);
                        final int slot = playerSlot;
                        new Thread(() -> buildAndSendSnapshot(slot), "net-snapshot-builder").start();

                    } else if (type == PacketType.SNAPSHOT) {
                        int seqAt = in.readInt();
                        int depth = in.readInt();
                        int branch = in.readInt();
                        byte[] gameBytes = new byte[in.readInt()];
                        in.readFully(gameBytes);
                        byte[] levelBytes = new byte[in.readInt()];
                        in.readFully(levelBytes);
                        applySnapshot(seqAt, depth, branch, gameBytes, levelBytes);

                    } else if (type == PacketType.ITEM_IDENTIFIED) {
                        // Legacy path kept for stray packets: apply directly.
                        applyItemIdentified(in.readUTF());

                    } else if (type == PacketType.PING) {
                        // Heartbeat — proves the connection is alive.
                    } else {
                        // Unknown/legacy type: framing is type-implicit, so we cannot
                        // skip its payload. Log loudly; subsequent reads may misparse.
                        lanLog("gameplayReader | UNEXPECTED packet type=%d slot=%d", type, playerSlot);
                    }
                } catch (IOException e) {
                    handleReaderDisconnect(playerSlot, e);
                    break;
                }
            }
        } catch (Exception e) {
            if (e instanceof InterruptedIOException) Thread.currentThread().interrupt();
            lanLog("gameplayReader | fatal %s slot=%d", e.getClass().getSimpleName(), playerSlot);
        } finally {
            readerCovered.remove(in);
            lanLog("gameplayReader | stopped slot=%d", playerSlot);
        }
    }

    private static void handleReaderDisconnect(int playerSlot, IOException e) {
        if (!lanMode || Thread.currentThread().isInterrupted()) return;
        GLog.w("Peer disconnected: %s", e.getClass().getSimpleName());
        Hero hero = heroForPlayer(playerSlot > 0 ? playerSlot : 0);
        releaseAllEchoGates();
        peerDisconnectSignal.dispatch(new PeerDisconnected(hero));
        try {
            com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene.notifyActorThread();
        } catch (Throwable ignored) {}
    }

    /** Follower: buffer, dedupe and apply commits strictly in globalSeq order. */
    private static void onCommitReceived(Commit c) {
        java.util.ArrayList<Commit> toApply = null;
        synchronized (applyLock) {
            if (c.globalSeq <= lastAppliedSeq) {
                lanLog("onCommitReceived | stale seq=%d (applied=%d) — re-acking", c.globalSeq, lastAppliedSeq);
                sendAckNow();
                return;
            }
            reorderBuffer.put(c.globalSeq, c);
            if (!resyncInProgress) {
                while (!reorderBuffer.isEmpty() && reorderBuffer.firstKey() == lastAppliedSeq + 1) {
                    Commit n = reorderBuffer.pollFirstEntry().getValue();
                    lastAppliedSeq = n.globalSeq;
                    if (toApply == null) toApply = new java.util.ArrayList<>();
                    toApply.add(n);
                }
            }
        }
        if (toApply != null) {
            for (Commit n : toApply) dispatchCommit(n);
        }
        sendAckNow();
    }

    /** Applies one commit to the local simulation (or releases the owner's echo gate). */
    private static void dispatchCommit(Commit c) {
        if (c.player == localPlayerIndex) {
            releaseEchoGate(c);
            return;
        }
        switch (c.inner) {
            case InnerOp.ACTION: {
                Hero h = heroForPlayer(c.player);
                if (h == null) {
                    lanLog("dispatchCommit | ACTION seq=%d for unknown player %d — dropped", c.globalSeq, c.player);
                    return;
                }
                synchronized (h.lanActionLock) {
                    h.lanActionInbox.addLast(c);
                    h.lanActionLock.notifyAll();
                }
                try {
                    com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene.notifyActorThread();
                } catch (Throwable ignored) {}
                break;
            }
            case InnerOp.ITEM_CHOICE:
                deliverChoiceCommit(c);
                break;
            case InnerOp.CELL_CHOICE:
                deliverChoiceCommit(c);
                break;
            case InnerOp.OPTION_CHOICE:
                deliverChoiceCommit(c);
                break;
            case InnerOp.ITEM_IDENTIFIED:
                applyItemIdentified(c.utf);
                break;
            default:
                lanLog("dispatchCommit | unknown inner=%d seq=%d", c.inner, c.globalSeq);
        }
    }

    private static void releaseEchoGate(Commit c) {
        PendingRequest pr = pendingRequests.remove(c.clientSeq);
        if (pr != null) {
            synchronized (pr) {
                pr.globalSeq = c.globalSeq;
                pr.notifyAll();
            }
        }
    }

    private static void releaseAllEchoGates() {
        java.util.ArrayList<PendingRequest> gates;
        synchronized (pendingRequests) {
            gates = new java.util.ArrayList<>(pendingRequests.values());
            pendingRequests.clear();
        }
        for (PendingRequest pr : gates) {
            synchronized (pr) {
                pr.notifyAll();
            }
        }
    }

    private static Hero heroForPlayer(int player) {
        if (Dungeon.heroes == null || player < 0 || player >= Dungeon.heroes.size()) return null;
        return Dungeon.heroes.get(player);
    }

    private static void applyItemIdentified(String className) {
        if (className == null || className.isEmpty()) return;
        Game.runOnRenderThread(() -> {
            try {
                Class<?> cls = Class.forName(className);
                Item item = (Item) Reflection.newInstance(cls);
                if (item != null) item.identify(false);
            } catch (Exception e) {
                GLog.w("Could not apply remote identification: %s", e.getMessage());
            }
        });
    }

    // ------------------------------------------------------------------
    // Choice commits: a choice may arrive before this device's sim has
    // reached the prompt (persistent readers dispatch eagerly). The
    // commit is stashed and applied the moment the prompt parks.
    // ------------------------------------------------------------------

    private static void deliverChoiceCommit(Commit c) {
        Runnable apply = null;
        synchronized (choiceLock) {
            if (c.inner == InnerOp.ITEM_CHOICE) {
                com.shatteredpixel.shatteredpixeldungeon.windows.WndBag.ItemSelector sel = pendingRemoteItemSelector;
                pendingRemoteItemSelector = null;
                if (sel == null) {
                    stashedItemChoice = c;
                    lanLog("deliverChoiceCommit | ITEM_CHOICE seq=%d stashed (prompt not parked yet)", c.globalSeq);
                } else {
                    apply = () -> applyItemChoice(sel, c);
                }
            } else if (c.inner == InnerOp.CELL_CHOICE) {
                com.shatteredpixel.shatteredpixeldungeon.scenes.CellSelector.Listener sel = pendingRemoteCellListener;
                pendingRemoteCellListener = null;
                if (sel == null) {
                    stashedCellChoice = c;
                    lanLog("deliverChoiceCommit | CELL_CHOICE seq=%d stashed (prompt not parked yet)", c.globalSeq);
                } else {
                    apply = () -> applyCellChoice(sel, c);
                }
            } else if (c.inner == InnerOp.OPTION_CHOICE) {
                LanChoiceHandler handler = pendingRemoteOptionHandler;
                pendingRemoteOptionHandler = null;
                if (handler == null) {
                    stashedOptionChoice = c;
                    lanLog("deliverChoiceCommit | OPTION_CHOICE seq=%d stashed (prompt not parked yet)", c.globalSeq);
                } else {
                    apply = () -> applyOptionChoice(handler, c);
                }
            }
        }
        if (apply != null) apply.run();
    }

    private static void applyItemChoice(com.shatteredpixel.shatteredpixeldungeon.windows.WndBag.ItemSelector sel, Commit c) {
        Hero h = heroForPlayer(c.player);
        Item chosen = null;
        if (c.targetPos != -1 && h != null) {
            chosen = h.itemAt((c.targetPos >>> 16) & 0xFFFF, c.targetPos & 0xFFFF);
        }
        lanLog("applyItemChoice | seq=%d payload=%d item=%s", c.globalSeq, c.targetPos,
                chosen != null ? chosen.getClass().getSimpleName() : "null");
        final Item finalChosen = chosen;
        resolveRemoteChoice(h, () -> sel.onSelect(finalChosen));
    }

    private static void applyCellChoice(com.shatteredpixel.shatteredpixeldungeon.scenes.CellSelector.Listener sel, Commit c) {
        lanLog("applyCellChoice | seq=%d cell=%d", c.globalSeq, c.targetPos);
        resolveRemoteChoice(heroForPlayer(c.player), () -> sel.onSelect(c.targetPos == -1 ? null : c.targetPos));
    }

    private static void applyOptionChoice(LanChoiceHandler handler, Commit c) {
        lanLog("applyOptionChoice | seq=%d index=%d", c.globalSeq, c.targetPos);
        resolveRemoteChoice(heroForPlayer(c.player), () -> handler.onLanChoice(c.targetPos));
    }

    /**
     * Parks a remote item prompt. If the choice commit already arrived it is
     * applied immediately (on a fresh thread, mirroring reader-thread context).
     */
    public static void parkRemoteItemSelector(com.shatteredpixel.shatteredpixeldungeon.windows.WndBag.ItemSelector sel) {
        Commit c;
        synchronized (choiceLock) {
            c = stashedItemChoice;
            stashedItemChoice = null;
            if (c == null) {
                pendingRemoteItemSelector = sel;
                return;
            }
        }
        final Commit fc = c;
        new Thread(() -> applyItemChoice(sel, fc), "net-choice-replay").start();
    }

    /** Parks a remote cell prompt; applies a stashed choice commit if one already arrived. */
    public static void parkRemoteCellListener(com.shatteredpixel.shatteredpixeldungeon.scenes.CellSelector.Listener sel) {
        Commit c;
        synchronized (choiceLock) {
            c = stashedCellChoice;
            stashedCellChoice = null;
            if (c == null) {
                pendingRemoteCellListener = sel;
                return;
            }
        }
        final Commit fc = c;
        new Thread(() -> applyCellChoice(sel, fc), "net-choice-replay").start();
    }

    /** Parks a remote option-dialog handler; applies a stashed choice commit if one already arrived. */
    public static void parkRemoteOptionHandler(LanChoiceHandler handler) {
        Commit c;
        synchronized (choiceLock) {
            c = stashedOptionChoice;
            stashedOptionChoice = null;
            if (c == null) {
                pendingRemoteOptionHandler = handler;
                return;
            }
        }
        final Commit fc = c;
        new Thread(() -> applyOptionChoice(handler, fc), "net-choice-replay").start();
    }

    private static void sendAckNow() {
        DataOutputStream out = clientOut;
        if (out == null) return;
        int seq;
        synchronized (applyLock) {
            seq = lastAppliedSeq;
        }
        try {
            synchronized (clientOutLock) {
                out.writeByte(PacketType.ACK);
                out.writeInt(seq);
                out.flush();
            }
        } catch (IOException ignored) {
            // Dead stream — disconnect detection handles it.
        }
    }

    /**
     * Retransmit loop. Leader: re-sends commits to followers whose ACK has
     * stalled. Follower: re-sends requests that never got a commit echo.
     */
    private static synchronized void startRetransmitLoop() {
        if (retransmitLoopRunning || !lanMode) return;
        retransmitLoopRunning = true;
        new Thread(() -> {
            try {
                while (lanMode) {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    if (!lanMode) break;
                    long now = System.currentTimeMillis();
                    if (isHost) {
                        int latest;
                        synchronized (seqLock) {
                            latest = globalSeqCounter;
                        }
                        for (int i = 0; i < outs.size(); i++) {
                            int slot = i + 1;
                            if (slot >= MAX_PLAYERS) break;
                            boolean stalled;
                            int from;
                            synchronized (seqLock) {
                                stalled = lastAckedSeq[slot] < latest
                                        && now - lastAckAt[slot] > COMMIT_RESEND_MS
                                        && now - lastResendAt[slot] > COMMIT_RESEND_MS;
                                from = lastAckedSeq[slot];
                            }
                            if (stalled) {
                                lastResendAt[slot] = now;
                                resendCommitsTo(i, from);
                            }
                        }
                    } else {
                        java.util.ArrayList<PendingRequest> stale = new java.util.ArrayList<>();
                        synchronized (pendingRequests) {
                            for (PendingRequest pr : pendingRequests.values()) {
                                if (now - pr.lastSentAt >= REQUEST_RESEND_MS) stale.add(pr);
                            }
                        }
                        for (PendingRequest pr : stale) sendRequestPacket(pr);
                    }
                }
            } finally {
                retransmitLoopRunning = false;
            }
        }, "net-retransmit").start();
    }

    /** Leader: replay all logged commits after fromSeq to one follower's stream. */
    private static void resendCommitsTo(int streamIndex, int fromSeq) {
        java.util.ArrayList<Commit> replay = new java.util.ArrayList<>();
        synchronized (seqLock) {
            for (Commit c : commitLog) {
                if (c.globalSeq > fromSeq) replay.add(c);
            }
            if (!replay.isEmpty() && replay.get(0).globalSeq != fromSeq + 1) {
                // Oldest needed commit already evicted from the ring — the follower
                // cannot catch up incrementally. It will hash-mismatch and snapshot.
                lanLog("resendCommitsTo | GAP: need seq %d but log starts at %d", fromSeq + 1, replay.get(0).globalSeq);
            }
        }
        if (replay.isEmpty()) return;
        lanLog("resendCommitsTo | stream=%d replaying %d commits from seq %d", streamIndex, replay.size(), fromSeq + 1);
        if (streamIndex >= outs.size()) return;
        DataOutputStream out = outs.get(streamIndex);
        Object lock = streamIndex < outLocks.size() ? outLocks.get(streamIndex) : out;
        for (Commit c : replay) {
            try {
                writeCommitTo(out, lock, c);
            } catch (IOException e) {
                lanLog("resendCommitsTo | write failed stream=%d: %s", streamIndex, e.getMessage());
                return;
            }
        }
    }

    // ------------------------------------------------------------------
    // State checks + snapshot resync: mobs (and everything else) may
    // never STAY diverged. Every device passes through the identical sim
    // point "about to execute commit K"; the leader broadcasts its hash
    // there, followers compare theirs and self-heal via snapshot.
    // ------------------------------------------------------------------

    /**
     * Called at the pre-execution sim point of an ACTION commit — by the
     * owner right after its echo gate releases, and by every other device
     * when it consumes the commit from the hero's inbox.
     */
    public static void onAboutToExecuteCommit(int globalSeq) {
        if (!lanMode || globalSeq <= 0) return;
        long hash = computeStateHash();
        if (isHost) {
            for (int i = 0; i < outs.size(); i++) {
                DataOutputStream out = outs.get(i);
                Object lock = i < outLocks.size() ? outLocks.get(i) : out;
                try {
                    synchronized (lock) {
                        out.writeByte(PacketType.STATE_CHECK);
                        out.writeInt(globalSeq);
                        out.writeLong(hash);
                        out.flush();
                    }
                } catch (IOException ignored) {}
            }
        } else {
            Long leader;
            synchronized (hashLock) {
                leader = leaderHashes.remove(globalSeq);
                if (leader == null) {
                    localHashes.put(globalSeq, hash);
                    trimHashWindow(localHashes);
                    return;
                }
            }
            compareStateHashes(globalSeq, hash, leader);
        }
    }

    private static void onStateCheckReceived(int globalSeq, long leaderHash) {
        Long mine;
        synchronized (hashLock) {
            mine = localHashes.remove(globalSeq);
            if (mine == null) {
                leaderHashes.put(globalSeq, leaderHash);
                trimHashWindow(leaderHashes);
                return;
            }
        }
        compareStateHashes(globalSeq, mine, leaderHash);
    }

    private static void trimHashWindow(java.util.LinkedHashMap<Integer, Long> map) {
        java.util.Iterator<?> it = map.entrySet().iterator();
        while (map.size() > HASH_WINDOW && it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    private static void compareStateHashes(int globalSeq, long local, long leader) {
        if (local == leader) return;
        GLog.n("DESYNC DETECTED at commit %d: local=%d leader=%d — requesting resync", globalSeq, local, leader);
        lanLog("compareStateHashes | MISMATCH seq=%d local=%d leader=%d", globalSeq, local, leader);
        desyncDetectedSignal.dispatch(new DesyncDetected(local, leader));
        requestSnapshotResync();
    }

    /** Follower: ask the leader for its authoritative state. Rate-limited. */
    public static void requestSnapshotResync() {
        if (!lanMode || isHost) return;
        long now = System.currentTimeMillis();
        if (now - lastSnapshotRequestAt < SNAPSHOT_REQUEST_COOLDOWN_MS) return;
        lastSnapshotRequestAt = now;
        resyncInProgress = true;
        DataOutputStream out = clientOut;
        if (out == null) return;
        try {
            synchronized (clientOutLock) {
                out.writeByte(PacketType.SNAPSHOT_REQUEST);
                out.flush();
            }
            lanLog("requestSnapshotResync | sent");
        } catch (IOException e) {
            GLog.w("Failed to request snapshot: %s", e.getMessage());
            resyncInProgress = false;
        }
    }

    /**
     * Leader: freeze sequencing, wait for the sim to quiesce, save, and ship
     * the full game+level bundles to the desynced follower. Runs on its own
     * thread (never the reader thread that received the request).
     */
    private static void buildAndSendSnapshot(int playerSlot) {
        if (!isHost || !lanMode) return;
        byte[] gameBytes, levelBytes;
        int seqAt, depth, branch;
        synchronized (seqLock) {
            try {
                // Wait for the sim to quiesce: the actor thread parks either at
                // its normal wait point (Actor.processing() == false) or blocked
                // on seqLock inside leaderSequence — both are pre-mutation points.
                long quiesceDeadline = System.currentTimeMillis() + 2000;
                while (com.shatteredpixel.shatteredpixeldungeon.actors.Actor.processing()
                        && System.currentTimeMillis() < quiesceDeadline) {
                    Thread.sleep(50);
                }
            } catch (Throwable ignored) {}
            try {
                Dungeon.saveAll();
                int slot = com.shatteredpixel.shatteredpixeldungeon.GamesInProgress.curSlot;
                depth = Dungeon.depth;
                branch = Dungeon.branch;
                gameBytes = com.watabou.utils.FileUtils.bundleToBytes(
                        com.watabou.utils.FileUtils.bundleFromFile(
                                com.shatteredpixel.shatteredpixeldungeon.GamesInProgress.gameFile(slot)));
                levelBytes = com.watabou.utils.FileUtils.bundleToBytes(
                        com.watabou.utils.FileUtils.bundleFromFile(
                                com.shatteredpixel.shatteredpixeldungeon.GamesInProgress.depthFile(slot, depth, branch)));
                seqAt = globalSeqCounter;
            } catch (Exception e) {
                GLog.n("Failed to build snapshot: %s", e.getMessage());
                lanLog("buildAndSendSnapshot | FAILED: %s", e);
                return;
            }
        }
        int streamIndex = playerSlot - 1;
        if (streamIndex < 0 || streamIndex >= outs.size()) return;
        DataOutputStream out = outs.get(streamIndex);
        Object lock = streamIndex < outLocks.size() ? outLocks.get(streamIndex) : out;
        try {
            synchronized (lock) {
                out.writeByte(PacketType.SNAPSHOT);
                out.writeInt(seqAt);
                out.writeInt(depth);
                out.writeInt(branch);
                out.writeInt(gameBytes.length);
                out.write(gameBytes);
                out.writeInt(levelBytes.length);
                out.write(levelBytes);
                out.flush();
            }
            lanLog("buildAndSendSnapshot | sent to player %d: seq=%d game=%dB level=%dB",
                    playerSlot, seqAt, gameBytes.length, levelBytes.length);
        } catch (IOException e) {
            GLog.w("Failed to send snapshot: %s", e.getMessage());
        }
    }

    /**
     * Follower: adopt the leader's authoritative state. Writes the bundles to
     * the local save slot, fast-forwards the applied-commit cursor, clears all
     * in-flight protocol state, and reloads the game via InterlevelScene.
     */
    private static void applySnapshot(int seqAt, int depth, int branch, byte[] gameBytes, byte[] levelBytes) {
        lanLog("applySnapshot | seq=%d depth=%d game=%dB level=%dB", seqAt, depth, branch, gameBytes.length);
        try {
            int slot = com.shatteredpixel.shatteredpixeldungeon.GamesInProgress.curSlot;
            com.watabou.utils.FileUtils.bundleToFile(
                    com.shatteredpixel.shatteredpixeldungeon.GamesInProgress.gameFile(slot),
                    com.watabou.utils.FileUtils.bundleFromBytes(gameBytes));
            com.watabou.utils.FileUtils.bundleToFile(
                    com.shatteredpixel.shatteredpixeldungeon.GamesInProgress.depthFile(slot, depth, branch),
                    com.watabou.utils.FileUtils.bundleFromBytes(levelBytes));
        } catch (Exception e) {
            GLog.n("Failed to apply snapshot: %s", e.getMessage());
            resyncInProgress = false;
            return;
        }

        synchronized (applyLock) {
            lastAppliedSeq = seqAt;
            reorderBuffer.headMap(seqAt + 1).clear();
        }
        synchronized (hashLock) {
            leaderHashes.clear();
            localHashes.clear();
        }
        synchronized (choiceLock) {
            stashedItemChoice = stashedCellChoice = stashedOptionChoice = null;
        }
        pendingRemoteItemSelector = null;
        pendingRemoteCellListener = null;
        pendingRemoteOptionHandler = null;
        releaseAllEchoGates();
        if (Dungeon.heroes != null) {
            for (Hero h : Dungeon.heroes) {
                if (h == null) continue;
                synchronized (h.lanActionLock) {
                    h.lanActionInbox.clear();
                    h.lanActionLock.notifyAll();
                }
            }
        }
        sendAckNow();

        Game.runOnRenderThread(() -> {
            try {
                com.shatteredpixel.shatteredpixeldungeon.scenes.InterlevelScene.mode =
                        com.shatteredpixel.shatteredpixeldungeon.scenes.InterlevelScene.Mode.CONTINUE;
                Game.switchScene(com.shatteredpixel.shatteredpixeldungeon.scenes.InterlevelScene.class);
            } catch (Throwable t) {
                GLog.n("Snapshot reload failed: %s", t.getMessage());
            }
        });

        // Once the reload finishes, drain any commits that queued up meanwhile.
        new Thread(() -> {
            try {
                for (int i = 0; i < 40; i++) {
                    Thread.sleep(250);
                    if (Dungeon.heroes != null && !com.shatteredpixel.shatteredpixeldungeon.scenes.InterlevelScene.mode.equals(
                            com.shatteredpixel.shatteredpixeldungeon.scenes.InterlevelScene.Mode.CONTINUE)) break;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            resyncInProgress = false;
            drainReorderBuffer();
            lanLog("applySnapshot | resync complete, protocol resumed");
        }, "net-resync-drain").start();
    }

    /** Applies any contiguous commits waiting in the reorder buffer. */
    private static void drainReorderBuffer() {
        java.util.ArrayList<Commit> toApply = null;
        synchronized (applyLock) {
            while (!reorderBuffer.isEmpty() && reorderBuffer.firstKey() == lastAppliedSeq + 1) {
                Commit n = reorderBuffer.pollFirstEntry().getValue();
                lastAppliedSeq = n.globalSeq;
                if (toApply == null) toApply = new java.util.ArrayList<>();
                toApply.add(n);
            }
        }
        if (toApply != null) {
            for (Commit n : toApply) dispatchCommit(n);
            sendAckNow();
        }
    }

    // ==================================================================
    // End of leader-sequenced commit protocol
    // ==================================================================

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
     * Broadcasts that an item has been identified, as a sequenced commit.
     * The class name is sent so peers can instantiate and identify the same item.
     */
    public static void sendItemIdentified(String itemClassName) {
        submitLocalOp(InnerOp.ITEM_IDENTIFIED, (byte) 0, 0, itemClassName, false);
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
                        hash ^= (long) h.pos  * 0x9e3779b97f4a7c15L;
                        hash ^= (long) h.HP   * 0x6c62272e07bb0142L;
                        hash ^= (long) h.exp  * 0x517cc1b727220a95L;
                        hash ^= (long) h.lvl  * 0xd2a98b26625eee7bL;
                    }
                }
            }
            if (Dungeon.level != null && Dungeon.level.mobs != null) {
                // Sort by position for deterministic hash — HashSet order differs per JVM.
                java.util.List<com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob> sortedMobs =
                        new java.util.ArrayList<>(Dungeon.level.mobs);
                sortedMobs.sort((a, b) -> Integer.compare(a.pos, b.pos));
                for (com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob m : sortedMobs) {
                    if (m != null) {
                        hash ^= (long) m.pos * 0xbf58476d1ce4e5b9L;
                        hash ^= (long) m.HP  * 0x94d049bb133111ebL;
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
        for (int i = 0; i < outs.size(); i++) {
            DataOutputStream out = outs.get(i);
            Object lock = i < outLocks.size() ? outLocks.get(i) : out;
            synchronized (lock) {
                out.writeByte(PacketType.START);
                out.writeInt(playerCount);
                out.writeLong(seed);
                out.flush();
            }
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
        remoteItemExecution = false;
        localItemExecution = false;
        remoteExecutionHero = null;
        pendingRemoteItemSelector = null;
        pendingRemoteCellListener = null;
        pendingRemoteOptionHandler = null;
        lanChallenges = 0;

        resetCommitProtocolState();
    }

    /** Resets all leader-sequenced protocol state. Called on cleanup and at the start of every hosted/joined game. */
    public static void resetCommitProtocolState() {
        releaseAllEchoGates();
        synchronized (seqLock) {
            globalSeqCounter = 0;
            commitLog.clear();
            lastClientSeqSeen = new int[MAX_PLAYERS];
            lastAckedSeq = new int[MAX_PLAYERS];
            lastAckAt = new long[MAX_PLAYERS];
            lastResendAt = new long[MAX_PLAYERS];
        }
        synchronized (applyLock) {
            lastAppliedSeq = 0;
            reorderBuffer.clear();
        }
        synchronized (requestLock) {
            clientSeqCounter = 0;
        }
        synchronized (hashLock) {
            leaderHashes.clear();
            localHashes.clear();
        }
        synchronized (choiceLock) {
            stashedItemChoice = stashedCellChoice = stashedOptionChoice = null;
        }
        readerCovered.clear();
        resyncInProgress = false;
        lastSnapshotRequestAt = 0;
    }

    // Test seams — leader-sequenced commit protocol
    public static int getGlobalSeqForTesting() {
        synchronized (seqLock) { return globalSeqCounter; }
    }
    public static int getLastAppliedSeqForTesting() {
        synchronized (applyLock) { return lastAppliedSeq; }
    }
    public static int getLastAckedSeqForTesting(int playerSlot) {
        synchronized (seqLock) { return lastAckedSeq[playerSlot]; }
    }
    public static int getPendingRequestCountForTesting() {
        return pendingRequests.size();
    }
    public static void setEchoTimeoutForTesting(int ms) {
        commitEchoTimeoutMs = ms;
    }
    /**
     * The monitor guarding writes to clientOut. Tests that simulate this
     * device's own outgoing frames by writing to the injected clientOut
     * directly must hold this lock, so their writes don't interleave with the
     * reader thread's ACK writes (which also go out clientOut).
     */
    public static Object clientOutLockForTesting() {
        return clientOutLock;
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

    // Test seam — fire sendAction check without touching sockets.
    // Returns the commit's globalSeq (-1 when overridden or unsequenced).
    public static int sendActionIfLocal(HeroAction action, int heroId) {
        if (!lanMode) return -1;
        if (sendActionOverride != null) { sendActionOverride.accept(action, heroId); return -1; }
        return sendAction(action, heroId);
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
     * Public so tests in other packages can verify round-trip symmetry.
     */
    public static byte encodeAction(HeroAction action) {
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
        if (action instanceof HeroAction.UseItem)    return ActionType.USE_ITEM;
        if (action instanceof HeroAction.UseItemAt)  return ActionType.USE_ITEM_AT;
        if (action instanceof HeroAction.ShopBuy)     return ActionType.SHOP_BUY;
        if (action instanceof HeroAction.ShopSell)    return ActionType.SHOP_SELL;
        if (action instanceof HeroAction.Search)     return ActionType.SEARCH;
        return ActionType.REST;
    }

    /**
     * Extracts the target position from a HeroAction for the wire protocol.
     * Public so tests in other packages can verify round-trip symmetry.
     */
    public static int getTargetPos(HeroAction action) {
        if (action instanceof HeroAction.Attack) {
            HeroAction.Attack a = (HeroAction.Attack) action;
            return a.target != null ? a.target.pos : a.dst;
        }
        if (action instanceof HeroAction.Interact) {
            HeroAction.Interact i = (HeroAction.Interact) action;
            return i.ch != null ? i.ch.pos : i.dst;
        }
        if (action instanceof HeroAction.UseItem) {
            HeroAction.UseItem u = (HeroAction.UseItem) action;
            return (u.bagOrdinal << 28) | ((u.slotIndex & 0x3FF) << 18)
                    | ((u.actionIdx & 0xFF) << 10);
        }
        if (action instanceof HeroAction.UseItemAt) {
            HeroAction.UseItemAt u = (HeroAction.UseItemAt) action;
            return (u.bagOrdinal << 28) | ((u.slotIndex & 0x3FF) << 18)
                    | ((u.verb & 0x3) << 16) | (u.cell & 0xFFFF);
        }
        if (action instanceof HeroAction.ShopSell) {
            HeroAction.ShopSell u = (HeroAction.ShopSell) action;
            return (u.bagOrdinal << 28) | ((u.slotIndex & 0x3FF) << 18) | (u.all ? 1 : 0);
        }
        return action != null ? action.dst : 0;
    }

    /**
     * Decodes a wire protocol byte + position back into a HeroAction.
     * For Attack/Interact the receiver looks up the Char by position at simulation time.
     * Public so tests in other packages can verify round-trip symmetry.
     */
    public static HeroAction decodeAction(byte actionType, int targetPos) {
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
        if (actionType == ActionType.USE_ITEM) {
            int bagOrdinal = (targetPos >>> 28) & 0xF;
            int slotIndex  = (targetPos >>> 18) & 0x3FF;
            int actionIdx  = (targetPos >>> 10) & 0xFF;
            return new HeroAction.UseItem(bagOrdinal, slotIndex, actionIdx);
        }
        if (actionType == ActionType.SHOP_BUY)       return new HeroAction.ShopBuy(targetPos);
        if (actionType == ActionType.SHOP_SELL) {
            int bagOrdinal = (targetPos >>> 28) & 0xF;
            int slotIndex  = (targetPos >>> 18) & 0x3FF;
            boolean all    = (targetPos & 1) != 0;
            return new HeroAction.ShopSell(bagOrdinal, slotIndex, all);
        }
        if (actionType == ActionType.USE_ITEM_AT) {
            int bagOrdinal = (targetPos >>> 28) & 0xF;
            int slotIndex  = (targetPos >>> 18) & 0x3FF;
            int verb       = (targetPos >>> 16) & 0x3;
            int cell       = targetPos & 0xFFFF;
            return new HeroAction.UseItemAt(bagOrdinal, slotIndex, verb, cell);
        }
        // REST decodes to a real action — it used to decode to null, which left the
        // remote hero waiting forever on a packet that had already been consumed
        if (actionType == ActionType.REST)           return new HeroAction.Rest(targetPos == 1);
        if (actionType == ActionType.SEARCH)         return new HeroAction.Search();
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

        // Host plays with its local challenge selection; clients adopt it below
        lanChallenges = com.shatteredpixel.shatteredpixeldungeon.SPDSettings.challenges();

        try {
            for (int i = 0; i < outs.size(); i++) {
                DataOutputStream out = outs.get(i);
                Object lock = i < outLocks.size() ? outLocks.get(i) : out;
                synchronized (lock) {
                    out.writeByte(PacketType.HANDSHAKE);
                    out.writeInt(PROTOCOL_VERSION); // EC-6.5: version field before seed
                    out.writeLong(seed);
                    out.writeInt(lanChallenges);
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
                Object lock = playerIndex < outLocks.size() ? outLocks.get(playerIndex) : out;
                synchronized (lock) {
                    out.writeByte(PacketType.CLASS_REJECTED);
                    out.writeInt(playerIndex);
                    out.flush();
                }
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
                    lanChallenges = in.readInt(); // adopt the host's challenge selection
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
