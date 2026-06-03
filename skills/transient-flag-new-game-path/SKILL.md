---
name: transient-flag-new-game-path
description: "Use a non-bundled static boolean to gate initialization logic inside a shared code path, setting the flag at any call site that requires it while leaving the load/continue path unaffected."
user-invocable: false
---
## When to use
When `Dungeon.switchLevel()` (or any other method shared between multiple call sites) needs to perform work that must run at some call sites but NOT on the load/continue path (`InterlevelScene.restore()`). The flag is the canonical way to distinguish call sites without duplicating the method or adding a parameter.

This pattern applies to:
- New-game startup (`Dungeon.init()`) — one-time initial placement.
- Every stair transition (`InterlevelScene.descend()`, `InterlevelScene.ascend()`) — recurring placement on each floor change.
- Any future call site that needs the same downstream work.

The load path (`InterlevelScene.restore()`) never sets the flag, so saved positions are always preserved on load.

## Steps
1. Declare a `public static boolean` field on `Dungeon` with a default of `false`:
   ```java
   public static boolean heroesNeedInitialPlacement = false;
   ```
   Do NOT add this field to any `storeInBundle` / `restoreFromBundle` call. Its absence from the bundle means it silently resets to `false` on every load — which is exactly the desired load-path behavior.

2. Set the flag to `true` at every call site that requires the downstream work. Do this just before the call to `switchLevel()`:
   ```java
   // In Dungeon.init() — new-game path
   if (heroes.size() > 1) {
       heroesNeedInitialPlacement = true;
   }

   // In InterlevelScene.descend() and InterlevelScene.ascend() — stair transition path
   if (Dungeon.heroes != null && Dungeon.heroes.size() > 1) {
       Dungeon.heroesNeedInitialPlacement = true;
   }
   Dungeon.switchLevel( level, destTransition.cell() );
   ```
   The guard (`heroes.size() > 1`) keeps single-player games on the unchanged code path.

3. Inside the shared code path (e.g. `switchLevel`), check the flag, clear it immediately (so it is single-use), then run the conditional logic:
   ```java
   if (heroesNeedInitialPlacement) {
       heroesNeedInitialPlacement = false;
       // ... placement work ...
   }
   ```
   Clearing before the work (not after) ensures a crash inside the block cannot leave the flag set true and re-trigger on the next call.

## Notes
- This pattern works because `Dungeon` fields that are not bundled default to their Java initializer values (`false` for boolean) every time a save file is loaded. The load path never touches `init()` or the stair methods, so the flag stays `false` throughout a load/continue run.
- The flag can be set by multiple independent call sites. Each sets it immediately before calling the shared method, so there is no risk of the flag being stale from a previous call site.
- If the conditional work needs passability checks (placing characters on a level), do them inside the flag block after `Dungeon.level` and `PathFinder` have been initialized — both are available by the time `switchLevel` is executing its main body.
- For passability fallback when placing extra heroes adjacent to a spawn point, iterate `PathFinder.NEIGHBOURS8` and use `level.passable[candidate] && Actor.findChar(candidate) == null` as the guard. Fall back to `pos` (same cell as hero[0]) only if no valid neighbour is found.
- Single-player games are unaffected as long as every set-site guards with `heroes.size() > 1`.
- Prefer setting the flag at the higher-level call site (e.g. `InterlevelScene`) rather than modifying the lower-level shared method (`switchLevel`). This keeps `switchLevel` free of call-site-specific knowledge.
