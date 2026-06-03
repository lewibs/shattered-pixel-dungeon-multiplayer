---
name: buff-uninitialized-sentinel
description: "Use -1 as the uninitialized sentinel for int position/state fields in a Buff so the first-turn stop check is skipped and premature detach is avoided."
user-invocable: false
---
## When to use

Any `Buff` that compares a current value against a "previous turn" snapshot to detect change (e.g. position delta, action delta, counter delta). Without a sentinel the buff sees `0 == 0` on its very first turn and incorrectly concludes "nothing changed" — triggering the stop/detach condition one turn too early.

## Steps

1. Declare tracked int fields with `= -1` as the default:
   ```java
   private int lastKnownTargetPos = -1; // -1 = uninitialized
   ```

2. In the stop/change check, skip the comparison when the sentinel is still present:
   ```java
   public boolean targetStopped() {
       Hero h = getTargetHero();
       if (h == null || !h.isAlive()) return true;
       // Only compare if we have seen a real position before
       return lastKnownTargetPos != -1 && h.pos == lastKnownTargetPos;
   }
   ```

3. In `buff.act()`, record the real value for the next turn:
   ```java
   @Override
   public boolean act() {
       spend(TICK);
       Hero h = getTargetHero();
       if (h != null && h.isAlive()) {
           lastKnownTargetPos = h.pos; // overwrites -1 after first tick
       }
       return true;
   }
   ```

4. Serialize the sentinel correctly — `bundle.getInt("key")` returns `0` for a missing key, not `-1`. Store the field explicitly:
   ```java
   bundle.put("lastKnownTargetPos", lastKnownTargetPos);
   // and in restoreFromBundle:
   lastKnownTargetPos = bundle.getInt("lastKnownTargetPos");
   ```
   Because `0` is a valid map position, using `0` as the sentinel would produce false positives on the first turn after a save/load if the target happened to be at cell 0. Always use `-1`.

## Notes

- Cell index `0` is a valid in-game position (top-left corner of the level). Never use `0` as "not yet set" for position fields.
- The same pattern applies to any int field that tracks a "previous" actor state: last health, last depth, last turn counter, etc.
- The canonical reference is `FollowHeroBuff.lastKnownTargetPos` and `FollowHeroBuff.targetStopped()`.
