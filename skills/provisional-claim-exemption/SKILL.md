---
name: provisional-claim-exemption
description: "When a shared set tracks resources claimed by all players, exempt the local player's own current live claim from the lockout predicate so they can change their selection before committing."
user-invocable: false
---
## When to use
Any time a shared collection (e.g. `GamesInProgress.selectedClasses`) is used both to:
- track the local player's provisional choice (so it is visible to peers), AND
- lock out other players from choosing the same option.

Without an exemption, the local player's own provisional entry locks out their own button, preventing them from changing their mind before confirming.

## Steps
1. Identify the shared set and the two roles it plays: provisional local claim, and peer lockout.
2. Write a predicate method on the UI element that checks both conditions:
   ```java
   boolean isTaken() {
       if (!sharedSet.contains(myResource)) return false;
       // Exempt the local player's own current live claim
       return myResource != localPlayerCurrentSelection;
   }
   ```
3. Use this predicate as the veto condition in the per-frame enable guard (see `render-loop-enable-overwrite` skill).
4. When the local player confirms their selection (e.g. presses "Select"), add their class to `selectedClasses` as a permanent entry (it was already there as a provisional one), then set a `confirmed` flag. After confirmation, the exemption no longer matters because all buttons are disabled anyway.
5. When the local player changes their provisional selection, remove the previous class from `selectedClasses` and add the new one — the exemption in `isTaken()` automatically tracks the new live selection.

## Notes
- In HeroSelectScene, `GamesInProgress.selectedClasses` holds ALL claimed classes (local provisional + remote confirmed). `GamesInProgress.selectedClass` holds the local player's current hovering/provisional pick.
- `HeroBtn.isTaken()` (added in the LAN lockout fix) returns `true` iff `cl` is in `selectedClasses` AND `cl != GamesInProgress.selectedClass`. The second condition is the exemption.
- `setSelectedHero()` handles the provisional update: it sends UNCLAIM for the previous class, removes it from `selectedClasses`, sends CLAIM for the new class, and adds it to `selectedClasses`. This keeps the set consistent.
- After confirmation (`lanHeroConfirmed = true`), all buttons are disabled via `for (StyledButton b : heroBtns) b.active = false` in the `onClick()` handler, so the exemption is moot post-confirm.
- This pattern generalizes to any lobby/selection screen where players pick from a finite shared pool (weapons, factions, spawn points, etc.).
