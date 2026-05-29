---
name: intercept-terminal-game-path
description: "How to intercept a single-player 'end of game' code path at the method level so multiplayer can silently skip it when other heroes are still alive."
user-invocable: false
---
## When to use
When a single-player method ends by calling a terminal function (e.g. `reallyDie()`, `Dungeon.fail()`, `GameScene.gameOver()`) that must be suppressed in multiplayer as long as at least one other hero survives. The existing `multiplayer-guard-pattern` skill covers behavior gates at method entry; this skill covers forking at the terminal call site inside the method body.

## Steps

1. **Locate the terminal call in the method body.** In `Hero.die()`, the sequence is:
   - `super.die(cause)` — removes the hero from the Actor system, fires sprite death animation
   - `reallyDie(cause)` — drops items, deletes save, shows game-over screen

   The interception point is *between* these two calls — after `super.die()` has already run so the hero is properly removed from the Actor system, but before `reallyDie()` which triggers the end-of-game UI.

2. **Count surviving heroes (excluding `this`).**
   ```java
   int livingOthers = 0;
   if (Dungeon.heroes != null) {
       for (Hero h : Dungeon.heroes) {
           if (h != this && h.isAlive()) livingOthers++;
       }
   }
   ```
   Use `h != this` (not `h != Dungeon.hero`) because `Dungeon.hero` may already be stale when `die()` fires.

3. **If others are alive, perform the silent-remove branch and return early.**
   ```java
   if (livingOthers > 0) {
       Dungeon.heroes.remove(this);
       Hero next = Dungeon.heroes.get(0); // first surviving hero
       next.activate(); // sets Dungeon.hero, Dungeon.quickslot, pans camera
       return;
   }
   ```
   Do NOT drop items, submit rankings, or show any game-over window in this branch.

4. **Fall through to the terminal call for the last-hero case.**
   ```java
   reallyDie(cause); // only reached when livingOthers == 0
   ```
   Single-player is unchanged: with exactly one hero in `Dungeon.heroes`, `livingOthers` is always 0.

5. **Guard each downstream terminal side-effect call site separately.**
   `Dungeon.fail()` is called from `Char.java`, not from `Hero.die()`. It must be guarded at its own call site:
   ```java
   boolean lastHero = true;
   if (Dungeon.heroes != null) {
       for (Hero h : Dungeon.heroes) {
           if (h != Dungeon.hero && h.isAlive()) { lastHero = false; break; }
       }
   }
   if (lastHero) Dungeon.fail(this);
   ```
   Search for every location that would submit rankings, delete saves, or show game-over UI, and guard each one. Do not assume that guarding `reallyDie()` is sufficient.

## Notes
- `super.die(cause)` (Char.die) must run unconditionally — it handles Actor removal and sprite death animation. The multiplayer fork goes *after* `super.die()`, not before it.
- `Hero.activate()` is the canonical method to make a hero the active singleton. It sets `Dungeon.hero`, transfers quickslot bindings, and pans the camera. Do not manually assign `Dungeon.hero` directly.
- The Ankh resurrection path (`WndResurrect`) returns early before reaching the `super.die()` call. The multiplayer fork never runs for Ankh users — this is correct, no special handling needed.
- Item drops happen inside `reallyDie()`. By returning early before `reallyDie()`, the dead hero's items are silently lost. This is intentional for the current design; revisit if inventory-drop-on-death is ever added.
- `Dungeon.heroes` still contains the dead hero at the point the guard runs (it was not removed by `super.die()`). Remove it explicitly before switching to the next hero, otherwise `heroes.get(0)` may return the dead hero.
- This pattern was first applied to `Hero.die()` (Hero.java ~line 2223) and `Char.java` (~line 580) for the single-hero-death-handling feature.
