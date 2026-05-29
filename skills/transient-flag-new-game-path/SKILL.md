---
name: transient-flag-new-game-path
description: "Use a non-bundled static boolean to gate new-game-only initialization logic inside a code path shared with the load/continue path."
user-invocable: false
---
## When to use
When `Dungeon.switchLevel()` (or any other method called by both `InterlevelScene.descend()` on a new game AND `InterlevelScene.restore()` on load) needs to perform a one-time initialization that must NOT run on the load path. The flag is the canonical way to distinguish the two call sites without duplicating the method or adding a parameter.

## Steps
1. Declare a `public static boolean` field on `Dungeon` with a default of `false`:
   ```java
   public static boolean heroesNeedInitialPlacement = false;
   ```
   Do NOT add this field to any `storeInBundle` / `restoreFromBundle` call. Its absence from the bundle means it silently resets to `false` on every load — which is exactly the desired load-path behavior.

2. Set the flag to `true` in `Dungeon.init()` (the new-game entry point) after the condition that requires one-time setup applies:
   ```java
   if (heroes.size() > 1) {
       heroesNeedInitialPlacement = true;
   }
   ```

3. Inside the shared code path (e.g. `switchLevel`), check the flag, clear it immediately (so it is single-use), then run the one-time logic:
   ```java
   if (heroesNeedInitialPlacement) {
       heroesNeedInitialPlacement = false;
       // ... one-time new-game-only work ...
   }
   ```
   Clearing before the work (not after) ensures a crash inside the block cannot leave the flag set true and re-trigger on the next call.

## Notes
- This pattern works because `Dungeon` fields that are not bundled default to their Java initializer values (`false` for boolean) every time a save file is loaded. The load path never touches `init()`, so the flag stays `false` throughout a load/continue run.
- If the one-time work needs passability checks (placing characters on a level), do them inside the flag block after `Dungeon.level` and `PathFinder` have been initialized — both are available by the time `switchLevel` is executing its main body.
- For passability fallback when placing extra heroes adjacent to a spawn point, iterate `PathFinder.NEIGHBOURS8` and use `level.passable[candidate] && Actor.findChar(candidate) == null` as the guard. Fall back to `pos` (same cell as hero[0]) only if no valid neighbour is found.
- Single-player games are unaffected as long as the flag is only set when `heroes.size() > 1`.
