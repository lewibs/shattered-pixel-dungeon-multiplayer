---
name: scale-balance-constant-by-player-count
description: "How to multiply a game balance constant by player count so single-player is unchanged and subclasses inherit the scaling automatically via super."
user-invocable: false
---
## When to use
Any time a numeric game constant (mob cap, loot quantity, resource rate, XP amount, etc.) should scale linearly with player count while leaving single-player behavior pixel-identical to before.

## Steps
1. Locate the method that returns the constant (e.g. `RegularLevel.mobLimit()`).
2. Compute `playerCount` at the top of the method counting only **alive** heroes. Dead heroes remain in `Dungeon.heroes` as tombstones for record-keeping (see `dungeon-heroes-tombstone-pattern` skill), so `Dungeon.heroes.size()` is NOT a safe alive count:
   ```java
   int playerCount = 0;
   if (Dungeon.heroes != null) {
       for (Hero h : Dungeon.heroes) {
           if (h.isAlive()) playerCount++;
       }
   }
   if (playerCount == 0) playerCount = 1;
   ```
3. Multiply every `return <value>;` path by `playerCount`:
   ```java
   // BEFORE
   return mobs;
   // AFTER
   return mobs * playerCount;
   ```
   - A return of `0` stays `0` regardless of `playerCount`, so floor-1 "no spawn" early returns are safe to multiply without a special case.
   - Hard-coded non-zero early returns (e.g. `return 10;` for the post-amulet floor-1 path) must be explicitly multiplied: `return 10 * playerCount;`.
4. Do NOT override the method in subclasses. If a subclass already calls `super.mobLimit()` and adjusts it (e.g. `MiningLevel` does `super.mobLimit() - 1`), the scaling is inherited for free — no changes needed in subclasses.
5. Do NOT touch `respawnCooldown()` or spawner logic. Only the cap changes; spawn frequency is intentionally unchanged so the spawner naturally fills the higher cap at the same rate.

## Variant: scaling item quantity with a drop loop (e.g. food)
When scaling the *number of items dropped* (not a return value), use a loop at the end of `createItems()`:
```java
int playerCount = (Dungeon.heroes != null && !Dungeon.heroes.isEmpty())
        ? Dungeon.heroes.size()
        : 1;
for (int i = 0; i < playerCount - 1; i++) {
    int cell = randomDropCell();
    if (cell != -1) {
        drop(Generator.random(Generator.Category.FOOD), cell);
    }
}
```
Key gotchas for this variant:
- **`Generator.Category.FOOD` has `firstProb=0, secondProb=0`** — it will never appear in `Generator.random()` with no arguments. Always pass the category explicitly: `Generator.random(Generator.Category.FOOD)`.
- `randomDropCell()` can return `-1` when no valid cell exists; the null-check prevents a crash.
- Place the loop *after* `Random.popGenerator()` at the end of `createItems()`, so the RNG stack is clean.
- Boss levels and special levels do not extend `RegularLevel`, so they do not call this `createItems()` and are unaffected.

## Notes
- `Dungeon.heroes` is the authoritative player-count source. The null + isEmpty guard is required because `heroes` may be null in menu/loading contexts where the method could theoretically be called.
- The fallback to `1` means single-player code paths produce exactly the same numeric result as before — no regression risk.
- Boss levels and special levels typically override `mobLimit()` to return `0` (base `Level` returns 0). They are unaffected by changes to `RegularLevel.mobLimit()` because they do not call `super`.
- This pattern was first applied to `RegularLevel.mobLimit()` for the scale-monster-spawns feature (2026-05-29).
- The drop-loop variant was added for `RegularLevel.createItems()` food scaling (2026-05-29).
