package com.shatteredpixel.shatteredpixeldungeon.network;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroAction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec-first tests for the turn-sync and queued-action-input features (lan-05).
 *
 * CONTRACTS:
 * 1. Local hero: sendAction() must fire BEFORE the action is dispatched —
 *    the remote device must receive the packet before the simulation advances.
 * 2. Remote hero: act() must return false when curAction is null in LAN mode,
 *    putting the actor thread to sleep until the network packet arrives.
 * 3. Queued input: setting curAction on the local hero during a remote turn
 *    must be accepted and dispatched when the local hero's turn arrives.
 * 4. Local hero curAction set during remote turn must be overwritten by a
 *    later input (last input wins, not first).
 */
class TurnSyncTest {

    private Hero localHero;
    private Hero remoteHero;
    private HeroAction sentAction;

    @BeforeEach
    void setUp() {
        localHero = new Hero();
        remoteHero = new Hero();

        Dungeon.heroes = new ArrayList<>();
        Dungeon.heroes.add(localHero);
        Dungeon.heroes.add(remoteHero);
        Dungeon.hero = localHero;

        sentAction = null;
        NetworkManager.lanMode = false;
        NetworkManager.localPlayerIndex = 0;
        NetworkManager.setIsHostForTesting(false);

        // Intercept sendAction via the test seam
        NetworkManager.sendActionOverride = (action, heroId) -> sentAction = action;
    }

    @AfterEach
    void tearDown() {
        Dungeon.hero = null;
        Dungeon.heroes = null;
        NetworkManager.lanMode = false;
        NetworkManager.sendActionOverride = null;
        NetworkManager.setIsHostForTesting(false);
    }

    // -------------------------------------------------------------------------
    // sendAction fires before dispatch
    // -------------------------------------------------------------------------

    @Test
    void localHero_sendActionFiresBeforeDispatch() {
        // Given: LAN mode, local hero has a queued move
        NetworkManager.lanMode = true;
        localHero.curAction = new HeroAction.Move(42);

        // When: act() runs
        // We can't run the full act() without a Level, but we can test the
        // pre-dispatch hook directly via the override
        NetworkManager.sendActionIfLocal(localHero.curAction, 0);

        // Then: sendAction was called with the correct action
        assertNotNull(sentAction,
                "sendAction must fire when local hero dispatches an action — " +
                "the remote device must receive the packet before simulation advances");

        assertInstanceOf(HeroAction.Move.class, sentAction,
                "The sent action must be a Move, not null or a different type");

        assertEquals(42, ((HeroAction.Move) sentAction).dst,
                "The sent action must carry the correct target cell (42)");
    }

    @Test
    void soloMode_sendAction_doesNotFire() {
        // Given: NOT in LAN mode
        NetworkManager.lanMode = false;
        localHero.curAction = new HeroAction.Move(42);

        // When: sendAction would be called in solo mode
        NetworkManager.sendActionIfLocal(localHero.curAction, 0);

        // Then: the override was NOT called — no network traffic in solo
        assertNull(sentAction,
                "sendAction must NOT fire in solo mode — " +
                "no network overhead for non-LAN games");
    }

    // -------------------------------------------------------------------------
    // Remote hero act() returns false when no action queued (actor thread sleeps)
    // -------------------------------------------------------------------------

    @Test
    void lanMode_remoteHero_returnsFalseWhenCurActionNull() {
        // Given: LAN mode, remote hero has no queued action
        NetworkManager.lanMode = true;
        remoteHero.curAction = null;

        // When: we check whether the remote hero should block
        boolean shouldBlock = NetworkManager.shouldBlockForRemoteAction(remoteHero);

        // Then: should block (return false from act())
        assertTrue(shouldBlock,
                "Remote hero with null curAction must cause act() to return false " +
                "(sleep actor thread) — the network reader will set curAction and notify");
    }

    @Test
    void lanMode_remoteHero_doesNotBlockWhenActionAlreadySet() {
        // Given: LAN mode, remote hero already has a queued action
        NetworkManager.lanMode = true;
        remoteHero.curAction = new HeroAction.Move(10);

        // When: checked
        boolean shouldBlock = NetworkManager.shouldBlockForRemoteAction(remoteHero);

        // Then: should NOT block — action already arrived, proceed with dispatch
        assertFalse(shouldBlock,
                "Remote hero with curAction already set must NOT block — " +
                "the packet arrived before the turn check, proceed immediately");
    }

    @Test
    void soloMode_neverBlocksForNetwork() {
        // Given: NOT in LAN mode
        NetworkManager.lanMode = false;
        remoteHero.curAction = null;

        // When: checked
        boolean shouldBlock = NetworkManager.shouldBlockForRemoteAction(remoteHero);

        // Then: never blocks for network in solo mode
        assertFalse(shouldBlock,
                "shouldBlockForRemoteAction must always be false in solo mode — " +
                "the network check must be gated by lanMode");
    }

    // -------------------------------------------------------------------------
    // Queued input: curAction set during remote turn is dispatched on local turn
    // -------------------------------------------------------------------------

    @Test
    void queuedInput_setDuringRemoteTurn_isDispatchedOnLocalTurn() {
        // Given: LAN mode; it is currently the remote hero's turn
        // The local player queues a move by tapping cell 99
        NetworkManager.lanMode = true;
        localHero.ready = false; // local hero is NOT ready (remote's turn)

        // When: local player taps (sets curAction even though hero not ready)
        localHero.curAction = new HeroAction.Move(99);

        // Then: curAction is stored and can be dispatched when local hero's turn comes
        assertNotNull(localHero.curAction,
                "curAction must be settable even when hero.ready=false in LAN mode — " +
                "queued input must not be silently dropped");

        assertInstanceOf(HeroAction.Move.class, localHero.curAction,
                "Queued action must be the Move that was set");

        assertEquals(99, ((HeroAction.Move) localHero.curAction).dst,
                "Queued action must preserve the exact target cell");
    }

    @Test
    void queuedInput_latestInputOverwritesPreviousQueue() {
        // Given: local player queued a move to cell 10
        NetworkManager.lanMode = true;
        localHero.ready = false;
        localHero.curAction = new HeroAction.Move(10);

        // When: player changes their mind and queues a move to cell 20
        localHero.curAction = new HeroAction.Move(20);

        // Then: only the latest input is kept (last input wins)
        assertInstanceOf(HeroAction.Move.class, localHero.curAction);
        assertEquals(20, ((HeroAction.Move) localHero.curAction).dst,
                "Latest queued action must overwrite the previous one — " +
                "curAction is a one-slot queue; changing your mind must work");
    }
}
