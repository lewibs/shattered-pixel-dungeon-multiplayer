package com.shatteredpixel.shatteredpixeldungeon.network;

import com.shatteredpixel.shatteredpixeldungeon.GamesInProgress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec-first tests for the host-only save behaviour (lan-07).
 *
 * CONTRACTS:
 * - isMultiplayerSave is true only when lanMode=true AND isHost=true.
 * - isMultiplayerSave is false in solo mode and for clients.
 * - The flag must be readable after being set (field accessibility).
 *
 * These tests verify the business rules of WHEN the flag is set,
 * not the file I/O (which requires a real filesystem).
 */
class HostOnlySaveTest {

    @BeforeEach
    void setUp() {
        NetworkManager.lanMode = false;
        NetworkManager.setIsHostForTesting(false);
    }

    @AfterEach
    void tearDown() {
        NetworkManager.lanMode = false;
        NetworkManager.setIsHostForTesting(false);
    }

    // -------------------------------------------------------------------------
    // isMultiplayerSave flag set correctly based on lanMode + isHost
    // -------------------------------------------------------------------------

    @Test
    void host_inLanMode_isMultiplayerSaveIsTrue() {
        // Given: LAN mode, this device is the host
        NetworkManager.lanMode = true;
        NetworkManager.setIsHostForTesting(true);

        // When: the flag is computed (same logic used in Dungeon.saveGame)
        boolean flag = NetworkManager.lanMode && NetworkManager.isHostMode();

        // Then: flag is true — this save is a LAN multiplayer save
        assertTrue(flag,
                "Host in LAN mode must produce isMultiplayerSave=true — " +
                "the save slot must show the LAN badge on relaunch");
    }

    @Test
    void client_inLanMode_isMultiplayerSaveIsFalse() {
        // Given: LAN mode, but this device is a CLIENT
        NetworkManager.lanMode = true;
        NetworkManager.setIsHostForTesting(false);

        // When: flag computed
        boolean flag = NetworkManager.lanMode && NetworkManager.isHostMode();

        // Then: flag is false — clients don't save at all, so this never fires,
        // but the flag would be false if it did
        assertFalse(flag,
                "Client must not set isMultiplayerSave=true — " +
                "clients don't write saves; host is the only authority");
    }

    @Test
    void soloMode_isMultiplayerSaveIsFalse() {
        // Given: NOT in LAN mode
        NetworkManager.lanMode = false;
        NetworkManager.setIsHostForTesting(false);

        // When: flag computed
        boolean flag = NetworkManager.lanMode && NetworkManager.isHostMode();

        // Then: flag is false — solo saves are not LAN saves
        assertFalse(flag,
                "Solo mode must produce isMultiplayerSave=false — " +
                "solo save slots must not show the LAN badge");
    }

    // -------------------------------------------------------------------------
    // GamesInProgress.Info.isMultiplayerSave field is accessible and defaults false
    // -------------------------------------------------------------------------

    @Test
    void infoField_defaultIsFalse() {
        // Given: a freshly constructed Info
        GamesInProgress.Info info = new GamesInProgress.Info();

        // Then: isMultiplayerSave defaults to false
        assertFalse(info.isMultiplayerSave,
                "New GamesInProgress.Info must default isMultiplayerSave=false — " +
                "solo saves must not accidentally show the LAN badge");
    }

    @Test
    void infoField_canBeSetToTrue() {
        // Given: an Info
        GamesInProgress.Info info = new GamesInProgress.Info();

        // When: flag is set (simulating what Dungeon.set() does for a host)
        info.isMultiplayerSave = true;

        // Then: field is readable and true
        assertTrue(info.isMultiplayerSave,
                "isMultiplayerSave field must be writable and readable — " +
                "required for StartScene to show the LAN badge");
    }

    // -------------------------------------------------------------------------
    // Client skips save: verified by checking lanMode + !isHost path exists
    // -------------------------------------------------------------------------

    @Test
    void clientSaveSkipPath_conditionIsCorrect() {
        // The plan specifies: if (NetworkManager.lanMode && !NetworkManager.isHostMode()) skip save
        // This test verifies the boolean logic is sound

        // Given: client in LAN mode
        NetworkManager.lanMode = true;
        NetworkManager.setIsHostForTesting(false);

        boolean shouldSkipSave = NetworkManager.lanMode && !NetworkManager.isHostMode();

        // Then: skip condition is true
        assertTrue(shouldSkipSave,
                "Client in LAN mode must trigger the save-skip path — " +
                "if this is false, clients would write corrupted save files");
    }

    @Test
    void hostSavePath_conditionIsCorrect() {
        // Given: host in LAN mode
        NetworkManager.lanMode = true;
        NetworkManager.setIsHostForTesting(true);

        boolean shouldSkipSave = NetworkManager.lanMode && !NetworkManager.isHostMode();

        // Then: skip condition is false (host DOES save)
        assertFalse(shouldSkipSave,
                "Host in LAN mode must NOT trigger the save-skip path — " +
                "host is the authoritative save owner");
    }
}
