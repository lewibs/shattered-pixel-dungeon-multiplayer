---
name: session-index-bounds-clamp
description: "Array/list accesses keyed by a session-scoped index (e.g. localPlayerIndex) must use Math.min() as a defensive clamp against stale index values."
user-invocable: false
---
## When to use
Any time a session-scoped integer (player index, slot index, hero index) is used to subscript into a list or array whose size can vary between sessions or game modes. This applies in GameScene, Dungeon, and any renderer/UI that reads from Dungeon.heroes by index.

## Steps
1. Identify the access: `list.get(index)` or `array[index]` where `index` comes from a session manager field rather than a local loop variable.
2. Clamp the index before use:
   ```java
   int safeIndex = Math.min(index, list.size() - 1);
   SomeType item = list.get(safeIndex);
   ```
3. Additionally fix the root cause (ensure cleanup() resets the index to 0 — see `session-manager-cleanup-completeness` skill). The clamp is a secondary guard, not a substitute for the root fix.
4. Add a comment at the clamp site explaining why it exists:
   ```java
   // Defensive clamp: localPlayerIndex may be stale (e.g. 1) after a LAN session
   // if NetworkManager.cleanup() was not called or did not reset the field.
   int safeIndex = Math.min(NetworkManager.localPlayerIndex, Dungeon.heroes.size() - 1);
   ```

## Notes
- The clamp must guard the access, not just assert. A failing assert in release builds does nothing; Math.min() always protects.
- If `list.size()` could be 0, add a null/empty check before the clamp to avoid returning index -1.
- This pattern was first applied in GameScene when `localPlayerIndex=1` (stale from a prior LAN session) caused `heroes.get(1)` on a single-hero list.
- The clamp is also appropriate anywhere Dungeon.heroes is indexed by a value sourced from the network or a configuration (player count, player slot) rather than a locally iterated counter.
