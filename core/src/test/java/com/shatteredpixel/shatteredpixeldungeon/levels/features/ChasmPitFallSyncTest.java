package com.shatteredpixel.shatteredpixeldungeon.levels.features;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for pit-fall party synchronization (shouldWaitForParty logic).
 *
 * The rule: when hero A falls into a pit, the scene switch is deferred until
 * all other alive heroes are either also WaitingToFall or have left via stairs.
 * shouldWaitForParty() encodes the "is there any active hero that hasn't fallen?"
 * check, so these tests prove the gate fires (or doesn't) for every relevant
 * combination of alive/dead/waiting heroes.
 */
class ChasmPitFallSyncTest {

    private Hero heroA;
    private Hero heroB;
    private Hero heroC;

    @BeforeEach
    void setUp() {
        heroA = new Hero();
        heroB = new Hero();
        heroC = new Hero();
        // Hero() sets HP = HT = 20, so all three start alive
    }

    @AfterEach
    void tearDown() {
        Dungeon.heroes = null;
    }

    // -------------------------------------------------------------------------
    // shouldWaitForParty — gate logic
    // -------------------------------------------------------------------------

    @Test
    void singlePlayer_neverWaits() {
        // Only one hero in the list — no one to wait for
        Dungeon.heroes = new ArrayList<>();
        Dungeon.heroes.add(heroA);

        assertFalse(Chasm.shouldWaitForParty(heroA),
                "Single-player fall should never attach WaitingToFall");
    }

    @Test
    void nullHeroes_neverWaits() {
        Dungeon.heroes = null;
        assertFalse(Chasm.shouldWaitForParty(heroA),
                "Null heroes list should never attach WaitingToFall");
    }

    @Test
    void twoAliveHeroes_fallingHeroShouldWait() {
        // heroA falls while heroB is still alive and active
        Dungeon.heroes = new ArrayList<>();
        Dungeon.heroes.add(heroA);
        Dungeon.heroes.add(heroB);

        assertTrue(Chasm.shouldWaitForParty(heroA),
                "Falling hero must wait when another alive hero is still active");
    }

    @Test
    void otherHeroIsDead_noWait() {
        // heroA falls, heroB is dead — nothing to wait for
        heroB.HP = 0;
        Dungeon.heroes = new ArrayList<>();
        Dungeon.heroes.add(heroA);
        Dungeon.heroes.add(heroB);

        assertFalse(Chasm.shouldWaitForParty(heroA),
                "Falling hero should not wait when only other hero is dead");
    }

    @Test
    void otherHeroAlreadyWaiting_noWait() {
        // heroB already has WaitingToFall — heroA is last active, fall immediately
        Buff.affect(heroB, Chasm.WaitingToFall.class);
        Dungeon.heroes = new ArrayList<>();
        Dungeon.heroes.add(heroA);
        Dungeon.heroes.add(heroB);

        assertFalse(Chasm.shouldWaitForParty(heroA),
                "Falling hero should not wait when all other alive heroes are already waiting");
    }

    @Test
    void threeHeroes_oneDeadOneAlive_shouldWait() {
        // heroC is dead, heroB is alive — heroA must wait for heroB
        heroC.HP = 0;
        Dungeon.heroes = new ArrayList<>();
        Dungeon.heroes.add(heroA);
        Dungeon.heroes.add(heroB);
        Dungeon.heroes.add(heroC);

        assertTrue(Chasm.shouldWaitForParty(heroA),
                "Falling hero must wait when at least one other alive hero is active");
    }

    @Test
    void threeHeroes_oneDeadOneWaiting_noWait() {
        // heroC is dead, heroB has WaitingToFall — heroA is last, fall immediately
        heroC.HP = 0;
        Buff.affect(heroB, Chasm.WaitingToFall.class);
        Dungeon.heroes = new ArrayList<>();
        Dungeon.heroes.add(heroA);
        Dungeon.heroes.add(heroB);
        Dungeon.heroes.add(heroC);

        assertFalse(Chasm.shouldWaitForParty(heroA),
                "Falling hero should not wait when all others are dead or already waiting");
    }

    @Test
    void fourHeroes_twoAlive_shouldWait() {
        // heroC and heroD alive, heroA falls — still two active heroes left
        Hero heroD = new Hero();
        heroB.HP = 0;
        Dungeon.heroes = new ArrayList<>();
        Dungeon.heroes.add(heroA);
        Dungeon.heroes.add(heroB); // dead
        Dungeon.heroes.add(heroC);
        Dungeon.heroes.add(heroD);

        assertTrue(Chasm.shouldWaitForParty(heroA),
                "shouldWait=true when 2 of 3 other heroes are still alive");
    }

    // -------------------------------------------------------------------------
    // WaitingToFall buff — attachment and state
    // -------------------------------------------------------------------------

    @Test
    void waitingToFallBuff_canBeAttached() {
        assertNull(heroA.buff(Chasm.WaitingToFall.class),
                "Hero should have no WaitingToFall buff initially");

        Buff.affect(heroA, Chasm.WaitingToFall.class);

        assertNotNull(heroA.buff(Chasm.WaitingToFall.class),
                "WaitingToFall buff should be present after Buff.affect");
    }

    @Test
    void waitingToFallBuff_storesFallIntoPitFlag() {
        Chasm.WaitingToFall w = Buff.affect(heroA, Chasm.WaitingToFall.class);
        w.fallIntoPit = true;

        assertTrue(heroA.buff(Chasm.WaitingToFall.class).fallIntoPit,
                "fallIntoPit flag should be readable from buff on hero");
    }

    @Test
    void waitingToFallBuff_isDetectedByOtherHeroCheck() {
        // After heroB gets WaitingToFall, heroA's shouldWaitForParty should return false
        Buff.affect(heroB, Chasm.WaitingToFall.class);
        Dungeon.heroes = new ArrayList<>();
        Dungeon.heroes.add(heroA);
        Dungeon.heroes.add(heroB);

        assertFalse(Chasm.shouldWaitForParty(heroA),
                "WaitingToFall hero must not count as an 'active' blocker");
    }

    // -------------------------------------------------------------------------
    // Sequential fall scenario — 3 heroes fall one by one
    // -------------------------------------------------------------------------

    @Test
    void sequentialFalls_lastHeroTriggersImmediately() {
        // Scenario: 3 heroes, A falls first, B falls second, C falls last
        Dungeon.heroes = new ArrayList<>();
        Dungeon.heroes.add(heroA);
        Dungeon.heroes.add(heroB);
        Dungeon.heroes.add(heroC);

        // A falls — B and C still active → should wait
        assertTrue(Chasm.shouldWaitForParty(heroA));
        Buff.affect(heroA, Chasm.WaitingToFall.class);

        // B falls — C still active, A is waiting → should wait
        assertTrue(Chasm.shouldWaitForParty(heroB));
        Buff.affect(heroB, Chasm.WaitingToFall.class);

        // C falls — A and B are both waiting → should NOT wait (C is last)
        assertFalse(Chasm.shouldWaitForParty(heroC),
                "Last falling hero should trigger scene switch immediately");
    }
}
