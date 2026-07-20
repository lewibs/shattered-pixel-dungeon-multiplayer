package com.shatteredpixel.shatteredpixeldungeon.lan;

import com.shatteredpixel.shatteredpixeldungeon.network.NetworkManager;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Helpers for driving the v3 leader-sequenced commit protocol in tests.
 *
 * Protocol summary:
 * - A FOLLOWER device only APPLIES ops it receives as COMMIT frames, strictly
 *   in contiguous globalSeq order (1, 2, 3, ...). To feed a follower's remote
 *   hero, write ACTION COMMITs into its upstream socket.
 * - The LEADER device (isHost=true) reads REQUEST frames from client streams,
 *   assigns the globalSeq itself, applies the op locally and broadcasts the
 *   COMMIT. To feed the leader's remote hero, write ACTION REQUESTs (with a
 *   per-client contiguous clientSeq: 1, 2, 3, ...).
 * - A follower's own hero BLOCKS inside sendAction until the leader echoes the
 *   commit. Tests exercising a follower's local hero must run a
 *   {@link FakeLeader} on the other end of the socket (or lower
 *   {@link NetworkManager#setEchoTimeoutForTesting}).
 */
public class LanTestProtocol {

    /** Writes one ACTION COMMIT frame — feeds a FOLLOWER device's reader. */
    public static void writeActionCommit(DataOutputStream out, int globalSeq, int player, int clientSeq,
                                         byte actionType, int targetPos) throws IOException {
        synchronized (out) {
            out.writeByte(NetworkManager.PacketType.COMMIT);
            out.writeInt(globalSeq);
            out.writeInt(player);
            out.writeInt(clientSeq);
            out.writeByte(NetworkManager.InnerOp.ACTION);
            out.writeByte(actionType);
            out.writeInt(targetPos);
            out.flush();
        }
    }

    /** Writes one choice COMMIT frame (ITEM_CHOICE / CELL_CHOICE / OPTION_CHOICE). */
    public static void writeChoiceCommit(DataOutputStream out, int globalSeq, int player,
                                         byte inner, int payload) throws IOException {
        synchronized (out) {
            out.writeByte(NetworkManager.PacketType.COMMIT);
            out.writeInt(globalSeq);
            out.writeInt(player);
            out.writeInt(0);
            out.writeByte(inner);
            out.writeByte((byte) 0);
            out.writeInt(payload);
            out.flush();
        }
    }

    /** Writes one ITEM_IDENTIFIED COMMIT frame. */
    public static void writeItemIdentifiedCommit(DataOutputStream out, int globalSeq, int player,
                                                 String className) throws IOException {
        synchronized (out) {
            out.writeByte(NetworkManager.PacketType.COMMIT);
            out.writeInt(globalSeq);
            out.writeInt(player);
            out.writeInt(0);
            out.writeByte(NetworkManager.InnerOp.ITEM_IDENTIFIED);
            out.writeByte((byte) 0);
            out.writeInt(0);
            out.writeUTF(className);
            out.flush();
        }
    }

    /** Writes one ACTION REQUEST frame — feeds the LEADER device's reader, as if from a client. */
    public static void writeActionRequest(DataOutputStream out, int clientSeq,
                                          byte actionType, int targetPos) throws IOException {
        synchronized (out) {
            out.writeByte(NetworkManager.PacketType.REQUEST);
            out.writeInt(clientSeq);
            out.writeByte(NetworkManager.InnerOp.ACTION);
            out.writeByte(actionType);
            out.writeInt(targetPos);
            out.flush();
        }
    }

    /** Writes one choice REQUEST frame — feeds the LEADER device's reader. */
    public static void writeChoiceRequest(DataOutputStream out, int clientSeq,
                                          byte inner, int payload) throws IOException {
        synchronized (out) {
            out.writeByte(NetworkManager.PacketType.REQUEST);
            out.writeInt(clientSeq);
            out.writeByte(inner);
            out.writeByte((byte) 0);
            out.writeInt(payload);
            out.flush();
        }
    }

    /**
     * Reads frames from a leader's outgoing stream until an ACTION COMMIT is
     * found; returns {globalSeq, player, actionType, targetPos}. Skips
     * STATE_CHECK / PING / choice commits. Times out via socket timeout.
     */
    public static int[] readNextActionCommit(DataInputStream in) throws IOException {
        while (true) {
            byte type = in.readByte();
            if (type == NetworkManager.PacketType.COMMIT) {
                int globalSeq = in.readInt();
                int player = in.readInt();
                in.readInt(); // clientSeq
                byte inner = in.readByte();
                byte actionType = in.readByte();
                int targetPos = in.readInt();
                if (inner == NetworkManager.InnerOp.ITEM_IDENTIFIED) in.readUTF();
                if (inner == NetworkManager.InnerOp.ACTION) {
                    return new int[]{globalSeq, player, actionType, targetPos};
                }
            } else if (type == NetworkManager.PacketType.STATE_CHECK) {
                in.readInt();
                in.readLong();
            } else if (type == NetworkManager.PacketType.PING) {
                // skip
            } else {
                throw new IOException("unexpected packet type " + type + " while scanning for ACTION COMMIT");
            }
        }
    }

    /**
     * A minimal fake leader for follower-perspective tests. Reads the
     * follower's upstream: echoes every REQUEST back as the next COMMIT
     * (releasing the follower's pessimistic echo gate), and swallows
     * ACK / PING / SNAPSHOT_REQUEST frames. Can also inject commits owned
     * by other players via {@link #sendActionCommitFrom}.
     */
    public static class FakeLeader implements AutoCloseable {
        private final DataInputStream fromFollower;
        private final DataOutputStream toFollower;
        private final int followerPlayerIndex;
        private final Thread thread;
        private volatile boolean running = true;
        private int globalSeq;
        public volatile int commitsEchoed = 0;

        public FakeLeader(DataInputStream fromFollower, DataOutputStream toFollower, int followerPlayerIndex) {
            this(fromFollower, toFollower, followerPlayerIndex, 0);
        }

        public FakeLeader(DataInputStream fromFollower, DataOutputStream toFollower,
                          int followerPlayerIndex, int startSeq) {
            this.fromFollower = fromFollower;
            this.toFollower = toFollower;
            this.followerPlayerIndex = followerPlayerIndex;
            this.globalSeq = startSeq;
            this.thread = new Thread(this::loop, "test-fake-leader");
            this.thread.setDaemon(true);
            this.thread.start();
        }

        private void loop() {
            try {
                while (running) {
                    byte type = fromFollower.readByte();
                    if (type == NetworkManager.PacketType.REQUEST) {
                        int clientSeq = fromFollower.readInt();
                        byte inner = fromFollower.readByte();
                        byte actionType = fromFollower.readByte();
                        int targetPos = fromFollower.readInt();
                        String utf = (inner == NetworkManager.InnerOp.ITEM_IDENTIFIED)
                                ? fromFollower.readUTF() : null;
                        synchronized (toFollower) {
                            toFollower.writeByte(NetworkManager.PacketType.COMMIT);
                            toFollower.writeInt(++globalSeq);
                            toFollower.writeInt(followerPlayerIndex);
                            toFollower.writeInt(clientSeq);
                            toFollower.writeByte(inner);
                            toFollower.writeByte(actionType);
                            toFollower.writeInt(targetPos);
                            if (inner == NetworkManager.InnerOp.ITEM_IDENTIFIED) {
                                toFollower.writeUTF(utf != null ? utf : "");
                            }
                            toFollower.flush();
                        }
                        commitsEchoed++;
                    } else if (type == NetworkManager.PacketType.ACK) {
                        fromFollower.readInt();
                    } else if (type == NetworkManager.PacketType.SNAPSHOT_REQUEST
                            || type == NetworkManager.PacketType.PING) {
                        // swallow
                    } else if (type == NetworkManager.PacketType.NAME_ANNOUNCE) {
                        fromFollower.readUTF();
                    } else {
                        // Unknown frame from follower — stop rather than misparse.
                        break;
                    }
                }
            } catch (IOException ignored) {
                // socket closed — test teardown
            }
        }

        /** Injects an ACTION commit owned by another player (e.g. the leader's own hero, player 0). */
        public void sendActionCommitFrom(int player, byte actionType, int targetPos) throws IOException {
            synchronized (toFollower) {
                writeActionCommit(toFollower, ++globalSeq, player, 0, actionType, targetPos);
            }
        }

        /** Injects a choice commit owned by another player. */
        public void sendChoiceCommitFrom(int player, byte inner, int payload) throws IOException {
            synchronized (toFollower) {
                writeChoiceCommit(toFollower, ++globalSeq, player, inner, payload);
            }
        }

        public int currentSeq() {
            return globalSeq;
        }

        @Override
        public void close() {
            running = false;
            thread.interrupt();
        }
    }
}
