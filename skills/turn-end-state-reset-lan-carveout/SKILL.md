---
name: turn-end-state-reset-lan-carveout
description: "Methods called at turn-end (e.g. Hero.ready()) that unconditionally clear shared state destroy LAN pre-queued actions; guard the reset with a LAN-mode check to preserve state needed across the turn boundary."
user-invocable: false
---
## When to use
Any time a player's queued action (set during a previous turn via pre-queuing) is silently lost — the hero just stands still even though the player tapped a destination — and the action was confirmed to reach `curAction` but then disappears before the actor loop sees it.

## Steps
1. Find the method that is called at the end of every hero turn to reset hero state for the next turn. In SPD this is `Hero.ready()`.
2. Identify every field that is unconditionally cleared (`= null`, `= false`, etc.).
3. For each field that participates in LAN action-queuing (e.g. `curAction`), wrap the reset in a mode guard:
   ```java
   if (!NetworkManager.lanMode) {
       curAction = null;
   }
   ```
4. Confirm that the actor loop (the `act()` method) checks `curAction != null` at the top of the next turn and dispatches immediately without requiring another player tap.
5. Add a comment explaining why the reset is suppressed in LAN mode, to prevent a future developer from "fixing" the carve-out.

## Notes
- In SPD, `Hero.ready()` set `curAction = null` unconditionally. After the 2026-05-30 fix, it only clears `curAction` outside LAN mode.
- The actor loop in `Hero.act()` already checks `curAction != null` at the top and dispatches it, so preserving the field is sufficient — no other change was needed.
- This pattern can apply to any "reset method" called at actor-turn boundaries. Before adding a LAN carve-out, confirm that the preserved state cannot cause a hang (e.g. a stale curAction that is never consumed). Test by verifying `curAction` is consumed and then cleared within the same `act()` invocation.
- Do not preserve `curAction` across saves/loads — it should still be transient. Verify the field is not written to the save bundle, or clear it explicitly in the save path.
