package com.shatteredpixel.shatteredpixeldungeon.network;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec-first tests for the activate() gate (lan-01).
 *
 * CONTRACT: In LAN mode, when a remote hero's turn fires, Dungeon.hero must
 * NOT be reassigned. The local player's hero stays pinned for the entire
 * session. Camera, UI, and singleton state must not be touched by remote turns.
 *
 * This is the foundation everything else depends on: if Dungeon.hero rotates,
 * FOV, toolbar state, and hash computation all break simultaneously.
 */
class ActivateGateTest {

    private Hero localHero;
    private Hero remoteHero;

    @BeforeEach
    void setUp() {
        localHero = new Hero();
        remoteHero = new Hero();

        Dungeon.heroes = new ArrayList<>();
        Dungeon.heroes.add(localHero);
        Dungeon.heroes.add(remoteHero);

        // Local device — Dungeon.hero is pinned to localHero
        Dungeon.hero = localHero;

        NetworkManager.lanMode = false; // default off
        NetworkManager.localPlayerIndex = 0;
    }

    @AfterEach
    void tearDown() {
        Dungeon.hero = null;
        Dungeon.heroes = null;
        NetworkManager.lanMode = false;
    }

    // -------------------------------------------------------------------------
    // Core contract: remote hero activate() must not steal Dungeon.hero
    // -------------------------------------------------------------------------

    @Test
    void lanMode_remoteHeroActivate_doesNotChangeDungeonHero() {
        // Given: LAN mode active, Dungeon.hero is the local hero
        NetworkManager.lanMode = true;
        assertSame(localHero, Dungeon.hero,
                "Pre-condition: Dungeon.hero must start as localHero");

        // When: the remote hero calls activate() (their turn fires)
        remoteHero.activate();

        // Then: Dungeon.hero is STILL the local hero
        assertSame(localHero, Dungeon.hero,
                "Remote hero activate() must NOT reassign Dungeon.hero in LAN mode — " +
                "the local player's hero must remain pinned for the entire session");
    }

    @Test
    void lanMode_localHeroActivate_keepsDungeonHeroSame() {
        // Given: LAN mode active, local hero activates (their turn fires)
        NetworkManager.lanMode = true;

        // When: local hero activates
        localHero.activate();

        // Then: Dungeon.hero is still the local hero (no-op assignment)
        assertSame(localHero, Dungeon.hero,
                "Local hero activate() in LAN mode must keep Dungeon.hero pointing to itself");
    }

    // -------------------------------------------------------------------------
    // Solo / pass-and-play mode: activate() must work normally (no regression)
    // -------------------------------------------------------------------------

    @Test
    void soloMode_activate_rotatesDungeonHero() {
        // Given: NOT in LAN mode (solo / pass-and-play)
        NetworkManager.lanMode = false;
        Dungeon.hero = localHero;

        // When: remoteHero (second party member in pass-and-play) activates
        remoteHero.activate();

        // Then: Dungeon.hero rotates to remoteHero — this is correct for pass-and-play
        assertSame(remoteHero, Dungeon.hero,
                "In solo/pass-and-play mode, activate() must still reassign Dungeon.hero — " +
                "the LAN gate must not break the existing turn handoff");
    }

    // -------------------------------------------------------------------------
    // Dungeon.hero pinning survives multiple remote turns in a row
    // -------------------------------------------------------------------------

    @Test
    void lanMode_multipleRemoteTurns_localHeroRemainsFixed() {
        // Given: LAN mode, 4 heroes in party
        Hero remote2 = new Hero();
        Hero remote3 = new Hero();
        Dungeon.heroes.add(remote2);
        Dungeon.heroes.add(remote3);
        NetworkManager.lanMode = true;

        // When: all remote heroes activate in sequence
        remoteHero.activate();
        remote2.activate();
        remote3.activate();

        // Then: Dungeon.hero has not moved from localHero
        assertSame(localHero, Dungeon.hero,
                "Dungeon.hero must remain pinned to localHero through all remote turns — " +
                "3 consecutive remote activations must not erode the pin");
    }

    // -------------------------------------------------------------------------
    // Quickslot must also remain pinned to local hero's quickslot
    // -------------------------------------------------------------------------

    @Test
    void lanMode_remoteHeroActivate_doesNotChangeQuickslot() {
        // Given: LAN mode; local hero has a distinct quickslot reference
        NetworkManager.lanMode = true;
        var localQuickslot = localHero.quickslot;
        Dungeon.quickslot = localQuickslot;

        // When: remote hero activates
        remoteHero.activate();

        // Then: Dungeon.quickslot still points to local hero's quickslot
        assertSame(localQuickslot, Dungeon.quickslot,
                "Remote hero activate() must not steal Dungeon.quickslot — " +
                "toolbar items and targeting would show the wrong hero's inventory");
    }
}
