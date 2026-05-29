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
3. **Add i18n keys** in `core/src/main/assets/messages/windows/windows.properties` following the convention `windows.<classname_lowercase>.<key>=value`.
4. **Reset all new static state fields** inside the window's confirm handler before switching scenes — do not assume they are clean from a prior run.
5. **Guard the new behaviour** in the target scene with `if (GamesInProgress.playerCount > 1)` so single-player code paths are entirely unchanged.

## Notes
- `addToFront()` is used (not `add()`) so the window layers correctly over scene content.
- Both `StartScene` and `TitleScene` contain independent paths to `HeroSelectScene`; both must be updated or the window will be skipped on first launch when no saves exist.
- The window must call `hide()` before `switchScene` — failing to do so leaves a dangling window object on the old scene and can cause NPEs on scene teardown.
