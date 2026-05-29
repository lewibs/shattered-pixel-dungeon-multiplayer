---
name: games-in-progress-static-state
description: "How to add new cross-scene state for multiplayer features using GamesInProgress static fields, and how to guard downstream code for backwards compatibility."
user-invocable: false
---
## When to use
When a multi-step UI flow (spanning scene switches) needs to pass configuration state into `Dungeon.init()` or any scene that follows hero selection. `GamesInProgress` is the canonical store for pre-game static state in this codebase.

## Steps
1. Declare new fields as `public static` on `GamesInProgress`. Initialise them to safe single-player defaults (e.g. `playerCount = 1`, `selectedClasses = new ArrayList<>()`, `currentPlayerSelecting = 0`).
2. Reset all new fields explicitly at the call site that triggers the new flow (do not rely on field initializers surviving a game restart within the same process). Reset must happen at the call site, not only inside `WndPlayerCount`: if `selectedClasses` is non-empty when `WndPlayerCount` opens, `HeroSelectScene` may iterate through stale entries from the prior run before the window ever fires its confirm handler.
3. In consuming code (`Dungeon.init()`, scenes), guard with a null/empty check and fall back to the legacy field (`selectedClass`) so existing saves and single-player games continue to work:
   ```java
   if (GamesInProgress.selectedClasses != null && !GamesInProgress.selectedClasses.isEmpty()) {
       // new multi-hero path
   } else {
       // legacy single-player fallback
   }
   ```
4. Add i18n keys for any new UI text in `core/src/main/assets/messages/` under the appropriate namespace (`scenes/` for scenes, `windows/` for windows).

## Notes
- `GamesInProgress.selectedClass` (singular) is the legacy single-player field; `selectedClasses` (plural) is the new list. Both must remain in sync for the fallback to work correctly.
- Static fields on `GamesInProgress` survive scene switches within the same process session. They do NOT persist to disk — they are purely in-memory flow state, reset at game start.
- `currentPlayerSelecting` is 0-based internally; display it as 1-based in UI (`currentPlayerSelecting + 1`).
- When a scene loops back to itself (e.g. `HeroSelectScene` re-entered for player 2), `Dungeon.hero` must be set to `null` at the top of `create()` to prevent stale hero references from the prior player's selection render.
