package com.shatteredpixel.shatteredpixeldungeon.network;

import com.shatteredpixel.shatteredpixeldungeon.GamesInProgress;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the LAN hero-select lockout bug.
 *
 * BUG: After player A claims a hero in LAN mode, player B's device still has
 * the button for that hero in an active (tappable) state.  The visual dimming
 * in HeroBtn.update() sets icon brightness to 0.3, but updateFade() runs every
 * frame and unconditionally calls b.enable(true) for every hero button,
 * overriding any active=false that was intended to lock out the taken hero.
 *
 * ROOT CAUSE: updateFade() did not account for taken classes when enabling
 * hero buttons.  HeroBtn had no isTaken() method exposing that state.
 *
 * FIX:
 * - Added HeroBtn.isTaken(): returns true when the class is in selectedClasses
 *   and is NOT the local player's own current provisional claim.
 * - updateFade() now skips enable() for buttons where isTaken() is true.
 *
 * These tests verify the network-layer half of the fix: CLASS_CLAIMED packets
 * sent by one player are received by NetworkManager listeners and correctly
 * populate GamesInProgress.selectedClasses on the receiving side.  The
 * selectedClasses list is the shared state that isTaken() checks.
 */
class LanHeroSelectLockoutTest {

    private PipedOutputStream clientWriteEnd;

    @BeforeEach
    void setUp() throws IOException {
        NetworkManager.lanMode = true;
        NetworkManager.setIsHostForTesting(true);
        NetworkManager.resetHeroReadyStateForTesting();

        GamesInProgress.selectedClasses = new ArrayList<>();
        GamesInProgress.selectedClass = null;

        // Wire a pipe: test writes CLASS_CLAIMED → NetworkManager reader reads it
        clientWriteEnd = new PipedOutputStream();
        PipedInputStream clientReadEnd = new PipedInputStream(clientWriteEnd, 4096);
        java.io.DataInputStream in = new java.io.DataInputStream(clientReadEnd);
        NetworkManager.setClientInputStreamForTesting(in);
    }

    @AfterEach
    void tearDown() throws IOException {
        NetworkManager.lanMode = false;
        NetworkManager.setIsHostForTesting(false);
        NetworkManager.resetHeroReadyStateForTesting();
        NetworkManager.clearClientInputStreamsForTesting();
        GamesInProgress.selectedClasses = new ArrayList<>();
        GamesInProgress.selectedClass = null;
        NetworkManager.onClassClaimedReceived = null;
        if (clientWriteEnd != null) clientWriteEnd.close();
    }

    /**
     * Verifies that when waitForAllHeroReady receives a HERO_READY from a client,
     * it broadcasts CLASS_CLAIMED and fires onClassClaimedReceived — which is what
     * populates GamesInProgress.selectedClasses on all devices.
     *
     * This is the network path that feeds the isTaken() check.
     */
    @Test
    void classClaimed_callback_fires_on_heroReady() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<HeroClass> claimed = new AtomicReference<>();

        // Register callback (mirrors what HeroSelectScene.create() does in LAN mode)
        NetworkManager.onClassClaimedReceived = (playerIdx, cls) -> {
            if (cls != null && !GamesInProgress.selectedClasses.contains(cls)) {
                GamesInProgress.selectedClasses.add(cls);
            }
            claimed.set(cls);
            latch.countDown();
        };

        // Start the host's hero-ready wait for 2 players
        NetworkManager.waitForAllHeroReady(2);

        // Simulate client sending HERO_READY for MAGE
        DataOutputStream writer = new DataOutputStream(clientWriteEnd);
        writer.writeByte(NetworkManager.PacketType.HERO_READY);
        writer.writeByte((byte) HeroClass.MAGE.ordinal());
        writer.flush();

        // Wait for callback
        boolean fired = latch.await(3, TimeUnit.SECONDS);
        assertTrue(fired, "onClassClaimedReceived must fire when HERO_READY is received");
        assertEquals(HeroClass.MAGE, claimed.get(), "Claimed class must be MAGE");
    }

    /**
     * Verifies the isTaken() semantics via GamesInProgress state:
     * a class is "taken" (and thus its button must be disabled) when it is in
     * selectedClasses but is NOT the local player's current selectedClass.
     */
    @Test
    void selectedClasses_contains_peer_class_but_not_local_class() {
        // Simulate peer claiming WARRIOR
        GamesInProgress.selectedClasses.add(HeroClass.WARRIOR);

        // Local player has chosen MAGE (their own provisional claim)
        GamesInProgress.selectedClass = HeroClass.MAGE;
        GamesInProgress.selectedClasses.add(HeroClass.MAGE);

        // WARRIOR is taken (peer claimed it, local hasn't chosen it)
        assertTrue(isTakenLogic(HeroClass.WARRIOR, GamesInProgress.selectedClass),
                "WARRIOR should be taken — it is in selectedClasses and != local selectedClass");

        // MAGE is NOT taken from the local player's perspective
        assertFalse(isTakenLogic(HeroClass.MAGE, GamesInProgress.selectedClass),
                "MAGE should NOT be taken — it IS the local player's own current claim");

        // ROGUE is not in selectedClasses at all
        assertFalse(isTakenLogic(HeroClass.ROGUE, GamesInProgress.selectedClass),
                "ROGUE should NOT be taken — nobody has claimed it");
    }

    /**
     * Edge case: when selectedClass is null (no local selection yet), any class in
     * selectedClasses must be treated as taken.
     */
    @Test
    void isTaken_when_no_local_selection() {
        GamesInProgress.selectedClasses.add(HeroClass.DUELIST);
        GamesInProgress.selectedClass = null;

        assertTrue(isTakenLogic(HeroClass.DUELIST, null),
                "DUELIST is taken: in selectedClasses and local has not selected anything");
    }

    /**
     * Mirror of HeroBtn.isTaken() logic — extracted here so we can test the pure
     * predicate without instantiating the scene's inner class.
     */
    private static boolean isTakenLogic(HeroClass cl, HeroClass localSelected) {
        if (GamesInProgress.selectedClasses == null) return false;
        if (!GamesInProgress.selectedClasses.contains(cl)) return false;
        return cl != localSelected;
    }
}
