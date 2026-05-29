---
name: spawnhero-quickslot-singleton-swap
description: "How to spawn additional Hero instances without corrupting Dungeon.quickslot, which is a global singleton used by initHero() during class initialization."
user-invocable: false
---
## When to use
Whenever calling `HeroClass.initHero(hero)` for any hero that is not the first (primary) hero. `initHero` writes item assignments into `Dungeon.quickslot`, which is a global; without the swap the slots of all heroes collide into the same QuickSlot object.

## Steps
1. Before calling `heroClass.initHero(h)`, save the current global: `QuickSlot savedSlot = Dungeon.quickslot;`
2. Point the global at the new hero's own slot: `Dungeon.quickslot = h.quickslot;`
3. Call `heroClass.initHero(h)` — it writes into `h.quickslot` via the global.
4. Restore the global: `Dungeon.quickslot = savedSlot;`
5. Add `h` to `Dungeon.heroes`. Set `Dungeon.hero = h` only for the first hero (index 0).

## Notes
- This pattern lives in `Dungeon.spawnHero()`. Any future hero-spawning helper must replicate this swap or hero quickslots will be corrupted.
- `Dungeon.hero` (the active singleton) must remain pointed at `heroes.get(0)` after all heroes are spawned; systems that haven't been updated for multi-hero still read `Dungeon.hero`.
- The `heroes` list in `Dungeon` is reset to `new ArrayList<>()` at the start of `Dungeon.init()` — always clear it before calling `spawnHero` in a loop.
