package com.shatteredpixel.shatteredpixeldungeon.lan;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.Statistics;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroAction;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.items.Gold;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.bags.Bag;
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
 * Full-game LAN vs pass-and-play sync test.
 *
 * Each scenario runs the same sequence of actions in three modes using the same
 * RNG seed, then compares a complete game-state snapshot. Any divergence signals
 * a desync bug.
 *
 * Covered actions:
 *   MOVE, ATTACK (kill + EXP), PICKUP (gold + item→inventory), OPEN_CHEST,
 *   USE_ITEM (from backpack), REST, and multi-round sequences mixing all of the above.
 *
 * Production changes required to reach this coverage:
 *   HeroAction.UseItem         — new action type encoding (bagOrdinal, slotIndex)
 *   NetworkManager.USE_ITEM    — wire protocol byte 11; encode/decode via targetPos
 *   Hero.actUseItem()          — finds item by bag+slot, calls item.execute()
 *   Hero.findItemForUse()      — bag lookup helper
 *   GameScene.cancel()         — null guard on cellSelector (headless safe)
 *   Item.onThrow()             — null guard on heap.sprite (headless safe)
 *   Heap.open()                — null guards on sprite/emitter (headless safe)
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class LanFullGameSyncTest {

    // -----------------------------------------------------------------------
    // Level geometry: 12-wide, 6-tall, fully passable.
    // hero0 starts left side (col 1), hero1 starts right side (col 10).
    // Plenty of room for movement + placing objects without collision.
    // -----------------------------------------------------------------------
    static final int W = 12, H = 6;
    static final int START0 = W + 1;    // row 1, col 1
    static final int START1 = W + 10;   // row 1, col 10

    // -----------------------------------------------------------------------
    // Minimal flat level
    // -----------------------------------------------------------------------
    static class MinimalLevel extends Level {
        MinimalLevel() {
            width = W; height = H; length = W * H;
            map         = new int[length];
            pit         = new boolean[length];
            passable    = new boolean[length];
            losBlocking = new boolean[length];
            solid       = new boolean[length];
            avoid       = new boolean[length];
            water       = new boolean[length];
            visited     = new boolean[length];
            mapped      = new boolean[length];
            heroFOV     = new boolean[length];
            transitions = new ArrayList<>();
            mobs        = new java.util.HashSet<>();
            heaps       = new com.watabou.utils.SparseArray<>();
            blobs       = new java.util.HashMap<>();
            plants      = new com.watabou.utils.SparseArray<>();
            traps       = new com.watabou.utils.SparseArray<>();
            customTiles = new ArrayList<>();
            customWalls = new ArrayList<>();

            secret     = new boolean[length];
            Arrays.fill(passable, true);
            Arrays.fill(heroFOV,  true);
            Arrays.fill(visited,  true);
            Arrays.fill(mapped,   true);
            // Mark boundary cells impassable so NEIGHBOURS9 in Dungeon.observe() never goes OOB
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
        // All cells are pre-marked visible; skip ShadowCaster to avoid boundary exceptions.
        @Override public void updateFieldOfView(com.shatteredpixel.shatteredpixeldungeon.actors.Char c, boolean[] fov) {
            Arrays.fill(fov, true);
        }
    }

    // -----------------------------------------------------------------------
    // TestHero: real Hero.act() with sprite calls stubbed out
    // -----------------------------------------------------------------------
    static class TestHero extends Hero {
        TestHero(HeroClass cls, int startPos) {
            heroClass = cls;
            HP = HT    = 30;
            pos        = startPos;
            actPriority = HERO_PRIO;
        }
        @Override protected boolean moveSprite(int from, int to) { return true; }
        @Override public void checkVisibleMobs() {}
    }

    // -----------------------------------------------------------------------
    // TestMob: idle mob that spends time and dies cleanly
    // -----------------------------------------------------------------------
    static class TestMob extends Mob {
        TestMob(int pos, int hp, int exp) {
            EXP        = exp;
            maxLvl     = 30;
            lootChance = 0f;
            alignment  = Char.Alignment.ENEMY;
            HP = HT    = hp;
            this.pos   = pos;
        }
        @Override protected boolean moveSprite(int from, int to) { return true; }
        @Override public boolean act() { spend(TICK); return true; }
    }

    // -----------------------------------------------------------------------
    // TestUsableItem: a plain backpack item that spends exactly 1 turn on use
    // -----------------------------------------------------------------------
    static class TestUsableItem extends Item {
        static final String AC_USE = "USE";
        TestUsableItem() { image = 0; stackable = false; }
        @Override public String defaultAction() { return AC_USE; }
        @Override public boolean isUpgradable() { return false; }
        @Override public boolean isIdentified() { return true;  }
        @Override public void execute(Hero hero, String action) {
            super.execute(hero, action);   // calls GameScene.cancel() — now null-safe
            if (AC_USE.equals(action)) {
                detach(hero.belongings.backpack);
                hero.spendAndNext(Actor.TICK);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Complete game-state snapshot for comparison
    // -----------------------------------------------------------------------
    static class GameState {
        final int   pos0,  pos1;
        final float time0, time1;
        final int   hp0,   hp1;
        final int   exp0,  exp1;
        final int   gold;
        final int   enemiesSlain;
        final int   backpackSize0, backpackSize1;
        final int   activeMobs;

        GameState(Hero h0, Hero h1) {
            pos0  = h0.pos;  pos1  = h1.pos;
            time0 = h0.getTimeForTesting(); time1 = h1.getTimeForTesting();
            hp0   = h0.HP;   hp1   = h1.HP;
            exp0  = h0.exp;  exp1  = h1.exp;
            gold  = Dungeon.gold;
            enemiesSlain   = Statistics.enemiesSlain;
            backpackSize0  = h0.belongings.backpack.items.size();
            backpackSize1  = h1.belongings.backpack.items.size();
            activeMobs     = Dungeon.level != null ? Dungeon.level.mobs.size() : 0;
        }

        void assertEquals(GameState other, String label) {
            String ctx = "\n  [this]  " + this + "\n  [other] " + other;
            org.junit.jupiter.api.Assertions.assertEquals(pos0,  other.pos0,  label + " hero0.pos"  + ctx);
            org.junit.jupiter.api.Assertions.assertEquals(pos1,  other.pos1,  label + " hero1.pos"  + ctx);
            org.junit.jupiter.api.Assertions.assertEquals(hp0,   other.hp0,   label + " hero0.hp"   + ctx);
            org.junit.jupiter.api.Assertions.assertEquals(hp1,   other.hp1,   label + " hero1.hp"   + ctx);
            org.junit.jupiter.api.Assertions.assertEquals(exp0,  other.exp0,  label + " hero0.exp"  + ctx);
            org.junit.jupiter.api.Assertions.assertEquals(exp1,  other.exp1,  label + " hero1.exp"  + ctx);
            org.junit.jupiter.api.Assertions.assertEquals(gold,  other.gold,  label + " gold"       + ctx);
            org.junit.jupiter.api.Assertions.assertEquals(enemiesSlain, other.enemiesSlain, label + " slain" + ctx);
            org.junit.jupiter.api.Assertions.assertEquals(backpackSize0, other.backpackSize0, label + " bag0" + ctx);
            org.junit.jupiter.api.Assertions.assertEquals(backpackSize1, other.backpackSize1, label + " bag1" + ctx);
            org.junit.jupiter.api.Assertions.assertEquals(activeMobs, other.activeMobs, label + " mobs" + ctx);
            org.junit.jupiter.api.Assertions.assertEquals(time0, other.time0, 0.001f, label + " hero0.time" + ctx);
            org.junit.jupiter.api.Assertions.assertEquals(time1, other.time1, 0.001f, label + " hero1.time" + ctx);
        }

        @Override public String toString() {
            return String.format(
                "h0(pos=%d t=%.1f hp=%d exp=%d bag=%d) h1(pos=%d t=%.1f hp=%d exp=%d bag=%d) gold=%d slain=%d mobs=%d",
                pos0, time0, hp0, exp0, backpackSize0,
                pos1, time1, hp1, exp1, backpackSize1,
                gold, enemiesSlain, activeMobs);
        }
    }

    // -----------------------------------------------------------------------
    // Socket infrastructure (reused from LanGameStateSyncTest pattern)
    // -----------------------------------------------------------------------
    private ServerSocket     serverSocket;
    private Socket           hostSideSocket, clientSocket;
    private DataInputStream  hostIn, clientIn;
    private DataOutputStream hostOut, clientOut;

    private TestHero hero0, hero1;
    private MinimalLevel level;

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

        hostIn  = new DataInputStream(hostSideSocket.getInputStream());
        hostOut = new DataOutputStream(hostSideSocket.getOutputStream());
        clientIn  = new DataInputStream(clientSocket.getInputStream());
        clientOut = new DataOutputStream(clientSocket.getOutputStream());
        NetworkManager.injectHostStreamsForTesting(hostIn, hostOut);
        NetworkManager.injectClientStreamsForTesting(clientIn, clientOut);

        buildWorld();

        NetworkManager.lanMode = true;
        NetworkManager.setIsHostForTesting(true);
        NetworkManager.localPlayerIndex = 0;
        NetworkManager.resetActionReaderForTesting();
        NetworkManager.resetCommitProtocolState();
        // Discard local heroes' performed-op sends so they never pollute the
        // shared loopback sockets and never block the actor thread on an echo.
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
        try { if (clientSocket   != null) clientSocket.close();   } catch (Exception ignored) {}
        try { if (hostSideSocket != null) hostSideSocket.close(); } catch (Exception ignored) {}
        try { if (serverSocket   != null) serverSocket.close();   } catch (Exception ignored) {}
        NetworkManager.injectHostStreamsForTesting(null, null);
        NetworkManager.injectClientStreamsForTesting(null, null);
        Dungeon.heroes = null;
        Dungeon.hero   = null;
        Dungeon.level  = null;
        Actor.clear();
        Statistics.reset();
    }

    /** Re-create heroes and level in a clean state. */
    void buildWorld() {
        hero0 = new TestHero(HeroClass.WARRIOR, START0);
        hero1 = new TestHero(HeroClass.MAGE,    START1);

        Dungeon.heroes = new ArrayList<>();
        Dungeon.heroes.add(hero0);
        Dungeon.heroes.add(hero1);
        Dungeon.customSeedText = "";
        Dungeon.gold = 0;

        level = new MinimalLevel();
        Dungeon.level = level;
        Dungeon.hero  = hero0;

        Actor.clear();
        Actor.addDelayed(hero0, 0);
        Actor.addDelayed(hero1, 0);
    }

    /** Rebuild the world then push a fixed seed so both runs are deterministic. */
    void resetForMode(long seed) {
        buildWorld();
        NetworkManager.resetActionReaderForTesting();
        Statistics.reset();
        Random.pushGenerator(seed);
    }

    void popSeed() {
        try { Random.popGenerator(); } catch (Exception ignored) {}
    }

    // -----------------------------------------------------------------------
    // Action helpers (local + remote via socket)
    // -----------------------------------------------------------------------

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

    // v3 sequence continuity: REQUESTs to the host device use a contiguous
    // clientSeq; COMMITs to the client device use a contiguous globalSeq seeded
    // from the leader's current counter (continuing past commits the host phase
    // already broadcast into the shared socket buffer).
    private int hostReqSeq = 0;
    private int clientCommitSeq = -1;

    void sendPacket(DataOutputStream out, int heroId, byte actionType, int payload)
            throws IOException {
        if (out == clientOut) { // fake client → host device: a REQUEST
            LanTestProtocol.writeActionRequest(out, ++hostReqSeq, actionType, payload);
        } else {                // fake leader → client device: a COMMIT
            if (clientCommitSeq < 0) clientCommitSeq = NetworkManager.getGlobalSeqForTesting();
            LanTestProtocol.writeActionCommit(out, ++clientCommitSeq, heroId, 0, actionType, payload);
        }
    }

    // --- Local actions ---

    void localMove(TestHero hero, int dst) {
        hero.curAction = new HeroAction.Move(dst);
        while (hero.curAction != null && hero.pos != dst) {
            if (!hero.act() && hero.pos != dst) break;
        }
    }

    void localAttack(TestHero hero, Char target) {
        hero.curAction = new HeroAction.Attack(target);
        hero.act();
    }

    void localPickup(TestHero hero) {
        hero.curAction = new HeroAction.PickUp(hero.pos);
        hero.act();
    }

    void localOpenChest(TestHero hero, int chestPos) {
        hero.curAction = new HeroAction.OpenChest(chestPos);
        hero.act();
    }

    void localUseItem(TestHero hero, int bagOrdinal, int slotIndex) {
        hero.curAction = new HeroAction.UseItem(bagOrdinal, slotIndex);
        hero.act();
    }

    void localRest(TestHero hero) {
        hero.resting   = true;
        hero.curAction = null;
        hero.act();
    }

    // --- Remote actions (host receives from client socket) ---

    void remoteMove_hostReceives(TestHero remote, int dst) throws Exception {
        // Per-step protocol: the owning device transmits each performed step as
        // an adjacent Move packet — remote heroes never pathfind. Walk the
        // straight line one packet per step, exactly as production now sends.
        while (remote.pos != dst) {
            int step = nextStepToward(remote.pos, dst);
            remote.curAction = null;
            NetworkManager.receiveActionAsync(remote);
            Thread.sleep(15);
            sendPacket(clientOut, Dungeon.heroes.indexOf(remote), NetworkManager.ActionType.MOVE, step);
            assertTrue(waitFor(remote, 2000), "HOST: remote hero must receive MOVE step");
            remote.act();
        }
        remote.curAction = null;
    }

    /** Straight-line next cell toward dst (diagonals allowed — matches shortest
     *  paths on the open MinimalLevel). */
    static int nextStepToward(int pos, int dst) {
        int row = pos / W, col = pos % W;
        int dstRow = dst / W, dstCol = dst % W;
        row += Integer.signum(dstRow - row);
        col += Integer.signum(dstCol - col);
        return row * W + col;
    }

    void remoteAttack_hostReceives(TestHero remote, Char target) throws Exception {
        remote.curAction = null;
        NetworkManager.receiveActionAsync(remote);
        Thread.sleep(15);
        sendPacket(clientOut, Dungeon.heroes.indexOf(remote), NetworkManager.ActionType.ATTACK, target.pos);
        assertTrue(waitFor(remote, 2000), "HOST: remote hero must receive ATTACK");
        remote.act();
    }

    void remotePickup_hostReceives(TestHero remote, int pos) throws Exception {
        remote.curAction = null;
        NetworkManager.receiveActionAsync(remote);
        Thread.sleep(15);
        sendPacket(clientOut, Dungeon.heroes.indexOf(remote), NetworkManager.ActionType.PICKUP, pos);
        assertTrue(waitFor(remote, 2000), "HOST: remote hero must receive PICKUP");
        remote.act();
    }

    void remoteOpenChest_hostReceives(TestHero remote, int chestPos) throws Exception {
        remote.curAction = null;
        NetworkManager.receiveActionAsync(remote);
        Thread.sleep(15);
        sendPacket(clientOut, Dungeon.heroes.indexOf(remote), NetworkManager.ActionType.OPEN_CHEST, chestPos);
        assertTrue(waitFor(remote, 2000), "HOST: remote hero must receive OPEN_CHEST");
        remote.act();
    }

    void remoteUseItem_hostReceives(TestHero remote, int bagOrdinal, int slotIndex) throws Exception {
        remote.curAction = null;
        NetworkManager.receiveActionAsync(remote);
        Thread.sleep(15);
        // Use the production wire encoding (bag<<28 | slot<<18 | actionIdx<<10)
        int payload = NetworkManager.getTargetPos(new HeroAction.UseItem(bagOrdinal, slotIndex));
        sendPacket(clientOut, Dungeon.heroes.indexOf(remote), NetworkManager.ActionType.USE_ITEM, payload);
        assertTrue(waitFor(remote, 2000), "HOST: remote hero must receive USE_ITEM");
        remote.act();
    }

    // --- Remote actions (client receives from host socket) ---

    void remoteMove_clientReceives(TestHero remote, int dst) throws Exception {
        // Per-step protocol — see remoteMove_hostReceives
        while (remote.pos != dst) {
            int step = nextStepToward(remote.pos, dst);
            remote.curAction = null;
            NetworkManager.receiveActionAsync(remote);
            Thread.sleep(15);
            sendPacket(hostOut, Dungeon.heroes.indexOf(remote), NetworkManager.ActionType.MOVE, step);
            assertTrue(waitFor(remote, 2000), "CLIENT: remote hero must receive MOVE step");
            remote.act();
        }
        remote.curAction = null;
    }

    void remoteAttack_clientReceives(TestHero remote, Char target) throws Exception {
        remote.curAction = null;
        NetworkManager.receiveActionAsync(remote);
        Thread.sleep(15);
        sendPacket(hostOut, Dungeon.heroes.indexOf(remote), NetworkManager.ActionType.ATTACK, target.pos);
        assertTrue(waitFor(remote, 2000), "CLIENT: remote hero must receive ATTACK");
        remote.act();
    }

    void remotePickup_clientReceives(TestHero remote, int pos) throws Exception {
        remote.curAction = null;
        NetworkManager.receiveActionAsync(remote);
        Thread.sleep(15);
        sendPacket(hostOut, Dungeon.heroes.indexOf(remote), NetworkManager.ActionType.PICKUP, pos);
        assertTrue(waitFor(remote, 2000), "CLIENT: remote hero must receive PICKUP");
        remote.act();
    }

    void remoteOpenChest_clientReceives(TestHero remote, int chestPos) throws Exception {
        remote.curAction = null;
        NetworkManager.receiveActionAsync(remote);
        Thread.sleep(15);
        sendPacket(hostOut, Dungeon.heroes.indexOf(remote), NetworkManager.ActionType.OPEN_CHEST, chestPos);
        assertTrue(waitFor(remote, 2000), "CLIENT: remote hero must receive OPEN_CHEST");
        remote.act();
    }

    void remoteUseItem_clientReceives(TestHero remote, int bagOrdinal, int slotIndex) throws Exception {
        remote.curAction = null;
        NetworkManager.receiveActionAsync(remote);
        Thread.sleep(15);
        // Use the production wire encoding (bag<<28 | slot<<18 | actionIdx<<10)
        int payload = NetworkManager.getTargetPos(new HeroAction.UseItem(bagOrdinal, slotIndex));
        sendPacket(hostOut, Dungeon.heroes.indexOf(remote), NetworkManager.ActionType.USE_ITEM, payload);
        assertTrue(waitFor(remote, 2000), "CLIENT: remote hero must receive USE_ITEM");
        remote.act();
    }

    // -----------------------------------------------------------------------
    // World setup helpers
    // -----------------------------------------------------------------------

    TestMob placeMob(int pos, int hp, int exp) {
        TestMob mob = new TestMob(pos, hp, exp);
        Actor.addDelayed(mob, 0);
        level.mobs.add(mob);
        return mob;
    }

    Heap placeGold(int pos, int amount) {
        Heap h = new Heap();
        h.pos = pos;
        h.drop(new Gold(amount));
        level.heaps.put(pos, h);
        return h;
    }

    Heap placeItem(int pos, Item item) {
        Heap h = new Heap();
        h.pos = pos;
        h.drop(item);
        level.heaps.put(pos, h);
        return h;
    }

    Heap placeChest(int pos) {
        Heap h = new Heap();
        h.pos  = pos;
        h.type = Heap.Type.CHEST;
        h.drop(new Gold(1));
        level.heaps.put(pos, h);
        return h;
    }

    /** Add an item directly into hero's backpack (no floor heap needed). */
    void giveItem(TestHero hero, Item item) {
        hero.belongings.backpack.items.add(item);
        item.getClass(); // ensure item is non-null; no pickup callback needed in tests
    }

    // -----------------------------------------------------------------------
    // The three-mode runner
    //
    // runScenario() is a functional interface called three times:
    //   (A) pass-and-play    — lanMode=false, both heroes local
    //   (B) LAN host         — hero0 local, hero1 remote (packets from clientOut)
    //   (C) LAN client       — hero1 local, hero0 remote (packets from hostOut)
    //
    // After each run we capture a GameState and compare all three.
    // -----------------------------------------------------------------------

    @FunctionalInterface
    interface Scenario {
        /** true = act as LAN host (hero0 local), false = act as LAN client (hero1 local). */
        void run(boolean isLanHost) throws Exception;
    }

    /**
     * Execute the scenario in pass-and-play mode (lanMode=false) and return the resulting
     * GameState. Seed is pushed before the run and popped after.
     */
    GameState runPassPlay(long seed, Runnable scenario) {
        NetworkManager.lanMode = false;
        Dungeon.hero = hero0;
        resetForMode(seed);
        scenario.run();
        GameState s = new GameState(hero0, hero1);
        popSeed();
        return s;
    }

    /**
     * Execute the scenario as LAN host (hero0 local, hero1 remote via clientOut→hostIn).
     */
    GameState runLanHost(long seed, ThrowingRunnable scenario) throws Exception {
        NetworkManager.lanMode = true;
        NetworkManager.setIsHostForTesting(true);
        NetworkManager.localPlayerIndex = 0;
        Dungeon.hero = hero0;
        resetForMode(seed);
        scenario.run();
        GameState s = new GameState(hero0, hero1);
        popSeed();
        return s;
    }

    /**
     * Execute the scenario as LAN client (hero1 local, hero0 remote via hostOut→clientIn).
     */
    GameState runLanClient(long seed, ThrowingRunnable scenario) throws Exception {
        NetworkManager.lanMode = true;
        NetworkManager.setIsHostForTesting(false);
        NetworkManager.localPlayerIndex = 1;
        Dungeon.hero = hero1;
        resetForMode(seed);
        scenario.run();
        GameState s = new GameState(hero0, hero1);
        popSeed();
        return s;
    }

    @FunctionalInterface interface ThrowingRunnable { void run() throws Exception; }

    // -----------------------------------------------------------------------
    // Random-sequence infrastructure
    // -----------------------------------------------------------------------

    enum SpecType { MOVE, ATTACK, PICKUP, OPEN_CHEST, USE_ITEM, SKIP }

    static class ActionSpec {
        final int heroIdx;
        final SpecType type;
        final int targetCell; // MOVE=dst, ATTACK/OPEN_CHEST=target cell, PICKUP=hero's pos
        final int slotIndex;  // USE_ITEM only

        ActionSpec(int heroIdx, SpecType type, int targetCell, int slotIndex) {
            this.heroIdx = heroIdx; this.type = type;
            this.targetCell = targetCell; this.slotIndex = slotIndex;
        }

        @Override public String toString() {
            if (type == SpecType.USE_ITEM) return "hero" + heroIdx + " USE_ITEM slot=" + slotIndex;
            if (type == SpecType.SKIP)     return "hero" + heroIdx + " SKIP";
            return "hero" + heroIdx + " " + type + " cell=" + targetCell;
        }
    }

    // Rich level: 3 mobs, 2 gold heaps, 2 chests, 2 floor items, 2 backpack items each hero
    static final int[] RAND_MOB_POS    = { W*2+2, W*2+9, W*3+6 };
    static final int[] RAND_GOLD_POS   = { W+3,   W+8            };
    static final int[] RAND_CHEST_POS  = { W*4+3, W*4+8          };
    static final int[] RAND_ITEM_POS   = { W*3+2, W*3+9          };
    static final int   RAND_MOB_HP     = 8;
    static final int   RAND_MOB_EXP    = 5;

    void setupRichLevel() {
        for (int p : RAND_MOB_POS)  placeMob(p, RAND_MOB_HP, RAND_MOB_EXP);
        for (int p : RAND_GOLD_POS) placeGold(p, 20);
        for (int p : RAND_CHEST_POS) placeChest(p);
        for (int p : RAND_ITEM_POS)  placeItem(p, new TestUsableItem());
        giveItem(hero0, new TestUsableItem());
        giveItem(hero0, new TestUsableItem());
        giveItem(hero1, new TestUsableItem());
        giveItem(hero1, new TestUsableItem());
    }

    Mob findMobAt(int pos) {
        if (level == null) return null;
        for (Mob mob : level.mobs) {
            if (mob.pos == pos && mob.HP > 0) return mob;
        }
        return null;
    }

    /** Pick a valid random action for one hero using seqRng (independent of SPD RNG). */
    ActionSpec pickRandomAction(java.util.Random rng) {
        boolean alive0 = hero0.HP > 0, alive1 = hero1.HP > 0;
        if (!alive0 && !alive1) return new ActionSpec(0, SpecType.SKIP, -1, -1);

        int heroIdx;
        if (!alive0) heroIdx = 1;
        else if (!alive1) heroIdx = 0;
        else heroIdx = rng.nextInt(2);
        TestHero hero  = heroIdx == 0 ? hero0 : hero1;
        TestHero other = heroIdx == 0 ? hero1 : hero0;

        List<ActionSpec> valid = new ArrayList<>();

        for (int offset : PathFinder.NEIGHBOURS8) {
            int adj = hero.pos + offset;
            if (adj < 0 || adj >= W * H || !level.passable[adj]) continue;

            Mob  mob  = findMobAt(adj);
            Heap heap = level.heaps.get(adj);

            if (mob != null) {
                valid.add(new ActionSpec(heroIdx, SpecType.ATTACK, adj, -1));
            } else if (adj != other.pos) {
                valid.add(new ActionSpec(heroIdx, SpecType.MOVE, adj, -1));
            }
            if (heap != null && heap.type == Heap.Type.CHEST) {
                valid.add(new ActionSpec(heroIdx, SpecType.OPEN_CHEST, adj, -1));
            }
        }

        Heap atPos = level.heaps.get(hero.pos);
        if (atPos != null && atPos.type == Heap.Type.HEAP) {
            valid.add(new ActionSpec(heroIdx, SpecType.PICKUP, hero.pos, -1));
        }

        for (int i = 0; i < hero.belongings.backpack.items.size(); i++) {
            valid.add(new ActionSpec(heroIdx, SpecType.USE_ITEM, -1, i));
        }

        if (valid.isEmpty()) return new ActionSpec(heroIdx, SpecType.SKIP, -1, -1);
        return valid.get(rng.nextInt(valid.size()));
    }

    void executeLocalSpec(ActionSpec spec) {
        TestHero hero = spec.heroIdx == 0 ? hero0 : hero1;
        switch (spec.type) {
            case MOVE:
                hero.curAction = new HeroAction.Move(spec.targetCell);
                hero.act();
                break;
            case ATTACK: {
                Mob mob = findMobAt(spec.targetCell);
                if (mob == null) break;
                hero.curAction = new HeroAction.Attack(mob);
                hero.act();
                break;
            }
            case PICKUP:
                if (level.heaps.get(spec.targetCell) == null) break;
                hero.curAction = new HeroAction.PickUp(spec.targetCell);
                hero.act();
                break;
            case OPEN_CHEST: {
                Heap h = level.heaps.get(spec.targetCell);
                if (h == null || h.type != Heap.Type.CHEST) break;
                hero.curAction = new HeroAction.OpenChest(spec.targetCell);
                hero.act();
                break;
            }
            case USE_ITEM:
                if (spec.slotIndex >= hero.belongings.backpack.items.size()) break;
                hero.curAction = new HeroAction.UseItem(0, spec.slotIndex);
                hero.act();
                break;
            case SKIP: break;
        }
    }

    void executeLanHostSpec(ActionSpec spec) throws Exception {
        boolean local = spec.heroIdx == 0; // host: hero0 is local
        TestHero hero  = local ? hero0 : hero1;
        switch (spec.type) {
            case MOVE:
                if (local) { hero.curAction = new HeroAction.Move(spec.targetCell); hero.act(); }
                else          remoteMove_hostReceives(hero1, spec.targetCell);
                break;
            case ATTACK: {
                Mob mob = findMobAt(spec.targetCell);
                if (mob == null) break;
                if (local) { hero.curAction = new HeroAction.Attack(mob); hero.act(); }
                else          remoteAttack_hostReceives(hero1, mob);
                break;
            }
            case PICKUP:
                if (level.heaps.get(spec.targetCell) == null) break;
                if (local) { hero.curAction = new HeroAction.PickUp(spec.targetCell); hero.act(); }
                else          remotePickup_hostReceives(hero1, spec.targetCell);
                break;
            case OPEN_CHEST: {
                Heap h = level.heaps.get(spec.targetCell);
                if (h == null || h.type != Heap.Type.CHEST) break;
                if (local) { hero.curAction = new HeroAction.OpenChest(spec.targetCell); hero.act(); }
                else          remoteOpenChest_hostReceives(hero1, spec.targetCell);
                break;
            }
            case USE_ITEM:
                if (spec.slotIndex >= hero.belongings.backpack.items.size()) break;
                if (local) { hero.curAction = new HeroAction.UseItem(0, spec.slotIndex); hero.act(); }
                else          remoteUseItem_hostReceives(hero1, 0, spec.slotIndex);
                break;
            case SKIP: break;
        }
    }

    void executeLanClientSpec(ActionSpec spec) throws Exception {
        boolean local = spec.heroIdx == 1; // client: hero1 is local
        TestHero hero  = local ? hero1 : hero0;
        switch (spec.type) {
            case MOVE:
                if (local) { hero.curAction = new HeroAction.Move(spec.targetCell); hero.act(); }
                else          remoteMove_clientReceives(hero0, spec.targetCell);
                break;
            case ATTACK: {
                Mob mob = findMobAt(spec.targetCell);
                if (mob == null) break;
                if (local) { hero.curAction = new HeroAction.Attack(mob); hero.act(); }
                else          remoteAttack_clientReceives(hero0, mob);
                break;
            }
            case PICKUP:
                if (level.heaps.get(spec.targetCell) == null) break;
                if (local) { hero.curAction = new HeroAction.PickUp(spec.targetCell); hero.act(); }
                else          remotePickup_clientReceives(hero0, spec.targetCell);
                break;
            case OPEN_CHEST: {
                Heap h = level.heaps.get(spec.targetCell);
                if (h == null || h.type != Heap.Type.CHEST) break;
                if (local) { hero.curAction = new HeroAction.OpenChest(spec.targetCell); hero.act(); }
                else          remoteOpenChest_clientReceives(hero0, spec.targetCell);
                break;
            }
            case USE_ITEM:
                if (spec.slotIndex >= hero.belongings.backpack.items.size()) break;
                if (local) { hero.curAction = new HeroAction.UseItem(0, spec.slotIndex); hero.act(); }
                else          remoteUseItem_clientReceives(hero0, 0, spec.slotIndex);
                break;
            case SKIP: break;
        }
    }

    // -----------------------------------------------------------------------
    // TESTS
    // -----------------------------------------------------------------------

    /**
     * Full-game action sequence covering every supported protocol action type:
     *   R1: hero0 moves right 1 cell (hero0.pos = START0+1)
     *   R2: hero1 moves left  2 cells (hero1.pos = START1-2)
     *   R3: hero0 attacks adjacent mob at START0+2 (1 HP → dies; EXP awarded)
     *   R4: hero0 picks up gold placed at hero0.pos
     *   R5: hero1 picks up a TestUsableItem placed at hero1.pos
     *   R6: hero0 opens a CHEST placed at START0+2 (mob's old cell)
     *   R7: hero1 uses the TestUsableItem from its backpack (USE_ITEM)
     *
     * Same RNG seed is used for all three modes. All fields of GameState must match.
     * Note: REST for remote heroes is tested separately; it is not in this sequence
     * because the LAN remote-hero wait loop requires a non-null curAction to unblock.
     */
    @Test
    void fullSequence_allActions_stateIdentical_allModes() throws Exception {
        final long SEED     = 0xDEADBEEFCAFEBABEL;
        final int  MOB_POS  = START0 + 2;   // adjacent to hero0 after R1
        final int  GOLD_AMT = 15;
        final int  MOB_EXP  = 4;

        // ---- (A) Pass-and-play ----
        NetworkManager.lanMode = false;
        resetForMode(SEED);
        Dungeon.hero = hero0;

        localMove(hero0, START0 + 1);                      // R1
        localMove(hero1, START1 - 2);                      // R2
        TestMob mob = placeMob(MOB_POS, 1, MOB_EXP);
        localAttack(hero0, mob);                           // R3 (mob HP=1: one-shots)
        placeGold(hero0.pos, GOLD_AMT);
        localPickup(hero0);                                // R4
        placeItem(hero1.pos, new TestUsableItem());
        localPickup(hero1);                                // R5
        placeChest(MOB_POS);                               // chest at mob's old cell
        localOpenChest(hero0, MOB_POS);                    // R6
        localUseItem(hero1, 0, 0);                         // R7

        GameState ppState = new GameState(hero0, hero1);
        popSeed();

        // ---- (B) LAN host: hero0 local, hero1 remote ----
        NetworkManager.lanMode = true;
        NetworkManager.setIsHostForTesting(true);
        NetworkManager.localPlayerIndex = 0;
        Dungeon.hero = hero0;
        resetForMode(SEED);

        localMove(hero0, START0 + 1);
        remoteMove_hostReceives(hero1, START1 - 2);
        mob = placeMob(MOB_POS, 1, MOB_EXP);
        localAttack(hero0, mob);
        placeGold(hero0.pos, GOLD_AMT);
        localPickup(hero0);
        placeItem(hero1.pos, new TestUsableItem());
        remotePickup_hostReceives(hero1, hero1.pos);
        placeChest(MOB_POS);
        localOpenChest(hero0, MOB_POS);
        remoteUseItem_hostReceives(hero1, 0, 0);

        GameState hostState = new GameState(hero0, hero1);
        popSeed();

        // ---- (C) LAN client: hero1 local, hero0 remote ----
        NetworkManager.lanMode = true;
        NetworkManager.setIsHostForTesting(false);
        NetworkManager.localPlayerIndex = 1;
        Dungeon.hero = hero1;
        resetForMode(SEED);

        remoteMove_clientReceives(hero0, START0 + 1);
        localMove(hero1, START1 - 2);
        mob = placeMob(MOB_POS, 1, MOB_EXP);
        remoteAttack_clientReceives(hero0, mob);
        placeGold(hero0.pos, GOLD_AMT);
        remotePickup_clientReceives(hero0, hero0.pos);
        placeItem(hero1.pos, new TestUsableItem());
        localPickup(hero1);
        placeChest(MOB_POS);
        remoteOpenChest_clientReceives(hero0, MOB_POS);
        localUseItem(hero1, 0, 0);

        GameState clientState = new GameState(hero0, hero1);
        popSeed();

        // ---- Assert all three modes are identical ----
        ppState.assertEquals(hostState,   "PP vs HOST:");
        ppState.assertEquals(clientState, "PP vs CLIENT:");

        // Sanity: effects actually happened
        assertTrue(ppState.enemiesSlain >= 1, "mob must have been killed");
        assertEquals(GOLD_AMT, ppState.gold,  "gold must have been picked up");
    }

    /**
     * USE_ITEM round-trip: place a TestUsableItem in hero0's backpack, use it.
     * Verifies that the item is consumed and time advances identically in all modes.
     */
    @Test
    void useItem_fromBackpack_consumedAndTimeAdvances_allModes() throws Exception {
        final long SEED = 0x1234567890ABCDEFL;

        // ---- (A) Pass-and-play ----
        NetworkManager.lanMode = false;
        resetForMode(SEED);
        Dungeon.hero = hero0;

        giveItem(hero0, new TestUsableItem());
        int sizeBefore = hero0.belongings.backpack.items.size();
        localUseItem(hero0, 0, 0);
        float ppTime0 = hero0.getTimeForTesting();
        int sizeAfterPP = hero0.belongings.backpack.items.size();
        popSeed();

        // ---- (B) LAN host ----
        NetworkManager.lanMode = true;
        NetworkManager.setIsHostForTesting(true);
        NetworkManager.localPlayerIndex = 0;
        Dungeon.hero = hero0;
        resetForMode(SEED);

        giveItem(hero0, new TestUsableItem());
        localUseItem(hero0, 0, 0);
        float hostTime0 = hero0.getTimeForTesting();
        int sizeAfterHost = hero0.belongings.backpack.items.size();
        popSeed();

        // ---- (C) LAN client: hero0 remote uses item ----
        NetworkManager.lanMode = true;
        NetworkManager.setIsHostForTesting(false);
        NetworkManager.localPlayerIndex = 1;
        Dungeon.hero = hero1;
        resetForMode(SEED);

        giveItem(hero0, new TestUsableItem());
        remoteUseItem_clientReceives(hero0, 0, 0);
        float clientTime0 = hero0.getTimeForTesting();
        int sizeAfterClient = hero0.belongings.backpack.items.size();
        popSeed();

        // ---- Assert ----
        assertEquals(ppTime0,      hostTime0,   0.001f, "hero0.time after USE_ITEM: PP vs HOST");
        assertEquals(ppTime0,      clientTime0, 0.001f, "hero0.time after USE_ITEM: PP vs CLIENT");
        assertEquals(sizeAfterPP,  sizeAfterHost,   "backpack size after use: PP vs HOST");
        assertEquals(sizeAfterPP,  sizeAfterClient, "backpack size after use: PP vs CLIENT");
        assertTrue(sizeAfterPP < sizeBefore, "item must have been consumed from backpack");
        assertTrue(ppTime0 > 0, "hero0 must have spent time using the item");
    }

    /**
     * Determinism check: running the same full sequence twice with the same seed
     * produces bit-identical results within pass-and-play mode.
     */
    @Test
    void determinism_sameSeedTwice_identicalState() {
        final long SEED = 0xFEEDFACEL;

        NetworkManager.lanMode = false;

        // Run 1
        resetForMode(SEED);
        Dungeon.hero = hero0;
        TestMob mob = placeMob(START0 + 1, 1, 3);
        localAttack(hero0, mob);
        placeGold(hero0.pos, 10);
        localPickup(hero0);
        localMove(hero1, START1 - 1);
        GameState run1 = new GameState(hero0, hero1);
        popSeed();

        // Run 2
        resetForMode(SEED);
        Dungeon.hero = hero0;
        mob = placeMob(START0 + 1, 1, 3);
        localAttack(hero0, mob);
        placeGold(hero0.pos, 10);
        localPickup(hero0);
        localMove(hero1, START1 - 1);
        GameState run2 = new GameState(hero0, hero1);
        popSeed();

        run1.assertEquals(run2, "DETERMINISM run1 vs run2:");
    }

    /**
     * USE_ITEM protocol round-trip: encode then decode must produce an identical action.
     */
    @Test
    void useItem_protocolRoundTrip() {
        for (int bag = 0; bag <= 3; bag++) {
            for (int slot = 0; slot < 10; slot++) {
                HeroAction.UseItem original = new HeroAction.UseItem(bag, slot);
                byte   encoded  = NetworkManager.encodeAction(original);
                int    pos      = NetworkManager.getTargetPos(original);
                HeroAction decoded = NetworkManager.decodeAction(encoded, pos);

                assertTrue(decoded instanceof HeroAction.UseItem,
                        "bag=" + bag + " slot=" + slot + ": decoded must be UseItem");
                HeroAction.UseItem u = (HeroAction.UseItem) decoded;
                assertEquals(bag,  u.bagOrdinal, "bag=" + bag + " slot=" + slot + ": bagOrdinal");
                assertEquals(slot, u.slotIndex,  "bag=" + bag + " slot=" + slot + ": slotIndex");
            }
        }
    }

    /**
     * Fuzz-style sync test: generate up to 100 random actions in pass-and-play mode
     * (driven by a fixed java.util.Random seed, not SPD RNG), then replay the exact
     * same action log in LAN-host and LAN-client modes with the same SPD seed.
     * GameState is compared after EVERY step so the first desync is pinpointed.
     *
     * Covered actions depend on what the random picker encounters:
     *   MOVE (to any adjacent passable cell), ATTACK (adjacent mob), PICKUP (at hero pos),
     *   OPEN_CHEST (adjacent chest heap), USE_ITEM (from backpack).
     *
     * The test fails as soon as any field (pos, hp, exp, gold, slain, bag size, time, mobs)
     * diverges between pass-and-play and either LAN mode.
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void randomSequence_manyActions_neverDesyncs() throws Exception {
        final long SPD_SEED  = 0xC0FFEE12345678L;
        final long SEQ_SEED  = 42L;
        final int  MAX_STEPS = 100;

        // ---- Phase 1: generate sequence and reference states in pass-and-play mode ----
        NetworkManager.lanMode = false;
        resetForMode(SPD_SEED);
        Dungeon.hero = hero0;
        setupRichLevel();

        java.util.Random seqRng = new java.util.Random(SEQ_SEED);
        List<ActionSpec> sequence = new ArrayList<>();
        List<GameState>  ppStates = new ArrayList<>();

        for (int step = 0; step < MAX_STEPS; step++) {
            if (hero0.HP <= 0 && hero1.HP <= 0) break;
            ActionSpec spec = pickRandomAction(seqRng);
            executeLocalSpec(spec);
            sequence.add(spec);
            ppStates.add(new GameState(hero0, hero1));
        }
        popSeed();

        // ---- Phase 2: replay in LAN-host mode, compare after every step ----
        NetworkManager.lanMode = true;
        NetworkManager.setIsHostForTesting(true);
        NetworkManager.localPlayerIndex = 0;
        Dungeon.hero = hero0;
        resetForMode(SPD_SEED);
        setupRichLevel();

        for (int step = 0; step < sequence.size(); step++) {
            executeLanHostSpec(sequence.get(step));
            ppStates.get(step).assertEquals(
                    new GameState(hero0, hero1),
                    "HOST desync at step " + step + " after " + sequence.get(step) + ":");
        }
        popSeed();

        // ---- Phase 3: replay in LAN-client mode, compare after every step ----
        NetworkManager.lanMode = true;
        NetworkManager.setIsHostForTesting(false);
        NetworkManager.localPlayerIndex = 1;
        Dungeon.hero = hero1;
        resetForMode(SPD_SEED);
        setupRichLevel();

        for (int step = 0; step < sequence.size(); step++) {
            executeLanClientSpec(sequence.get(step));
            ppStates.get(step).assertEquals(
                    new GameState(hero0, hero1),
                    "CLIENT desync at step " + step + " after " + sequence.get(step) + ":");
        }
        popSeed();

        // Sanity: verify meaningful coverage
        long attacks = sequence.stream().filter(s -> s.type == SpecType.ATTACK).count();
        long moves   = sequence.stream().filter(s -> s.type == SpecType.MOVE).count();
        long useItem = sequence.stream().filter(s -> s.type == SpecType.USE_ITEM).count();
        assertTrue(attacks + moves + useItem >= 20,
                "Expected ≥20 real actions; attacks=" + attacks + " moves=" + moves + " useItem=" + useItem
                + "\nFull sequence: " + sequence);
    }

    /**
     * Pass-and-play determinism test: run the same random action sequence twice with
     * the same SPD seed and java.util.Random seed, in pure PnP mode (no LAN at all).
     * Game state is compared after EVERY step between run 1 and run 2.
     *
     * If this fails it means PnP mode itself is non-deterministic — a prerequisite bug
     * that would make any LAN-vs-PnP sync comparison meaningless.
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void randomSequence_passAndPlay_deterministic() {
        final long SPD_SEED = 0xC0FFEE12345678L;
        final long SEQ_SEED = 42L;
        final int  MAX_STEPS = 100;

        // ---- Run 1: generate sequence and record per-step states ----
        NetworkManager.lanMode = false;
        resetForMode(SPD_SEED);
        Dungeon.hero = hero0;
        setupRichLevel();

        java.util.Random seqRng = new java.util.Random(SEQ_SEED);
        List<ActionSpec> sequence = new ArrayList<>();
        List<GameState>  run1States = new ArrayList<>();

        for (int step = 0; step < MAX_STEPS; step++) {
            if (hero0.HP <= 0 && hero1.HP <= 0) break;
            ActionSpec spec = pickRandomAction(seqRng);
            executeLocalSpec(spec);
            sequence.add(spec);
            run1States.add(new GameState(hero0, hero1));
        }
        popSeed();

        // ---- Run 2: replay exact same sequence and compare per-step ----
        NetworkManager.lanMode = false;
        resetForMode(SPD_SEED);
        Dungeon.hero = hero0;
        setupRichLevel();

        for (int step = 0; step < sequence.size(); step++) {
            executeLocalSpec(sequence.get(step));
            run1States.get(step).assertEquals(
                    new GameState(hero0, hero1),
                    "PnP non-determinism at step " + step + " after " + sequence.get(step) + ":");
        }
        popSeed();
    }

    /**
     * Protocol round-trip for every existing non-item action type (regression guard).
     */
    @Test
    void allOtherActions_protocolRoundTrip() {
        assertEquals(NetworkManager.ActionType.MOVE,      NetworkManager.encodeAction(new HeroAction.Move(42)));
        assertEquals(NetworkManager.ActionType.PICKUP,    NetworkManager.encodeAction(new HeroAction.PickUp(5)));
        assertEquals(NetworkManager.ActionType.OPEN_CHEST,NetworkManager.encodeAction(new HeroAction.OpenChest(7)));
        assertEquals(NetworkManager.ActionType.ATTACK,    NetworkManager.encodeAction(new HeroAction.Attack(null)));
        assertEquals(NetworkManager.ActionType.REST,      NetworkManager.encodeAction(null));
        assertEquals(NetworkManager.ActionType.MINE,      NetworkManager.encodeAction(new HeroAction.Mine(3)));
        assertEquals(NetworkManager.ActionType.ALCHEMY,   NetworkManager.encodeAction(new HeroAction.Alchemy(1)));
    }
}
