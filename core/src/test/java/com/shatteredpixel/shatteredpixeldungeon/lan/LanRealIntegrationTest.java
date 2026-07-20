package com.shatteredpixel.shatteredpixeldungeon.lan;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.Statistics;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroAction;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Rat;
import com.shatteredpixel.shatteredpixeldungeon.items.Gold;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
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
 * True integration sync test. Uses real Rat mobs with live AI — they hunt heroes,
 * move through pathfinding, and attack using Random.NormalIntRange() for damage rolls
 * and Char.attack() for hit/miss checks. Mob turns are driven after every hero action
 * so RNG is consumed exactly as it would be in a real game loop.
 *
 * A fixed SPD seed is pushed for every mode run. The action sequence is pre-generated
 * using a separate java.util.Random (not SPD's Random) so it is stable. The sequence is
 * replayed identically in LAN-host and LAN-client modes. GameState — including each mob's
 * individual HP — is compared after every single step.
 *
 * If any code path consumes RNG differently in LAN vs PnP (or calls Random an extra
 * time, or skips a call that PnP makes), hero HP, mob HP, or EXP will diverge and
 * the test fails immediately at that step naming the action that caused it.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class LanRealIntegrationTest {

    // 16-wide × 10-tall — heroes and mobs have room to move; boundary cells
    // are impassable so NEIGHBOURS9 in Dungeon.observe() never goes OOB.
    static final int W = 16, H = 10;
    static final int START0 = W + 2;   // row 1, col 2
    static final int START1 = W + 13;  // row 1, col 13

    // -----------------------------------------------------------------------
    // Flat level — all interior cells passable, all cells visible
    // -----------------------------------------------------------------------
    static class RealLevel extends Level {
        RealLevel() {
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
            secret       = new boolean[length];
            discoverable = new boolean[length];
            transitions = new ArrayList<>();
            mobs        = new java.util.HashSet<>();
            heaps       = new com.watabou.utils.SparseArray<>();
            blobs       = new java.util.HashMap<>();
            plants      = new com.watabou.utils.SparseArray<>();
            traps       = new com.watabou.utils.SparseArray<>();
            customTiles = new ArrayList<>();
            customWalls = new ArrayList<>();

            Arrays.fill(passable, true);
            Arrays.fill(heroFOV,  true);
            Arrays.fill(visited,  true);
            Arrays.fill(mapped,   true);
            // Mark boundary row/col impassable so NEIGHBOURS9 never goes OOB
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
        // All cells pre-marked visible; skip ShadowCaster to avoid boundary OOB
        @Override public void updateFieldOfView(Char c, boolean[] fov) {
            Arrays.fill(fov, true);
        }
    }

    // -----------------------------------------------------------------------
    // RealHero: full Hero.act() with sprite stubs
    // -----------------------------------------------------------------------
    static class RealHero extends Hero {
        RealHero(HeroClass cls, int startPos) {
            heroClass = cls;
            HP = HT    = 5000; // rats deal max 4 dmg; 5 rats × 120 steps = 2400 max total
            pos        = startPos;
            actPriority = HERO_PRIO;
        }
        @Override protected boolean moveSprite(int from, int to) { return true; }
        @Override public void checkVisibleMobs() {}
    }

    // -----------------------------------------------------------------------
    // RealMob: Rat with real AI, real attack/damage RNG, sprite stubs.
    //
    // Uses Random.NormalIntRange(1,4) for damage and Char.attack() for hit/miss —
    // the same RNG calls as in a live game. If LAN and PnP consume RNG differently
    // the mob HP or hero HP will diverge and the test catches it.
    // -----------------------------------------------------------------------
    static class RealMob extends Rat {
        RealMob(int pos) {
            this.pos = pos;
            HP = HT  = 12; // survives a few hits so mobs are around for multiple turns
            state    = HUNTING; // start hunting immediately — no sleeping phase
        }
        @Override protected boolean moveSprite(int from, int to) { return true; }
        // Widen access so runMobTurns() can call it from the test package
        @Override public boolean act() { return super.act(); }
    }

    // -----------------------------------------------------------------------
    // RngItem: item that calls Random.Int() on use so RNG divergence is caught
    // -----------------------------------------------------------------------
    static class RngItem extends Item {
        static final String AC_USE = "USE";
        RngItem() { image = 0; stackable = false; }
        @Override public String defaultAction() { return AC_USE; }
        @Override public boolean isUpgradable() { return false; }
        @Override public boolean isIdentified() { return true; }
        @Override public void execute(Hero hero, String action) {
            super.execute(hero, action);
            if (AC_USE.equals(action)) {
                // Consume RNG — if modes diverge, the resulting HP will differ
                int roll = Random.Int(1, 5);
                hero.HP = Math.max(1, hero.HP - roll);
                detach(hero.belongings.backpack);
                hero.spendAndNext(Actor.TICK);
            }
        }
    }

    // -----------------------------------------------------------------------
    // GameState snapshot — includes every mob's HP keyed by spawn position
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
        final int   totalMobHp;    // sum of all living mob HP — catches damage RNG divergence

        GameState(Hero h0, Hero h1, Set<Mob> mobs) {
            pos0  = h0.pos;  pos1  = h1.pos;
            time0 = h0.getTimeForTesting(); time1 = h1.getTimeForTesting();
            hp0   = h0.HP;   hp1   = h1.HP;
            exp0  = h0.exp;  exp1  = h1.exp;
            gold  = Dungeon.gold;
            enemiesSlain   = Statistics.enemiesSlain;
            backpackSize0  = h0.belongings.backpack.items.size();
            backpackSize1  = h1.belongings.backpack.items.size();
            activeMobs     = mobs.size();
            int hp = 0;
            for (Mob m : mobs) hp += m.HP;
            totalMobHp = hp;
        }

        void assertEquals(GameState o, String label) {
            String ctx = "\n  [expected] " + this + "\n  [actual]   " + o;
            org.junit.jupiter.api.Assertions.assertEquals(pos0,  o.pos0,  label + " hero0.pos"    + ctx);
            org.junit.jupiter.api.Assertions.assertEquals(pos1,  o.pos1,  label + " hero1.pos"    + ctx);
            org.junit.jupiter.api.Assertions.assertEquals(hp0,   o.hp0,   label + " hero0.hp"     + ctx);
            org.junit.jupiter.api.Assertions.assertEquals(hp1,   o.hp1,   label + " hero1.hp"     + ctx);
            org.junit.jupiter.api.Assertions.assertEquals(exp0,  o.exp0,  label + " hero0.exp"    + ctx);
            org.junit.jupiter.api.Assertions.assertEquals(exp1,  o.exp1,  label + " hero1.exp"    + ctx);
            org.junit.jupiter.api.Assertions.assertEquals(gold,  o.gold,  label + " gold"         + ctx);
            org.junit.jupiter.api.Assertions.assertEquals(enemiesSlain, o.enemiesSlain, label + " slain"    + ctx);
            org.junit.jupiter.api.Assertions.assertEquals(backpackSize0, o.backpackSize0, label + " bag0"   + ctx);
            org.junit.jupiter.api.Assertions.assertEquals(backpackSize1, o.backpackSize1, label + " bag1"   + ctx);
            org.junit.jupiter.api.Assertions.assertEquals(activeMobs,    o.activeMobs,    label + " mobs"   + ctx);
            org.junit.jupiter.api.Assertions.assertEquals(totalMobHp,    o.totalMobHp,    label + " mobHp"  + ctx);
            org.junit.jupiter.api.Assertions.assertEquals(time0, o.time0, 0.001f, label + " hero0.time" + ctx);
            org.junit.jupiter.api.Assertions.assertEquals(time1, o.time1, 0.001f, label + " hero1.time" + ctx);
        }

        @Override public String toString() {
            return String.format(
                "h0(pos=%d t=%.1f hp=%d exp=%d bag=%d) h1(pos=%d t=%.1f hp=%d exp=%d bag=%d) gold=%d slain=%d mobs=%d mobHp=%d",
                pos0, time0, hp0, exp0, backpackSize0,
                pos1, time1, hp1, exp1, backpackSize1,
                gold, enemiesSlain, activeMobs, totalMobHp);
        }
    }

    // -----------------------------------------------------------------------
    // Socket infrastructure
    // -----------------------------------------------------------------------
    private ServerSocket     serverSocket;
    private Socket           hostSideSocket, clientSocket;
    private DataInputStream  hostIn, clientIn;
    private DataOutputStream hostOut, clientOut;

    private RealHero hero0, hero1;
    private RealLevel level;

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
        catch (Exception e) { throw new IOException("socket setup", e); }

        hostIn    = new DataInputStream(hostSideSocket.getInputStream());
        hostOut   = new DataOutputStream(hostSideSocket.getOutputStream());
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

    void buildWorld() {
        hero0 = new RealHero(HeroClass.WARRIOR, START0);
        hero1 = new RealHero(HeroClass.MAGE,    START1);

        Dungeon.heroes = new ArrayList<>();
        Dungeon.heroes.add(hero0);
        Dungeon.heroes.add(hero1);
        Dungeon.customSeedText = "";
        Dungeon.gold = 0;

        level = new RealLevel();
        Dungeon.level = level;
        Dungeon.hero  = hero0;

        Actor.clear();
        Actor.addDelayed(hero0, 0);
        Actor.addDelayed(hero1, 0);
    }

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
    // Rich level: real Rats adjacent to heroes (they will immediately hunt),
    // gold heaps, RngItems in backpacks
    // -----------------------------------------------------------------------

    // Fixed spawn positions — chosen so mobs start adjacent to each hero
    static final int MOB0A = W * 1 + 3;   // row 1, col 3  — next to hero0 (col 2)
    static final int MOB0B = W * 2 + 2;   // row 2, col 2  — diagonal from hero0
    static final int MOB1A = W * 1 + 12;  // row 1, col 12 — next to hero1 (col 13)
    static final int MOB1B = W * 2 + 13;  // row 2, col 13 — diagonal from hero1
    static final int MOB_MID = W * 5 + 8; // row 5, col 8  — middle of map

    static final int[] GOLD_POS  = { W * 3 + 4,  W * 3 + 11  };
    static final int[] CHEST_POS = { W * 7 + 4,  W * 7 + 11  };

    void setupRichLevel() {
        placeMob(MOB0A);
        placeMob(MOB0B);
        placeMob(MOB1A);
        placeMob(MOB1B);
        placeMob(MOB_MID);

        for (int p : GOLD_POS)  placeGold(p, 25);
        for (int p : CHEST_POS) placeChest(p);

        // RngItems in backpacks — their execute() uses Random so any RNG divergence
        // immediately shows up in hero HP after the item is used
        giveItem(hero0, new RngItem());
        giveItem(hero0, new RngItem());
        giveItem(hero1, new RngItem());
        giveItem(hero1, new RngItem());
    }

    RealMob placeMob(int pos) {
        RealMob mob = new RealMob(pos);
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

    Heap placeChest(int pos) {
        Heap h = new Heap();
        h.pos  = pos;
        h.type = Heap.Type.CHEST;
        h.drop(new Gold(1));
        level.heaps.put(pos, h);
        return h;
    }

    void giveItem(RealHero hero, Item item) {
        hero.belongings.backpack.items.add(item);
    }

    // -----------------------------------------------------------------------
    // Run all living mob turns. This is the key difference from the previous
    // test: mobs use real Rat AI, move through PathFinder, and attack heroes
    // using Random.NormalIntRange() for damage — same RNG as production.
    // -----------------------------------------------------------------------
    void runMobTurns() {
        // Sort by pos so every run iterates mobs in the same order regardless of
        // HashSet identity-hash ordering, keeping RNG consumption consistent.
        List<Mob> sorted = new ArrayList<>(level.mobs);
        sorted.sort(Comparator.comparingInt(m -> m.pos));
        for (Mob mob : sorted) {
            if (mob.HP > 0 && mob instanceof RealMob) {
                ((RealMob) mob).act();
            }
        }
    }

    // -----------------------------------------------------------------------
    // Action infrastructure (same socket helpers as LanFullGameSyncTest)
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

    // -- Local execution --

    void localMove(RealHero hero, int dst) {
        hero.curAction = new HeroAction.Move(dst);
        hero.act();
    }

    void localAttack(RealHero hero, Char target) {
        hero.curAction = new HeroAction.Attack(target);
        hero.act();
    }

    void localPickup(RealHero hero) {
        hero.curAction = new HeroAction.PickUp(hero.pos);
        hero.act();
    }

    void localOpenChest(RealHero hero, int chestPos) {
        hero.curAction = new HeroAction.OpenChest(chestPos);
        hero.act();
    }

    void localUseItem(RealHero hero, int slotIndex) {
        hero.curAction = new HeroAction.UseItem(0, slotIndex);
        hero.act();
    }

    // -- Remote actions (host receives from clientOut) --

    void remoteMoveHost(RealHero remote, int dst) throws Exception {
        remote.curAction = null;
        NetworkManager.receiveActionAsync(remote);
        Thread.sleep(15);
        sendPacket(clientOut, Dungeon.heroes.indexOf(remote), NetworkManager.ActionType.MOVE, dst);
        assertTrue(waitFor(remote, 2000), "HOST: must receive MOVE");
        // v3: the first act() decodes the commit from the inbox into curAction,
        // which then persists across the multi-step getCloser walk.
        do {
            if (!remote.act() && remote.pos != dst) break;
        } while (remote.curAction != null && remote.pos != dst);
    }

    void remoteAttackHost(RealHero remote, Char target) throws Exception {
        remote.curAction = null;
        NetworkManager.receiveActionAsync(remote);
        Thread.sleep(15);
        sendPacket(clientOut, Dungeon.heroes.indexOf(remote), NetworkManager.ActionType.ATTACK, target.pos);
        assertTrue(waitFor(remote, 2000), "HOST: must receive ATTACK");
        remote.act();
    }

    void remotePickupHost(RealHero remote) throws Exception {
        remote.curAction = null;
        NetworkManager.receiveActionAsync(remote);
        Thread.sleep(15);
        sendPacket(clientOut, Dungeon.heroes.indexOf(remote), NetworkManager.ActionType.PICKUP, remote.pos);
        assertTrue(waitFor(remote, 2000), "HOST: must receive PICKUP");
        remote.act();
    }

    void remoteOpenChestHost(RealHero remote, int chestPos) throws Exception {
        remote.curAction = null;
        NetworkManager.receiveActionAsync(remote);
        Thread.sleep(15);
        sendPacket(clientOut, Dungeon.heroes.indexOf(remote), NetworkManager.ActionType.OPEN_CHEST, chestPos);
        assertTrue(waitFor(remote, 2000), "HOST: must receive OPEN_CHEST");
        remote.act();
    }

    void remoteUseItemHost(RealHero remote, int slot) throws Exception {
        remote.curAction = null;
        NetworkManager.receiveActionAsync(remote);
        Thread.sleep(15);
        // Use the production wire encoding (bag<<28 | slot<<18 | actionIdx<<10)
        sendPacket(clientOut, Dungeon.heroes.indexOf(remote), NetworkManager.ActionType.USE_ITEM,
                NetworkManager.getTargetPos(new HeroAction.UseItem(0, slot)));
        assertTrue(waitFor(remote, 2000), "HOST: must receive USE_ITEM");
        remote.act();
    }

    // -- Remote actions (client receives from hostOut) --

    void remoteMoveClient(RealHero remote, int dst) throws Exception {
        remote.curAction = null;
        NetworkManager.receiveActionAsync(remote);
        Thread.sleep(15);
        sendPacket(hostOut, Dungeon.heroes.indexOf(remote), NetworkManager.ActionType.MOVE, dst);
        assertTrue(waitFor(remote, 2000), "CLIENT: must receive MOVE");
        // v3: the first act() decodes the commit from the inbox into curAction,
        // which then persists across the multi-step getCloser walk.
        do {
            if (!remote.act() && remote.pos != dst) break;
        } while (remote.curAction != null && remote.pos != dst);
    }

    void remoteAttackClient(RealHero remote, Char target) throws Exception {
        remote.curAction = null;
        NetworkManager.receiveActionAsync(remote);
        Thread.sleep(15);
        sendPacket(hostOut, Dungeon.heroes.indexOf(remote), NetworkManager.ActionType.ATTACK, target.pos);
        assertTrue(waitFor(remote, 2000), "CLIENT: must receive ATTACK");
        remote.act();
    }

    void remotePickupClient(RealHero remote) throws Exception {
        remote.curAction = null;
        NetworkManager.receiveActionAsync(remote);
        Thread.sleep(15);
        sendPacket(hostOut, Dungeon.heroes.indexOf(remote), NetworkManager.ActionType.PICKUP, remote.pos);
        assertTrue(waitFor(remote, 2000), "CLIENT: must receive PICKUP");
        remote.act();
    }

    void remoteOpenChestClient(RealHero remote, int chestPos) throws Exception {
        remote.curAction = null;
        NetworkManager.receiveActionAsync(remote);
        Thread.sleep(15);
        sendPacket(hostOut, Dungeon.heroes.indexOf(remote), NetworkManager.ActionType.OPEN_CHEST, chestPos);
        assertTrue(waitFor(remote, 2000), "CLIENT: must receive OPEN_CHEST");
        remote.act();
    }

    void remoteUseItemClient(RealHero remote, int slot) throws Exception {
        remote.curAction = null;
        NetworkManager.receiveActionAsync(remote);
        Thread.sleep(15);
        // Use the production wire encoding (bag<<28 | slot<<18 | actionIdx<<10)
        sendPacket(hostOut, Dungeon.heroes.indexOf(remote), NetworkManager.ActionType.USE_ITEM,
                NetworkManager.getTargetPos(new HeroAction.UseItem(0, slot)));
        assertTrue(waitFor(remote, 2000), "CLIENT: must receive USE_ITEM");
        remote.act();
    }

    // -----------------------------------------------------------------------
    // Random action picker — queries live game state using seqRng (not SPD Random)
    // -----------------------------------------------------------------------

    enum SpecType { MOVE, ATTACK, PICKUP, OPEN_CHEST, USE_ITEM, SKIP }

    static class ActionSpec {
        final int heroIdx;
        final SpecType type;
        final int targetCell;
        final int slotIndex;

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

    Mob findMobAt(int pos) {
        for (Mob m : level.mobs) if (m.pos == pos && m.HP > 0) return m;
        return null;
    }

    ActionSpec pickAction(java.util.Random rng) {
        boolean alive0 = hero0.HP > 0, alive1 = hero1.HP > 0;
        if (!alive0 && !alive1) return new ActionSpec(0, SpecType.SKIP, -1, -1);

        int idx;
        if (!alive0) idx = 1; else if (!alive1) idx = 0; else idx = rng.nextInt(2);
        RealHero hero  = idx == 0 ? hero0 : hero1;
        RealHero other = idx == 0 ? hero1 : hero0;

        List<ActionSpec> valid = new ArrayList<>();

        for (int offset : PathFinder.NEIGHBOURS8) {
            int adj = hero.pos + offset;
            if (adj < 0 || adj >= W * H || !level.passable[adj]) continue;
            Mob  mob  = findMobAt(adj);
            Heap heap = level.heaps.get(adj);
            if (mob != null) {
                valid.add(new ActionSpec(idx, SpecType.ATTACK, adj, -1));
            } else if (adj != other.pos) {
                valid.add(new ActionSpec(idx, SpecType.MOVE, adj, -1));
            }
            if (heap != null && heap.type == Heap.Type.CHEST) {
                valid.add(new ActionSpec(idx, SpecType.OPEN_CHEST, adj, -1));
            }
        }

        Heap atPos = level.heaps.get(hero.pos);
        if (atPos != null && atPos.type == Heap.Type.HEAP) {
            valid.add(new ActionSpec(idx, SpecType.PICKUP, hero.pos, -1));
        }

        for (int i = 0; i < hero.belongings.backpack.items.size(); i++) {
            valid.add(new ActionSpec(idx, SpecType.USE_ITEM, -1, i));
        }

        if (valid.isEmpty()) return new ActionSpec(idx, SpecType.SKIP, -1, -1);
        return valid.get(rng.nextInt(valid.size()));
    }

    // -----------------------------------------------------------------------
    // Execute a spec locally (PnP / local hero in LAN), then run mob turns
    // -----------------------------------------------------------------------

    void execLocal(ActionSpec spec) {
        RealHero hero = spec.heroIdx == 0 ? hero0 : hero1;
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
        runMobTurns(); // real mob AI + RNG after every hero action
    }

    // Execute a spec as LAN host (hero0 local, hero1 remote)
    void execHost(ActionSpec spec) throws Exception {
        boolean local = spec.heroIdx == 0;
        RealHero hero = local ? hero0 : hero1;
        switch (spec.type) {
            case MOVE:
                if (local) { hero.curAction = new HeroAction.Move(spec.targetCell); hero.act(); }
                else          remoteMoveHost(hero1, spec.targetCell);
                break;
            case ATTACK: {
                Mob mob = findMobAt(spec.targetCell);
                if (mob == null) break;
                if (local) { hero.curAction = new HeroAction.Attack(mob); hero.act(); }
                else          remoteAttackHost(hero1, mob);
                break;
            }
            case PICKUP:
                if (level.heaps.get(spec.targetCell) == null) break;
                if (local) { hero.curAction = new HeroAction.PickUp(spec.targetCell); hero.act(); }
                else          remotePickupHost(hero1);
                break;
            case OPEN_CHEST: {
                Heap h = level.heaps.get(spec.targetCell);
                if (h == null || h.type != Heap.Type.CHEST) break;
                if (local) { hero.curAction = new HeroAction.OpenChest(spec.targetCell); hero.act(); }
                else          remoteOpenChestHost(hero1, spec.targetCell);
                break;
            }
            case USE_ITEM:
                if (spec.slotIndex >= hero.belongings.backpack.items.size()) break;
                if (local) { hero.curAction = new HeroAction.UseItem(0, spec.slotIndex); hero.act(); }
                else          remoteUseItemHost(hero1, spec.slotIndex);
                break;
            case SKIP: break;
        }
        runMobTurns();
    }

    // Execute a spec as LAN client (hero1 local, hero0 remote)
    void execClient(ActionSpec spec) throws Exception {
        boolean local = spec.heroIdx == 1;
        RealHero hero = local ? hero1 : hero0;
        switch (spec.type) {
            case MOVE:
                if (local) { hero.curAction = new HeroAction.Move(spec.targetCell); hero.act(); }
                else          remoteMoveClient(hero0, spec.targetCell);
                break;
            case ATTACK: {
                Mob mob = findMobAt(spec.targetCell);
                if (mob == null) break;
                if (local) { hero.curAction = new HeroAction.Attack(mob); hero.act(); }
                else          remoteAttackClient(hero0, mob);
                break;
            }
            case PICKUP:
                if (level.heaps.get(spec.targetCell) == null) break;
                if (local) { hero.curAction = new HeroAction.PickUp(spec.targetCell); hero.act(); }
                else          remotePickupClient(hero0);
                break;
            case OPEN_CHEST: {
                Heap h = level.heaps.get(spec.targetCell);
                if (h == null || h.type != Heap.Type.CHEST) break;
                if (local) { hero.curAction = new HeroAction.OpenChest(spec.targetCell); hero.act(); }
                else          remoteOpenChestClient(hero0, spec.targetCell);
                break;
            }
            case USE_ITEM:
                if (spec.slotIndex >= hero.belongings.backpack.items.size()) break;
                if (local) { hero.curAction = new HeroAction.UseItem(0, spec.slotIndex); hero.act(); }
                else          remoteUseItemClient(hero0, spec.slotIndex);
                break;
            case SKIP: break;
        }
        runMobTurns();
    }

    GameState snap() {
        return new GameState(hero0, hero1, level.mobs);
    }

    // -----------------------------------------------------------------------
    // THE TEST
    //
    // Phase 1: generate action sequence in PnP with real mobs running after
    //          every hero action. Record GameState after each step.
    //
    // Phase 2: replay exact sequence in LAN-host mode; mobs run again.
    //          Compare GameState after every step.
    //
    // Phase 3: same replay in LAN-client mode; compare per-step.
    //
    // If any code path consumes RNG differently in LAN vs PnP — an extra call,
    // a skipped call, wrong ordering — hero HP or mob HP will diverge and the
    // test fails immediately at that step with the action that caused it.
    // -----------------------------------------------------------------------

    @Test
    void realGame_withLiveRats_neverDesyncs() throws Exception {
        final long SPD_SEED = 0xFACEFEED_ABCD1234L;
        final long SEQ_SEED = 999L;
        final int  MAX_STEPS = 120;

        // ---- Phase 1: PnP — generate sequence + reference states ----
        NetworkManager.lanMode = false;
        resetForMode(SPD_SEED);
        Dungeon.hero = hero0;
        setupRichLevel();

        java.util.Random seqRng = new java.util.Random(SEQ_SEED);
        List<ActionSpec> sequence = new ArrayList<>();
        List<GameState>  ppStates = new ArrayList<>();

        for (int step = 0; step < MAX_STEPS; step++) {
            if (hero0.HP <= 0 && hero1.HP <= 0) break;
            ActionSpec spec = pickAction(seqRng);
            execLocal(spec);
            sequence.add(spec);
            ppStates.add(snap());
        }
        popSeed();

        // Verify meaningful coverage: mobs must have attacked heroes at least once
        int totalDamageReceived = (2 * 50) - (ppStates.isEmpty() ? 100 :
                ppStates.get(ppStates.size()-1).hp0 + ppStates.get(ppStates.size()-1).hp1);
        assertTrue(totalDamageReceived > 0 || ppStates.get(ppStates.size()-1).enemiesSlain > 0,
                "Mobs should have attacked or been killed; hero HP delta=" + totalDamageReceived);

        // ---- Phase 2: LAN-host replay, compare after every step ----
        NetworkManager.lanMode = true;
        NetworkManager.setIsHostForTesting(true);
        NetworkManager.localPlayerIndex = 0;
        Dungeon.hero = hero0;
        resetForMode(SPD_SEED);
        setupRichLevel();

        for (int step = 0; step < sequence.size(); step++) {
            execHost(sequence.get(step));
            ppStates.get(step).assertEquals(snap(),
                    "HOST desync at step " + step + " after " + sequence.get(step) + ":");
        }
        popSeed();

        // ---- Phase 3: LAN-client replay, compare after every step ----
        NetworkManager.lanMode = true;
        NetworkManager.setIsHostForTesting(false);
        NetworkManager.localPlayerIndex = 1;
        Dungeon.hero = hero1;
        resetForMode(SPD_SEED);
        setupRichLevel();

        for (int step = 0; step < sequence.size(); step++) {
            execClient(sequence.get(step));
            ppStates.get(step).assertEquals(snap(),
                    "CLIENT desync at step " + step + " after " + sequence.get(step) + ":");
        }
        popSeed();

        long attacks  = sequence.stream().filter(s -> s.type == SpecType.ATTACK).count();
        long moves    = sequence.stream().filter(s -> s.type == SpecType.MOVE).count();
        long useItems = sequence.stream().filter(s -> s.type == SpecType.USE_ITEM).count();
        assertTrue(attacks + moves >= 10,
                "Expected real game coverage; attacks=" + attacks + " moves=" + moves
                        + " useItems=" + useItems + "\nSequence: " + sequence);
    }
}
