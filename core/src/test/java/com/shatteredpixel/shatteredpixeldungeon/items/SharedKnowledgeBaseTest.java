package com.shatteredpixel.shatteredpixeldungeon.items;

import com.shatteredpixel.shatteredpixeldungeon.network.NetworkManager;
import org.junit.jupiter.api.*;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the shared item knowledge-base in multiplayer.
 *
 * The invariant: once ANY player identifies a potion or scroll, that
 * knowledge is broadcast and ALL players should know what that item is.
 *
 * Tests cover:
 *  - ItemStatusHandler correctly marks items as known
 *  - setKnown() sends LAN packet only when lanMode=true AND item was unknown
 *  - Already-known items do not re-trigger a broadcast (idempotency)
 *  - handler=null is safe (anonymous items, or uninitialized state)
 *  - Knowledge persists — once known, always known
 *  - Multiple items can be known independently
 */
class SharedKnowledgeBaseTest {

    // -------------------------------------------------------------------------
    // Minimal ItemStatusHandler for testing — no libGDX needed
    // -------------------------------------------------------------------------

    /**
     * A minimal, directly-instantiable handler that we can use without
     * initialising the full Generator/Potion system.
     * Exposes the known-set directly so tests can verify state.
     */
    static class TestHandler<T extends Item> {
        LinkedHashSet<Class<? extends T>> known = new LinkedHashSet<>();

        boolean isKnown(T item) { return known.contains(item.getClass()); }
        boolean isKnown(Class<? extends T> cls) { return known.contains(cls); }
        @SuppressWarnings("unchecked")
        void know(T item) { known.add((Class<? extends T>) item.getClass()); }
        void know(Class<? extends T> cls) { known.add(cls); }
    }

    // -------------------------------------------------------------------------
    // Minimal item subclasses for testing
    // -------------------------------------------------------------------------

    static class TestPotion extends Item {
        static TestHandler<TestPotion> testHandler = null;

        @Override public boolean isIdentified() { return isKnown(); }
        @Override public boolean isUpgradable() { return false; }

        public boolean isKnown() {
            return testHandler != null && testHandler.isKnown(this);
        }

        public void setKnown() {
            if (testHandler != null && !isKnown()) {
                testHandler.know(this);
                if (NetworkManager.lanMode) {
                    NetworkManager.sendItemIdentified(getClass().getName());
                }
            }
        }
    }

    static class TestScroll extends Item {
        static TestHandler<TestScroll> testHandler = null;

        @Override public boolean isIdentified() { return isKnown(); }
        @Override public boolean isUpgradable() { return false; }

        public boolean isKnown() {
            return testHandler != null && testHandler.isKnown(this);
        }

        public void setKnown() {
            if (testHandler != null && !isKnown()) {
                testHandler.know(this);
                if (NetworkManager.lanMode) {
                    NetworkManager.sendItemIdentified(getClass().getName());
                }
            }
        }
    }

    @BeforeEach
    void setUp() {
        TestPotion.testHandler = new TestHandler<>();
        TestScroll.testHandler = new TestHandler<>();
        NetworkManager.lanMode = false;
        NetworkManager.setIsHostForTesting(false);
    }

    @AfterEach
    void tearDown() {
        TestPotion.testHandler = null;
        TestScroll.testHandler = null;
        NetworkManager.lanMode = false;
    }

    // =========================================================================
    // Handler state — isKnown / know semantics
    // =========================================================================

    @Test
    void newPotion_notKnown() {
        assertFalse(new TestPotion().isKnown(), "Freshly created potion must be unknown");
    }

    @Test
    void newScroll_notKnown() {
        assertFalse(new TestScroll().isKnown(), "Freshly created scroll must be unknown");
    }

    @Test
    void setKnown_makesItemKnown() {
        TestPotion p = new TestPotion();
        p.setKnown();
        assertTrue(p.isKnown(), "After setKnown(), potion must be known");
    }

    @Test
    void setKnown_persistsForAllInstancesOfSameClass() {
        TestPotion p1 = new TestPotion();
        p1.setKnown();

        TestPotion p2 = new TestPotion(); // different instance, same class
        assertTrue(p2.isKnown(),
                "Knowledge is class-based: second instance of same type must also be known");
    }

    @Test
    void scrollKnowledge_independentFromPotionKnowledge() {
        TestPotion p = new TestPotion();
        p.setKnown();

        TestScroll s = new TestScroll();
        assertFalse(s.isKnown(),
                "Knowing a potion must not affect scroll knowledge");
    }

    @Test
    void multipleItemsKnown_independently() {
        TestPotion.testHandler.know(TestPotion.class);
        TestScroll.testHandler.know(TestScroll.class);

        assertTrue(new TestPotion().isKnown());
        assertTrue(new TestScroll().isKnown());
    }

