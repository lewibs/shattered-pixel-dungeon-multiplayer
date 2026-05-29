---
name: sequential-multi-hero-ui-chain
description: "How to show a UI selection window for every hero in sequence after a single item activation, using a static pendingHeroes queue and showNextPending()."
user-invocable: false
---
## When to use
Any time a single-item activation (boss drop, artifact, etc.) must prompt every eligible hero with a selection window one-by-one. Examples already using this pattern: `TengusMask` (subclass choice), `KingsCrown` (armor ability choice). Apply any time `Dungeon.heroes.size() > 1` and you need sequential modal UI per hero.

## Steps
1. Add a `static ArrayList<Hero> pendingHeroes = new ArrayList<>()` field to the item class.
2. Add a `static void showNextPending()` method:
   ```java
   public static void showNextPending() {
       // optional: skip heroes that no longer need this choice
       while (!pendingHeroes.isEmpty() && /* hero already satisfied */ ) {
           pendingHeroes.remove(0);
       }
       if (pendingHeroes.isEmpty()) return;
       Hero next = pendingHeroes.remove(0);
       Item.curUser = next;                          // required — window callbacks read curUser
       GameScene.show(new WndYourWindow(new YourItem(), next));
   }
   ```
3. In `execute()`, just before showing the window for the activating hero, populate and clear the queue:
   ```java
   pendingHeroes.clear();
   for (Hero h : Dungeon.heroes) {
       if (h != hero /* and any eligibility filter */) {
           pendingHeroes.add(h);
       }
   }
   GameScene.show(new WndYourWindow(this, hero));
   ```
4. At the end of the callback method that handles the hero's choice (e.g. `choose()`, `upgradeArmor()`), call `showNextPending()`. The window for the next hero is shown only after the current hero completes their selection.
5. In any mob `die()` method that drops this item, replace `if (Dungeon.hero.fieldToCheck == value)` with a loop over `Dungeon.heroes` to check any/all heroes — `Dungeon.hero` is only hero[0] and will miss players 2+.

## Notes
- `Item.curUser` must be set to each pending hero before showing their window. Window callbacks (e.g. `WndChooseSubclass.onSelect`) call `curUser` methods internally; if it still points at the activating hero, the upgrade applies to the wrong character.
- Pass `new YourItem()` (a fresh instance) to the window constructor for chained heroes, not `this`. The activating hero consumes `this` (it gets detached). Chained heroes need their own carrier object, but because the item is `unique = true` and the detach is a no-op for non-inventory items, this is safe.
- `pendingHeroes.clear()` in `execute()` handles re-activation (e.g. a second mask picked up mid-run) — it resets the queue each time a fresh activation occurs.
- The skip-while loop inside `showNextPending()` handles heroes whose state changed between activation and their turn in the queue (e.g. they somehow gained a subclass another way). Always check eligibility at pop time, not just at queue-fill time.
- Single-player is completely unaffected: `pendingHeroes` stays empty and `showNextPending()` is a no-op. No size guard is required.
- This pattern was first applied to `TengusMask` and `KingsCrown`. The same idiom applies to any future multi-hero sequential selection (talent unlocks, special item choices, etc.).
