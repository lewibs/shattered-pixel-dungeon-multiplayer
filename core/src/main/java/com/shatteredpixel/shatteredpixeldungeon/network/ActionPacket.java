package com.shatteredpixel.shatteredpixeldungeon.network;

/**
 * Data transfer object for action packets sent over the network.
 * Represents a single hero action that needs to be transmitted to remote peers.
 */
public class ActionPacket {
    public static final byte TYPE = 1;

    public int heroId;           // ID of the hero performing the action
    public byte actionType;      // Type of action (Move, Attack, Interact, etc.)
    public int targetPos;        // Target cell index for the action

    public ActionPacket(int heroId, byte actionType, int targetPos) {
        this.heroId = heroId;
        this.actionType = actionType;
        this.targetPos = targetPos;
    }

    public ActionPacket() {
        this(0, (byte)0, 0);
    }
}
