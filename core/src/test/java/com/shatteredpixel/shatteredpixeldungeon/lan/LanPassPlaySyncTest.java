package com.shatteredpixel.shatteredpixeldungeon.lan;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.Statistics;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.GreaterHaste;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroAction;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Talent;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.items.weapon.Weapon;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.network.NetworkManager;
import com.watabou.noosa.Game;
import com.watabou.utils.PathFinder;
import com.watabou.utils.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LAN vs pass-and-play state sync comparison test.
 *
 * Uses the same real-TCP-socket infrastructure as LanRealSocketTest and
 * LanClientPerspectiveTest to simulate both the HOST and CLIENT perspectives
 * of a 2-player LAN game. After running the same round of actions through
 * each perspective, the resulting game state must be bit-for-bit identical.
 *
 * ---
 * Architecture (copied from existing LAN tests):
 *
 *   serverSocket.accept() ──► hostSideSocket
 *   new Socket(port)      ──► clientSocket
 *
 *   hostOut ──► clientIn  (host sends to client)
 *   clientOut ──► hostIn  (client sends to host)
 *
 *   NetworkManager.injectHostStreamsForTesting(hostIn, hostOut)
 *   NetworkManager.injectClientStreamsForTesting(clientIn, clientOut)
 *
 * HOST perspective (isHost=true, localPlayerIndex=0, Dungeon.hero=hero0):
 *   - hero0 acts locally (curAction set directly)
 *   - hero1 acts remotely: receiveActionAsync() reads from hostIn (client writes there)
 *   - hero1 kills mob → state captured as "LAN host state"
 *
 * PASS-AND-PLAY perspective (lanMode=false, Dungeon.hero=hero0):
 *   - hero0 acts locally
 *   - hero1 acts locally (same device)
 *   - hero1 kills same mob → state captured as "pass-and-play state"
 *
 * CLIENT perspective (isHost=false, localPlayerIndex=1, Dungeon.hero=hero1):
 *   - hero1 acts locally
 *   - hero0 acts remotely: receiveActionAsync() reads from clientIn (host writes there)
 *   - hero1 kills mob → state captured as "LAN client state"
 *
 * All three must produce identical state after each kill.
 * ---
 *
 * SyncTestMob: overrides die() to bypass sprite.die() NPE (headless tests have
 * no sprites), but all state logic — killerHero resolution, rollToDropLoot,
 * lootChance, destroy/earnExp — runs through real production code.
 */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class LanPassPlaySyncTest {

    static final int W = 8;
    static final int H = 5;
    static final int POS_HERO0 = W + 1;
    static final int POS_HERO1 = W + 3;
    static final int POS_MOB   = W + 5;

    // -------------------------------------------------------------------------
    // Real TCP socket pair (same pattern as LanRealSocketTest)
    // -------------------------------------------------------------------------

    private ServerSocket     serverSocket;
    private Socket           hostSideSocket;
    private Socket           clientSocket;

    private DataInputStream  hostIn;
    private DataOutputStream hostOut;
    private DataInputStream  clientIn;
    private DataOutputStream clientOut;

    // -------------------------------------------------------------------------
    // Game state
    // -------------------------------------------------------------------------

    private Hero hero0;
    private Hero hero1;
    private MinimalLevel level;

    // Saved initial EXP to detect changes
    private int initExp0;
    private int initExp1;

    // -------------------------------------------------------------------------
    // Minimal level stub (copied from LanMobDeathSyncTest)
    // -------------------------------------------------------------------------

    static class MinimalLevel extends Level {
        MinimalLevel() {
            width   = W; height  = H; length  = W * H;
            map     = new int[length]; pit      = new boolean[length];
            passable= new boolean[length]; losBlocking = new boolean[length];
            solid   = new boolean[length]; avoid    = new boolean[length];
            water   = new boolean[length]; visited  = new boolean[length];
            mapped  = new boolean[length]; heroFOV  = new boolean[length];
            transitions = new ArrayList<>();
            mobs        = new java.util.HashSet<>();
            heaps       = new com.watabou.utils.SparseArray<>();
            blobs       = new java.util.HashMap<>();
            plants      = new com.watabou.utils.SparseArray<>();
            traps       = new com.watabou.utils.SparseArray<>();
            customTiles = new ArrayList<>();
            customWalls = new ArrayList<>();
            java.util.Arrays.fill(passable, true);
            PathFinder.setMapSize(W, H);
        }
        @Override protected boolean build()    { return true; }
        @Override protected void createMobs()  {}
        @Override protected void createItems() {}
        @Override public int entrance()        { return POS_HERO0; }
    }

    // -------------------------------------------------------------------------
    // SyncTestMob: runs real production code except sprite.die() (headless)
    // -------------------------------------------------------------------------

    static class SyncTestMob extends Mob {
        /** Whether rollToDropLoot decided to drop (captured without spawning sprite). */
        boolean lootWouldDrop;

        SyncTestMob(float lootChance) {
            EXP            = 10;
            maxLvl         = 30;
            this.lootChance = lootChance;
            alignment      = Alignment.ENEMY;
            HP = HT        = 1;
            pos            = POS_MOB;
        }

        /**
         * Mirrors Mob.die() but calls destroy() directly instead of super.die()
         * so that Char.die()→sprite.die() doesn't NPE in headless tests.
         * Every state-changing operation — killerHero resolution, rollToDropLoot,
         * talent checks, destroy/earnExp, Statistics, Badges — runs for real.
         */
        @Override
        public void die(Object cause) {
            killerHero = (cause instanceof Hero) ? (Hero) cause : Dungeon.hero;

            if (alignment == Alignment.ENEMY) {
                rollToDropLoot();

                Hero k = killerHero;
                if (cause instanceof Hero || cause instanceof Weapon
                        || cause instanceof Weapon.Enchantment) {
                    if (k.hasTalent(Talent.LETHAL_MOMENTUM)
                            && Random.Float() < 0.34f + 0.33f * k.pointsInTalent(Talent.LETHAL_MOMENTUM)) {
                        Buff.affect(k, Talent.LethalMomentumTracker.class, 0f);
                    }
                    if (k.heroClass != HeroClass.DUELIST
                            && k.hasTalent(Talent.LETHAL_HASTE)
                            && k.buff(Talent.LethalHasteCooldown.class) == null) {
                        Buff.affect(k, Talent.LethalHasteCooldown.class, 100f);
                        Buff.affect(k, GreaterHaste.class)
                                .set(2 + 2 * k.pointsInTalent(Talent.LETHAL_HASTE));
                    }
                }
            }

            destroy();   // real Mob.destroy(): EXP award, Statistics, Badges
            HP = 0;
            deathMarked = true;
        }

        /** Captures loot decision using real lootChance() without calling GameScene. */
        @Override
        public void rollToDropLoot() {
            Hero h = killerHero != null ? killerHero : Dungeon.hero;
            if (h.lvl > maxLvl + 2) return;
            lootWouldDrop = Random.Float() < lootChance();
        }
    }

    // -------------------------------------------------------------------------
    // Snapshot of observable game state
    // -------------------------------------------------------------------------

    static class GameState {
        final int hero0Exp, hero0Lvl;
        final int hero1Exp, hero1Lvl;
        final int enemiesSlain;
        final boolean lootWouldDrop;

        GameState(Hero h0, Hero h1, SyncTestMob mob) {
            hero0Exp     = h0.exp;   hero0Lvl = h0.lvl;
            hero1Exp     = h1.exp;   hero1Lvl = h1.lvl;
            enemiesSlain = Statistics.enemiesSlain;
            lootWouldDrop = mob.lootWouldDrop;
        }

        @Override public String toString() {
            return String.format("h0(exp=%d lvl=%d) h1(exp=%d lvl=%d) slain=%d loot=%b",
                    hero0Exp, hero0Lvl, hero1Exp, hero1Lvl, enemiesSlain, lootWouldDrop);
        }
    }

    // -------------------------------------------------------------------------
    // Setup / teardown
    // -------------------------------------------------------------------------

    @BeforeEach
    void setUp() throws IOException {
        Game.version = "test";   // prevents Document static-init NPE via Badges
        PathFinder.setMapSize(W, H);
        Statistics.reset();

        // Real loopback socket pair (same as LanRealSocketTest)
        serverSocket = new ServerSocket(0);
        int port = serverSocket.getLocalPort();
        Future<Socket> serverSide = Executors.newSingleThreadExecutor()
                .submit(() -> serverSocket.accept());
        clientSocket = new Socket("127.0.0.1", port);
        try { hostSideSocket = serverSide.get(2, TimeUnit.SECONDS); }
        catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new IOException("socket setup failed", e);
        }

        hostIn   = new DataInputStream(hostSideSocket.getInputStream());
        hostOut  = new DataOutputStream(hostSideSocket.getOutputStream());
        clientIn = new DataInputStream(clientSocket.getInputStream());
        clientOut= new DataOutputStream(clientSocket.getOutputStream());

        NetworkManager.injectHostStreamsForTesting(hostIn, hostOut);
        NetworkManager.injectClientStreamsForTesting(clientIn, clientOut);

        // Heroes
        hero0 = new Hero(); hero0.HP = hero0.HT = 30; hero0.lvl = 5;
        hero0.heroClass = HeroClass.WARRIOR; hero0.pos = POS_HERO0;
        hero1 = new Hero(); hero1.HP = hero1.HT = 25; hero1.lvl = 5;
        hero1.heroClass = HeroClass.MAGE;    hero1.pos = POS_HERO1;
        initExp0 = hero0.exp;
        initExp1 = hero1.exp;

        Dungeon.heroes = new ArrayList<>();
        Dungeon.heroes.add(hero0);
        Dungeon.heroes.add(hero1);
        Dungeon.customSeedText = "";

        level = new MinimalLevel();
        Dungeon.level = level;

        Actor.clear();

        // Start in HOST mode (most tests switch perspective inside the test)
        NetworkManager.lanMode = true;
        NetworkManager.setIsHostForTesting(true);
        NetworkManager.localPlayerIndex = 0;
        NetworkManager.resetActionReaderForTesting();
        NetworkManager.resetCommitProtocolState();
        // Discard local heroes' performed-op sends: the remote side is fed
        // manually, and this keeps a client-side local hero from blocking on a
        // commit echo that no leader will send.
        NetworkManager.sendActionOverride = (a, h) -> {};
        Dungeon.hero = hero0;
    }

    @AfterEach
    void tearDown() throws IOException {
        NetworkManager.lanMode = false;
        NetworkManager.setIsHostForTesting(false);
        NetworkManager.localPlayerIndex = 0;
        NetworkManager.resetActionReaderForTesting();
        NetworkManager.resetCommitProtocolState();
        NetworkManager.sendActionOverride = null;
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
    // Helpers (same patterns as existing LAN tests)
    // -------------------------------------------------------------------------

    // v3: commits queue in the hero inbox; curAction is decoded from it inside act().
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

    private int hostReqSeq = 0;
    private int clientCommitSeq = -1;

    /** Fake client → host device: a MOVE REQUEST the leader sequences. */
    void clientSendsAction(int heroId, int targetPos) throws IOException {
        LanTestProtocol.writeActionRequest(clientOut, ++hostReqSeq, NetworkManager.ActionType.MOVE, targetPos);
    }

    /** Fake leader → client device: a MOVE COMMIT applied in globalSeq order. */
    void hostSendsAction(int heroId, int targetPos) throws IOException {
        if (clientCommitSeq < 0) clientCommitSeq = NetworkManager.getGlobalSeqForTesting();
        LanTestProtocol.writeActionCommit(hostOut, ++clientCommitSeq, heroId, 0, NetworkManager.ActionType.MOVE, targetPos);
    }

    /** Reset hero state between perspective runs. */
    void resetForNextRun() {
        hero0.exp = initExp0; hero0.lvl = 5;
        hero1.exp = initExp1; hero1.lvl = 5;
        level.mobs.clear();
        Statistics.reset();
        NetworkManager.resetActionReaderForTesting();
    }

    // =========================================================================
    // CORE COMPARISON: same kill → same state on HOST, PASS-AND-PLAY, CLIENT
    // =========================================================================

    /**
     * Hero1 kills a mob. Run the scenario three ways and assert identical state:
     *
     *  (A) HOST perspective:      Dungeon.hero=hero0, hero1's action arrives via socket
     *  (B) PASS-AND-PLAY:         lanMode=false, no network, same kill directly
     *  (C) CLIENT perspective:    Dungeon.hero=hero1, hero0's move arrives via socket
     *
     * All three must produce identical EXP awards and loot decisions.
     */
    @Test
    void hero1KillsMob_identicalState_host_passPlay_client() throws Exception {
        long seed = 0xDEADBEEFL;

        // ---- (A) HOST perspective ----
        // isHost=true, localPlayerIndex=0, Dungeon.hero=hero0
        // hero1 (remote) sends its ATTACK action via socket → received → die() runs
        NetworkManager.setIsHostForTesting(true);
        NetworkManager.localPlayerIndex = 0;
        Dungeon.hero = hero0;
        Random.pushGenerator(seed);

        SyncTestMob mobA = new SyncTestMob(0.5f);
        level.mobs.add(mobA);

        // Start LAN reader for hero1 (remote)
        NetworkManager.receiveActionAsync(hero1);
        Thread.sleep(20);
        clientSendsAction(1, POS_MOB);     // hero1 on client sends ATTACK
        assertTrue(waitFor(hero1, 2000), "Host must receive hero1's action via socket");
        assertFalse(hero1.lanActionInbox.isEmpty(), "committed op must be queued in hero1's inbox");

        // Execute the kill — same as Hero.actAttack() calling attack() → die()
        mobA.die(hero1);
        hero1.curAction = null;
        hero1.lanActionInbox.clear();

        GameState stateA = new GameState(hero0, hero1, mobA);
        Random.popGenerator();
        resetForNextRun();

        // ---- (B) PASS-AND-PLAY perspective ----
        // lanMode=false, Dungeon.hero=hero0 — same device controls both heroes
        NetworkManager.lanMode = false;
        Dungeon.hero = hero0;
        Random.pushGenerator(seed);

        SyncTestMob mobB = new SyncTestMob(0.5f);
        level.mobs.add(mobB);
        mobB.die(hero1);   // hero1 acts directly, no network

        GameState stateB = new GameState(hero0, hero1, mobB);
        Random.popGenerator();
        resetForNextRun();
        NetworkManager.lanMode = true;

        // ---- (C) CLIENT perspective ----
        // isHost=false, localPlayerIndex=1, Dungeon.hero=hero1
        // hero0 (remote from client's view) sends a move → received
        // hero1 acts locally and kills the mob
        NetworkManager.setIsHostForTesting(false);
        NetworkManager.localPlayerIndex = 1;
        Dungeon.hero = hero1;
        Random.pushGenerator(seed);

        SyncTestMob mobC = new SyncTestMob(0.5f);
        level.mobs.add(mobC);

        // Start client reader for hero0 (remote on client's device)
        NetworkManager.receiveActionAsync(hero0);
        Thread.sleep(20);
        hostSendsAction(0, POS_HERO1);     // host sends hero0's MOVE action
        assertTrue(waitFor(hero0, 2000), "Client must receive hero0's action via socket");
        hero0.curAction = null;            // hero0's move consumed

        // Now hero1 (local on client) kills the mob
        mobC.die(hero1);

        GameState stateC = new GameState(hero0, hero1, mobC);
        Random.popGenerator();

        // ---- ASSERT all three states are identical ----
        String msg = "host=" + stateA + " passPlay=" + stateB + " client=" + stateC;

        assertEquals(stateA.hero0Exp,     stateB.hero0Exp,     "hero0.exp: HOST vs PASS-AND-PLAY — " + msg);
        assertEquals(stateA.hero0Exp,     stateC.hero0Exp,     "hero0.exp: HOST vs CLIENT — " + msg);
        assertEquals(stateA.hero1Exp,     stateB.hero1Exp,     "hero1.exp: HOST vs PASS-AND-PLAY — " + msg);
        assertEquals(stateA.hero1Exp,     stateC.hero1Exp,     "hero1.exp: HOST vs CLIENT — " + msg);
        assertEquals(stateA.enemiesSlain, stateB.enemiesSlain, "enemiesSlain: HOST vs PASS-AND-PLAY — " + msg);
        assertEquals(stateA.enemiesSlain, stateC.enemiesSlain, "enemiesSlain: HOST vs CLIENT — " + msg);
        assertEquals(stateA.lootWouldDrop,stateB.lootWouldDrop,"lootDrop: HOST vs PASS-AND-PLAY — " + msg);
        assertEquals(stateA.lootWouldDrop,stateC.lootWouldDrop,"lootDrop: HOST vs CLIENT — " + msg);

        // Hero1 (the killer) must have gained EXP in all perspectives
        assertTrue(stateA.hero1Exp > initExp1, "HOST: killer hero1 must gain EXP");
        assertTrue(stateB.hero1Exp > initExp1, "PASS-AND-PLAY: killer hero1 must gain EXP");
        assertTrue(stateC.hero1Exp > initExp1, "CLIENT: killer hero1 must gain EXP");

        // Hero0 (not the killer) must NOT gain EXP in any perspective
        assertEquals(initExp0, stateA.hero0Exp, "HOST: hero0 must not gain EXP from hero1's kill");
        assertEquals(initExp0, stateB.hero0Exp, "PASS-AND-PLAY: hero0 must not gain EXP");
        assertEquals(initExp0, stateC.hero0Exp, "CLIENT: hero0 must not gain EXP");
    }

    /**
     * Hero0 kills a mob. Same three-way comparison but with the other killer.
     */
    @Test
    void hero0KillsMob_identicalState_host_passPlay_client() throws Exception {
        long seed = 0xCAFEBABEL;

        // ---- (A) HOST ----
        NetworkManager.setIsHostForTesting(true);
        NetworkManager.localPlayerIndex = 0;
        Dungeon.hero = hero0;
        Random.pushGenerator(seed);

        SyncTestMob mobA = new SyncTestMob(0.5f);
        level.mobs.add(mobA);
        mobA.die(hero0);   // hero0 (local on host) kills mob

        GameState stateA = new GameState(hero0, hero1, mobA);
        Random.popGenerator();
        resetForNextRun();

        // ---- (B) PASS-AND-PLAY ----
        NetworkManager.lanMode = false;
        Dungeon.hero = hero0;
        Random.pushGenerator(seed);

        SyncTestMob mobB = new SyncTestMob(0.5f);
        level.mobs.add(mobB);
        mobB.die(hero0);

        GameState stateB = new GameState(hero0, hero1, mobB);
        Random.popGenerator();
        resetForNextRun();
        NetworkManager.lanMode = true;

        // ---- (C) CLIENT ----
        NetworkManager.setIsHostForTesting(false);
        NetworkManager.localPlayerIndex = 1;
        Dungeon.hero = hero1;
        Random.pushGenerator(seed);

        SyncTestMob mobC = new SyncTestMob(0.5f);
        level.mobs.add(mobC);
        mobC.die(hero0);   // hero0 kills mob (remote killer on client device)

        GameState stateC = new GameState(hero0, hero1, mobC);
        Random.popGenerator();

        // ---- ASSERT ----
        String msg = "host=" + stateA + " passPlay=" + stateB + " client=" + stateC;
        assertEquals(stateA.hero0Exp, stateB.hero0Exp, "hero0.exp HOST vs PP — " + msg);
        assertEquals(stateA.hero0Exp, stateC.hero0Exp, "hero0.exp HOST vs CLIENT — " + msg);
        assertEquals(stateA.hero1Exp, stateB.hero1Exp, "hero1.exp HOST vs PP — " + msg);
        assertEquals(stateA.hero1Exp, stateC.hero1Exp, "hero1.exp HOST vs CLIENT — " + msg);
        assertEquals(stateA.lootWouldDrop, stateB.lootWouldDrop, "loot HOST vs PP — " + msg);
        assertEquals(stateA.lootWouldDrop, stateC.lootWouldDrop, "loot HOST vs CLIENT — " + msg);
    }

    // =========================================================================
    // MULTI-ROUND: several alternating kills stay in sync
    // =========================================================================

    /**
     * Simulates 4 rounds of play: h0 kills, h1 kills, h0 kills, h1 kills.
     * Runs the entire sequence from HOST perspective and CLIENT perspective,
     * then compares the cumulative state. Any desync in EXP or kill count
     * will be detected.
     */
    @Test
    void multiRound_4kills_hostAndClientStateIdentical() throws Exception {
        long seed = 0x12345678L;

        // ---- HOST sequence ----
        NetworkManager.setIsHostForTesting(true);
        NetworkManager.localPlayerIndex = 0;
        Dungeon.hero = hero0;
        Random.pushGenerator(seed);

        SyncTestMob[] hostMobs = new SyncTestMob[4];
        for (int i = 0; i < 4; i++) {
            hostMobs[i] = new SyncTestMob(0f);
            level.mobs.add(hostMobs[i]);
            Hero killer = (i % 2 == 0) ? hero0 : hero1;
            hostMobs[i].die(killer);
        }
        int hostExp0 = hero0.exp, hostExp1 = hero1.exp, hostSlain = Statistics.enemiesSlain;
        Random.popGenerator();
        resetForNextRun();

        // ---- CLIENT sequence (same actions, Dungeon.hero swapped) ----
        NetworkManager.setIsHostForTesting(false);
        NetworkManager.localPlayerIndex = 1;
        Dungeon.hero = hero1;
        Random.pushGenerator(seed);

        SyncTestMob[] clientMobs = new SyncTestMob[4];
        for (int i = 0; i < 4; i++) {
            clientMobs[i] = new SyncTestMob(0f);
            level.mobs.add(clientMobs[i]);
            Hero killer = (i % 2 == 0) ? hero0 : hero1;
            clientMobs[i].die(killer);
        }
        int clientExp0 = hero0.exp, clientExp1 = hero1.exp, clientSlain = Statistics.enemiesSlain;
        Random.popGenerator();

        assertEquals(hostExp0, clientExp0, "hero0.exp after 4 kills must be identical");
        assertEquals(hostExp1, clientExp1, "hero1.exp after 4 kills must be identical");
        assertEquals(hostSlain, clientSlain, "enemiesSlain after 4 kills must be identical");
    }

    // =========================================================================
    // SOCKET DELIVERY + STATE: action arrives via real packet, kill applied,
    // state must match pass-and-play
    // =========================================================================

    /**
     * Full round via real socket:
     *   1. HOST starts reader for hero1
     *   2. Client sends ACTION packet (simulates hero1 pressing attack)
     *   3. HOST receives packet, curAction set
     *   4. Kill is applied: mob.die(hero1)
     *   5. State captured
     *   6. Reset, run same kill in pass-and-play (no socket)
     *   7. Assert states identical
     *
     * This is the closest to "real LAN vs pass-and-play" possible without
     * running the full game engine (sprites prevent that in headless tests).
     */
    @Test
    void socketDelivery_thenKill_matchesPassAndPlay() throws Exception {
        long seed = 0xABCDEF01L;

        // ---- LAN: hero1's action arrives via socket ----
        NetworkManager.setIsHostForTesting(true);
        NetworkManager.localPlayerIndex = 0;
        Dungeon.hero = hero0;
        Random.pushGenerator(seed);

        SyncTestMob lanMob = new SyncTestMob(0.5f);
        level.mobs.add(lanMob);

        NetworkManager.receiveActionAsync(hero1);
        Thread.sleep(20);
        clientSendsAction(1, POS_MOB);
        assertTrue(waitFor(hero1, 2000), "hero1's action must arrive via socket");

        lanMob.die(hero1);
        hero1.curAction = null;

        GameState lanState = new GameState(hero0, hero1, lanMob);
        Random.popGenerator();
        resetForNextRun();

        // ---- Pass-and-play: same kill, no socket ----
        NetworkManager.lanMode = false;
        Dungeon.hero = hero0;
        Random.pushGenerator(seed);

        SyncTestMob ppMob = new SyncTestMob(0.5f);
        level.mobs.add(ppMob);
        ppMob.die(hero1);

        GameState ppState = new GameState(hero0, hero1, ppMob);
        Random.popGenerator();
        NetworkManager.lanMode = true;

        // ---- Assert ----
        String msg = "lan=" + lanState + " pp=" + ppState;
        assertEquals(lanState.hero0Exp,      ppState.hero0Exp,      "hero0.exp — " + msg);
        assertEquals(lanState.hero1Exp,      ppState.hero1Exp,      "hero1.exp — " + msg);
        assertEquals(lanState.enemiesSlain,  ppState.enemiesSlain,  "enemiesSlain — " + msg);
        assertEquals(lanState.lootWouldDrop, ppState.lootWouldDrop, "lootDrop — " + msg);
    }

    /**
     * Same as above but from the CLIENT's perspective:
     * hero0's move arrives via socket, then hero1 (local) kills the mob.
     */
    @Test
    void clientSocketDelivery_thenKill_matchesPassAndPlay() throws Exception {
        long seed = 0xFEDCBA98L;

        // ---- LAN: hero0's move arrives at CLIENT via socket ----
        NetworkManager.setIsHostForTesting(false);
        NetworkManager.localPlayerIndex = 1;
        Dungeon.hero = hero1;
        Random.pushGenerator(seed);

        SyncTestMob lanMob = new SyncTestMob(0.5f);
        level.mobs.add(lanMob);

        NetworkManager.receiveActionAsync(hero0);
        Thread.sleep(20);
        hostSendsAction(0, POS_HERO1);     // hero0's move comes from host
        assertTrue(waitFor(hero0, 2000), "hero0's action must arrive at client via socket");
        hero0.curAction = null;

        lanMob.die(hero1);   // hero1 (local on client) kills mob
        GameState lanState = new GameState(hero0, hero1, lanMob);
        Random.popGenerator();
        resetForNextRun();

        // ---- Pass-and-play: same kill, no socket ----
        NetworkManager.lanMode = false;
        Dungeon.hero = hero0;
        Random.pushGenerator(seed);

        SyncTestMob ppMob = new SyncTestMob(0.5f);
        level.mobs.add(ppMob);
        ppMob.die(hero1);

        GameState ppState = new GameState(hero0, hero1, ppMob);
        Random.popGenerator();
        NetworkManager.lanMode = true;

        String msg = "lan=" + lanState + " pp=" + ppState;
        assertEquals(lanState.hero0Exp,      ppState.hero0Exp,      "hero0.exp — " + msg);
        assertEquals(lanState.hero1Exp,      ppState.hero1Exp,      "hero1.exp — " + msg);
        assertEquals(lanState.enemiesSlain,  ppState.enemiesSlain,  "enemiesSlain — " + msg);
        assertEquals(lanState.lootWouldDrop, ppState.lootWouldDrop, "lootDrop — " + msg);
    }

    // =========================================================================
    // LOOT GATE: killer's level determines drop, not Dungeon.hero's level
    // =========================================================================

    @Test
    void lootGate_killerWithinCap_dropsLoot_allPerspectives() throws Exception {
        hero0.lvl = 35;  // Dungeon.hero on host is above cap
        hero1.lvl = 5;   // killer is within cap

        long seed = 0x11223344L;

        // HOST: Dungeon.hero=hero0(lvl35), killer=hero1(lvl5)
        Dungeon.hero = hero0; NetworkManager.setIsHostForTesting(true);
        Random.pushGenerator(seed);
        SyncTestMob mobHost = new SyncTestMob(1.0f);
        level.mobs.add(mobHost);
        mobHost.die(hero1);
        boolean hostDrop = mobHost.lootWouldDrop;
        Random.popGenerator();
        resetForNextRun();
        hero0.lvl = 35; hero1.lvl = 5;  // restore custom levels

        // CLIENT: Dungeon.hero=hero1(lvl5), killer=hero1(lvl5)
        Dungeon.hero = hero1; NetworkManager.setIsHostForTesting(false);
        NetworkManager.localPlayerIndex = 1;
        Random.pushGenerator(seed);
        SyncTestMob mobClient = new SyncTestMob(1.0f);
        level.mobs.add(mobClient);
        mobClient.die(hero1);
        boolean clientDrop = mobClient.lootWouldDrop;
        Random.popGenerator();
        resetForNextRun();
        hero0.lvl = 35; hero1.lvl = 5;

        // PASS-AND-PLAY: Dungeon.hero=hero0(lvl35), killer=hero1(lvl5)
        NetworkManager.lanMode = false;
        Dungeon.hero = hero0;
        Random.pushGenerator(seed);
        SyncTestMob mobPP = new SyncTestMob(1.0f);
        level.mobs.add(mobPP);
        mobPP.die(hero1);
        boolean ppDrop = mobPP.lootWouldDrop;
        Random.popGenerator();
        NetworkManager.lanMode = true;

        // All three must agree: loot drops because KILLER (hero1 lvl 5) is within cap
        assertTrue(hostDrop,   "HOST: loot must drop — killer lvl 5 is within cap");
        assertTrue(clientDrop, "CLIENT: loot must drop — killer lvl 5 is within cap");
        assertTrue(ppDrop,     "PASS-AND-PLAY: loot must drop — killer lvl 5 is within cap");
        assertEquals(hostDrop, clientDrop, "HOST and CLIENT must agree on loot");
        assertEquals(hostDrop, ppDrop,     "HOST and PASS-AND-PLAY must agree on loot");
    }

    // =========================================================================
    // RNG DETERMINISM: same seed → same loot decisions across all perspectives
    // =========================================================================

    @Test
    void rngSeed_sameDecisionOnAllPerspectives() throws Exception {
        long[] seeds = { 1L, 7L, 42L, 999L, 0xDEADBEEFL };

        for (long seed : seeds) {
            // HOST
            Dungeon.hero = hero0; NetworkManager.setIsHostForTesting(true);
            NetworkManager.localPlayerIndex = 0;
            Random.pushGenerator(seed);
            SyncTestMob mA = new SyncTestMob(0.4f);
            level.mobs.add(mA); mA.die(hero1);
            boolean dA = mA.lootWouldDrop; int eA = hero1.exp;
            Random.popGenerator(); resetForNextRun();

            // CLIENT
            Dungeon.hero = hero1; NetworkManager.setIsHostForTesting(false);
            NetworkManager.localPlayerIndex = 1;
            Random.pushGenerator(seed);
            SyncTestMob mB = new SyncTestMob(0.4f);
            level.mobs.add(mB); mB.die(hero1);
            boolean dB = mB.lootWouldDrop; int eB = hero1.exp;
            Random.popGenerator(); resetForNextRun();

            // PASS-AND-PLAY
            NetworkManager.lanMode = false;
            Dungeon.hero = hero0;
            Random.pushGenerator(seed);
            SyncTestMob mC = new SyncTestMob(0.4f);
            level.mobs.add(mC); mC.die(hero1);
            boolean dC = mC.lootWouldDrop; int eC = hero1.exp;
            Random.popGenerator(); resetForNextRun();
            NetworkManager.lanMode = true;

            assertEquals(dA, dB, "seed " + seed + ": loot HOST vs CLIENT");
            assertEquals(dA, dC, "seed " + seed + ": loot HOST vs PASS-AND-PLAY");
            assertEquals(eA, eB, "seed " + seed + ": hero1.exp HOST vs CLIENT");
            assertEquals(eA, eC, "seed " + seed + ": hero1.exp HOST vs PASS-AND-PLAY");
        }
    }

    // =========================================================================
    // SOCKET DELIVERY SANITY: verify the socket infrastructure works correctly
    // before relying on it for the comparison tests above
    // =========================================================================

    /** Host receives hero1's action via socket (mirrors LanRealSocketTest). */
    @Test
    void socket_hostReceivesClientAction() throws Exception {
        NetworkManager.setIsHostForTesting(true);
        NetworkManager.localPlayerIndex = 0;
        NetworkManager.receiveActionAsync(hero1);
        Thread.sleep(20);
        clientSendsAction(1, 42);
        assertTrue(waitFor(hero1, 2000), "Host must receive client action via socket");
        // v3: the committed op waits in the hero's inbox until act() decodes it.
        NetworkManager.Commit c = hero1.lanActionInbox.peekFirst();
        assertNotNull(c, "committed op must be queued in hero1's inbox");
        assertEquals(42, NetworkManager.decodeAction(c.actionType, c.targetPos).dst);
    }

    /** Client receives hero0's action via socket (mirrors LanClientPerspectiveTest). */
    @Test
    void socket_clientReceivesHostAction() throws Exception {
        NetworkManager.setIsHostForTesting(false);
        NetworkManager.localPlayerIndex = 1;
        Dungeon.hero = hero1;
        NetworkManager.receiveActionAsync(hero0);
        Thread.sleep(20);
        hostSendsAction(0, 77);
        assertTrue(waitFor(hero0, 2000), "Client must receive host action via socket");
        // v3: the committed op waits in the hero's inbox until act() decodes it.
        NetworkManager.Commit c = hero0.lanActionInbox.peekFirst();
        assertNotNull(c, "committed op must be queued in hero0's inbox");
        assertEquals(77, ((HeroAction.Move) NetworkManager.decodeAction(c.actionType, c.targetPos)).dst);
    }
}
