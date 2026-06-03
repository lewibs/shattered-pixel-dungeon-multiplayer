---
name: actor-init-timing-placement
description: "When placing characters on a new level before Actor.init() runs, Actor.findChar() always returns null — use a local HashSet to track occupied cells instead."
user-invocable: false
---
## When to use

Any time you need to place multiple characters (heroes, NPCs, spawns) on a level at the point where `Dungeon.switchLevel()` calls the placement logic but *before* `Actor.init()` is invoked. This window covers anything called from the hero-placement block in `switchLevel()` up to line ~527 where `Actor.init()` executes.

## Steps

1. Identify that your placement code runs before `Actor.init()` — look for it being called in `Dungeon.switchLevel()` prior to the `Actor.init()` call.
2. Do NOT call `Actor.findChar(cell)` to check if a cell is occupied. It queries the actor-by-position index, which is not yet populated, and will always return `null`.
3. Instead, maintain a `HashSet<Integer> occupied` local to the placement method:
   - Pre-populate it with cells already committed (e.g. the entrance cell where hero[0] lands).
   - For each character being placed, check `!occupied.contains(candidate)` before claiming a cell.
   - After assigning `h.pos = candidate`, immediately add `candidate` to `occupied`.
4. The pattern looks like:
   ```java
   HashSet<Integer> occupied = new HashSet<>();
   occupied.add(entrancePos);          // seed with already-committed cells
   for (int i = 1; i < heroes.size(); i++) {
       for (int offset : PathFinder.NEIGHBOURS8) {
           int candidate = base + offset;
           if (level.passable[candidate] && !occupied.contains(candidate)) {
               heroes.get(i).pos = candidate;
               occupied.add(candidate);
               break;
           }
       }
   }
   ```
5. After `Actor.init()` runs, `Actor.findChar()` is safe to use for subsequent collision checks (e.g. the mob-displacement loop already in `switchLevel()`).

## Notes

- The root cause of the original stair-descent stacking bug (all 3 heroes landing on the same cell) was exactly this: `Actor.findChar()` was used inside `placeHeroesNearEntrance()` but `Actor.init()` had not yet run.
- This timing window is easy to miss because `Actor.findChar()` compiles and runs silently — it just always returns `null`, making every cell look free.
- The fix was introduced in `Dungeon.placeHeroesNearEntrance()` — see that method for the canonical reference implementation.
- Unit tests covering this pattern live in `StairDescentPlacementTest.java`, including a `Regression` nested class that explicitly documents the before/after behavior.
