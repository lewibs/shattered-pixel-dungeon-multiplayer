package com.shatteredpixel.shatteredpixeldungeon.network;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the waitForAllHeroReady() race condition:
 *
 * BUG: waitForAllHeroReady(playerCount) starts reader threads THEN sets
 * heroReadyCount = 1 (host self-count) in a synchronized block. If a client's
 * HERO_READY packet is already buffered and the reader thread processes it
 * BEFORE the main thread's synchronized block runs, the sequence is:
 *
 *   Thread: heroReadyCount = 0 + 1 = 1 → 1 >= 2? NO → heroReadyReceived stays false
 *   Main:   heroReadyCount = 1 (overwrites — but already 1, still wrong)
 *
 * Result: heroReadyReceived is NEVER set to true. Host never shows "Start Game"
 * button. Both devices stay on HeroSelectScene indefinitely.
 *
 * FIX: Set heroReadyCount = 1 BEFORE starting reader threads so the thread
 * increments from 1 → 2, correctly triggering heroReadyReceived = true.
 */
class HeroReadyRaceTest {

    /** Pipe used to simulate the client socket stream on the host side. */
    private PipedOutputStream clientWriteEnd;

    @BeforeEach
    void setUp() throws IOException {
        NetworkManager.lanMode = true;
        NetworkManager.setIsHostForTesting(true);
        NetworkManager.resetHeroReadyStateForTesting();

        // Wire a pipe: test writes to clientWriteEnd → NetworkManager reads from ins
        clientWriteEnd = new PipedOutputStream();
        PipedInputStream clientReadEnd = new PipedInputStream(clientWriteEnd, 4096);
        java.io.DataInputStream in = new java.io.DataInputStream(clientReadEnd);

        // Install the piped stream into NetworkManager so waitForAllHeroReady uses it
        NetworkManager.setClientInputStreamForTesting(in);
    }

    @AfterEach
    void tearDown() {
        NetworkManager.lanMode = false;
        NetworkManager.setIsHostForTesting(false);
        NetworkManager.resetHeroReadyStateForTesting();
        NetworkManager.clearClientInputStreamsForTesting();
        try { if (clientWriteEnd != null) clientWriteEnd.close(); } catch (IOException ignored) {}
    }

    /**
     * HAPPY PATH: HERO_READY arrives AFTER waitForAllHeroReady() initializes
     * heroReadyCount = 1. Thread increments to 2, heroReadyReceived = true.
     *
     * This test verifies the FIXED behavior where heroReadyCount is set to 1
     * before reader threads start.
     */
    @Test
    void heroReadyAfterInit_setsHeroReadyReceived() throws Exception {
        // 2 players (host + 1 client)
        int playerCount = 2;

        // Call waitForAllHeroReady — this should initialize heroReadyCount=1 first
        NetworkManager.waitForAllHeroReady(playerCount);

        // Now simulate client sending HERO_READY (AFTER waitForAllHeroReady initialized)
        DataOutputStream writer = new DataOutputStream(clientWriteEnd);
        writer.writeByte(NetworkManager.PacketType.HERO_READY);
        writer.writeByte((byte) HeroClass.WARRIOR.ordinal());
        writer.flush();

        // Give the reader thread time to process
        long deadline = System.currentTimeMillis() + 3000;
        while (!NetworkManager.isHeroReadyReceived() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }

        assertTrue(NetworkManager.isHeroReadyReceived(),
                "heroReadyReceived must be true after both players send HERO_READY");
    }

    /**
     * RACE CONDITION TEST: HERO_READY arrives BEFORE waitForAllHeroReady()
     * initializes heroReadyCount = 1. With the BUG, the reader thread increments
     * heroReadyCount from 0→1 first, then the main thread sets it back to 1.
     * heroReadyReceived stays false.
     *
     * With the FIX (heroReadyCount initialized before threads start), the reader
     * thread increments from 1→2 regardless of timing, so heroReadyReceived = true.
     *
     * This test verifies that heroReadyReceived is set to true even in the race
     * scenario where HERO_READY is fully buffered before waitForAllHeroReady runs.
     */
    @Test
    void heroReadyBeforeInit_raceCondition_heroReadyReceivedMustBeTrue() throws Exception {
        // Write HERO_READY BEFORE calling waitForAllHeroReady so bytes are buffered
        DataOutputStream writer = new DataOutputStream(clientWriteEnd);
        writer.writeByte(NetworkManager.PacketType.HERO_READY);
        writer.writeByte((byte) HeroClass.MAGE.ordinal());
        writer.flush();

        // Small delay to ensure bytes are fully buffered before the reader thread starts
        Thread.sleep(10);

        // Now call waitForAllHeroReady — in the buggy version, thread reads HERO_READY
        // before heroReadyCount=1 is set, resulting in heroReadyReceived never becoming true
        NetworkManager.waitForAllHeroReady(2);

        // Wait for heroReadyReceived to become true
        long deadline = System.currentTimeMillis() + 3000;
        while (!NetworkManager.isHeroReadyReceived() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }

        assertTrue(NetworkManager.isHeroReadyReceived(),
                "heroReadyReceived must be true even when HERO_READY is received before " +
                "heroReadyCount is initialized — the race condition must not prevent game start");
    }

    /**
     * Verifies the final heroReadyCount is correct after the race scenario.
     * With the fix, count should reach playerCount (not stay at 1).
     */
    @Test
    void heroReadyCount_isCorrectAfterRace() throws Exception {
        // Write HERO_READY before calling waitForAllHeroReady
        DataOutputStream writer = new DataOutputStream(clientWriteEnd);
        writer.writeByte(NetworkManager.PacketType.HERO_READY);
        writer.writeByte((byte) HeroClass.ROGUE.ordinal());
        writer.flush();

        Thread.sleep(10);

        NetworkManager.waitForAllHeroReady(2);

        // Wait for processing
        long deadline = System.currentTimeMillis() + 3000;
        while (!NetworkManager.isHeroReadyReceived() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }

        assertEquals(2, NetworkManager.getHeroReadyCountForTesting(),
                "heroReadyCount must equal playerCount (2) after both players ready — " +
                "the race must not leave count stuck at 1");
    }
}
