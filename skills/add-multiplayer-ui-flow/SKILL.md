---
name: add-multiplayer-ui-flow
description: "How to intercept an existing single-player scene transition and insert a multiplayer setup window (WndPlayerCount pattern) without breaking the single-player path."
user-invocable: false
---
## When to use
When adding a new pre-game configuration step (player count, difficulty, team composition, etc.) that must gate an existing scene switch (e.g. StartScene → HeroSelectScene) without altering the single-player happy path.

## Steps
1. **Create a new `Wnd*` class** (`Window` subclass) that sets the new static state on `GamesInProgress` and then calls `ShatteredPixelDungeon.switchScene(TargetScene.class)` on confirmation. Call `hide()` before switching scenes so the window dismisses cleanly.
2. **In the calling scene**, replace the direct `ShatteredPixelDungeon.switchScene(TargetScene.class)` call with `scene.addToFront(new WndYourWindow())`. Do this in every entry point that used to switch directly (both `StartScene.SaveSlotButton.onClick()` and `TitleScene` new-game path).
3. **Enumerate ALL new-game entry points** — there are at least three independent code paths that lead to `HeroSelectScene`:
   - `StartScene.SaveSlotButton.onClick()` — user picks a save slot
   - `TitleScene` new-game path — no saves exist
   - `GameScene.gameOver()` restart button — player clicks "New Game" after dying
   Each path must independently route through `WndPlayerCount`. A pattern applied to only two of the three will silently bypass the window on the third.
4. **Reset all `GamesInProgress` multiplayer fields at the call site** before showing `WndPlayerCount`, not just inside the window:
   ```java
   GamesInProgress.selectedClass = null;
   GamesInProgress.playerCount = 1;
   GamesInProgress.selectedClasses = new ArrayList<>();
   GamesInProgress.currentPlayerSelecting = 0;
   GamesInProgress.curSlot = GamesInProgress.firstEmpty();
   GameScene.show(new WndPlayerCount());
   ```
   Static fields survive within the same process session. If a prior run populated `selectedClasses`, those values persist and cause `HeroSelectScene` to see a non-empty list on the next run, skipping player selection steps.
5. **Add i18n keys** in `core/src/main/assets/messages/windows/windows.properties` following the convention `windows.<classname_lowercase>.<key>=value`.
6. **Guard the new behaviour** in the target scene with `if (GamesInProgress.playerCount > 1)` so single-player code paths are entirely unchanged.

## Notes
- `addToFront()` is used (not `add()`) so the window layers correctly over scene content.
- `GameScene.show()` (not `addToFront()`) is required when called from within `GameScene` — it registers the window correctly on the current scene without requiring a scene reference.
- Both `StartScene` and `TitleScene` contain independent paths to `HeroSelectScene`; both must be updated or the window will be skipped on first launch when no saves exist. `GameScene.gameOver()` is a third independent path added when multiplayer was introduced and must be kept in sync.
- The window must call `hide()` before `switchScene` — failing to do so leaves a dangling window object on the old scene and can cause NPEs on scene teardown.
- When adding any future new-game entry point (e.g. a "retry" button, an in-run scene change), audit all three existing entry points first and apply the same `WndPlayerCount` routing to the new one.
