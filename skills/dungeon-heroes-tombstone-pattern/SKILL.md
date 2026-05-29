---
name: dungeon-heroes-tombstone-pattern
description: "Dead heroes stay in Dungeon.heroes forever for record-keeping; all gameplay iterators must guard with isAlive()."
user-invocable: false
---
## When to use
Any time you add or modify code that iterates `Dungeon.heroes` and the iteration is for a *gameplay* purpose (not rankings/record-keeping). Dead heroes remain in the list as tombstones — they are inert actors but their stale state (position, HP, etc.) will corrupt gameplay logic if not filtered.

## Steps
1. Identify the purpose of the iteration:
   - **Record-keeping** (Rankings, save metadata, party-size display): iterate all entries — dead heroes must be included. Do NOT add an `isAlive()` guard.
   - **Gameplay** (mob scaling, transition gates, item selection, spawn logic, position checks): skip dead heroes with an `isAlive()` guard.

2. For gameplay iterators, add a guard as the first statement inside the loop:
   ```java
   for (Hero h : Dungeon.heroes) {
       if (!h.isAlive()) continue;
       // ... gameplay logic ...
   }
   ```

3. For gameplay methods that derive a *count* of active players (e.g. mob scaling, food scaling), count only alive heroes:
   ```java
   int playerCount = 0;
   for (Hero h : Dungeon.heroes) {
       if (h.isAlive()) playerCount++;
   }
   if (playerCount == 0) playerCount = 1; // fallback for safety
   ```
   Do NOT use `Dungeon.heroes.size()` for gameplay balance constants — it includes dead heroes.

4. For item/ability selection loops (e.g. `TengusMask`, `KingsCrown`) that collect heroes into a `pendingHeroes` list, skip dead heroes before adding:
   ```java
   for (Hero h : Dungeon.heroes) {
       if (!h.isAlive()) continue;
       pendingHeroes.add(h);
   }
   ```

5. `Hero.isAlive()` is the canonical liveness check. It returns `HP > 0`. Dead heroes have `HP == 0` after `super.die()` runs. Do not use `HP > 0` inline — call `isAlive()` for readability and future-proofing.

## Why dead heroes stay in the list
`Rankings.submit()` reads `Dungeon.heroes` after the last hero dies to populate `rec.heroClasses[]`, `rec.armorTiers[]`, and `rec.heroLevels[]`. The array length determines whether `RankingsScene` renders a solo icon or a multi-hero grid. If dead heroes are removed before `Rankings.submit()` runs, the run is misrecorded as having fewer heroes than it actually did.

The actor system (`Actor.remove()`) already guarantees dead heroes never act — removing them from `Dungeon.heroes` provides no safety benefit and actively destroys history.

## Callers audited (as of 2026-05-29)
| Site | Purpose | Guard needed |
|------|---------|-------------|
| `Rankings.submit()` | Record party composition | No — include dead |
| `Level.activateTransition()` | Distance check for stair gate | Yes — skip dead (stale pos) |
| `RegularLevel.mobLimit()` | Mob cap scaling | Yes — count alive only |
| `RegularLevel.createItems()` | Food drop scaling | Yes — count alive only |
| `TengusMask.execute()` | Subclass selection queue | Yes — skip dead |
| `KingsCrown.execute()` | Ability selection queue | Yes — skip dead |

## Notes
- `Dungeon.heroes.size()` must NEVER be used for gameplay balance after multi-hero death is possible. Use an alive-count loop instead.
- The tombstone pattern was introduced when fixing the bug where a multiplayer run was recorded as a solo run in Rankings (2026-05-29). The root cause was `Hero.die()` calling `Dungeon.heroes.remove(this)` for non-last heroes.
- Any future site that iterates `Dungeon.heroes` for a gameplay purpose should be added to the audit table above.
- Single-player runs are unaffected: the sole hero never dies non-finally (death ends the run), so `Dungeon.heroes` always has exactly one alive entry during single-player gameplay.
