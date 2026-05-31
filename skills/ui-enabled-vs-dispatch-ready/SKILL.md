---
name: ui-enabled-vs-dispatch-ready
description: "Enabling a UI component does not bypass the dispatch method's own ready guards; when adding LAN action-queuing, audit every guard inside the component's select/dispatch path, not just the component's enabled flag."
user-invocable: false
---
## When to use
Any time you set `component.enabled = true` (or keep it enabled) in LAN mode to allow players to queue an action while the current action is still executing, and the taps are still being silently discarded.

## Steps
1. Set the UI component's `enabled` flag as intended — this controls whether the component receives raw input events at all.
2. Open the component's dispatch method (e.g. `CellSelector.select()`) and look for every guard that tests `Dungeon.hero.ready` or equivalent state.
3. For each such guard, evaluate whether it makes sense in LAN queuing mode. If the intent is "allow a pre-queued tap even while not ready", introduce a boolean alias:
   ```java
   boolean readyOrLan = Dungeon.hero.ready
       || (NetworkManager.lanMode && Dungeon.hero.isAlive());
   ```
4. Replace `Dungeon.hero.ready` in the guard with `readyOrLan`.
5. Verify no other method in the call chain re-checks `ready` and swallows the action before it reaches `curAction`.

## Notes
- In SPD, `CellSelector.select()` had its own `Dungeon.hero.ready` check that ran independently of the `enabled` flag. Setting `enabled = true` only got the tap past the scroll-area filter; the `select()` body still discarded it.
- The fix in `CellSelector.java` (2026-05-30) added `readyOrLan` to allow taps to set `curAction` while the hero was mid-action.
- This pattern is distinct from the render-loop-enable-overwrite skill: that one is about per-frame calls silently re-enabling a component; this one is about the dispatch method having its own guards that are invisible from outside the component.
- Always search the dispatch path for any reference to `hero.ready`, `Game.ready`, or equivalent before declaring that "enabling the component" is sufficient for queuing.
