package com.shatteredpixel.shatteredpixeldungeon.lan;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.Statistics;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroAction;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.items.Gold;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
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

            secret       = new boolean[length];
            Arrays.fill(passable, true);
            Arrays.fill(heroFOV,  true);
            Arrays.fill(visited,  true);   // heroes can see/path everywhere
            Arrays.fill(mapped,   true);
            // Mark boundary cells impassable — PathFinder assumes an impassable
            // border (real levels always have one) and goes OOB without it.
            for (int i = 0; i < length; i++) {
                int row = i / W, col = i % W;
                if (row == 0 || row == H - 1 || col == 0 || col == W - 1) passable[i] = false;
            }
            PathFinder.setMapSize(W, H);
        }
        @Override protected boolean build()    { return true; }
        @Override protected void createMobs()  {}
        @Override protected void createItems() {}
        @Override public int entrance()        { return START0; }
        // All cells are pre-marked visible; skip ShadowCaster so FOV stays
        // all-true in every mode. This keeps chars visible to pathfinding,
        // so local heroes route around occupied cells exactly like the
        // per-step remote replay does (remote heroes refuse occupied steps).
        @Override public void updateFieldOfView(Char c, boolean[] fov) {
            Arrays.fill(fov, true);
        }
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
        NetworkManager.resetCommitProtocolState();
        // Discard local heroes' performed-op sends so they never pollute the
        // shared loopback sockets (which the test reuses for remote delivery)
        // and never block the actor thread on a commit echo.
        NetworkManager.sendActionOverride = (a, h) -> {};
    }

    @AfterEach
    void tearDown() throws IOException {
        NetworkManager.sendActionOverride = null;
        NetworkManager.lanMode = false;
        NetworkManager.setIsHostForTesting(false);
        NetworkManager.localPlayerIndex = 0;
        NetworkManager.resetActionReaderForTesting();
        NetworkManager.resetCommitProtocolState();
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

    // v3 protocol: commits queue in the hero's inbox; curAction is only decoded
    // from the inbox inside act(). So "delivery arrived" means the inbox is
    // non-empty (or act() already consumed it into curAction).
    boolean waitFor(Hero h, long maxMs) throws InterruptedException {
        synchronized (h.lanActionLock) {
            long deadline = System.currentTimeMillis() + maxMs;
            while (h.lanActionInbox.isEmpty() && h.curAction == null && NetworkManager.lanMode) {
                long rem = deadline - System.currentTimeMillis();
                if (rem <= 0) break;
                h.lanActionLock.wait(rem);
            }
            return !h.lanActionInbox.isEmpty() || h.curAction != null;
        }
    }

    // v3 sequence continuity across the host/client phases of one test method.
    // hostReqSeq: contiguous clientSeq for REQUESTs the fake client feeds the
    // host device. clientCommitSeq: contiguous globalSeq for COMMITs the fake
    // leader feeds the client device — lazily seeded from the leader's current
    // counter so it continues past commits the host phase already broadcast into
    // the shared socket buffer (those get read first when the client reader starts).
    private int hostReqSeq = 0;
    private int clientCommitSeq = -1;

    /** Fake client → host device: a REQUEST the leader will sequence. */
    void clientSend(int heroId, byte actionType, int pos) throws IOException {
        LanTestProtocol.writeActionRequest(clientOut, ++hostReqSeq, actionType, pos);
    }

    /** Fake leader → client device: a COMMIT applied in globalSeq order. */
    void hostSend(int heroId, byte actionType, int pos) throws IOException {
        if (clientCommitSeq < 0) clientCommitSeq = NetworkManager.getGlobalSeqForTesting();
        LanTestProtocol.writeActionCommit(hostOut, ++clientCommitSeq, heroId, 0, actionType, pos);
    }

    void clientSendMove(int heroId, int dst)  throws IOException { clientSend(heroId, NetworkManager.ActionType.MOVE, dst); }
    void hostSendMove(int heroId, int dst)    throws IOException { hostSend(heroId, NetworkManager.ActionType.MOVE, dst); }

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
     * Mirror of Hero.getCloser()'s step selection for a LOCAL (owning-device)
     * hero with move intent {@code target}: returns the cell that hero would
     * actually step to, or -1 if it would not move (arrived / blocked / no path).
     * This is what production transmits as the performed atomic Move packet.
     */
    int performedStep(Hero h, int target) {
        if (target == h.pos) return -1;
        if (Dungeon.level.adjacent(h.pos, target)) {
            if (Actor.findChar(target) == null
                    && (Dungeon.level.passable[target] || Dungeon.level.avoid[target])) {
                return target;
            }
            return -1;
        }
        int len = Dungeon.level.length();
        boolean[] p = Dungeon.level.passable;
        boolean[] v = Dungeon.level.visited;
        boolean[] m = Dungeon.level.mapped;
        boolean[] passable = new boolean[len];
        for (int i = 0; i < len; i++) passable[i] = p[i] && (v[i] || m[i]);
        boolean[] fov = h.fieldOfView != null ? h.fieldOfView : Dungeon.level.heroFOV;
        PathFinder.Path path = Dungeon.findPath(h, target, passable, fov, true);
        if (path == null || path.isEmpty()) return -1;
        return path.getFirst();
    }

    /**
     * Deliver a MOVE action to a remote hero via socket, then run act().
     * Used from the HOST side (receives from clientOut → hostIn).
     */
    void moveToRemoteViaSocket_hostReceives(TestHero remoteHero, int dst) throws Exception {
        // Per-step protocol: the owning device transmits each performed step as an
        // adjacent Move packet — remote heroes never pathfind. Compute each step
        // exactly as the owning hero's getCloser() would, one packet per step.
        while (remoteHero.pos != dst) {
            int step = performedStep(remoteHero, dst);
            if (step == -1) break; // owner's hero could not move — nothing transmitted
            int before = remoteHero.pos;
            remoteHero.curAction = null;
            NetworkManager.receiveActionAsync(remoteHero);
            Thread.sleep(15);
            clientSendMove(Dungeon.heroes.indexOf(remoteHero), step);
            assertTrue(waitFor(remoteHero, 2000), "Remote hero must receive step packet");
            remoteHero.act();
            if (remoteHero.pos == before) break; // safety: no progress
        }
        remoteHero.curAction = null;
    }

    /**
     * Deliver a MOVE action to a remote hero via socket, then run act().
     * Used from the CLIENT side (receives from hostOut → clientIn).
     */
    void moveToRemoteViaSocket_clientReceives(TestHero remoteHero, int dst) throws Exception {
        // Per-step protocol — see moveToRemoteViaSocket_hostReceives
        while (remoteHero.pos != dst) {
            int step = performedStep(remoteHero, dst);
            if (step == -1) break; // owner's hero could not move — nothing transmitted
            int before = remoteHero.pos;
            remoteHero.curAction = null;
            NetworkManager.receiveActionAsync(remoteHero);
            Thread.sleep(15);
            hostSendMove(Dungeon.heroes.indexOf(remoteHero), step);
            assertTrue(waitFor(remoteHero, 2000), "Remote hero must receive step packet");
            remoteHero.act();
            if (remoteHero.pos == before) break; // safety: no progress
        }
        remoteHero.curAction = null;
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
            // hero1 remote: deliver the PERFORMED step via socket (per-step protocol).
            // If the owning hero would not move this round, nothing is transmitted.
            int step = performedStep(hero1, dsts1[i]);
            hero1.curAction = null;
            if (step != -1) {
                NetworkManager.receiveActionAsync(hero1);
                Thread.sleep(10);
                clientSendMove(1, step);
                assertTrue(waitFor(hero1, 2000), "Round " + i + ": host must get hero1 action");
                hero1.act();
            }
        }
        GameState hostState = new GameState(hero0, hero1);
        resetHeroes();

        // ---- LAN client ----
        NetworkManager.setIsHostForTesting(false);
        NetworkManager.localPlayerIndex = 1;
        Dungeon.hero = hero1;
        for (int i = 0; i < 6; i++) {
            // hero0 remote: deliver the PERFORMED step via socket (per-step protocol).
            int step = performedStep(hero0, dsts0[i]);
            hero0.curAction = null;
            if (step != -1) {
                NetworkManager.receiveActionAsync(hero0);
                Thread.sleep(10);
                hostSendMove(0, step);
                assertTrue(waitFor(hero0, 2000), "Round " + i + ": client must get hero0 action");
                hero0.act();
            }
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

    // =========================================================================
    // TestMob: headless Mob for attack/kill tests
    // =========================================================================

    /**
     * Minimal Mob for headless tests. Has no sprite and runs real production
     * die() → destroy() → earnExp() logic. The null guard on sprite.die() in
     * Char.die() prevents NPE when this mob is killed.
     */
    static class TestMob extends Mob {
        TestMob(int pos, int hp, int exp) {
            EXP       = exp;
            maxLvl    = 30;
            lootChance = 0; // no loot drops
            alignment = Char.Alignment.ENEMY;
            HP = HT   = hp;
            this.pos  = pos;
        }

        /** No sprite in headless mode — skip. */
        @Override
        protected boolean moveSprite(int from, int to) {
            return true;
        }

        /** Skip any sprite/AI work in act() — the test drives attacking directly. */
        @Override
        public boolean act() {
            // idle: just skip this mob's turn
            spend(TICK);
            return true;
        }
    }

    // =========================================================================
    // Extended GameState that also captures exp and Dungeon.gold
    // =========================================================================

    static class ExtendedGameState {
        final int pos0, pos1;
        final float time0, time1;
        final int hp0, hp1;
        final int exp0, exp1;
        final int gold;
        final int enemiesSlain;

        ExtendedGameState(Hero h0, Hero h1) {
            pos0 = h0.pos;  pos1 = h1.pos;
            time0 = h0.getTimeForTesting(); time1 = h1.getTimeForTesting();
            hp0  = h0.HP;   hp1  = h1.HP;
            exp0 = h0.exp;  exp1 = h1.exp;
            gold = Dungeon.gold;
            enemiesSlain = Statistics.enemiesSlain;
        }

        @Override public String toString() {
            return String.format(
                "h0(pos=%d t=%.1f hp=%d exp=%d) h1(pos=%d t=%.1f hp=%d exp=%d) gold=%d slain=%d",
                pos0, time0, hp0, exp0, pos1, time1, hp1, exp1, gold, enemiesSlain);
        }
    }

    /** Reset extended state including gold and statistics. */
    void resetAll() {
        // Recreate heroes to avoid accumulation of exp/level/HP across test runs
        hero0 = new TestHero(HeroClass.WARRIOR, START0);
        hero1 = new TestHero(HeroClass.MAGE,    START1);
        Dungeon.heroes = new ArrayList<>();
        Dungeon.heroes.add(hero0);
        Dungeon.heroes.add(hero1);
        Dungeon.hero = hero0;
        Dungeon.gold = 0;
        Statistics.reset();
        // Remove any leftover mobs/heaps from Actor queue
        Actor.clear();
        Actor.addDelayed(hero0, 0);
        Actor.addDelayed(hero1, 0);
        level.mobs.clear();
        level.heaps.clear();
        NetworkManager.resetActionReaderForTesting();
    }

    /** Place a mob adjacent to the hero and register it in Actor queue + level. */
    TestMob placeMob(int pos, int hp, int exp) {
        TestMob mob = new TestMob(pos, hp, exp);
        Actor.addDelayed(mob, 0);
        level.mobs.add(mob);
        return mob;
    }

    /** Place a Gold heap at the given position. */
    void placeGold(int pos, int amount) {
        Heap heap = new Heap();
        heap.pos  = pos;
        heap.drop(new Gold(amount));
        level.heaps.put(pos, heap);
    }

    /** Run hero.act() with an Attack action toward the given target. */
    void actAttackLocal(TestHero hero, Char target) {
        hero.curAction = new HeroAction.Attack(target);
        hero.act();
    }

    /** Run hero.act() with a PickUp action at the hero's current position. */
    void actPickupLocal(TestHero hero) {
        hero.curAction = new HeroAction.PickUp(hero.pos);
        hero.act();
    }

    void clientSendAttack(int heroId, int targetPos) throws IOException { clientSend(heroId, NetworkManager.ActionType.ATTACK, targetPos); }
    void hostSendAttack(int heroId, int targetPos)   throws IOException { hostSend(heroId, NetworkManager.ActionType.ATTACK, targetPos); }
    void clientSendPickup(int heroId, int pos)        throws IOException { clientSend(heroId, NetworkManager.ActionType.PICKUP, pos); }
    void hostSendPickup(int heroId, int pos)          throws IOException { hostSend(heroId, NetworkManager.ActionType.PICKUP, pos); }

    /**
     * Deliver an ATTACK action to a remote hero via socket and run act().
     * Used from the HOST side (receives from clientOut → hostIn).
     */
    void attackRemoteViaSocket_hostReceives(TestHero remoteHero, Char target) throws Exception {
        remoteHero.curAction = null;
        NetworkManager.receiveActionAsync(remoteHero);
        Thread.sleep(15);
        clientSendAttack(Dungeon.heroes.indexOf(remoteHero), target.pos);
        assertTrue(waitFor(remoteHero, 2000), "Remote hero must receive attack packet");
        remoteHero.act();
    }

    /**
     * Deliver an ATTACK action to a remote hero via socket and run act().
     * Used from the CLIENT side (receives from hostOut → clientIn).
     */
    void attackRemoteViaSocket_clientReceives(TestHero remoteHero, Char target) throws Exception {
        remoteHero.curAction = null;
        NetworkManager.receiveActionAsync(remoteHero);
        Thread.sleep(15);
        hostSendAttack(Dungeon.heroes.indexOf(remoteHero), target.pos);
        assertTrue(waitFor(remoteHero, 2000), "Remote hero must receive attack packet");
        remoteHero.act();
    }

    /**
     * Deliver a PICKUP action to a remote hero via socket and run act().
     * HOST receives from client.
     */
    void pickupRemoteViaSocket_hostReceives(TestHero remoteHero, int pickupPos) throws Exception {
        remoteHero.curAction = null;
        NetworkManager.receiveActionAsync(remoteHero);
        Thread.sleep(15);
        clientSendPickup(Dungeon.heroes.indexOf(remoteHero), pickupPos);
        assertTrue(waitFor(remoteHero, 2000), "Remote hero must receive pickup packet");
        remoteHero.act();
    }

    /**
     * Deliver a PICKUP action to a remote hero via socket and run act().
     * CLIENT receives from host.
     */
    void pickupRemoteViaSocket_clientReceives(TestHero remoteHero, int pickupPos) throws Exception {
        remoteHero.curAction = null;
        NetworkManager.receiveActionAsync(remoteHero);
        Thread.sleep(15);
        hostSendPickup(Dungeon.heroes.indexOf(remoteHero), pickupPos);
        assertTrue(waitFor(remoteHero, 2000), "Remote hero must receive pickup packet");
        remoteHero.act();
    }

    // =========================================================================
    // ATTACK TEST: hero0 attacks adjacent mob, mob dies, EXP awarded
    // =========================================================================

    /**
     * hero0 attacks an adjacent TestMob with 1 HP until it dies.
     * Verifies:
     *  - mob is dead after the attack
     *  - hero0.exp increased by mob.EXP (or hero0 leveled up)
     *  - Statistics.enemiesSlain incremented
     *  - All three modes produce identical state
     */
    @Test
    void attack_killMob_expAwarded_allModes() throws Exception {
        final int MOB_POS = START0 + 1;   // adjacent to hero0
        final int MOB_EXP = 5;
        final int GOLD_AMOUNT = 0; // no gold in this test

        // ---- (A) Pass-and-play ----
        NetworkManager.lanMode = false;
        Dungeon.hero = hero0;
        Dungeon.gold = 0;
        Statistics.reset();

        TestMob mob = placeMob(MOB_POS, 1, MOB_EXP);
        int initialExp0 = hero0.exp;

        actAttackLocal(hero0, mob);

        ExtendedGameState ppState = new ExtendedGameState(hero0, hero1);
        assertTrue(!mob.isAlive() || mob.HP <= 0, "mob must be dead after attack (PP)");
        assertTrue(ppState.exp0 > initialExp0 || ppState.enemiesSlain >= 1,
                "hero0 must gain EXP or mob is counted as slain (PP)");
        resetAll();
        NetworkManager.lanMode = true;

        // ---- (B) LAN host: hero0 local, mob on level ----
        NetworkManager.setIsHostForTesting(true);
        NetworkManager.localPlayerIndex = 0;
        Dungeon.hero = hero0;
        Dungeon.gold = 0;
        Statistics.reset();

        mob = placeMob(MOB_POS, 1, MOB_EXP);
        actAttackLocal(hero0, mob);

        ExtendedGameState hostState = new ExtendedGameState(hero0, hero1);
        resetAll();

        // ---- (C) LAN client: hero0 remote, hero1 local (hero0 attacks mob) ----
        NetworkManager.setIsHostForTesting(false);
        NetworkManager.localPlayerIndex = 1;
        Dungeon.hero = hero1;
        Dungeon.gold = 0;
        Statistics.reset();

        mob = placeMob(MOB_POS, 1, MOB_EXP);
        attackRemoteViaSocket_clientReceives(hero0, mob);

        ExtendedGameState clientState = new ExtendedGameState(hero0, hero1);

        // ---- Assert: exp0 and enemiesSlain must match across all three modes ----
        String modes = "\npp=" + ppState + "\nhost=" + hostState + "\nclient=" + clientState;
        assertEquals(ppState.exp0,       hostState.exp0,       "hero0.exp: PP vs HOST" + modes);
        assertEquals(ppState.exp0,       clientState.exp0,     "hero0.exp: PP vs CLIENT" + modes);
        assertEquals(ppState.enemiesSlain, hostState.enemiesSlain, "enemiesSlain: PP vs HOST" + modes);
        assertEquals(ppState.enemiesSlain, clientState.enemiesSlain, "enemiesSlain: PP vs CLIENT" + modes);

        // Sanity: mob was actually killed and EXP was awarded
        assertTrue(ppState.enemiesSlain >= 1, "At least one enemy must have been slain");
    }

    // =========================================================================
    // PICKUP TEST: hero0 picks up Gold, Dungeon.gold increases
    // =========================================================================

    /**
     * Place Gold on hero0's starting position. hero0 picks it up.
     * Verifies Dungeon.gold is identical across all three modes.
     */
    @Test
    void pickup_gold_dungeonGoldIncreases_allModes() throws Exception {
        final int GOLD_AMOUNT = 25;

        // ---- (A) Pass-and-play ----
        NetworkManager.lanMode = false;
        Dungeon.hero = hero0;
        Dungeon.gold = 0;
        Statistics.reset();

        placeGold(hero0.pos, GOLD_AMOUNT);
        actPickupLocal(hero0);

        ExtendedGameState ppState = new ExtendedGameState(hero0, hero1);
        assertEquals(GOLD_AMOUNT, ppState.gold, "PP: Dungeon.gold must increase by " + GOLD_AMOUNT);
        resetAll();
        NetworkManager.lanMode = true;

        // ---- (B) LAN host ----
        NetworkManager.setIsHostForTesting(true);
        NetworkManager.localPlayerIndex = 0;
        Dungeon.hero = hero0;
        Dungeon.gold = 0;
        Statistics.reset();

        placeGold(hero0.pos, GOLD_AMOUNT);
        actPickupLocal(hero0);

        ExtendedGameState hostState = new ExtendedGameState(hero0, hero1);
        resetAll();

        // ---- (C) LAN client: hero0 remote picks up gold ----
        NetworkManager.setIsHostForTesting(false);
        NetworkManager.localPlayerIndex = 1;
        Dungeon.hero = hero1;
        Dungeon.gold = 0;
        Statistics.reset();

        placeGold(hero0.pos, GOLD_AMOUNT);
        pickupRemoteViaSocket_clientReceives(hero0, hero0.pos);

        ExtendedGameState clientState = new ExtendedGameState(hero0, hero1);

        // ---- Assert ----
        String modes = "\npp=" + ppState + "\nhost=" + hostState + "\nclient=" + clientState;
        assertEquals(ppState.gold, hostState.gold,   "Dungeon.gold: PP vs HOST" + modes);
        assertEquals(ppState.gold, clientState.gold, "Dungeon.gold: PP vs CLIENT" + modes);
        assertEquals(GOLD_AMOUNT, ppState.gold, "PP: gold must equal " + GOLD_AMOUNT + modes);
    }

    // =========================================================================
    // MULTI-ROUND SEQUENCE: move + attack + pickup + move
    // =========================================================================

    /**
     * Four-round sequence:
     *   Round 1: hero0 moves right 1 step
     *   Round 2: hero0 attacks adjacent mob (killing it)
     *   Round 3: hero1 picks up gold at its starting position
     *   Round 4: hero1 moves left 1 step
     *
     * Verifies complete state snapshot (positions + exp + gold) matches
     * across pass-and-play, LAN host, and LAN client modes.
     */
    @Test
    void multiRoundSequence_moveAttackPickupMove_identicalState_allModes() throws Exception {
        final int MOB_POS    = START0 + 2;  // 2 cells right of hero0 start, hero0 moves to col+1 first
        final int MOB_EXP    = 3;
        final int GOLD_AMOUNT = 10;

        // ---- (A) Pass-and-play ----
        NetworkManager.lanMode = false;
        Dungeon.hero = hero0;
        Dungeon.gold = 0;
        Statistics.reset();

        // Place gold at hero1's starting pos; hero1 will pick it up in round 3
        placeGold(hero1.pos, GOLD_AMOUNT);

        // Round 1: hero0 moves right 1 step to MOB_POS - 1 = START0 + 1
        hero0.curAction = new HeroAction.Move(START0 + 1);
        hero0.act();

        // Round 2: hero0 attacks mob (mob is at START0+2, hero0 is now at START0+1 = adjacent)
        TestMob mob = placeMob(MOB_POS, 1, MOB_EXP);
        actAttackLocal(hero0, mob);

        // Round 3: hero1 picks up gold at its own position
        actPickupLocal(hero1);

        // Round 4: hero1 moves left 1 step
        hero1.curAction = new HeroAction.Move(START1 - 1);
        hero1.act();

        ExtendedGameState ppState = new ExtendedGameState(hero0, hero1);
        resetAll();
        NetworkManager.lanMode = true;

        // ---- (B) LAN host ----
        NetworkManager.setIsHostForTesting(true);
        NetworkManager.localPlayerIndex = 0;
        Dungeon.hero = hero0;
        Dungeon.gold = 0;
        Statistics.reset();

        placeGold(hero1.pos, GOLD_AMOUNT);

        // R1: hero0 moves
        hero0.curAction = new HeroAction.Move(START0 + 1);
        hero0.act();

        // R2: hero0 attacks mob
        mob = placeMob(MOB_POS, 1, MOB_EXP);
        actAttackLocal(hero0, mob);

        // R3: hero1 remote picks up gold
        hero1.curAction = null;
        NetworkManager.receiveActionAsync(hero1);
        Thread.sleep(10);
        clientSendPickup(1, hero1.pos);
        assertTrue(waitFor(hero1, 2000), "host: hero1 must receive pickup action");
        hero1.act();

        // R4: hero1 remote moves
        hero1.curAction = null;
        NetworkManager.receiveActionAsync(hero1);
        Thread.sleep(10);
        clientSendMove(1, START1 - 1);
        assertTrue(waitFor(hero1, 2000), "host: hero1 must receive move action");
        hero1.act();

        ExtendedGameState hostState = new ExtendedGameState(hero0, hero1);
        resetAll();

        // ---- (C) LAN client ----
        NetworkManager.setIsHostForTesting(false);
        NetworkManager.localPlayerIndex = 1;
        Dungeon.hero = hero1;
        Dungeon.gold = 0;
        Statistics.reset();

        placeGold(hero1.pos, GOLD_AMOUNT);

        // R1: hero0 remote moves
        hero0.curAction = null;
        NetworkManager.receiveActionAsync(hero0);
        Thread.sleep(10);
        hostSendMove(0, START0 + 1);
        assertTrue(waitFor(hero0, 2000), "client: hero0 must receive move action");
        hero0.act();

        // R2: hero0 remote attacks mob
        mob = placeMob(MOB_POS, 1, MOB_EXP);
        attackRemoteViaSocket_clientReceives(hero0, mob);

        // R3: hero1 picks up gold (local)
        actPickupLocal(hero1);

        // R4: hero1 moves (local)
        hero1.curAction = new HeroAction.Move(START1 - 1);
        hero1.act();

        ExtendedGameState clientState = new ExtendedGameState(hero0, hero1);

        // ---- Assert all three match ----
        String modes = "\npp=" + ppState + "\nhost=" + hostState + "\nclient=" + clientState;
        assertEquals(ppState.pos0, hostState.pos0,     "hero0.pos: PP vs HOST" + modes);
        assertEquals(ppState.pos0, clientState.pos0,   "hero0.pos: PP vs CLIENT" + modes);
        assertEquals(ppState.pos1, hostState.pos1,     "hero1.pos: PP vs HOST" + modes);
        assertEquals(ppState.pos1, clientState.pos1,   "hero1.pos: PP vs CLIENT" + modes);
        assertEquals(ppState.exp0, hostState.exp0,     "hero0.exp: PP vs HOST" + modes);
        assertEquals(ppState.exp0, clientState.exp0,   "hero0.exp: PP vs CLIENT" + modes);
        assertEquals(ppState.gold, hostState.gold,     "Dungeon.gold: PP vs HOST" + modes);
        assertEquals(ppState.gold, clientState.gold,   "Dungeon.gold: PP vs CLIENT" + modes);
        assertEquals(ppState.enemiesSlain, hostState.enemiesSlain,   "slain: PP vs HOST" + modes);
        assertEquals(ppState.enemiesSlain, clientState.enemiesSlain, "slain: PP vs CLIENT" + modes);

        // Sanity checks
        assertNotEquals(START0, ppState.pos0, "hero0 must have moved from START0");
        assertNotEquals(START1, ppState.pos1, "hero1 must have moved from START1");
        assertTrue(ppState.gold == GOLD_AMOUNT, "gold must have been picked up");
        assertTrue(ppState.enemiesSlain >= 1, "at least one mob must have been killed");
    }

    // =========================================================================
    // TURN TIME CONSISTENCY: attack and pickup should advance time identically
    // =========================================================================

    /**
     * Verifies that hero.time (actor scheduling time) advances identically
     * after an attack action in all three modes.
     */
    @Test
    void turnTime_identicalAfterAttack_allModes() throws Exception {
        final int MOB_POS = START0 + 1;

        // ---- Pass-and-play ----
        NetworkManager.lanMode = false;
        Dungeon.hero = hero0;
        Statistics.reset();

        TestMob mob = placeMob(MOB_POS, 999, 0); // high HP so mob survives the attack
        actAttackLocal(hero0, mob);
        float ppTime0 = hero0.getTimeForTesting();
        resetAll();
        NetworkManager.lanMode = true;

        // ---- LAN host ----
        NetworkManager.setIsHostForTesting(true);
        NetworkManager.localPlayerIndex = 0;
        Dungeon.hero = hero0;
        Statistics.reset();

        mob = placeMob(MOB_POS, 999, 0);
        actAttackLocal(hero0, mob);
        float hostTime0 = hero0.getTimeForTesting();
        resetAll();

        // ---- LAN client ----
        NetworkManager.setIsHostForTesting(false);
        NetworkManager.localPlayerIndex = 1;
        Dungeon.hero = hero1;
        Statistics.reset();

        mob = placeMob(MOB_POS, 999, 0);
        attackRemoteViaSocket_clientReceives(hero0, mob);
        float clientTime0 = hero0.getTimeForTesting();

        assertEquals(ppTime0, hostTime0,   0.001f, "hero0.time after attack: PP vs HOST");
        assertEquals(ppTime0, clientTime0, 0.001f, "hero0.time after attack: PP vs CLIENT");
        assertTrue(ppTime0 > 0, "hero0 must have spent time attacking");
    }

    // =========================================================================
    // OPEN CHEST: hero opens a CHEST-type heap; items become accessible
    // =========================================================================

    /**
     * Place a CHEST-type heap adjacent to hero0. hero0 opens it via HeroAction.OpenChest.
     * The chest converts to HEAP type (items visible) and hero0 spends Key.TIME_TO_UNLOCK.
     * Verifies turn-time and heap type are identical across all three modes.
     *
     * Note: CHEST (non-locked) type needs no key — it only requires the hero to be adjacent
     * and issue an OpenChest action. Null guards were added to Heap.open() so it runs
     * without a sprite/scene context.
     */
    @Test
    void openChest_chestBecomesHeap_turnTimeAdvances_allModes() throws Exception {
        final int CHEST_POS = START0 + 1;   // adjacent to hero0

        // ---- (A) Pass-and-play ----
        NetworkManager.lanMode = false;
        Dungeon.hero = hero0;
        Dungeon.gold = 0;
        Statistics.reset();

        Heap chest = placeChest(CHEST_POS);
        hero0.curAction = new HeroAction.OpenChest(CHEST_POS);
        hero0.act();
        float ppTime0 = hero0.getTimeForTesting();
        Heap.Type ppHeapType = chest.type;
        resetAll();
        NetworkManager.lanMode = true;

        // ---- (B) LAN host ----
        NetworkManager.setIsHostForTesting(true);
        NetworkManager.localPlayerIndex = 0;
        Dungeon.hero = hero0;

        chest = placeChest(CHEST_POS);
        hero0.curAction = new HeroAction.OpenChest(CHEST_POS);
        hero0.act();
        float hostTime0 = hero0.getTimeForTesting();
        Heap.Type hostHeapType = chest.type;
        resetAll();

        // ---- (C) LAN client: hero0 remote opens chest ----
        NetworkManager.setIsHostForTesting(false);
        NetworkManager.localPlayerIndex = 1;
        Dungeon.hero = hero1;

        chest = placeChest(CHEST_POS);
        hero0.curAction = null;
        NetworkManager.receiveActionAsync(hero0);
        Thread.sleep(15);
        hostSend(0, NetworkManager.ActionType.OPEN_CHEST, CHEST_POS);
        assertTrue(waitFor(hero0, 2000), "client: hero0 must receive open-chest packet");
        hero0.act();
        float clientTime0 = hero0.getTimeForTesting();
        Heap.Type clientHeapType = chest.type;

        // ---- Assert ----
        assertEquals(ppHeapType,    hostHeapType,   "heap type after open: PP vs HOST");
        assertEquals(ppHeapType,    clientHeapType, "heap type after open: PP vs CLIENT");
        assertEquals(ppTime0, hostTime0,   0.001f, "hero0.time after open-chest: PP vs HOST");
        assertEquals(ppTime0, clientTime0, 0.001f, "hero0.time after open-chest: PP vs CLIENT");
        assertEquals(Heap.Type.HEAP, ppHeapType,    "chest must be converted to HEAP after opening");
        assertTrue(ppTime0 > 0, "hero0 must have spent time opening the chest");
    }

    /** Place a CHEST-type heap (no key required to open) at the given position. */
    Heap placeChest(int pos) {
        Heap heap = new Heap();
        heap.pos  = pos;
        heap.type = Heap.Type.CHEST;
        heap.drop(new Gold(5));
        level.heaps.put(pos, heap);
        return heap;
    }

    // =========================================================================
    // RESTING: hero passes a turn; turn time advances by TIME_TO_REST
    // =========================================================================

    /**
     * Set hero.resting=true and call act() with curAction=null — this exercises
     * the rest-a-turn path (spendConstant(TIME_TO_REST)).
     *
     * Note: In LAN mode, item use from the quickslot or backpack (potions, scrolls,
     * food) goes through item.execute() → hero.spendAndNext() DIRECTLY and is NOT
     * transmitted as a HeroAction packet. That code path is outside the LAN sync
     * protocol and would require a USE_ITEM action type to be added in order to keep
     * both simulations in sync. The rest action tested here covers the null-curAction
     * path that IS in the actor system.
     */
    @Test
    void rest_turnTimeAdvances_allModes() throws Exception {
        final float TIME_TO_REST = 1f;  // Actor.TICK

        // ---- (A) Pass-and-play ----
        NetworkManager.lanMode = false;
        Dungeon.hero = hero0;

        hero0.resting = true;
        hero0.curAction = null;
        hero0.act();
        float ppTime0 = hero0.getTimeForTesting();
        resetHeroes();
        NetworkManager.lanMode = true;

        // ---- (B) LAN host: hero0 is local, rests ----
        NetworkManager.setIsHostForTesting(true);
        NetworkManager.localPlayerIndex = 0;
        Dungeon.hero = hero0;

        hero0.resting = true;
        hero0.curAction = null;
        hero0.act();
        float hostTime0 = hero0.getTimeForTesting();
        resetHeroes();

        // ---- (C) LAN client: hero1 is local, hero0 is remote — hero1 rests ----
        NetworkManager.setIsHostForTesting(false);
        NetworkManager.localPlayerIndex = 1;
        Dungeon.hero = hero1;

        hero1.resting = true;
        hero1.curAction = null;
        hero1.act();
        float clientTime1 = hero1.getTimeForTesting();

        // ---- Assert ----
        assertEquals(ppTime0,    hostTime0,   0.001f, "hero0.time after rest: PP vs HOST");
        assertEquals(ppTime0,    clientTime1, 0.001f, "hero.time after rest: PP vs CLIENT (hero1)");
        assertEquals(TIME_TO_REST, ppTime0,   0.001f, "resting must spend exactly TIME_TO_REST");
    }

    // =========================================================================
    // BACKPACK / QUICKSLOT ITEM USE: pick up, put in bag, verify inventory sync
    // =========================================================================

    /**
     * Hero picks up a PotionOfHealing from the floor (PICKUP action — which IS in the
     * LAN protocol). Verifies the item lands in the hero's inventory in all three modes.
     *
     * Direct item use from the backpack or quickslot (potion drink, scroll read) goes
     * through item.execute() and is NOT transmitted as a LAN action packet — that is a
     * known gap in the protocol. This test covers the closest synchronised operation:
     * the PICKUP that puts an item into the inventory.
     */
    @Test
    void pickup_itemIntoInventory_allModes() throws Exception {
        // ---- (A) Pass-and-play ----
        NetworkManager.lanMode = false;
        Dungeon.hero = hero0;
        Dungeon.gold = 0;
        Statistics.reset();

        placeItem(hero0.pos, new TestItem());
        int beforePP = hero0.belongings.backpack.items.size();
        actPickupLocal(hero0);
        int afterPP = hero0.belongings.backpack.items.size();
        resetAll();
        NetworkManager.lanMode = true;

        // ---- (B) LAN host ----
        NetworkManager.setIsHostForTesting(true);
        NetworkManager.localPlayerIndex = 0;
        Dungeon.hero = hero0;

        placeItem(hero0.pos, new TestItem());
        actPickupLocal(hero0);
        int afterHost = hero0.belongings.backpack.items.size();
        resetAll();

        // ---- (C) LAN client: hero0 remote picks up item ----
        NetworkManager.setIsHostForTesting(false);
        NetworkManager.localPlayerIndex = 1;
        Dungeon.hero = hero1;

        placeItem(hero0.pos, new TestItem());
        pickupRemoteViaSocket_clientReceives(hero0, hero0.pos);
        int afterClient = hero0.belongings.backpack.items.size();

        // ---- Assert ----
        assertTrue(afterPP > beforePP, "PP: hero0 must have picked up the potion");
        assertEquals(afterPP,   afterHost,   "inventory size after pickup: PP vs HOST");
        assertEquals(afterPP,   afterClient, "inventory size after pickup: PP vs CLIENT");
    }

    // =========================================================================
    // TestItem: a plain stackable item with no texture requirements
    // =========================================================================

    /** Minimal item for inventory tests — no sprite sheet access, just raw Item. */
    static class TestItem extends com.shatteredpixel.shatteredpixeldungeon.items.Item {
        TestItem() { image = 0; stackable = false; }
        @Override public boolean isUpgradable()  { return false; }
        @Override public boolean isIdentified()  { return true;  }
    }

    /** Place a single item in a HEAP at the given position. */
    void placeItem(int pos, com.shatteredpixel.shatteredpixeldungeon.items.Item item) {
        Heap heap = new Heap();
        heap.pos = pos;
        heap.drop(item);
        level.heaps.put(pos, heap);
    }
}
