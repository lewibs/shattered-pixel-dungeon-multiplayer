---
name: multiplayer-guard-pattern
description: "How to add multiplayer-aware logic to an existing single-player method without breaking single-player behavior."
user-invocable: false
---
## When to use
Any time a mechanic (transition, action gate, event trigger, etc.) needs to behave differently when multiple heroes are present, while remaining completely inert for single-player runs.

## Steps
1. Locate the single-player method that needs multi-hero awareness (e.g. `Level.activateTransition()`).
2. Identify the earliest safe insertion point — typically just after any early-return guards (like `if (locked) return false;`) so the new check does not run unnecessary work.
3. Add a null-safe size guard before iterating `Dungeon.heroes`:
   ```java
   if (Dungeon.heroes != null && Dungeon.heroes.size() > 1) {
       for (Hero other : Dungeon.heroes) {
           if (other == hero) continue; // skip the acting hero
           // ... multiplayer-specific logic ...
       }
   }
   ```
4. Inside the loop, use `Level.distance(a, b)` (Chebyshev distance) for adjacency checks — `distance <= 1` means adjacent or same cell. Do NOT use Manhattan or Euclidean math; the game grid is Chebyshev.
5. Show feedback via `GLog.w(Messages.get(Level.class, "your_key"))` when blocking, and add the key to `core/src/main/assets/messages/levels/levels.properties` as `levels.level.your_key=...`.
6. Return the existing single-player return value unchanged when the guard is not triggered.

## Notes
- The guard `heroes.size() > 1` is the canonical single-player bypass. Never skip it — code that iterates `heroes` unconditionally will run on single-player if `heroes` contains exactly one entry (which it does).
- `Dungeon.hero` is still the acting/primary hero singleton. Use it for single-player-safe reads; use `Dungeon.heroes` only inside the size guard.
- `Level.adjacent(a, b)` returns `distance(a,b) == 1` (excludes same cell). Use `distance(a,b) <= 1` when same-cell should also be considered "close enough."
- This pattern was first applied to `Level.activateTransition()` for the party stair gate feature. The same idiom applies to any future party-cohesion gates (boss entry, shop entry, portal use, etc.).
- Message keys in `levels.properties` follow the form `levels.level.<key>` and are retrieved with `Messages.get(Level.class, "<key>")`.
