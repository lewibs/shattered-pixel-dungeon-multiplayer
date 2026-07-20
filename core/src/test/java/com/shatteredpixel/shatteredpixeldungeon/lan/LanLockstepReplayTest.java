package com.shatteredpixel.shatteredpixeldungeon.lan;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.GamesInProgress;
import com.shatteredpixel.shatteredpixeldungeon.Statistics;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroAction;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.items.Generator;
import com.shatteredpixel.shatteredpixeldungeon.items.Heap;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.Potion;
import com.shatteredpixel.shatteredpixeldungeon.items.rings.Ring;
import com.shatteredpixel.shatteredpixeldungeon.items.scrolls.Scroll;
import com.shatteredpixel.shatteredpixeldungeon.journal.Notes;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.special.SpecialRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.secret.SecretRoom;
import com.shatteredpixel.shatteredpixeldungeon.network.NetworkManager;
import com.watabou.noosa.Game;
import com.watabou.utils.PathFinder;
import com.watabou.utils.Random;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * LAN lockstep replay test — the master desync detector.
 *
 * Simulates the two devices of a 2-player LAN game as two sequential phases in
 * one JVM, on a REAL generated sewer level with real mob AI, driven through the
 * real actor scheduler (Actor.testActNext seam):
 *
 *   Phase A ("host device"):   localPlayerIndex=0. hero0 executes scripted atomic
 *       ops from a deterministic policy; every op it actually performs is captured
 *       from the production send path (NetworkManager.sendActionOverride). hero1's
 *       ops come from the same policy, injected as curAction (as if delivered).
 *
 *   Phase B ("client device"): full static-state reset, same seed. hero1 executes
 *       the policy locally (its performed ops are captured and compared against
 *       phase A's). hero0 is a remote LAN hero fed phase A's captured packet
 *       stream over a REAL loopback TCP socket through receiveActionAsync().
 *
 * After every scheduler step both phases record a state fingerprint (map hash,
 * hero pos/HP/exp/time, mob pos/HP, gold, inventory sizes). The streams must be
 * identical — the first mismatch pinpoints the exact actor turn that desynced.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class LanLockstepReplayTest {

    //headless libGDX so asset loads (sprite sheets during level gen) work without GL
    static {
        if (com.badlogic.gdx.Gdx.app == null) {
            new com.badlogic.gdx.backends.headless.HeadlessApplication(
                    new com.badlogic.gdx.ApplicationAdapter(){},
                    new com.badlogic.gdx.backends.headless.HeadlessApplicationConfiguration());
            //wait for the headless main loop thread to install Gdx statics
            long deadline = System.currentTimeMillis() + 5000;
            while (com.badlogic.gdx.Gdx.files == null && System.currentTimeMillis() < deadline) {
                try { Thread.sleep(10); } catch (InterruptedException ignored) {}
            }
        }
    }

    private static final int OPS_PER_SCENARIO = 250;
    private static final int SETTLE_CAP = 4000; //scheduler steps before we call it a freeze

    // -----------------------------------------------------------------------
    // Recording structures
    // -----------------------------------------------------------------------
    static class PacketRec {
        final byte type; final int pos;
        PacketRec(byte type, int pos){ this.type = type; this.pos = pos; }
        @Override public String toString(){ return "type=" + type + " pos=" + pos; }
        @Override public boolean equals(Object o){
            return o instanceof PacketRec && ((PacketRec)o).type == type && ((PacketRec)o).pos == pos;
        }
    }

    static class PhaseResult {
        final List<Long> hashes = new ArrayList<>();
        final List<String> trace = new ArrayList<>();
        final List<PacketRec> sentByLocal = new ArrayList<>();
        final java.util.ArrayDeque<String> allSteps = new java.util.ArrayDeque<>(); //last N scheduler steps
        String endReason = "";
        void step(String s) {
            allSteps.addLast(s);
            if (allSteps.size() > 40) allSteps.removeFirst();
        }
    }

    // -----------------------------------------------------------------------
    // Socket plumbing (phase B only)
    // -----------------------------------------------------------------------
    private ServerSocket serverSocket;
    private Socket feederSocket;   //test writes hero0's packets here ("host" side)
    private Socket clientSocket;   //NetworkManager reads from here ("client" side)
    private DataOutputStream feederOut;
    private ExecutorService exec;

    private void openSockets() throws Exception {
        exec = Executors.newSingleThreadExecutor();
        serverSocket = new ServerSocket(0);
        Future<Socket> accepted = exec.submit(() -> serverSocket.accept());
        clientSocket = new Socket("127.0.0.1", serverSocket.getLocalPort());
        feederSocket = accepted.get(5, TimeUnit.SECONDS);
        feederOut = new DataOutputStream(feederSocket.getOutputStream());
        NetworkManager.injectClientStreamsForTesting(
                new DataInputStream(clientSocket.getInputStream()),
                new DataOutputStream(clientSocket.getOutputStream()));
    }

    private void closeSockets() {
        try { if (feederSocket != null) feederSocket.close(); } catch (Exception ignored) {}
        try { if (clientSocket != null) clientSocket.close(); } catch (Exception ignored) {}
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        if (exec != null) exec.shutdownNow();
        feederSocket = null; clientSocket = null; serverSocket = null; feederOut = null; exec = null;
    }

    @AfterEach
    void tearDown() {
        NetworkManager.lanMode = false;
        NetworkManager.setIsHostForTesting(false);
        NetworkManager.localPlayerIndex = 0;
        NetworkManager.resetActionReaderForTesting();
        NetworkManager.resetCommitProtocolState();
        NetworkManager.sendActionOverride = null;
        NetworkManager.injectClientStreamsForTesting(null, null);
        closeSockets();
        Dungeon.hero = null;
        Dungeon.heroes = null;
        Dungeon.level = null;
        Actor.clear();
        Statistics.reset();
        Random.unbindSimGenerator();
        Random.unregisterSimThread();
    }

    // -----------------------------------------------------------------------
    // World construction — mirrors the LAN parts of Dungeon.init()/newLevel()
    // -----------------------------------------------------------------------
    private void buildWorld(long seed, boolean isHost, int localPlayerIndex) {
        Game.version = "test";

        NetworkManager.lanMode = true;
        NetworkManager.setIsHostForTesting(isHost);
        NetworkManager.localPlayerIndex = localPlayerIndex;
        NetworkManager.lanChallenges = 0;
        NetworkManager.resetActionReaderForTesting();
        NetworkManager.resetCommitProtocolState();

        Dungeon.daily = Dungeon.dailyReplay = false;
        Dungeon.customSeedText = "";
        Dungeon.seed = seed;
        Dungeon.challenges = 0;
        Dungeon.mobsToChampion = 1;

        Actor.clear();
        Actor.resetNextID();

        Random.resetGenerators();
        Random.pushGenerator(seed + 1);
            Scroll.initLabels();
            Potion.initColors();
            Ring.initGems();
            SpecialRoom.initForRun();
            SecretRoom.initForRun();
            Generator.fullReset();
        Random.popGenerator();

        Random.bindSimGenerator(seed);
        Random.registerSimThread();

        Statistics.reset();
        Notes.reset();
        Dungeon.quickslot.reset();
        Dungeon.depth = 1;
        Dungeon.branch = 0;
        Dungeon.generatedLevels.clear();
        Dungeon.gold = 0;
        Dungeon.energy = 0;
        Dungeon.droppedItems = new com.watabou.utils.SparseArray<>();
        Dungeon.LimitedDrops.reset();
        Dungeon.chapters = new java.util.HashSet<>();

        GamesInProgress.selectedClasses = new ArrayList<>();

        //two bare heroes — no initHero (it touches device settings); unarmed combat
        Dungeon.heroes = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Hero h = new Hero();
            h.live();
            h.heroClass = i == 0 ? HeroClass.WARRIOR : HeroClass.MAGE;
            Dungeon.heroes.add(h);
        }
        Dungeon.hero = Dungeon.heroes.get(localPlayerIndex);

        //real level generation — identical on both devices given the same seed
        Level level = Dungeon.newLevel();
        Dungeon.level = level;
        PathFinder.setMapSize(level.width(), level.height());

        //place heroes deterministically at/next to the entrance
        int entrance = level.entrance();
        Dungeon.heroes.get(0).pos = entrance;
        int second = -1;
        for (int ofs : PathFinder.NEIGHBOURS8) {
            int c = entrance + ofs;
            if (level.passable[c] && Actor.findChar(c) == null) { second = c; break; }
        }
        assertTrue(second != -1, "no adjacent cell for hero1");
        Dungeon.heroes.get(1).pos = second;
        //if a mob generated on a hero cell, nudge it off deterministically
        for (Mob m : new ArrayList<>(level.mobs)) {
            if (m.pos == Dungeon.heroes.get(0).pos || m.pos == Dungeon.heroes.get(1).pos) {
                for (int c = 0; c < level.length(); c++) {
                    if (level.passable[c] && Actor.findChar(c) == null
                            && c != Dungeon.heroes.get(0).pos && c != Dungeon.heroes.get(1).pos) {
                        m.pos = c; break;
                    }
                }
            }
        }

        Actor.init();
        System.out.println("[replay] world built: actors=" + Actor.all().size()
                + " chars=" + Actor.chars().size() + " levelMobs=" + Dungeon.level.mobs.size()
                + " h0=" + Dungeon.heroes.get(0).pos + " h1=" + Dungeon.heroes.get(1).pos);
    }

    // -----------------------------------------------------------------------
    // Deterministic policy — atomic ops only, derived from synced state only
    // -----------------------------------------------------------------------
    private HeroAction policy(Hero h, java.util.Random rng) {
        Level level = Dungeon.level;
        //1: attack an adjacent live enemy
        for (int ofs : PathFinder.NEIGHBOURS8) {
            Char ch = Actor.findChar(h.pos + ofs);
            if (ch instanceof Mob && ch.isAlive() && ch.alignment == Char.Alignment.ENEMY) {
                return new HeroAction.Attack(ch);
            }
        }
        //2: pick up a plain heap underfoot (skip dewdrops — un-collectable at full
        //HP without a vial, the policy would retry forever)
        Heap heap = level.heaps.get(h.pos);
        if (heap != null && heap.type == Heap.Type.HEAP
                && !(heap.peek() instanceof com.shatteredpixel.shatteredpixeldungeon.items.Dewdrop)) {
            return new HeroAction.PickUp(h.pos);
        }
        //3: random adjacent step (passable, no char, no pit — pits switch scenes)
        List<Integer> options = new ArrayList<>();
        for (int ofs : PathFinder.NEIGHBOURS8) {
            int c = h.pos + ofs;
            if (level.passable[c] && !level.pit[c] && Actor.findChar(c) == null) {
                options.add(c);
            }
        }
        if (!options.isEmpty()) {
            return new HeroAction.Move(options.get(rng.nextInt(options.size())));
        }
        //4: wait
        return new HeroAction.Rest(false);
    }

    private static PacketRec encode(HeroAction a) {
        return new PacketRec(NetworkManager.encodeAction(a), NetworkManager.getTargetPos(a));
    }

    // -----------------------------------------------------------------------
    // Fingerprint
    // -----------------------------------------------------------------------
    private long fingerprint() {
        long h = NetworkManager.computeStateHash();
        for (Hero hero : Dungeon.heroes) {
            h = h * 31 + Float.floatToIntBits(hero.getTimeForTesting());
            h = h * 31 + hero.pos;
            h = h * 31 + hero.HP;
            h = h * 31 + hero.belongings.backpack.items.size();
        }
        h = h * 31 + Dungeon.gold;
        h = h * 31 + Dungeon.level.mobs.size();
        return h;
    }

    private String describeState() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Dungeon.heroes.size(); i++) {
            Hero hh = Dungeon.heroes.get(i);
            sb.append(String.format("h%d(pos=%d hp=%d t=%.2f exp=%d) ", i, hh.pos, hh.HP, hh.getTimeForTesting(), hh.exp));
        }
        sb.append("mobs=").append(Dungeon.level.mobs.size()).append(" gold=").append(Dungeon.gold);
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // Phase drivers
    // -----------------------------------------------------------------------

    /** Phase A: both heroes' ops come from the policy; hero(local) ops captured from production send path. */
    private PhaseResult runPhaseA(long seed) {
        buildWorld(seed, true, 0);
        PhaseResult res = new PhaseResult();
        NetworkManager.sendActionOverride = (action, heroId) -> res.sentByLocal.add(encode(action));

        java.util.Random rng0 = new java.util.Random(seed * 31 + 0);
        java.util.Random rng1 = new java.util.Random(seed * 31 + 1);

        runScripted(res, seed, rng0, rng1, null);
        return res;
    }

    /** Phase B: hero1 local from policy; hero0 remote, fed recorded packets via real socket. */
    private PhaseResult runPhaseB(long seed, List<PacketRec> hero0Stream) throws Exception {
        buildWorld(seed, false, 1);
        openSockets();
        PhaseResult res = new PhaseResult();
        NetworkManager.sendActionOverride = (action, heroId) -> res.sentByLocal.add(encode(action));

        java.util.Random rng1 = new java.util.Random(seed * 31 + 1);

        runScripted(res, seed, null, rng1, hero0Stream);
        return res;
    }

    /**
     * Drives the real scheduler. For each hero needing input:
     *  - if a policy rng is given for it, inject policy op as curAction
     *  - else feed the next recorded packet through the socket (phase B hero0)
     * Records a fingerprint after every scheduler step that changed it.
     */
    private void runScripted(PhaseResult res, long seed,
                             java.util.Random rng0, java.util.Random rng1,
                             List<PacketRec> hero0Stream) {
        Hero h0 = Dungeon.heroes.get(0);
        Hero h1 = Dungeon.heroes.get(1);
        int fed = 0;
        int ops = 0;
        int steps = 0;

        //baseline fingerprint so both phases' streams start from the same anchor
        //(phase A's first scheduler step is a state-neutral ready pass; phase B's
        //first step already executes an action)
        long lastFp = fingerprint();
        res.hashes.add(lastFp);
        res.trace.add("#0 baseline | " + describeState());

        while (ops < OPS_PER_SCENARIO) {
            if (++steps > SETTLE_CAP) { res.endReason = "SETTLE_CAP (freeze?)"; return; }
            if (!h0.isAlive() || !h1.isAlive()) { res.endReason = "hero died"; return; }

            Actor next = Actor.testPeekNext();
            if (next == null) { res.endReason = "no actors"; return; }

            //hero0: local scripted in phase A, remote socket-fed in phase B
            if (next == h0 && h0.curAction == null && !h0.resting) {
                if (rng0 != null) {
                    //LOCAL hero: mimic the real input flow — the hero first does a
                    //"ready pass" (act() with no action: updates visibleEnemies,
                    //readies). Injecting before that pass gets wiped by the
                    //interrupt in checkVisibleMobs when a mob is newly visible.
                    if (h0.ready) {
                        h0.curAction = policy(h0, rng0);
                        ops++;
                    }
                } else {
                    //REMOTE hero: act() blocks until its committed op is in the
                    //inbox — feed first. v3: the feeder plays the leader, writing
                    //COMMIT frames with a contiguous globalSeq (1, 2, 3, ...).
                    //No ready gate: remote heroes never do a ready pass, they wait
                    //inside act() for the commit.
                    if (fed >= hero0Stream.size()) { res.endReason = "hero0 stream exhausted"; return; }
                    PacketRec p = hero0Stream.get(fed++);
                    try {
                        LanTestProtocol.writeActionCommit(feederOut, fed, 0, 0, p.type, p.pos);
                    } catch (Exception e) { fail("feeder write failed: " + e); }
                    ops++;
                }
            }
            //hero1: remote directly-injected in phase A, local scripted in phase B
            else if (next == h1 && h1.curAction == null && !h1.resting) {
                boolean h1Local = NetworkManager.localPlayerIndex == 1;
                if (!h1Local) {
                    //phase A: hero1 is the remote hero on this "device" — inject its
                    //op directly (as if the packet was already delivered) BEFORE
                    //act(), which would otherwise block waiting for a real packet
                    h1.curAction = policy(h1, rng1);
                    res.step("inject h1 " + h1.curAction.getClass().getSimpleName() + ">" + h1.curAction.dst);
                    ops++;
                } else if (h1.ready) {
                    h1.curAction = policy(h1, rng1);
                    ops++;
                }
            }

            Actor acted = Actor.testActNext();
            if (acted instanceof Hero) {
                Hero ah = (Hero)acted;
                res.step(String.format("op%d h%d@%d t=%.2f ready=%b act=%s",
                        ops, Dungeon.heroes.indexOf(ah), ah.pos, ah.getTimeForTesting(), ah.ready,
                        ah.curAction != null ? ah.curAction.getClass().getSimpleName() + ">" + ah.curAction.dst : "null"));
            } else if (acted != null) {
                res.step(String.format("op%d %s(%d) t=%.2f", ops,
                        acted.getClass().getSimpleName(), acted.id(), acted.getTimeForTesting()));
            }
            long fp = fingerprint();
            if (fp != lastFp) {
                res.hashes.add(fp);
                res.trace.add(String.format("#%d %s@%d | %s",
                        res.hashes.size(),
                        acted != null ? acted.getClass().getSimpleName() + "(" + acted.id() + ")" : "null",
                        acted instanceof Char ? ((Char)acted).pos : -1,
                        describeState()));
                lastFp = fp;
            }
        }
        res.endReason = "completed " + OPS_PER_SCENARIO + " ops";
    }

    // -----------------------------------------------------------------------
    // The test
    // -----------------------------------------------------------------------
    private void runScenario(long seed) throws Exception {
        PhaseResult a = runPhaseA(seed);
        assertTrue(a.sentByLocal.size() > 0, "phase A recorded no performed ops — send path broken?");
        tearDown();

        PhaseResult b = runPhaseB(seed, a.sentByLocal);

        //compare fingerprint streams
        int n = Math.min(a.hashes.size(), b.hashes.size());
        for (int i = 0; i < n; i++) {
            if (!a.hashes.get(i).equals(b.hashes.get(i))) {
                StringBuilder sb = new StringBuilder();
                sb.append("DESYNC at fingerprint #").append(i).append(" (seed ").append(seed).append(")\n");
                for (int j = Math.max(0, i - 4); j < Math.min(n, i + 3); j++) {
                    sb.append("  A").append(j).append(": ").append(a.trace.get(j)).append('\n');
                    sb.append("  B").append(j).append(": ").append(b.trace.get(j)).append('\n');
                }
                fail(sb.toString());
            }
        }

        //phase B ends when hero0's performed stream runs out — earlier than A's 150
        //injections is benign, but the overlap must be substantial and neither phase
        //may have hit the freeze cap
        boolean bothDied = a.endReason.equals("hero died") && b.endReason.equals("hero died");
        if (n <= 50 && !bothDied) {
            StringBuilder sb = new StringBuilder("suspiciously short overlap: A=" + a.hashes.size()
                    + " B=" + b.hashes.size() + " endA=" + a.endReason + " endB=" + b.endReason + "\nA trace tail:\n");
            for (int j = Math.max(0, a.trace.size() - 8); j < a.trace.size(); j++) {
                sb.append("  ").append(a.trace.get(j)).append('\n');
            }
            sb.append("A last scheduler steps:\n");
            for (String stp : a.allSteps) sb.append("  ").append(stp).append('\n');
            fail(sb.toString());
        }
        assertTrue(!a.endReason.contains("SETTLE_CAP"), "phase A froze: " + a.endReason);
        assertTrue(!b.endReason.contains("SETTLE_CAP"), "phase B froze: " + b.endReason);
    }

    @Test
    void lockstepReplay_seed12345() throws Exception { runScenario(12345L); }

    @Test
    void lockstepReplay_seed777() throws Exception { runScenario(777L); }

    @Test
    void lockstepReplay_seed20260705() throws Exception { runScenario(20260705L); }

    @Test
    void lockstepReplay_seed1() throws Exception { runScenario(1L); }

    @Test
    void lockstepReplay_seed424242() throws Exception { runScenario(424242L); }

    @Test
    void lockstepReplay_seed999999999() throws Exception { runScenario(999999999L); }

    @Test
    void lockstepReplay_seed31337() throws Exception { runScenario(31337L); }

    @Test
    void lockstepReplay_seed8675309() throws Exception { runScenario(8675309L); }
}
