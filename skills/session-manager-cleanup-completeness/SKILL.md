---
name: session-manager-cleanup-completeness
description: "Every stateful field in a session manager (e.g. NetworkManager) must be explicitly reset in cleanup() to prevent cross-session corruption."
user-invocable: false
---
## When to use
When adding a new field to any class that manages session-scoped state (NetworkManager, GamesInProgress, or similar singletons) and that class has a cleanup()/reset()/disconnect() method called between sessions.

## Steps
1. When adding a new field to a session manager, immediately add a matching reset line inside cleanup() (or the equivalent teardown method) in the same commit/change.
2. Reset to the safe single-player default — not just zero or null blindly. For player indices, 0 is the safe default (host/local player). For lists, clear() or reassign to an empty collection.
3. Audit the full field list of the class before merging: compare every declared instance field against every assignment in cleanup(). Missing fields are the bug.
4. When a cleanup() method is not present, create one and call it from every code path that ends a session (disconnect, game over, return to main menu).

## Notes
- The failure mode is silent: the stale field value from session N is used as a valid index/flag in session N+1, causing crashes only under the specific conditions that expose the mismatch (e.g., solo play after a LAN session).
- `NetworkManager.localPlayerIndex` omitted from cleanup() caused `heroes.get(1)` on a size-1 list in solo play, resulting in IndexOutOfBoundsException in GameScene. Fix: `localPlayerIndex = 0` in cleanup().
- Cross-session corruption bugs are hard to reproduce in automated tests because they require two consecutive sessions in the same process. Manual test protocol: start a LAN game, quit to menu, start a solo game — verify no crash.
- Apply the same audit to any static fields on GamesInProgress that are set during a multiplayer session and not cleared on the path back to solo play.
- `LinkedBlockingQueue` fields used for inter-thread packet routing (e.g. `hashQueue`, `resyncQueue`) must be cleared in `cleanup()` via `queue.clear()` — not reassigned. Stale queue entries from a previous session will be consumed as valid packets in the next session, causing immediate desync or ghost actions.
