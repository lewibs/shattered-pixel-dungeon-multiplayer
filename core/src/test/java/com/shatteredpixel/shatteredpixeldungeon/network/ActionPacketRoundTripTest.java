package com.shatteredpixel.shatteredpixeldungeon.network;

import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroAction;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spec-first tests for ACTION packet serialization.
 *
 * These tests verify the CONTRACT from lan-02: every HeroAction type that a
 * player can perform must survive a full encode → wire → decode round-trip with
 * all fields intact. They do NOT test implementation details — they test that
 * the packet protocol defined in the plan actually works end-to-end.
 *
 * A test failure here means the plan spec is violated. Fix the implementation,
 * not the test.
 */
class ActionPacketRoundTripTest {

    // -------------------------------------------------------------------------
    // Helpers — write one ACTION packet to a byte buffer, read it back
    // -------------------------------------------------------------------------

    private HeroAction roundTrip(HeroAction action) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);

        // Write exactly what sendAction() is supposed to write (per plan lan-02):
        // [byte type=1] [int heroId] [byte actionType] [int targetPos]
        out.writeByte(NetworkManager.PacketType.ACTION);
        out.writeInt(0); // heroId — not under test here
        out.writeByte(NetworkManager.encodeAction(action));
        out.writeInt(NetworkManager.getTargetPos(action));
        out.flush();

        DataInputStream in = new DataInputStream(
                new ByteArrayInputStream(baos.toByteArray()));

        byte type = in.readByte();
        assertEquals(NetworkManager.PacketType.ACTION, type,
                "First byte must be ACTION packet type (1)");
        int heroId = in.readInt();
        byte actionType = in.readByte();
        int targetPos = in.readInt();

        return NetworkManager.decodeAction(actionType, targetPos);
    }

    // -------------------------------------------------------------------------
    // Move action — most common; target cell must survive round-trip exactly
    // -------------------------------------------------------------------------

    @Test
    void moveAction_targetCellSurvivesRoundTrip() throws Exception {
        // Given: a Move action targeting cell 42
        HeroAction original = new HeroAction.Move(42);

        // When: encoded and decoded through the wire format
        HeroAction decoded = roundTrip(original);

        // Then: the decoded action is a Move
        assertInstanceOf(HeroAction.Move.class, decoded,
                "Move action must decode back to HeroAction.Move, not " + decoded);

        // And: the target cell is preserved exactly
        assertEquals(42, ((HeroAction.Move) decoded).dst,
                "Move target cell must survive encode/decode unchanged");
    }

    @Test
    void moveAction_differentCells_areDistinct() throws Exception {
        // Given: two Move actions to different cells
        HeroAction move10 = new HeroAction.Move(10);
        HeroAction move255 = new HeroAction.Move(255);

        // When: both round-tripped
        HeroAction decoded10 = roundTrip(move10);
        HeroAction decoded255 = roundTrip(move255);

        // Then: cells are not confused
        assertEquals(10, ((HeroAction.Move) decoded10).dst,
                "Cell 10 must not become 255 after encode/decode");
        assertEquals(255, ((HeroAction.Move) decoded255).dst,
                "Cell 255 must not become 10 after encode/decode");
    }

    @Test
    void moveAction_largeCellIndex_preservedExactly() throws Exception {
        // Given: a Move to a large cell index (e.g. 32x32 level = 1024 cells)
        HeroAction original = new HeroAction.Move(1023);

        // When: round-tripped
        HeroAction decoded = roundTrip(original);

        // Then: the full integer value is preserved (int field, not byte)
        assertInstanceOf(HeroAction.Move.class, decoded);
        assertEquals(1023, ((HeroAction.Move) decoded).dst,
                "Large cell index must be stored as int, not truncated to byte");
    }

    // -------------------------------------------------------------------------
    // Attack action — must carry the target position (mob's cell)
    // -------------------------------------------------------------------------

    @Test
    void attackAction_targetCellSurvivesRoundTrip() throws Exception {
        // Given: an Attack action — target mob is at cell 77.
        // On the wire we encode target position; the receiver looks up the Char
        // at that cell (simulation is identical, so the Char exists there).
        HeroAction.Attack original = new HeroAction.Attack(null);
        original.dst = 77; // cell position encoded via dst field

        // When: round-tripped
        HeroAction decoded = roundTrip(original);

        // Then: decoded as Attack
        assertInstanceOf(HeroAction.Attack.class, decoded,
                "Attack action must decode back to HeroAction.Attack");

        // And: the cell position is preserved (receiver uses this to look up the Char)
        assertEquals(77, decoded.dst,
                "Attack target cell must survive encode/decode — " +
                "receiver looks up Char by position in the deterministic simulation");
    }

    // -------------------------------------------------------------------------
    // Each action type encodes to a DISTINCT byte — no two types share a code
    // -------------------------------------------------------------------------

    @Test
    void allActionTypes_haveDistinctEncodingBytes() throws Exception {
        // Given: one instance of each action type defined in the plan (lan-02)
        // Move=0, Attack=1, Interact=2, PickUp=3, OpenChest=4, Unlock=5,
        // LvlTransition=6, Buy=7, Mine=8, Alchemy=9, rest=10
        byte[] codes = {
            NetworkManager.encodeAction(new HeroAction.Move(0)),
            NetworkManager.encodeAction(new HeroAction.Attack(null)),
            NetworkManager.encodeAction(new HeroAction.Interact(null)),
            NetworkManager.encodeAction(new HeroAction.PickUp(0)),
            NetworkManager.encodeAction(new HeroAction.OpenChest(0)),
            NetworkManager.encodeAction(new HeroAction.Unlock(0)),
            NetworkManager.encodeAction(new HeroAction.LvlTransition(0)),
            NetworkManager.encodeAction(new HeroAction.Buy(0)),
            NetworkManager.encodeAction(new HeroAction.Mine(0)),
            NetworkManager.encodeAction(new HeroAction.Alchemy(0)),
        };

        // Then: all codes are distinct
        for (int i = 0; i < codes.length; i++) {
            for (int j = i + 1; j < codes.length; j++) {
                assertNotEquals(codes[i], codes[j],
                        "Action types at index " + i + " and " + j +
                        " share encoding byte " + codes[i] + " — each type must be unique");
            }
        }
    }

    // -------------------------------------------------------------------------
    // Null / unknown action must not crash the receiver
    // -------------------------------------------------------------------------

    @Test
    void unknownActionByte_decodesGracefully() throws Exception {
        // Given: a byte value not in the defined range (e.g. 127)
        // When: decoded
        HeroAction result = NetworkManager.decodeAction((byte) 127, 0);

        // Then: returns null or a safe default — must not throw
        // (null is acceptable; the caller handles null curAction)
        // This test verifies no ArrayIndexOutOfBoundsException or NPE
    }
}