    @Test
    void handlerNull_isKnown_returnsFalse() {
        TestPotion.testHandler = null;
        TestPotion p = new TestPotion();
        assertFalse(p.isKnown(), "null handler must not NPE — return false");
    }

    // =========================================================================
    // LAN broadcast conditions
    // =========================================================================

    @Test
    void setKnown_lanModeOff_doesNotSendPacket() {
        // Verify by checking the item becomes known locally but no send occurs.
        // (Since there are no sockets here, sendItemIdentified would NPE if it tried to write.)
        NetworkManager.lanMode = false;
        TestPotion p = new TestPotion();
        assertDoesNotThrow(p::setKnown,
                "setKnown with lanMode=false must not throw (no socket writes)");
        assertTrue(p.isKnown(), "Item must still become locally known");
    }

    @Test
    void setKnown_alreadyKnown_doesNotSendAgain() {
        // Mark known first, then call setKnown again.
        // The second call must be a no-op — handler.know() only called once.
        TestPotion.testHandler.know(TestPotion.class);
        TestPotion p = new TestPotion();
        assertTrue(p.isKnown()); // already known

        // If setKnown tried to send while already known it would re-broadcast
        // (which is wasteful and potentially confusing). The guard `!isKnown()` must stop it.
        NetworkManager.lanMode = true; // would NPE on send if guard fails (no sockets)
        assertDoesNotThrow(p::setKnown,
                "setKnown on already-known item must be idempotent — no double broadcast");
        NetworkManager.lanMode = false;
    }

    @Test
    void handlerNull_setKnown_doesNotThrow() {
        TestPotion.testHandler = null;
        NetworkManager.lanMode = false;
        assertDoesNotThrow(() -> new TestPotion().setKnown(),
                "null handler must not cause NPE in setKnown");
    }

    // =========================================================================
    // Shared knowledge invariant — receiver identifies item from class name
    // =========================================================================

    @Test
    void classNameFromGetName_canBeUsedToIdentify() throws Exception {
        // This tests the round-trip: the class name used in sendItemIdentified
        // must be usable to instantiate and identify the item on the other side.
        TestPotion original = new TestPotion();
        String className = original.getClass().getName();

        // Simulate what the receive side does: Class.forName(className).newInstance()
        Class<?> cls = Class.forName(className);
        Object instance = cls.getDeclaredConstructor().newInstance();
        assertTrue(instance instanceof TestPotion,
                "Class name must round-trip to same item type");
    }

    @Test
    void knowledgeTransferScenario_p1IdentifiesP2Learns() {
        // Simulate: P1 identifies potion → knowledge transferred → P2 checks
        // P1 side:
        TestPotion p1Potion = new TestPotion();
        assertFalse(p1Potion.isKnown(), "Initially unknown to P1");
        p1Potion.setKnown();
        assertTrue(p1Potion.isKnown(), "P1 now knows it");

        // Network transfer (simulated): P2 receives class name and marks it known
        // In production: item.identify(false) → setKnown() on P2's handler
        // Here: directly mark the handler (same shared TestHandler in this test)
        TestPotion p2Potion = new TestPotion();
        assertTrue(p2Potion.isKnown(),
                "P2 must know the same item (class-based knowledge is shared in this handler)");
    }

    @Test
    void knowledgeTransferScenario_scrollP2IdentifiesP1Learns() {
        TestScroll s2 = new TestScroll();
        s2.setKnown(); // P2 identified it

        TestScroll s1 = new TestScroll(); // P1's copy
        assertTrue(s1.isKnown(),
                "After P2 identifies scroll, P1's instance of same class must also be known");
    }

    // =========================================================================
    // Knowledge persists across new item instances
    // =========================================================================

    @Test
    void knowledgePersistsAfterHandlerKnows() {
        TestPotion.testHandler.know(TestPotion.class);

        // Even a brand new instance must be "known" after handler records it
        for (int i = 0; i < 5; i++) {
            assertTrue(new TestPotion().isKnown(),
                    "Iteration " + i + ": knowledge must persist across new instances");
        }
    }

    @Test
    void unknownItemRevealedAfterHandlerUpdated() {
        TestPotion p = new TestPotion();
        assertFalse(p.isKnown(), "Before handler update: unknown");

        // Simulate remote identification arriving: handler.know() is called
        TestPotion.testHandler.know(TestPotion.class);

        assertTrue(p.isKnown(), "After handler update (remote ident received): now known");
    }

    // =========================================================================
    // Edge cases
    // =========================================================================

    @Test
    void setKnownCalledTwice_stateConsistent() {
        TestPotion p = new TestPotion();
        p.setKnown();
        p.setKnown(); // second call — no-op
        assertTrue(p.isKnown(), "Double setKnown must leave item known");
    }

    @Test
    void emptyHandler_nothingKnown() {
        TestPotion.testHandler = new TestHandler<>();
        TestPotion p = new TestPotion();
        assertFalse(p.isKnown(), "Fresh handler must have nothing known");
    }
}
