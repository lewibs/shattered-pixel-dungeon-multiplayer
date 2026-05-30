---
name: falling-hero-exclusion
description: "Heroes carrying Chasm.WaitingToFall or Chasm.Falling buffs have a pre-assigned fall-cell and must be skipped in any stair-adjacent placement or party-iteration logic."
user-invocable: false
---
## When to use

Whenever iterating over `Dungeon.heroes` (or an `ArrayList<Hero>`) to assign positions, trigger level-entry effects, or perform any action that assumes a hero is "arriving via stairs". A falling hero is mid-chasm and has already had its destination cell assigned — overwriting its `pos` or including it in stair logic corrupts the fall animation and landing.

## Steps

1. Before acting on a hero, check for both falling states:
   ```java
   if (h.buff(Chasm.WaitingToFall.class) != null
           || h.buff(Chasm.Falling.class) != null) {
       continue; // or skip / handle separately
   }
   ```
2. `Chasm.WaitingToFall` — hero has stepped into a pit cell on the current floor and is waiting for the fall sequence to begin. Their `pos` is the pit cell on the *current* floor.
3. `Chasm.Falling` — the fall animation is in progress. Their `pos` is the landing cell on the *destination* floor.
4. In both cases the `pos` value is load-bearing and must NOT be overwritten by generic placement code.
5. Do NOT add the falling hero's cell to a stair-adjacent `occupied` set — the fall cell is on a different part of the level and should not block stair neighbours.

## Notes

- The canonical usage is in `Dungeon.placeHeroesNearEntrance()` — see that method.
- A second guard exists at `Dungeon` line ~1046 (party iteration during level switch) that also skips `WaitingToFall` heroes.
- Forgetting this guard causes two bugs simultaneously: the falling hero teleports to a stair cell (visible glitch), and a stair-arriving hero loses its adjacent slot (they overlap with hero[0] at the entrance).
- Unit tests covering both buff states live in `StairDescentPlacementTest.java` under the `FallingHeroes` nested class.
