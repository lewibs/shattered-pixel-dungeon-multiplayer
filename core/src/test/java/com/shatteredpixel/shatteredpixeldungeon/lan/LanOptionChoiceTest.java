package com.shatteredpixel.shatteredpixeldungeon.lan;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.Statistics;
import com.shatteredpixel.shatteredpixeldungeon.actors.Actor;
import com.shatteredpixel.shatteredpixeldungeon.actors.Char;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.network.NetworkManager;
import com.watabou.noosa.Game;
import com.watabou.utils.PathFinder;
import com.watabou.utils.Random;
import org.junit.jupiter.api.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OPTION_CHOICE protocol: mid-action option dialogs (WndOptions, quest reward
 * windows) broadcast the picked index; remote devices resolve their parked copy
 * of the dialog from the packet — with the deterministic sim RNG and with
 * remoteItemExecution set so nested prompts park too.
 */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class LanOptionChoiceTest {

    static final int W = 10, H = 6;

    static class FlatLevel extends Level {
        FlatLevel() {
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
            secret      = new boolean[length];
            transitions = new ArrayList<>();
            mobs        = new java.util.LinkedHashSet<>();
            heaps       = new com.watabou.utils.SparseArray<>();
            blobs       = new java.util.LinkedHashMap<>();
            plants      = new com.watabou.utils.SparseArray<>();
            traps       = new com.watabou.utils.SparseArray<>();
            customTiles = new ArrayList<>();
            customWalls = new ArrayList<>();
            Arrays.fill(passable, true);
            PathFinder.setMapSize(W, H);
        }
        @Override protected boolean build()    { return true; }
        @Override protected void createMobs()  {}
        @Override protected void createItems() {}
        @Override public int entrance()        { return W + 1; }
        @Override public void updateFieldOfView(Char c, boolean[] fov) { Arrays.fill(fov, true); }
    }

    static class TestHero extends Hero {
        TestHero(HeroClass cls, int startPos) {
            heroClass = cls;
            HP = HT = 20;
            pos = startPos;
            actPriority = HERO_PRIO;
        }
        @Override protected boolean moveSprite(int from, int to) { return true; }
        @Override public void checkVisibleMobs() {}
    }

    private ServerSocket serverSocket;
    private Socket hostSideSocket, clientSocket;
    private DataInputStream hostIn, clientIn;
    private DataOutputStream hostOut, clientOut;

    TestHero hero0, hero1;

    @BeforeEach
    void setUp() throws IOException {
        Game.version = "test";
        PathFinder.setMapSize(W, H);
        Statistics.reset();
        Random.resetGenerators();
        Random.unbindSimGenerator();
        Random.unregisterSimThread();

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

        hero0 = new TestHero(HeroClass.WARRIOR, W + 1);
        hero1 = new TestHero(HeroClass.MAGE, W + 5);
        Dungeon.heroes = new ArrayList<>();
        Dungeon.heroes.add(hero0);
        Dungeon.heroes.add(hero1);
        Dungeon.hero = hero0;
        Dungeon.level = new FlatLevel();

        Actor.clear();
        Actor.addDelayed(hero0, 0);
        Actor.addDelayed(hero1, 0);

        NetworkManager.lanMode = true;
        NetworkManager.setIsHostForTesting(false); // this device is the client
        NetworkManager.localPlayerIndex = 0;       // hero1 is remote here
        NetworkManager.resetActionReaderForTesting();
        NetworkManager.resetCommitProtocolState();
        NetworkManager.sendActionOverride = (a, h) -> {};
    }

    @AfterEach
    void tearDown() throws IOException {
        NetworkManager.sendActionOverride = null;
        NetworkManager.lanMode = false;
        NetworkManager.setIsHostForTesting(false);
        NetworkManager.localPlayerIndex = 0;
        NetworkManager.pendingRemoteOptionHandler = null;
        NetworkManager.resetActionReaderForTesting();
        NetworkManager.resetCommitProtocolState();
        Random.unbindSimGenerator();
        if (clientSocket   != null && !clientSocket.isClosed())   clientSocket.close();
        if (hostSideSocket != null && !hostSideSocket.isClosed()) hostSideSocket.close();
        if (serverSocket   != null && !serverSocket.isClosed())   serverSocket.close();
        NetworkManager.injectHostStreamsForTesting(null, null);
        NetworkManager.injectClientStreamsForTesting(null, null);
        Dungeon.heroes = null;
        Dungeon.hero = null;
        Dungeon.level = null;
        Actor.clear();
        Statistics.reset();
    }

    @Test
    void optionChoicePacket_resolvesParkedHandler_inSimContext() throws Exception {
        Random.bindSimGenerator(999L);
        long simStateBefore = Random.getSimGeneratorState();

        CountDownLatch resolved = new CountDownLatch(1);
        int[] received = { Integer.MIN_VALUE };
        boolean[] remoteFlagDuring = { false };

        NetworkManager.pendingRemoteOptionHandler = choice -> {
            received[0] = choice;
            remoteFlagDuring[0] = NetworkManager.remoteItemExecution;
            Random.Int(1000); // the choice's effect draws gameplay RNG
            resolved.countDown();
        };

        // reader waits for packets addressed to the remote hero
        NetworkManager.receiveActionAsync(hero1);

        // the owning device (remote hero1 = player 1) broadcasts choice #2 as a
        // leader-sequenced commit
        LanTestProtocol.writeChoiceCommit(hostOut, 1, 1, NetworkManager.InnerOp.OPTION_CHOICE, 2);

        assertTrue(resolved.await(5, TimeUnit.SECONDS), "handler must be resolved by the packet");
        assertEquals(2, received[0], "handler must receive the broadcast option index");
        assertTrue(remoteFlagDuring[0],
                "remoteItemExecution must be set during resolution so nested prompts park");
        assertNotEquals(simStateBefore, Random.getSimGeneratorState(),
                "the choice effect must draw from the deterministic sim generator");
        assertNull(NetworkManager.pendingRemoteOptionHandler, "handler slot must be cleared");
    }

    @Test
    void resolveLocalChoice_wrapsEffectInSimContextAndLocalExecution() {
        Random.bindSimGenerator(4242L);
        long before = Random.getSimGeneratorState();
        boolean[] localFlagDuring = { false };

        NetworkManager.resolveLocalChoice(() -> {
            localFlagDuring[0] = NetworkManager.localItemExecution;
            Random.Int(1000);
        });

        assertTrue(localFlagDuring[0],
                "localItemExecution must be set during owner-side choice resolution");
        assertFalse(NetworkManager.localItemExecution, "flag must be restored afterwards");
        assertNotEquals(before, Random.getSimGeneratorState(),
                "owner-side choice effect must draw from the deterministic sim generator");
    }
}
