# LAN Leader-Sequenced Commit Protocol (v3)

## Why

The v2 protocol broadcast raw `ACTION` packets peer-to-peer with optimistic
execution and per-turn reader threads. It had three structural desync sources:

1. The host never relayed one client's gameplay packets to other clients — in
   3–4 player games client A's actions never reached client B at all.
2. The op's sender executed immediately, before anyone confirmed delivery, so
   reader-lifecycle races silently forked the simulations.
3. There was no dedup/retransmit and no recovery: the `STATE_HASH` detector
   (every 10 turns) only reported; divergence was permanent.

v3 makes the host a **leader**: every gameplay op goes to the leader, gets a
place in one global order, and is dispersed from there. Execution is
**pessimistic** — nobody (including the op's owner and the leader itself)
executes an op before it holds a commit sequence number.

## The flow

```
follower input : follower --REQUEST(clientSeq, op)--> leader
leader         : stamps globalSeq --COMMIT(globalSeq, player, clientSeq, op)--> ALL followers
                 (+ applies remote ops locally)
follower       : applies commits strictly in globalSeq order, sends ACK
owner          : blocked in submitLocalOp until its own COMMIT echo returns,
                 then executes
leader input   : same, minus the network hop (leaderSequence directly)
```

All op kinds ride the same stream (`InnerOp`): hero ACTIONs, mid-action
ITEM/CELL/OPTION choices, ITEM_IDENTIFIED. This is what fixes 3+ player relay:
the leader broadcasts every commit to every follower.

## Reliability (acks, dedup, retransmit)

- The leader keeps a ring buffer of the last 1024 commits (`commitLog`) and
  each follower's highest ACKed seq. If a follower's ACK stalls
  (`COMMIT_RESEND_MS`), the leader replays everything after it.
- Followers dedupe by `globalSeq`: a stale commit (≤ lastAppliedSeq) is
  re-ACKed and dropped — retransmits can never double-apply an op.
- Followers that get no echo re-send their REQUEST every `REQUEST_RESEND_MS`;
  the leader dedupes requests by per-player `clientSeq`, so a re-sent request
  is never sequenced twice.
- Out-of-order commits park in a `reorderBuffer` and apply once contiguous.

## Dispatch

One **persistent reader thread per socket** (`ensureGameplayReaders`, started
at gameplay start and lazily from `Hero.act`/`submitLocalOp`). Committed
ACTIONs are queued into the owning hero's `lanActionInbox` (keyed by the
packet's `player` field — never by which stream they arrived on) and decoded
at execution time inside `Hero.act()`. Choice commits that arrive before the
local sim parks its prompt are stashed (`deliverChoiceCommit` /
`parkRemote*`) and applied at park time — arrival order no longer matters.

## Desync detection + self-heal (mobs can never STAY diverged)

Mobs and all other actors remain a deterministic local simulation (see
`lan-lockstep-determinism.md`) — mob decisions never cross the wire. The
protocol guarantees identical *inputs*; determinism guarantees identical
*outcomes*; and when a determinism bug slips through anyway:

- Every device passes through the identical sim point "about to execute
  commit K" (`onAboutToExecuteCommit`). The leader broadcasts its state hash
  there (`STATE_CHECK`); each follower computes its own at its own execution
  of K and compares (order-independent bookkeeping in
  `leaderHashes`/`localHashes`).
- On mismatch the follower fires `desyncDetectedSignal` and calls
  `requestSnapshotResync()` (rate-limited to one per 5 s).
- The leader freezes sequencing (`seqLock`), waits for its sim to quiesce,
  runs `Dungeon.saveAll()`, and ships the game + current-level bundles plus
  its current globalSeq (`SNAPSHOT`).
- The follower writes the bundles to its local save slot, fast-forwards
  `lastAppliedSeq` to the snapshot seq, clears all in-flight protocol state,
  and reloads via `InterlevelScene.Mode.CONTINUE`. Commits that arrived during
  the reload drain from the reorder buffer afterwards.

The leader never resyncs — it is the definition of truth.

## Wire formats

```
REQUEST(25)     [int clientSeq][byte inner][byte actionType][int targetPos][UTF if ITEM_IDENTIFIED]
COMMIT(26)      [int globalSeq][int player][int clientSeq][byte inner][byte actionType][int targetPos][UTF if ITEM_IDENTIFIED]
ACK(27)         [int lastAppliedSeq]
STATE_CHECK(28) [int globalSeq][long hash]
SNAPSHOT_REQUEST(29)  (empty)
SNAPSHOT(30)    [int seqAt][int depth][int branch][int gameLen][bytes][int levelLen][bytes]
```

`PROTOCOL_VERSION` is 3; the old `ACTION`/`STATE_HASH` frames are dead.

## Key invariants for future changes

- Never execute a locally-originated gameplay op without first passing it
  through `submitLocalOp` (via `Hero.lanSendPerformed` or the choice senders).
- Commits must be applied in contiguous `globalSeq` order on followers; any
  new packet type that mutates sim state must be an `InnerOp`, not a new
  top-level packet, or it escapes the ordering/dedup/retransmit guarantees.
- `onAboutToExecuteCommit(seq)` must be called at the pre-execution sim point
  on every device — if you add a new consumption path for commits, call it.
- The reader threads own the sockets after gameplay starts; lobby-phase code
  must not read from `ins`/`clientIn` once `ensureGameplayReaders` has run.

## Testing

`core/src/test/java/.../lan/LanTestProtocol.java` writes v3 frames and provides
`FakeLeader` (echoes a follower's REQUESTs as COMMITs). The master test remains
`LanLockstepReplayTest`: identical per-step state fingerprints across a
host-device run and a client-device run fed the same commit stream.
