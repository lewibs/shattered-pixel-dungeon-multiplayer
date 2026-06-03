package com.shatteredpixel.shatteredpixeldungeon.lan;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.Statistics;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroAction;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.network.NetworkManager;
import com.watabou.noosa.Game;
import com.watabou.utils.PathFinder;
import com.watabou.utils.Random;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Timeout;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the real Hero.act() headlessly and compares game state between LAN mode
 * and pass-and-play mode after the same action sequences.
 *
 * Three minimal production null-safety fixes were required to run headlessly:
 *   GameScene.ready()        — if (scene == null) return;
 *   GameScene.selectCell()   — if (cellSelector == null) return;
 *   AttackIndicator.updateState() — if (instance == null) return;
 *
 * TestHero overrides two methods to remove sprite dependencies:
 *   moveSprite() — pos is already updated by Char.move(); the sprite call is
 *                  purely visual and has no effect on game state.
 *   checkVisibleMobs() — no-op; test levels have no mobs so this is a no-op
 *                        anyway, and we avoid potential sprite.turnTo() calls.
 *
 * The game state captured after each round:
 *   - hero0.pos, hero1.pos  (where did each hero end up)
 *   - hero0.getTimeForTesting(), hero1.getTimeForTesting() (turn order: did each hero spend the right time)
 *   - hero0.HP, hero1.HP    (no damage in move-only tests, should be unchanged)
 */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class LanGameStateSyncTest {

    // 10-wide 6-tall level. Row 1 (y=1) is a clear corridor.
    // hero0 starts at col 1, hero1 at col 5. Both can move freely left/right.
    static final int W = 10, H = 6;
    static final int START0 = W + 1;   // row 1, col 1
    static final int START1 = W + 5;   // row 1, col 5

    // -------------------------------------------------------------------------
    // Minimal flat level
    // -------------------------------------------------------------------------

    static class MinimalLevel extends Level {
        MinimalLevel() {
            width  = W; height = H; length = W * H;
            map          = new int[length];
            pit          = new boolean[length];
            passable     = new boolean[length];
            losBlocking  = new boolean[length];
            solid        = new boolean[length];
            avoid        = new boolean[length];
            water        = new boolean[length];
            visited      = new boolean[length];
            mapped       = new boolean[length];
            heroFOV      = new boolean[length];
            transitions  = new ArrayList<>();
            mobs         = new java.util.HashSet<>();
            heaps        = new com.watabou.utils.SparseArray<>();
            blobs        = new java.util.HashMap<>();
            plants       = new com.watabou.utils.SparseArray<>();
            traps        = new com.watabou.utils.SparseArray<>();
            customTiles  = new ArrayList<>();
            customWalls  = new ArrayList<>();

            Arrays.fill(passable, true);
            Arrays.fill(heroFOV,  true);
            Arrays.fill(visited,  true);   // heroes can see/path everywhere
            Arrays.fill(mapped,   true);
            PathFinder.setMapSize(W, H);
        }
        @Override protected boolean build()    { return true; }
        @Override protected void createMobs()  {}
        @Override protected void createItems() {}
        @Override public int entrance()        { return START0; }
    }

    // -------------------------------------------------------------------------
    // TestHero: real Hero.act() minus sprite calls
    // -------------------------------------------------------------------------

    static class TestHero extends Hero {
        TestHero(HeroClass cls, int startPos) {
            heroClass = cls;
            HP = HT = 20;
            pos = startPos;
            actPriority = HERO_PRIO;
        }

        /** pos is already updated by Char.move() before this is called — no-op. */
        @Override
        protected boolean moveSprite(int from, int to) {
            return true;
        }

        /** No mobs in this level — skip to avoid sprite.turnTo() calls. */
        @Override
        public void checkVisibleMobs() {}
    }

    // -------------------------------------------------------------------------
    // Captured game state
    // -------------------------------------------------------------------------

    static class GameState {
        final int pos0, pos1;
        final float time0, time1;
        final int hp0, hp1;

        GameState(Hero h0, Hero h1) {
            pos0  = h0.pos;  pos1  = h1.pos;
            time0 = h0.getTimeForTesting(); time1 = h1.getTimeForTesting();
            hp0   = h0.HP;   hp1   = h1.HP;
        }

        @Override public String toString() {
            return String.format("h0(pos=%d t=%.1f hp=%d) h1(pos=%d t=%.1f hp=%d)",
                    pos0, time0, hp0, pos1, time1, hp1);
        }
    }

    // -------------------------------------------------------------------------
    // Socket pair (same pattern as LanRealSocketTest)
    // -------------------------------------------------------------------------

    private ServerSocket     serverSocket;
    private Socket           hostSideSocket;
    private Socket           clientSocket;
    private DataInputStream  hostIn;
    private DataOutputStream hostOut;
    private DataInputStream  clientIn;
    private DataOutputStream clientOut;

    private TestHero hero0;
    private TestHero hero1;
    private MinimalLevel level;

    // -------------------------------------------------------------------------
    // Setup / teardown
    // -------------------------------------------------------------------------

    @BeforeEach
    void setUp() throws IOException {
        Game.version = "test";
        PathFinder.setMapSize(W, H);
        Statistics.reset();
        Random.resetGenerators();

        serverSocket = new ServerSocket(0);
        int port = serverSocket.getLocalPort();
        Future<Socket> serverSide = Executors.newSingleThreadExecutor()
                .submit(() -> serverSocket.accept());
        clientSocket = new Socket("127.0.0.1", port);
        try { hostSideSocket = serverSide.get(2, TimeUnit.SECONDS); }
        catch (Exception e) { throw new IOException("socket setup failed", e); }

        hostIn    = new DataInputStream(hostSideSocket.getInputStream());
        hostOut   = new DataOutputStream(hostSideSocket.getOutputStream());
        clientIn  = new DataInputStream(clientSocket.getInputStream());
        clientOut = new DataOutputStream(clientSocket.getOutputStream());
        NetworkManager.injectHostStreamsForTesting(hostIn, hostOut);
        NetworkManager.injectClientStreamsForTesting(clientIn, clientOut);

        hero0 = new TestHero(HeroClass.WARRIOR, START0);
        hero1 = new TestHero(HeroClass.MAGE,    START1);

        Dungeon.heroes = new ArrayList<>();
        Dungeon.heroes.add(hero0);
        Dungeon.heroes.add(hero1);
        Dungeon.customSeedText = "";

        level = new MinimalLevel();
        Dungeon.level = level;
        Dungeon.hero  = hero0;

        Actor.clear();
        // Add heroes to actor queue so spend/next work correctly
        Actor.addDelayed(hero0, 0);
        Actor.addDelayed(hero1, 0);

        NetworkManager.lanMode = true;
        NetworkManager.setIsHostForTesting(true);
        NetworkManager.localPlayerIndex = 0;
        NetworkManager.resetActionReaderForTesting();
    }

    @AfterEach
    void tearDown() throws IOException {
        NetworkManager.lanMode = false;
        NetworkManager.setIsHostForTesting(false);
        NetworkManager.localPlayerIndex = 0;
        NetworkManager.resetActionReaderForTesting();
        if (clientSocket   != null && !clientSocket.isClosed())   clientSocket.close();
        if (hostSideSocket != null && !hostSideSocket.isClosed()) hostSideSocket.close();
        if (serverSocket   != null && !serverSocket.isClosed())   serverSocket.close();
        NetworkManager.injectHostStreamsForTesting(null, null);
        NetworkManager.injectClientStreamsForTesting(null, null);
        Dungeon.heroes = null;
        Dungeon.hero   = null;
        Dungeon.level  = null;
        Actor.clear();
        Statistics.reset();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Wait for hero.curAction to be populated via LAN delivery. */
    boolean waitFor(Hero h, long maxMs) throws InterruptedException {
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

    /** Send a MOVE packet from the client socket (simulates P2 sending their action). */
    void clientSendMove(int heroId, int dst) throws IOException {
        clientOut.writeByte(NetworkManager.PacketType.ACTION);
        clientOut.writeInt(heroId);
        clientOut.writeByte(NetworkManager.ActionType.MOVE);
        clientOut.writeInt(dst);
        clientOut.flush();
    }

    /** Send a MOVE packet from the host socket (simulates P1 sending their action). */
    void hostSendMove(int heroId, int dst) throws IOException {
        hostOut.writeByte(NetworkManager.PacketType.ACTION);
        hostOut.writeInt(heroId);
        hostOut.writeByte(NetworkManager.ActionType.MOVE);
        hostOut.writeInt(dst);
        hostOut.flush();
    }

    /**
     * Run one step of hero.act() for a LOCAL hero (direct action, no sockets).
     * Returns false when the hero finishes the move (reached destination or blocked).
     */
    boolean actLocal(TestHero hero, int dst) {
        if (hero.curAction == null) hero.curAction = new HeroAction.Move(dst);
        return hero.act();
    }

    /**
     * Run a complete multi-step move: keep calling act() until the hero
     * reaches dst or stops moving.
     */
    void moveToLocal(TestHero hero, int dst) {
        hero.curAction = new HeroAction.Move(dst);
        while (hero.curAction != null && hero.pos != dst) {
            boolean cont = hero.act();
            if (!cont && hero.pos != dst) break;
        }
    }

    /**
     * Deliver a MOVE action to a remote hero via socket, then run act().
     * Used from the HOST side (receives from clientOut → hostIn).
     */
    void moveToRemoteViaSocket_hostReceives(TestHero remoteHero, int dst) throws Exception {
        remoteHero.curAction = null;
        NetworkManager.receiveActionAsync(remoteHero);
        Thread.sleep(15);
        clientSendMove(Dungeon.heroes.indexOf(remoteHero), dst);
        assertTrue(waitFor(remoteHero, 2000), "Remote hero must receive move packet");
        // curAction is now set — act() will execute without blocking
        while (remoteHero.curAction != null && remoteHero.pos != dst) {
            boolean cont = remoteHero.act();
            if (!cont && remoteHero.pos != dst) break;
        }
    }

    /**
     * Deliver a MOVE action to a remote hero via socket, then run act().
     * Used from the CLIENT side (receives from hostOut → clientIn).
     */
    void moveToRemoteViaSocket_clientReceives(TestHero remoteHero, int dst) throws Exception {
        remoteHero.curAction = null;
        NetworkManager.receiveActionAsync(remoteHero);
        Thread.sleep(15);
        hostSendMove(Dungeon.heroes.indexOf(remoteHero), dst);
        assertTrue(waitFor(remoteHero, 2000), "Remote hero must receive move packet");
        while (remoteHero.curAction != null && remoteHero.pos != dst) {
            boolean cont = remoteHero.act();
            if (!cont && remoteHero.pos != dst) break;
        }
    }

    /** Reset hero positions and state for next run. */
    void resetHeroes() {
        hero0.pos = START0; hero0.curAction = null; hero0.ready = false;
        hero1.pos = START1; hero1.curAction = null; hero1.ready = false;
        hero0.setTimeForTesting(0); hero1.setTimeForTesting(0);
        Actor.clear();
        Actor.addDelayed(hero0, 0);
        Actor.addDelayed(hero1, 0);
        NetworkManager.resetActionReaderForTesting();
    }

    // =========================================================================
    // CORE TEST: same moves → same positions in all three modes
    // =========================================================================

    /**
     * Each hero makes 3 moves. Run through:
     *   (A) pass-and-play (lanMode=false, Dungeon.hero=hero0)
     *   (B) LAN host      (lanMode=true,  isHost=true,  localPlayerIndex=0, Dungeon.hero=hero0)
     *   (C) LAN client    (lanMode=true,  isHost=false, localPlayerIndex=1, Dungeon.hero=hero1)
     *
     * The destinations are intentionally different for each hero so positions
     * can't coincidentally match by accident.
     */
    @Test
    void threeMoves_identicalPositions_passPlayVsLanHostVsLanClient() throws Exception {
        // Destinations: hero0 moves right 3 cells, hero1 moves left 2 cells
        int dst0 = START0 + 3;   // 12
        int dst1 = START1 - 2;   // 13

        // ---- (A) Pass-and-play ----
        NetworkManager.lanMode = false;
        Dungeon.hero = hero0;
        moveToLocal(hero0, dst0);
        moveToLocal(hero1, dst1);
        GameState ppState = new GameState(hero0, hero1);
        resetHeroes();
        NetworkManager.lanMode = true;

        // ---- (B) LAN host: hero0 local, hero1 remote via socket ----
        NetworkManager.setIsHostForTesting(true);
        NetworkManager.localPlayerIndex = 0;
        Dungeon.hero = hero0;
        moveToLocal(hero0, dst0);
        moveToRemoteViaSocket_hostReceives(hero1, dst1);
        GameState hostState = new GameState(hero0, hero1);
        resetHeroes();

        // ---- (C) LAN client: hero0 remote via socket, hero1 local ----
        NetworkManager.setIsHostForTesting(false);
        NetworkManager.localPlayerIndex = 1;
        Dungeon.hero = hero1;
        moveToRemoteViaSocket_clientReceives(hero0, dst0);
        moveToLocal(hero1, dst1);
        GameState clientState = new GameState(hero0, hero1);

        // ---- Assert all three match ----
        String modes = "\npp=" + ppState + "\nhost=" + hostState + "\nclient=" + clientState;
        assertEquals(ppState.pos0, hostState.pos0,   "hero0.pos: PP vs HOST" + modes);
        assertEquals(ppState.pos0, clientState.pos0, "hero0.pos: PP vs CLIENT" + modes);
        assertEquals(ppState.pos1, hostState.pos1,   "hero1.pos: PP vs HOST" + modes);
        assertEquals(ppState.pos1, clientState.pos1, "hero1.pos: PP vs CLIENT" + modes);
        assertEquals(ppState.hp0,  hostState.hp0,    "hero0.HP: PP vs HOST" + modes);
        assertEquals(ppState.hp0,  clientState.hp0,  "hero0.HP: PP vs CLIENT" + modes);
        assertEquals(ppState.hp1,  hostState.hp1,    "hero1.HP: PP vs HOST" + modes);
        assertEquals(ppState.hp1,  clientState.hp1,  "hero1.HP: PP vs CLIENT" + modes);

        // Verify heroes actually moved (positions changed from start)
        assertNotEquals(START0, ppState.pos0, "hero0 must have moved from START0");
        assertNotEquals(START1, ppState.pos1, "hero1 must have moved from START1");
    }

    /**
     * 6 rounds of alternating moves. Each hero moves one step per round.
     * State must be identical in all three modes after all rounds.
     */
    @Test
    void sixRounds_alternatingMoves_identicalState() throws Exception {
        // Sequence of single-step targets (each one step further right/left)
        int[] dsts0 = { START0+1, START0+2, START0+3, START0+4, START0+3, START0+2 };
        int[] dsts1 = { START1-1, START1-2, START1-1, START1-2, START1-1, START1   };

        // ---- Pass-and-play ----
        NetworkManager.lanMode = false;
        Dungeon.hero = hero0;
        for (int i = 0; i < 6; i++) {
            hero0.curAction = new HeroAction.Move(dsts0[i]);
            hero0.act();
            hero1.curAction = new HeroAction.Move(dsts1[i]);
            hero1.act();
        }
        GameState ppState = new GameState(hero0, hero1);
        resetHeroes();
        NetworkManager.lanMode = true;

        // ---- LAN host ----
        NetworkManager.setIsHostForTesting(true);
        NetworkManager.localPlayerIndex = 0;
        Dungeon.hero = hero0;
        for (int i = 0; i < 6; i++) {
            hero0.curAction = new HeroAction.Move(dsts0[i]);
            hero0.act();
            // hero1 remote: deliver via socket
            hero1.curAction = null;
            NetworkManager.receiveActionAsync(hero1);
            Thread.sleep(10);
            clientSendMove(1, dsts1[i]);
            assertTrue(waitFor(hero1, 2000), "Round " + i + ": host must get hero1 action");
            hero1.act();
        }
        GameState hostState = new GameState(hero0, hero1);
        resetHeroes();

        // ---- LAN client ----
        NetworkManager.setIsHostForTesting(false);
        NetworkManager.localPlayerIndex = 1;
        Dungeon.hero = hero1;
        for (int i = 0; i < 6; i++) {
            // hero0 remote: deliver via socket
            hero0.curAction = null;
            NetworkManager.receiveActionAsync(hero0);
            Thread.sleep(10);
            hostSendMove(0, dsts0[i]);
            assertTrue(waitFor(hero0, 2000), "Round " + i + ": client must get hero0 action");
            hero0.act();
            hero1.curAction = new HeroAction.Move(dsts1[i]);
            hero1.act();
        }
        GameState clientState = new GameState(hero0, hero1);

        // ---- Assert ----
        String modes = "\npp=" + ppState + "\nhost=" + hostState + "\nclient=" + clientState;
        assertEquals(ppState.pos0, hostState.pos0,   "hero0.pos after 6 rounds: PP vs HOST" + modes);
        assertEquals(ppState.pos0, clientState.pos0, "hero0.pos after 6 rounds: PP vs CLIENT" + modes);
        assertEquals(ppState.pos1, hostState.pos1,   "hero1.pos after 6 rounds: PP vs HOST" + modes);
        assertEquals(ppState.pos1, clientState.pos1, "hero1.pos after 6 rounds: PP vs CLIENT" + modes);
    }

    /**
     * Verifies turn time (hero.time) is identical across modes.
     * Each step costs TIME_TO_MOVE. If time diverges, turn order diverges,
     * which causes all subsequent state to diverge.
     */
    @Test
    void turnTime_identicalAfterMoves_allModes() throws Exception {
        int dst0 = START0 + 2;
        int dst1 = START1 - 2;

        // Pass-and-play
        NetworkManager.lanMode = false;
        Dungeon.hero = hero0;
        moveToLocal(hero0, dst0);
        moveToLocal(hero1, dst1);
        float ppTime0 = hero0.getTimeForTesting(), ppTime1 = hero1.getTimeForTesting();
        resetHeroes();
        NetworkManager.lanMode = true;

        // LAN host
        NetworkManager.setIsHostForTesting(true);
        NetworkManager.localPlayerIndex = 0;
        Dungeon.hero = hero0;
        moveToLocal(hero0, dst0);
        moveToRemoteViaSocket_hostReceives(hero1, dst1);
        float hostTime0 = hero0.getTimeForTesting(), hostTime1 = hero1.getTimeForTesting();
        resetHeroes();

        // LAN client
        NetworkManager.setIsHostForTesting(false);
        NetworkManager.localPlayerIndex = 1;
        Dungeon.hero = hero1;
        moveToRemoteViaSocket_clientReceives(hero0, dst0);
        moveToLocal(hero1, dst1);
        float clientTime0 = hero0.getTimeForTesting(), clientTime1 = hero1.getTimeForTesting();

        assertEquals(ppTime0, hostTime0,   0.001f, "hero0.getTimeForTesting() PP vs HOST");
        assertEquals(ppTime0, clientTime0, 0.001f, "hero0.getTimeForTesting() PP vs CLIENT");
        assertEquals(ppTime1, hostTime1,   0.001f, "hero1.getTimeForTesting() PP vs HOST");
        assertEquals(ppTime1, clientTime1, 0.001f, "hero1.getTimeForTesting() PP vs CLIENT");

        assertTrue(ppTime0 > 0, "hero0 must have spent time moving");
        assertTrue(ppTime1 > 0, "hero1 must have spent time moving");
    }

    /**
     * Sanity check: passing the same RNG seed and same moves produces
     * identical results on repeated runs (determinism within a single mode).
     */
    @Test
    void determinism_sameSeedSameMoves_sameState() {
        long seed = 0xCAFEBABEL;

        NetworkManager.lanMode = false;
        Dungeon.hero = hero0;
        Random.pushGenerator(seed);
        moveToLocal(hero0, START0 + 3);
        moveToLocal(hero1, START1 - 2);
        GameState run1 = new GameState(hero0, hero1);
        Random.popGenerator();
        resetHeroes();

        Random.pushGenerator(seed);
        moveToLocal(hero0, START0 + 3);
        moveToLocal(hero1, START1 - 2);
        GameState run2 = new GameState(hero0, hero1);
        Random.popGenerator();

        assertEquals(run1.pos0, run2.pos0, "hero0 position must be deterministic");
        assertEquals(run1.pos1, run2.pos1, "hero1 position must be deterministic");
        assertEquals(run1.time0, run2.time0, 0.001f, "hero0 time must be deterministic");
    }
}
