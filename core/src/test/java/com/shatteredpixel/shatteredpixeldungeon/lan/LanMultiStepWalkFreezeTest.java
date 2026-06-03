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
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that reproduce and verify the fix for the REMAINING LAN freeze bug:
 *
 * Root cause: In the pre-fix code, Hero.act() called receiveActionAsync()
 * unconditionally on EVERY act() call for a remote hero — including intermediate
 * steps of a multi-step walk where curAction is already set. This started a
 * stale reader that blocked on readByte() and consumed the NEXT turn's action
 * packet early. When the walk completed, ready() cleared curAction. The next
 * remote turn found the action already consumed and cleared — permanent freeze.
 *
 * Fix: In Hero.act() LAN block, only call receiveActionAsync() when curAction
 * is null. During intermediate steps of a multi-step walk, curAction is already
 * set, so no stale reader is started.
 *
 * Test strategy: The tests directly reproduce the bug at the network protocol
 * level by simulating both the buggy and fixed code paths in the same test:
 *
 *   Buggy path:  calls receiveActionAsync() even when curAction != null
 *   Fixed path:  guards with (curAction == null) before calling receiveActionAsync()
 *
 * This approach lets us test the network behavior without needing full act()
 * invocation (which requires sprites, levels, etc.).
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
        Dungeon.heroes = null;
        Dungeon.hero   = null;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    void clientSendsMove(int targetPos) throws IOException {
        clientOut.writeByte(NetworkManager.PacketType.ACTION);
        clientOut.writeInt(1);   // heroId = 1 (remote hero)
        clientOut.writeByte(0);  // MOVE
        clientOut.writeInt(targetPos);
        clientOut.flush();
    }

    /**
     * Standard start-of-turn: receiveActionAsync only when curAction is null.
     * This is the FIXED code path.
     */
    HeroAction fixedTurnStart(long maxWaitMs) throws InterruptedException {
        synchronized (remoteHero.lanActionLock) {
            // FIXED: only start reader when curAction is null
            if (remoteHero.curAction == null) {
                NetworkManager.receiveActionAsync(remoteHero);
            }
            long deadline = System.currentTimeMillis() + maxWaitMs;
            while (remoteHero.curAction == null && NetworkManager.lanMode) {
                long rem = deadline - System.currentTimeMillis();
                if (rem <= 0) break;
                remoteHero.lanActionLock.wait(rem);
            }
        }
        return remoteHero.curAction;
    }

    /**
     * Simulates the BUGGY intermediate-step code path (before the fix):
     * calls receiveActionAsync() unconditionally even when curAction is already set.
     * This is the code path that starts a stale reader.
     */
    void buggyIntermediateStep() {
        synchronized (remoteHero.lanActionLock) {
            // BUG: receiveActionAsync called even when curAction != null
            NetworkManager.receiveActionAsync(remoteHero);
            // curAction != null → while would exit immediately
        }
    }

    /**
     * Simulates the FIXED intermediate-step code path (after the fix):
     * does NOT call receiveActionAsync() when curAction is already set.
     * No stale reader is started.
     */
    void fixedIntermediateStep() {
        // FIXED: act() checks curAction != null and skips receiveActionAsync()
        // Nothing to do here — curAction is already set, no reader needed
        assertNotNull(remoteHero.curAction,
                "Fixed intermediate step: curAction must be set (we're mid-walk)");
    }

    // =========================================================================
    // Bug 1: Stale reader (BUGGY path) consumes next-turn's action early.
    //
    // Sequence:
    //   Turn 1 step 1: curAction null → reader starts → A1 received → curAction = A1
    //   Turn 1 step 2 (buggy): receiveActionAsync() called unconditionally → stale reader
    //   Client sends A2 while stale reader is alive → stale reader consumes A2
    //   ready() clears curAction
    //   Turn 2: new reader starts but A2 already consumed → freeze
    //
    // With FIXED path:
    //   Turn 1 step 2: curAction != null → receiveActionAsync() NOT called → no stale reader
    //   ready() clears curAction
    //   Turn 2: new reader starts, waits for A2, receives it → success
    //
    // This test demonstrates both paths side by side.
    // =========================================================================

    @Test
    void buggyPath_staleReaderStealsNextAction_causesFreezeOnNextTurn()
            throws Exception {

        // === BUGGY PATH DEMONSTRATION ===
        // Turn 1, step 1: receive A1 (start of turn — reader correctly started)
        clientSendsMove(10);
        synchronized (remoteHero.lanActionLock) {
            NetworkManager.receiveActionAsync(remoteHero);
            long deadline = System.currentTimeMillis() + 3000;
            while (remoteHero.curAction == null && NetworkManager.lanMode) {
                long rem = deadline - System.currentTimeMillis();
                if (rem <= 0) break;
                remoteHero.lanActionLock.wait(rem);
            }
        }
        assertNotNull(remoteHero.curAction, "Step 1: must receive A1");
        assertEquals(10, ((HeroAction.Move) remoteHero.curAction).dst);

        // Turn 1, step 2 (BUGGY): start stale reader even though curAction is set
        buggyIntermediateStep();

        // Verify stale reader is now running
        Thread.sleep(30); // let stale reader reach readByte()
        assertTrue(NetworkManager.isActionReaderRunning(),
                "BUGGY: stale reader should be running after intermediate step");

        // Client sends A2 (next turn action) while stale reader is alive
        clientSendsMove(20);
        Thread.sleep(60); // stale reader consumes A2

        // Walk completes: ready() clears curAction
        remoteHero.curAction = null;
        Thread.sleep(20);

        // Turn 2 (BUGGY): A2 already consumed, new reader starts but no more data
        // This should time out (null) demonstrating the freeze
        synchronized (remoteHero.lanActionLock) {
            NetworkManager.receiveActionAsync(remoteHero);
            long deadline = System.currentTimeMillis() + 800; // short timeout for test speed
            while (remoteHero.curAction == null && NetworkManager.lanMode) {
                long rem = deadline - System.currentTimeMillis();
                if (rem <= 0) break;
                remoteHero.lanActionLock.wait(rem);
            }
        }

        // With the buggy path, A2 was consumed by the stale reader and cleared by ready().
        // The test expects null here (demonstrating the freeze).
        assertNull(remoteHero.curAction,
                "BUGGY PATH CONFIRMED: stale reader consumed A2, ready() cleared it, " +
                "turn 2 reader got no data → freeze (returns null after timeout). " +
                "This is the multi-step walk stale reader bug.");
    }

    @Test
    void fixedPath_noStaleReader_nextTurnReceivesAction()
            throws Exception {

        // === FIXED PATH DEMONSTRATION ===
        // Turn 1, step 1: receive A1 (start of turn — reader correctly started)
        clientSendsMove(10);
        HeroAction a1 = fixedTurnStart(3000);
        assertNotNull(a1, "Step 1: must receive A1");
        assertEquals(10, ((HeroAction.Move) a1).dst);

        // Turn 1, step 2 (FIXED): curAction != null → NO reader started
        fixedIntermediateStep();

        // Verify NO stale reader is running
        Thread.sleep(30);
        assertFalse(NetworkManager.isActionReaderRunning(),
                "FIXED: no stale reader should be running after intermediate step");

        // Client sends A2 while NO stale reader is running — action safe in TCP buffer
        clientSendsMove(20);
        Thread.sleep(30); // A2 sits in buffer

        // Walk completes: ready() clears curAction
        remoteHero.curAction = null;

        // Turn 2 (FIXED): A2 is still in the buffer, new reader receives it
        HeroAction a2 = fixedTurnStart(3000);
        assertNotNull(a2,
                "FIXED PATH: A2 must be received on turn 2. " +
                "No stale reader consumed it, ready() did not wipe it.");
        assertEquals(20, ((HeroAction.Move) a2).dst,
                "Fixed: turn 2 must receive position 20");
    }

    // =========================================================================
    // Bug 2: Three-step walk with stale reader (BUGGY path)
    //
    // Step 1: reader starts, A1 received, curAction set
    // Step 2 (buggy): stale reader starts, A2 arrives, stale reader consumes it,
    //                 notifyAll fires (nobody waiting), stale reader resets guard
    // Step 3 (buggy): receiveActionAsync() no-op (guard reset, but curAction still set,
    //                 though if A2 was already consumed+set, another stale reader starts)
    // Walk ends: ready() clears curAction (A2 was already cleared if it was set)
    // Turn 2: no data → freeze
    // =========================================================================

    @Test
    void buggyPath_threeStepWalk_freezeOnNextTurn() throws Exception {
        // Step 1: A1 received
        clientSendsMove(10);
        synchronized (remoteHero.lanActionLock) {
            NetworkManager.receiveActionAsync(remoteHero);
            long dl = System.currentTimeMillis() + 3000;
            while (remoteHero.curAction == null && NetworkManager.lanMode) {
                long rem = dl - System.currentTimeMillis();
                if (rem <= 0) break;
                remoteHero.lanActionLock.wait(rem);
            }
        }
        assertNotNull(remoteHero.curAction, "Step 1 must receive A1");

        // Step 2 (buggy): stale reader starts
        buggyIntermediateStep();
        Thread.sleep(30);

        // Client sends A2 early — stale reader from step 2 consumes it
        clientSendsMove(20);
        Thread.sleep(60);

        // Step 3: after stale reader consumed A2 and reset guard, another buggy call
        // curAction was set to A2 by stale reader; now call again
        buggyIntermediateStep(); // guard reset by step-2 reader, so this starts another stale reader
        Thread.sleep(30);

        // Walk ends: ready() clears curAction
        remoteHero.curAction = null;
        Thread.sleep(20);

        // Turn 2: expects A2 but it was already consumed. With the fix, this should work.
        // With the bug, this times out.
        synchronized (remoteHero.lanActionLock) {
            NetworkManager.receiveActionAsync(remoteHero);
            long dl = System.currentTimeMillis() + 800;
            while (remoteHero.curAction == null && NetworkManager.lanMode) {
                long rem = dl - System.currentTimeMillis();
                if (rem <= 0) break;
                remoteHero.lanActionLock.wait(rem);
            }
        }

        assertNull(remoteHero.curAction,
                "BUGGY PATH: three-step walk — stale readers consumed A2, " +
                "turn 2 gets nothing → freeze. " +
                "Fix: guard receiveActionAsync() with curAction == null.");
    }

    @Test
    void fixedPath_threeStepWalk_noFreeze() throws Exception {
        // Step 1: A1 received (only reader started here — curAction was null)
        clientSendsMove(10);
        HeroAction a1 = fixedTurnStart(3000);
        assertNotNull(a1, "Step 1: must receive A1");

        // Steps 2 and 3 (FIXED): curAction != null → NO readers started
        fixedIntermediateStep();
        fixedIntermediateStep();

        // Client sends A2 between steps (safe — no reader consuming it)
        clientSendsMove(20);
        Thread.sleep(30);

        // Walk ends: ready() clears curAction
        remoteHero.curAction = null;

        // Turn 2: fresh reader starts, receives A2 from buffer
        HeroAction a2 = fixedTurnStart(3000);
        assertNotNull(a2, "FIXED: three-step walk — turn 2 must receive A2");
        assertEquals(20, ((HeroAction.Move) a2).dst, "Fixed: position must be 20");
    }

    // =========================================================================
    // Bug 3: Guard state validation — buggy path leaves guard true mid-walk
    //
    // After the buggy intermediate step starts a stale reader, the guard becomes
    // true. If the stale reader hasn't yet processed the packet, another call to
    // receiveActionAsync() is a no-op (guard is true). This demonstrates the
    // guard correctly prevents double-readers during the stale-reader phase,
    // but cannot prevent the core issue (one stale reader is enough to steal A2).
    // =========================================================================

    @Test
    void buggyPath_guardTrueDuringStaleReader_demonstratesStaleReaderIsAlive()
            throws Exception {

        // Turn 1, step 1: receive A1
        clientSendsMove(10);
        synchronized (remoteHero.lanActionLock) {
            NetworkManager.receiveActionAsync(remoteHero);
            long dl = System.currentTimeMillis() + 3000;
            while (remoteHero.curAction == null && NetworkManager.lanMode) {
                long rem = dl - System.currentTimeMillis();
                if (rem <= 0) break;
                remoteHero.lanActionLock.wait(rem);
            }
        }
        assertNotNull(remoteHero.curAction, "Step 1: A1 received");
        // Let step-1 reader's finally block run (it exits after notifyAll)
        Thread.sleep(50);
        assertFalse(NetworkManager.isActionReaderRunning(),
                "After step 1, guard must be false (reader exited after A1 delivery)");

        // Step 2 (buggy): start stale reader
        buggyIntermediateStep();
        Thread.sleep(30);

        // Guard is now true — stale reader running
        assertTrue(NetworkManager.isActionReaderRunning(),
                "After buggy intermediate step: guard must be true (stale reader running). " +
                "This stale reader will consume A2 when it arrives.");

        // Client sends A2 — stale reader gets it, guard resets
        clientSendsMove(20);
        Thread.sleep(80); // stale reader processes A2

        // After stale reader consumed A2 and exited, guard is false
        assertFalse(NetworkManager.isActionReaderRunning(),
                "After stale reader consumed A2: guard must be false");

        // But curAction was set to A2 and then cleared by ready():
        remoteHero.curAction = null; // simulate ready()

        // Turn 2: no data left → timeout → freeze
        synchronized (remoteHero.lanActionLock) {
            NetworkManager.receiveActionAsync(remoteHero);
            long dl = System.currentTimeMillis() + 600;
            while (remoteHero.curAction == null && NetworkManager.lanMode) {
                long rem = dl - System.currentTimeMillis();
                if (rem <= 0) break;
                remoteHero.lanActionLock.wait(rem);
            }
        }
        assertNull(remoteHero.curAction,
                "After stale reader consumed A2 and ready() cleared it: " +
                "turn 2 has no action → FREEZE. " +
                "Fix prevents this by never starting the stale reader.");
    }

    @Test
    void fixedPath_guardFalseDuringIntermediateSteps_noStaleReader()
            throws Exception {

        // Turn 1, step 1: receive A1
        clientSendsMove(10);
        HeroAction a1 = fixedTurnStart(3000);
        assertNotNull(a1, "Step 1: A1 received");
        Thread.sleep(50); // let step-1 reader's finally run
        assertFalse(NetworkManager.isActionReaderRunning(),
                "After step 1 reader exits, guard must be false");

        // Steps 2 and 3 (FIXED): curAction != null → NO reader started → guard stays false
        fixedIntermediateStep();
        Thread.sleep(30);
        assertFalse(NetworkManager.isActionReaderRunning(),
                "FIXED: guard must stay false during intermediate steps");

        fixedIntermediateStep();
        Thread.sleep(30);
        assertFalse(NetworkManager.isActionReaderRunning(),
                "FIXED: guard must still be false after step 3");

        // Client sends A2 — nobody is consuming it (no stale reader)
        clientSendsMove(20);
        Thread.sleep(30);

        // Walk ends: ready() clears curAction
        remoteHero.curAction = null;

        // Turn 2: fresh reader starts, receives A2
        HeroAction a2 = fixedTurnStart(3000);
        assertNotNull(a2, "FIXED: turn 2 must receive A2");
        assertEquals(20, ((HeroAction.Move) a2).dst, "FIXED: turn 2 position must be 20");
    }
}
