---
name: scale-balance-constant-by-player-count
description: "How to multiply a game balance constant by player count so single-player is unchanged and subclasses inherit the scaling automatically via super."
user-invocable: false
---
## When to use
Any time a numeric game constant (mob cap, loot quantity, resource rate, XP amount, etc.) should scale linearly with player count while leaving single-player behavior pixel-identical to before.

## Steps
1. Locate the method that returns the constant (e.g. `RegularLevel.mobLimit()`).
2. Compute `playerCount` at the top of the method using a null-safe guard:
   ```java
   int playerCount = (Dungeon.heroes != null && !Dungeon.heroes.isEmpty())
           ? Dungeon.heroes.size()
           : 1;
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

## Notes
- `Dungeon.heroes` is the authoritative player-count source. The null + isEmpty guard is required because `heroes` may be null in menu/loading contexts where the method could theoretically be called.
- The fallback to `1` means single-player code paths produce exactly the same numeric result as before — no regression risk.
- Boss levels and special levels typically override `mobLimit()` to return `0` (base `Level` returns 0). They are unaffected by changes to `RegularLevel.mobLimit()` because they do not call `super`.
- This pattern was first applied to `RegularLevel.mobLimit()` for the scale-monster-spawns feature (2026-05-29).
