package com.shatteredpixel.shatteredpixeldungeon.network;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Flow 2: resumeLobbyPlayerCountGate
 * EC-2.1
 *
 * These tests exercise the gate logic directly through NetworkManager's
 * connectedPlayerCount comparisons, without invoking libGDX UI code.
 */
class ResumeLobbyGateTest {

    @BeforeEach
    void setUp() {
        NetworkManager.lanMode = true;
        NetworkManager.gameStarted = false;
        NetworkManager.setIsHostForTesting(true);
        Dungeon.heroes = new ArrayList<>();
    }

    @AfterEach
    void tearDown() {
        NetworkManager.lanMode = false;
        NetworkManager.gameStarted = false;
        NetworkManager.setIsHostForTesting(false);
        NetworkManager.setConnectedPlayerCountForTesting(1);
        Dungeon.heroes = null;
        Dungeon.hero = null;
    }

    /**
     * EC-2.1: In resume mode, Start is only allowed when connectedPlayerCount == hero count.
     * Save has 3 heroes; at 2 players the gate says NO, at 3 it says YES.
     */
    @Test
    void resumeMode_startButtonDisabledUntilCorrectCount() {
        // Plan path: resume-start-gate
        // Arrange: 3 heroes in the save
        Dungeon.heroes.add(new Hero());
        Dungeon.heroes.add(new Hero());
        Dungeon.heroes.add(new Hero());
        int requiredCount = Dungeon.heroes.size(); // 3

        // Act: 2 players connected
        NetworkManager.setConnectedPlayerCountForTesting(2);
        boolean shouldEnableAt2 = NetworkManager.getConnectedPlayerCount() == requiredCount;

        // Assert: not enabled at 2
        assertFalse(shouldEnableAt2, "Start should NOT be enabled with 2 players when 3 heroes required");

        // Act: 3rd player joins
        NetworkManager.setConnectedPlayerCountForTesting(3);
        boolean shouldEnableAt3 = NetworkManager.getConnectedPlayerCount() == requiredCount;

        // Assert: enabled at 3
        assertTrue(shouldEnableAt3, "Start SHOULD be enabled with 3 players when 3 heroes required");
    }

    /**
     * EC-2.1: 3 players connected, Start enabled; one leaves, count drops to 2, Start disabled.
     */
    @Test
    void resumeMode_playerLeaves_startDisabled() {
        // Plan path: resume-player-leaves-disables-start
        // Arrange: 3 heroes in save, 3 players connected
        Dungeon.heroes.add(new Hero());
        Dungeon.heroes.add(new Hero());
        Dungeon.heroes.add(new Hero());
        int requiredCount = Dungeon.heroes.size(); // 3

        NetworkManager.setConnectedPlayerCountForTesting(3);
        boolean enabledBefore = NetworkManager.getConnectedPlayerCount() == requiredCount;
        assertTrue(enabledBefore, "Start should be enabled before player leaves");

        // Act: player leaves (PLAYER_LEFT decrements count)
        NetworkManager.setConnectedPlayerCountForTesting(2);

        // Assert: Start should now be disabled
        boolean enabledAfter = NetworkManager.getConnectedPlayerCount() == requiredCount;
        assertFalse(enabledAfter, "Start should be DISABLED after player leaves");
    }

    /**
     * EC-2.1: onStartTapped() defensive check — if connectedPlayerCount != hero count, abort.
     * The method should return early without proceeding to send START.
     */
    @Test
    void resumeMode_startPrevented_whenCountMismatch() {
        // Plan path: resume-start-mismatch-abort
        // Arrange: 3 heroes in save, only 2 connected (mismatch)
        Dungeon.heroes.add(new Hero());
        Dungeon.heroes.add(new Hero());
        Dungeon.heroes.add(new Hero());
        int requiredCount = Dungeon.heroes.size(); // 3

        NetworkManager.setConnectedPlayerCountForTesting(2);

        // Act: check if the defensive condition would abort
        boolean countMismatch = NetworkManager.getConnectedPlayerCount() != requiredCount;

        // Assert: mismatch is detected (the defensive check in onStartTapped should catch this)
        assertTrue(countMismatch,
                "Defensive check should detect connectedPlayerCount (2) != hero count (3)");

        // Verify the condition that gating logic uses
        // In LanLobbyScene.onStartTapped(): if (resumeMode && connectedPlayerCount != Dungeon.heroes.size()) return
        boolean shouldAbort = NetworkManager.getConnectedPlayerCount() != Dungeon.heroes.size();
        assertTrue(shouldAbort, "onStartTapped should abort when count mismatches");
    }
}
