---
name: hero-act-buff-interception
description: "How to auto-drive Hero.act() from a buff — injecting a curAction before the ready() fallthrough to implement auto-move, macro, or follow behaviours."
user-invocable: false
---
## When to use

Any time a buff needs to supply an automatic `HeroAction` on the hero's behalf — follow, patrol, rest-until-healed, auto-explore, etc. — without replacing the normal input-driven path. The hook sits at the `curAction == null` branch in `Hero.act()`, just before the call to `ready()`.

## Steps

1. Create a `Buff` subclass (e.g. `FollowHeroBuff`) that encapsulates the automation state.

2. In `Hero.act()`, locate the block that calls `ready()` when `curAction == null`. Insert the buff check **immediately before** that block:
   ```java
   YourBuff auto = buff(YourBuff.class);
   if (curAction == null && auto != null) {
       if (auto.shouldStop() || visibleEnemies.size() > 0) {
           auto.detach();
           // fall through to ready() — player regains control
       } else {
           int targetPos = auto.getTargetPos();
           if (targetPos >= 0 && targetPos != pos) {
               curAction = new HeroAction.Move(targetPos);
               // fall through to the curAction dispatch block below
           }
           // if already at target, fall through to ready()
       }
   }
   if (curAction == null) {
       ready();
       return false;
   }
   // existing curAction dispatch (actMove, actAttack, etc.)
   ```

3. The buff does **not** call `hero.spend()` or return `false` from its own `act()`. It only updates its internal tracking state and returns `true` to persist. `Hero.act()` handles all time-spending.

4. Guard all toolbar/UI entry points for the buff:
   - Check `hero != null && hero.ready` before attaching.
   - Check `Dungeon.heroes.size() > 1` (or whatever the feature prerequisite is) before offering the button.
   - Provide a toggle-off path: if the buff is already attached, clicking the button calls `buff.detach()`.

5. Interrupt on enemy visibility: the `visibleEnemies.size() > 0` check in step 2 mirrors the existing rest/wait interrupt logic and should always be included for any automation buff to prevent the hero sleepwalking into combat.

## Notes

- `curAction` is set to `null` at multiple points in `Hero.act()` (after action completes, on interrupts, etc.). The injection point at the top of the `curAction == null` block is the only safe place — setting `curAction` from inside `buff.act()` would race with those resets.
- The buff's `act()` fires **after** `Hero.act()` in actor-queue order during the same game turn (see `buff-act-fires-after-hero-act` skill). This means any state the buff records (e.g. `lastKnownTargetPos`) reflects the target's position at end-of-turn, which becomes the "previous position" for the next turn's stop check.
- Always serialize buff state fields with `storeInBundle` / `restoreFromBundle` so follow mode survives save/load. Use `-1` as the uninitialized sentinel for position fields (see `buff-uninitialized-sentinel` skill).
- The canonical reference implementation is `FollowHeroBuff.java` + the injection block added to `Hero.act()` around line 920.
