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
 * Tests that reproduce the persistent-reader deadlock:
 *
 * Root cause: the reader thread loops indefinitely (while lanMode &&
 * remoteHero != null). After delivering turn-N's action it immediately
 * reads turn-(N+1)'s bytes from the stream. If those bytes arrive while
 * the game is still processing turn N, the reader delivers them into
 * curAction BEFORE ready() clears curAction at the end of turn N.
 * ready() then wipes that pre-delivered action and turn N+1 hangs forever.
 *
 * Fix: break out of the reader loop after delivering ONE action packet and
 * reset the actionReaderRunning guard, so receiveActionAsync() starts a
 * fresh reader for each remote turn.
 */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class LanPersistentReaderDeadlockTest {

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
        Future<Socket> serverSide = Executors.newSingleThreadExecutor()
                .submit(() -> serverSocket.accept());
        clientSocket = new Socket("127.0.0.1", port);
        try {
            hostSideSocket = serverSide.get(2, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new IOException("setup failed", e);
        }

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

    void clientSendsMove(int targetPos) throws IOException {
        clientOut.writeByte(NetworkManager.PacketType.ACTION);
        clientOut.writeInt(1);   // heroId = 1
        clientOut.writeByte(0);  // MOVE
        clientOut.writeInt(targetPos);
        clientOut.flush();
    }

    /**
     * Simulates exactly what Hero.act() does for a remote hero:
     * - calls receiveActionAsync() (idempotent guard)
     * - waits on lanActionLock until curAction != null
     * - returns curAction
     * - sets curAction = null (simulates ready() clearing it)
     */
    HeroAction simulateRemoteAct(long maxWaitMs) throws InterruptedException {
        synchronized (remoteHero.lanActionLock) {
            NetworkManager.receiveActionAsync(remoteHero);
            long deadline = System.currentTimeMillis() + maxWaitMs;
            while (remoteHero.curAction == null && NetworkManager.lanMode) {
                long rem = deadline - System.currentTimeMillis();
                if (rem <= 0) break;
                remoteHero.lanActionLock.wait(rem);
            }
        }
        HeroAction received = remoteHero.curAction;
        // Simulate ready() clearing curAction at end of turn
        remoteHero.curAction = null;
        return received;
    }

    // =========================================================================
    // Bug 1: Pre-delivered action wiped by ready()
    //
    // Scenario: remote sends BOTH turn-1 and turn-2 packets back-to-back before
    // the host has consumed turn-1. The persistent reader reads turn-2 into
    // curAction, then ready() at the end of turn-1 clears it. Turn-2 hangs.
    //
    // Before fix: turn2Action == null (deadlock / 5-second timeout)
    // After  fix: turn2Action != null (correct)
    // =========================================================================

    @Test
    void preSentTurnTwoPacket_notLostWhenReadyClears() throws Exception {
        // Client sends BOTH actions before the host processes either
        clientSendsMove(10); // turn 1 action
        clientSendsMove(20); // turn 2 action — arrives while turn 1 still processing
        Thread.sleep(20);    // let both bytes reach the TCP receive buffer

        // --- Turn 1 ---
        HeroAction turn1 = simulateRemoteAct(2000);
        assertNotNull(turn1, "Turn 1 action must be received");
        assertEquals(10, ((HeroAction.Move) turn1).dst, "Turn 1: wrong destination");
        // ready() already cleared curAction in simulateRemoteAct

        // --- Turn 2 ---
        // With the buggy persistent reader, turn-2 bytes were consumed by the reader
        // during turn-1 processing and stored in curAction, then ready() wiped them.
        // The reader is still blocked waiting for turn-3 bytes that never come.
        // receiveActionAsync() is a no-op (guard still true).
        // curAction stays null → simulateRemoteAct times out → test FAILS before fix.
        HeroAction turn2 = simulateRemoteAct(2000);
        assertNotNull(turn2,
                "Turn 2 action must be received. " +
                "If null: persistent reader consumed turn-2 bytes but ready() wiped curAction " +
                "before act() could read it. This is the recurring LAN freeze.");
        assertEquals(20, ((HeroAction.Move) turn2).dst, "Turn 2: wrong destination");
    }

    // =========================================================================
    // Bug 2: Guard never resets — second turn hangs even without pre-send
    //
    // If the reader exits (IOException / loop condition false) but the guard
    // actionReaderRunningPerStream[0] stays true, the second receiveActionAsync()
    // call is a no-op and turn 2 hangs.
    //
    // This test verifies the guard IS reset after each action so a new reader
    // can start for the next turn.
    // =========================================================================

    @Test
    void guardResetsAfterActionDelivered_newReaderStartsForTurn2() throws Exception {
        // Turn 1: send + receive
        clientSendsMove(11);
        HeroAction turn1 = simulateRemoteAct(2000);
        assertNotNull(turn1, "Turn 1 must be received");
        assertEquals(11, ((HeroAction.Move) turn1).dst);

        // After turn 1 the guard must be reset so a new reader can start
        // Give the reader thread a moment to exit its finally block
        Thread.sleep(50);
        assertFalse(NetworkManager.isActionReaderRunningForStream(0),
                "Guard must be false after action delivered so turn-2 reader can start");

        // Turn 2: new reader starts because guard is false
        clientSendsMove(22);
        HeroAction turn2 = simulateRemoteAct(2000);
        assertNotNull(turn2,
                "Turn 2 must be received after guard reset. " +
                "If null: guard stayed true, receiveActionAsync() was a no-op, deadlock.");
        assertEquals(22, ((HeroAction.Move) turn2).dst);
    }

    // =========================================================================
    // Bug 3: Five-turn alternating sequence (the reported "few moves then freeze")
    //
    // Each remote turn: host processes previous turn then calls receiveActionAsync()
    // for next turn. The persistent reader must NOT still be blocking on the stream
    // for turn-2 bytes when we start turn-3's reader.
    // =========================================================================

    @Test
    void fiveTurns_neverFreeze() throws Exception {
        int[] positions = {10, 20, 30, 40, 50};
        for (int i = 0; i < positions.length; i++) {
            clientSendsMove(positions[i]);
            HeroAction action = simulateRemoteAct(2000);
            assertNotNull(action, "Turn " + (i + 1) + " must not freeze");
            assertEquals(positions[i], ((HeroAction.Move) action).dst,
                    "Turn " + (i + 1) + " wrong position");
        }
    }

    // =========================================================================
    // Bug 4: Remote sends action while previous is being processed
    //
    // Models the real-world case: network latency is low, remote player is fast.
    // Remote player's turn-N+1 packet arrives before host finishes turn-N.
    // =========================================================================

    @Test
    void fastRemotePlayer_actionArrivesEarly_neverLost() throws Exception {
        // Turn 1: start reader, send action immediately
        clientSendsMove(1);
        HeroAction turn1 = simulateRemoteAct(2000);
        assertNotNull(turn1, "Fast remote: turn 1 must be received");

        // Remote sends turn-2 action while host is "still processing" turn 1
        // (simulated by sending before we call simulateRemoteAct for turn 2)
        clientSendsMove(2);
        Thread.sleep(10); // packet in flight, reader might grab it immediately

        // Turn 2 must still be receivable
        HeroAction turn2 = simulateRemoteAct(2000);
        assertNotNull(turn2, "Fast remote: turn 2 must not be lost even if delivered early");
        assertEquals(2, ((HeroAction.Move) turn2).dst);
    }

    // =========================================================================
    // Bug 5: Ten rapid back-to-back turns without freeze
    // =========================================================================

    @Test
    void tenRapidTurns_noFreeze() throws Exception {
        for (int i = 1; i <= 10; i++) {
            clientSendsMove(i * 7);
            HeroAction action = simulateRemoteAct(2000);
            assertNotNull(action, "Rapid turn " + i + " must not freeze");
            assertEquals(i * 7, ((HeroAction.Move) action).dst,
                    "Rapid turn " + i + " wrong position");
        }
    }
}
