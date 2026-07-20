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

import static org.junit.jupiter.api.Assertions.*;

/**
 * v3 (leader-sequenced commit protocol) regression tests for the OLD
 * multi-step walk freeze bug.
 *
 * The old v2 bug: Hero.act() started a per-turn reader thread on EVERY act()
 * call for a remote hero, including intermediate steps of a multi-step walk.
 * The stale reader consumed the NEXT turn's action packet early; when the walk
 * completed, ready() cleared curAction and the action was lost — permanent
 * freeze.
 *
 * In v3 that failure mode is structurally impossible:
 *   - readers are PERSISTENT (one per client stream, "net-reader-p<slot>"),
 *     started idempotently by ensureGameplayReaders()/receiveActionAsync();
 *   - a commit is never "consumed" by a reader — it is buffered in the owning
 *     hero's lanActionInbox and stays there until the hero's turn drains it;
 *   - a multi-step walk consumes commits one per step, none can be stolen.
 *
 * These tests drive the real leader-side protocol over a real loopback socket:
 * this device is the LEADER (isHost=true, localPlayerIndex=0); the test plays
 * the remote client, feeding REQUEST frames with a contiguous clientSeq.
 */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class LanMultiStepWalkFreezeTest {

    private ServerSocket      serverSocket;
    private Socket            hostSideSocket;
    private Socket            clientSocket;

    private DataInputStream   hostIn;
    private DataOutputStream  hostOut;
    private DataInputStream   clientIn;
    private DataOutputStream  clientOut;

    private Hero localHero;
    private Hero remoteHero;

    /** contiguous per-client request counter — the leader dedupes on it */
    private int clientSeq;

    @BeforeEach
    void setUp() throws IOException {
        serverSocket = new ServerSocket(0);
        int port = serverSocket.getLocalPort();
        Future<Socket> serverSide =
                Executors.newSingleThreadExecutor().submit(() -> serverSocket.accept());
        clientSocket = new Socket("127.0.0.1", port);
        try {
            hostSideSocket = serverSide.get(2, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new IOException("setup failed", e);
        }

        hostIn    = new DataInputStream(hostSideSocket.getInputStream());
        hostOut   = new DataOutputStream(hostSideSocket.getOutputStream());
        clientIn  = new DataInputStream(clientSocket.getInputStream());
        clientOut = new DataOutputStream(clientSocket.getOutputStream());

        // LEADER device: reads REQUESTs from ins.get(0) = hostIn. The test
        // holds the raw client end (clientOut) and never injects it.
        NetworkManager.injectHostStreamsForTesting(hostIn, hostOut);
        NetworkManager.injectClientStreamsForTesting(null, null);

        localHero  = new Hero(); localHero.HP  = localHero.HT  = 20;
        remoteHero = new Hero(); remoteHero.HP = remoteHero.HT = 20;
        Dungeon.heroes = new ArrayList<>();
        Dungeon.heroes.add(localHero);
        Dungeon.heroes.add(remoteHero);
        Dungeon.hero = localHero;

        NetworkManager.setIsHostForTesting(true);
        NetworkManager.localPlayerIndex = 0;
        NetworkManager.resetActionReaderForTesting();
        NetworkManager.resetCommitProtocolState();
        clientSeq = 0;

        // let readers from previous test classes drain before counting threads
        awaitNetReaderCount(0, 2000);

        NetworkManager.lanMode = true;
    }

    @AfterEach
    void tearDown() throws IOException {
        NetworkManager.lanMode = false;
        NetworkManager.setIsHostForTesting(false);
        // Close sockets first: unblocks the persistent reader's readByte()
        if (clientSocket   != null && !clientSocket.isClosed())   clientSocket.close();
        if (hostSideSocket != null && !hostSideSocket.isClosed()) hostSideSocket.close();
        if (serverSocket   != null && !serverSocket.isClosed())   serverSocket.close();
        awaitNetReaderCount(0, 2000);
        NetworkManager.resetActionReaderForTesting();
        NetworkManager.resetCommitProtocolState();
        NetworkManager.injectHostStreamsForTesting(null, null);
        NetworkManager.injectClientStreamsForTesting(null, null);
        Dungeon.heroes = null;
        Dungeon.hero   = null;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** The remote client asks the leader to sequence a MOVE op. */
    void clientSendsMove(int targetPos) throws IOException {
        LanTestProtocol.writeActionRequest(clientOut, ++clientSeq,
                NetworkManager.ActionType.MOVE, targetPos);
    }

    /**
     * Start-of-turn as Hero.act() does it in v3: make sure the persistent
     * readers run, then drain the next commit from the hero's inbox and decode
     * it at execution time. Returns null on timeout (no commit available).
     */
    HeroAction turnStart(long maxWaitMs) throws InterruptedException {
        NetworkManager.receiveActionAsync(remoteHero); // idempotent shim
        synchronized (remoteHero.lanActionLock) {
            long deadline = System.currentTimeMillis() + maxWaitMs;
            while (remoteHero.lanActionInbox.isEmpty() && NetworkManager.lanMode) {
                long rem = deadline - System.currentTimeMillis();
                if (rem <= 0) break;
                remoteHero.lanActionLock.wait(rem);
            }
            NetworkManager.Commit c = remoteHero.lanActionInbox.pollFirst();
            if (c == null) return null;
            remoteHero.curAction = NetworkManager.decodeAction(c.actionType, c.targetPos);
            return remoteHero.curAction;
        }
    }

    int inboxSize() {
        synchronized (remoteHero.lanActionLock) {
            return remoteHero.lanActionInbox.size();
        }
    }

    /** Waits until the hero's inbox holds at least n commits. */
    boolean awaitInboxSize(int n, long maxWaitMs) throws InterruptedException {
        synchronized (remoteHero.lanActionLock) {
            long deadline = System.currentTimeMillis() + maxWaitMs;
            while (remoteHero.lanActionInbox.size() < n) {
                long rem = deadline - System.currentTimeMillis();
                if (rem <= 0) break;
                remoteHero.lanActionLock.wait(rem);
            }
            return remoteHero.lanActionInbox.size() >= n;
        }
    }

    static int countNetReaderThreads() {
        int n = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.isAlive() && t.getName().startsWith("net-reader-")) n++;
        }
        return n;
    }

    static void awaitNetReaderCount(int expected, long maxWaitMs) {
        long deadline = System.currentTimeMillis() + maxWaitMs;
        while (countNetReaderThreads() != expected
                && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(10); } catch (InterruptedException e) { return; }
        }
    }

    // =========================================================================
    // Old bug 1: a stale per-turn reader stole the next turn's packet.
    // v3 invariant: mid-walk receiveActionAsync() calls are idempotent no-ops
    // (readers are persistent) and a commit arriving mid-walk is buffered in
    // the hero's inbox — it can never be stolen or lost.
    // =========================================================================

    @Test
    void midWalkReaderCalls_cannotStealNextCommit_deliveredNextTurn()
            throws Exception {

        // Turn 1, step 1: A1 committed and consumed
        clientSendsMove(10);
        HeroAction a1 = turnStart(3000);
        assertNotNull(a1, "Step 1: must receive A1");
        assertEquals(10, ((HeroAction.Move) a1).dst);

        // Turn 1, steps 2..3 (the OLD buggy call pattern): receiveActionAsync
        // on every intermediate act() call. In v3 this is a harmless idempotent
        // shim — no per-turn reader exists to go stale.
        NetworkManager.receiveActionAsync(remoteHero);
        NetworkManager.receiveActionAsync(remoteHero);

        // Client sends A2 mid-walk — in v2 the stale reader consumed it here
        clientSendsMove(20);
        assertTrue(awaitInboxSize(1, 2000),
                "A2's commit must be buffered in the hero's lanActionInbox — " +
                "it cannot be consumed early by any reader");

        // Walk completes: ready() clears curAction
        remoteHero.curAction = null;

        // Turn 2: A2 is still in the inbox and must be delivered — the v2
        // freeze (packet stolen + wiped) is structurally impossible now.
        HeroAction a2 = turnStart(2000);
        assertNotNull(a2,
                "Turn 2 must receive A2 from the inbox. In v2 the stale reader " +
                "consumed it mid-walk and ready() wiped it — a permanent freeze.");
        assertEquals(20, ((HeroAction.Move) a2).dst);
    }

    @Test
    void intermediateSteps_startNoExtraReaderThreads_nextTurnReceivesAction()
            throws Exception {

        // Turn 1, step 1
        clientSendsMove(10);
        HeroAction a1 = turnStart(3000);
        assertNotNull(a1, "Step 1: must receive A1");
        assertEquals(10, ((HeroAction.Move) a1).dst);

        int readers = countNetReaderThreads();
        assertTrue(readers >= 1, "the persistent reader must be running");

        // Intermediate steps: repeated calls must not add reader threads
        NetworkManager.receiveActionAsync(remoteHero);
        NetworkManager.receiveActionAsync(remoteHero);
        Thread.sleep(50);
        assertEquals(readers, countNetReaderThreads(),
                "intermediate-step receiveActionAsync() calls must be no-ops — " +
                "one persistent reader per stream, never a stale extra thread");

        // A2 sent mid-walk sits safely in the inbox
        clientSendsMove(20);

        // Walk completes
        remoteHero.curAction = null;

        HeroAction a2 = turnStart(3000);
        assertNotNull(a2, "Turn 2 must receive A2");
        assertEquals(20, ((HeroAction.Move) a2).dst,
                "turn 2 must receive position 20");
    }

    // =========================================================================
    // Old bug 2: three-step walk with stale readers lost the next-turn packet.
    // v3 invariant: commits queue in the inbox and a multi-step walk consumes
    // them strictly one per step, in commit order, none lost.
    // =========================================================================

    @Test
    void threeStepWalk_commitsQueueInInbox_consumedOnePerStep_noneLost()
            throws Exception {

        // Readers run from gameplay start (HeroSelectScene) — bring them up before
        // the client streams its ops, so the leader sequences them into the inbox.
        NetworkManager.ensureGameplayReaders();

        // Client pre-sends three ops (as if entered during a long walk)
        clientSendsMove(10);
        clientSendsMove(20);
        clientSendsMove(30);

        assertTrue(awaitInboxSize(3, 2000),
                "all three commits must be buffered per hero — none consumed early");

        // Consumed one per turn, in leader-sequenced order
        HeroAction s1 = turnStart(1000);
        assertNotNull(s1);
        assertEquals(10, ((HeroAction.Move) s1).dst, "first commit first");
        remoteHero.curAction = null;

        HeroAction s2 = turnStart(1000);
        assertNotNull(s2);
        assertEquals(20, ((HeroAction.Move) s2).dst, "second commit second");
        remoteHero.curAction = null;

        HeroAction s3 = turnStart(1000);
        assertNotNull(s3);
        assertEquals(30, ((HeroAction.Move) s3).dst, "third commit third");
        remoteHero.curAction = null;

        assertNull(turnStart(300), "no phantom commits beyond the three sent");
    }

    @Test
    void threeStepWalk_midWalkCommit_stillDeliveredAfterWalkCompletes()
            throws Exception {

        // Step 1: A1 consumed
        clientSendsMove(10);
        HeroAction a1 = turnStart(3000);
        assertNotNull(a1, "Step 1: must receive A1");

        // Steps 2 and 3: the old buggy unconditional calls — now no-ops
        NetworkManager.receiveActionAsync(remoteHero);
        NetworkManager.receiveActionAsync(remoteHero);

        // A2 arrives between steps — buffered, not stolen
        clientSendsMove(20);
        assertTrue(awaitInboxSize(1, 2000), "A2 must wait in the inbox");

        // Yet another intermediate call must not disturb the buffered commit
        NetworkManager.receiveActionAsync(remoteHero);
        Thread.sleep(50);
        assertEquals(1, inboxSize(),
                "the buffered commit must survive intermediate receiveActionAsync() calls");

        // Walk ends
        remoteHero.curAction = null;

        HeroAction a2 = turnStart(3000);
        assertNotNull(a2, "three-step walk — turn 2 must receive A2, no freeze");
        assertEquals(20, ((HeroAction.Move) a2).dst, "position must be 20");
    }

    // =========================================================================
    // Old bug 3: the per-turn reader guard flip-flopped mid-walk. The guard
    // mechanics are dead in v3. Equivalent invariants: exactly one persistent
    // reader thread per client stream regardless of how often the shim is
    // called, and a commit arriving mid-walk waits untouched in the inbox.
    // =========================================================================

    @Test
    void persistentReader_isSingleton_acrossRepeatedShimCalls()
            throws Exception {

        int baseline = countNetReaderThreads();

        NetworkManager.receiveActionAsync(remoteHero);
        awaitNetReaderCount(baseline + 1, 2000);
        assertEquals(baseline + 1, countNetReaderThreads(),
                "first call must start exactly one persistent reader for the " +
                "single client stream (net-reader-p1)");

        for (int i = 0; i < 5; i++) {
            NetworkManager.receiveActionAsync(remoteHero);
        }
        Thread.sleep(100);
        assertEquals(baseline + 1, countNetReaderThreads(),
                "repeated receiveActionAsync()/ensureGameplayReaders() calls must " +
                "never spawn duplicate reader threads — duplicates were the v2 " +
                "stream-corruption bug");

        // ... and the singleton reader still delivers
        clientSendsMove(10);
        HeroAction a1 = turnStart(2000);
        assertNotNull(a1, "the singleton persistent reader must deliver commits");
        assertEquals(10, ((HeroAction.Move) a1).dst);
    }

    @Test
    void commitArrivingMidWalk_waitsInInbox_untilWalkCompletes()
            throws Exception {

        // Turn 1: A1 consumed — hero is now "mid-walk" with curAction set
        clientSendsMove(10);
        HeroAction a1 = turnStart(3000);
        assertNotNull(a1, "Step 1: A1 received");

        // A2 arrives mid-walk
        clientSendsMove(20);
        assertTrue(awaitInboxSize(1, 2000),
                "the mid-walk commit must wait in the hero's inbox");
        Thread.sleep(100);
        assertEquals(1, inboxSize(),
                "still exactly one buffered commit — nothing may consume it early");
        assertTrue(remoteHero.curAction instanceof HeroAction.Move
                        && ((HeroAction.Move) remoteHero.curAction).dst == 10,
                "curAction must stay the in-progress walk — the buffered commit " +
                "must not overwrite it (in v2 the stale reader did exactly that)");

        // Walk ends: ready() clears curAction
        remoteHero.curAction = null;

        // Turn 2: the buffered commit is delivered
        HeroAction a2 = turnStart(2000);
        assertNotNull(a2, "turn 2 must receive the buffered A2");
        assertEquals(20, ((HeroAction.Move) a2).dst, "turn 2 position must be 20");
        assertEquals(0, inboxSize(), "inbox drained — one commit per step");
    }
}
