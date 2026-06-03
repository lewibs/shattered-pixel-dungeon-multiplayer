---
name: pit-fall-sync-design
description: Correct design for pit-fall party synchronization — any hero can fall, transition gates on all heroes ready
metadata:
  type: project
---

## Pit-Fall Party Sync — Intended Flow

Any of the 4 heroes can be the falling one. The logic must be hero-agnostic:

1. A hero steps into a pit → they enter **WaitingToFall** state (paused on current floor)
2. Dead heroes are ignored entirely
3. The floor transition fires when ALL alive heroes are in one of:
   - WaitingToFall (fell into pit, waiting)
   - Going down stairs (triggering descent)
4. On the new floor:
   - WaitingToFall heroes → land at fall cell, receive Chasm.Falling damage
   - Stair heroes → land at stair entrance

**Why:** — Both previous attempts broke because they assumed only hero[0] drives transitions. The fix must work regardless of which hero falls, which uses stairs, and which is dead.

**How to apply:** When implementing, ensure:
- heroFall() accepts any Hero, not just Dungeon.hero
- Any hero can trigger activateTransition (not gated on ch == Dungeon.hero)
- WaitingToFall buff works on any hero
- The "last ready hero" check counts ALL alive heroes, not just hero[0]

Also update MEMORY.md index.
