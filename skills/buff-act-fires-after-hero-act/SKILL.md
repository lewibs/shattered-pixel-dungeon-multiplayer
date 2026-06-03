---
name: buff-act-fires-after-hero-act
description: "buff.act() is scheduled after hero.act() in the same game turn, so state recorded inside buff.act() reflects end-of-turn values — not the values the hero saw when making its decision."
user-invocable: false
---
## When to use

Any time a buff needs to snapshot an actor's state "as of this turn" for comparison on the next turn (position, health, action, etc.), or any time you are reasoning about what value a buff field holds at the moment `Hero.act()` reads it.

## Steps

1. Understand the ordering:
   - `Hero.act()` fires first. It reads buff fields, sets `curAction`, and spends time.
   - After the hero's `act()` returns, the actor queue processes the next scheduled actor — which for attached buffs means `buff.act()` fires later in the same logical turn.
   - Therefore, when `Hero.act()` starts, buff fields contain the values written by `buff.act()` at the **end of the previous turn**.

2. Design buff state accordingly:
   - If you want "what was the target's position last turn?", write it in `buff.act()` (which runs at end-of-turn) and read it at the start of `Hero.act()`. This gives you a one-turn lag, which is exactly right for change detection.
   - Do NOT try to snapshot state inside `Hero.act()` after the move — by then `buff.act()` has not yet run for this turn, so you cannot use buff fields as a write-back channel within the same turn.

3. Timing diagram for a follow-buff turn:
   ```
   Turn N:
     Hero.act()  — reads lastKnownTargetPos (written by buff in turn N-1)
                 — decides whether target moved; sets curAction = HeroAction.Move(...)
                 — hero moves
     buff.act()  — writes lastKnownTargetPos = target.pos  (captures end-of-turn N position)

   Turn N+1:
     Hero.act()  — reads lastKnownTargetPos (now = target's pos at end of turn N)
                 — compares against target.pos right now to detect movement
   ```

4. If the buff needs to react to something that happened *during* `Hero.act()` in the same turn (rare), use a callback or flag set during `Hero.act()` rather than relying on `buff.act()` order.

## Notes

- This ordering is determined by how `Actor.addDelayed` and the priority queue work: buffs attached via `Buff.affect()` are scheduled with the same `time` as the hero but with lower priority, so they resolve after the hero in a tie.
- The one-turn lag is a feature, not a bug: it ensures the buff always has a stable "previous" snapshot when `Hero.act()` runs.
- Canonical reference: `FollowHeroBuff` — `lastKnownTargetPos` is written in `buff.act()` and read in the `Hero.act()` injection block to determine if the target moved.
