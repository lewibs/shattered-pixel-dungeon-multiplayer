---
name: render-loop-enable-overwrite
description: "When a scene method calls b.enable() every render frame, any active=false state set by async callbacks is silently overwritten; guard the call by computing canEnable with all constraints before calling enable() once."
user-invocable: false
---
## When to use
Any time you set `component.active = false` or call `component.enable(false)` from an async callback (network handler, event listener, background thread dispatch) and the component still responds to input — or you add a state constraint (e.g. "this button is locked by a remote player") that needs to survive the scene's `update()` / `updateFade()` / `draw()` cycle.

The failure mode is subtle: the scene's per-frame method unconditionally calls `b.enable(alpha != 0)`, which re-enables the button on every frame after it was disabled, so the disable has no lasting effect.

## Steps
1. Locate the per-frame method that calls `component.enable(someCondition)` (often `updateFade()` in SPD scenes).
2. Instead of passing `someCondition` directly, compute a `boolean canEnable = someCondition` first.
3. Before calling `enable()`, apply additional veto conditions via `if (canEnable && <extra constraint>) canEnable = false;`.
4. Call `component.enable(canEnable)` exactly once with the final value.
5. Extract the "is this component currently locked out?" logic into a dedicated predicate method (e.g. `HeroBtn.isTaken()`) so the condition is testable and readable without inline comments.

## Notes
- In HeroSelectScene the bug manifested as: `CLASS_CLAIMED` handler set buttons `active=false`, but `updateFade()` ran 60 times/sec and called `b.enable(alpha != 0)`, restoring `active=true` on the very next frame.
- The fix (lines 705-716 of `HeroSelectScene.java`) computes `boolean canEnable = alpha != 0` then checks `((HeroBtn) b).isTaken()` before calling `b.enable(canEnable)`.
- This pattern applies to any UI state that must persist across frames when set from outside the render loop (network callbacks, timers, background threads that dispatch via `Game.runOnRenderThread()`).
- Do not set `component.active` directly as a workaround — the per-frame call will still overwrite it. The fix must be inside the per-frame method itself.
- If multiple constraints exist (claimed by peer, locally confirmed, alpha == 0), chain them all with `if (canEnable && ...)` guards rather than a single complex boolean, for readability and future-proofing.
