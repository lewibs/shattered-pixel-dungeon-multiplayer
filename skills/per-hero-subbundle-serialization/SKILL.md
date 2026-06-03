---
name: per-hero-subbundle-serialization
description: "How to persist per-hero state that was previously only stored at the Dungeon singleton level, using a sub-bundle inside Hero.storeInBundle/restoreFromBundle with a backward-compat guard."
user-invocable: false
---
## When to use
Any time a field exists on `Hero` that was originally only serialized at the `Dungeon` level (e.g., via a static singleton), and the multiplayer mod has given each hero its own instance of that field. Without per-hero serialization, only the active hero's data survives a save/load cycle; all other heroes get a fresh default on reload.

## Steps
1. Declare a private constant for the sub-bundle key at the top of `Hero`:
   ```java
   private static final String QUICKSLOT = "quickslot";
   ```
2. In `Hero.storeInBundle()`, create a fresh `Bundle`, call the field's own serialization method on it, then embed it:
   ```java
   Bundle qsBundle = new Bundle();
   quickslot.storePlaceholders( qsBundle );
   bundle.put( QUICKSLOT, qsBundle );
   ```
3. In `Hero.restoreFromBundle()`, guard the restore with `bundle.contains()` so old saves (which lack the key) do not throw or reset data:
   ```java
   if (bundle.contains( QUICKSLOT )) {
       quickslot.restorePlaceholders( bundle.getBundle( QUICKSLOT ) );
   }
   ```
4. Leave any existing Dungeon-level serialization (`Dungeon.quickslot.storePlaceholders/restorePlaceholders`) intact. It acts as a fallback for single-hero saves and does not conflict — on load, the per-hero sub-bundle (step 3) is authoritative for each hero, while the Dungeon-level call only touches `Dungeon.quickslot` (the active-hero singleton pointer).
5. No changes to `hero.activate()` are needed — it already does `Dungeon.quickslot = this.quickslot`, so once the per-hero field is correctly hydrated, the singleton tracks correctly.

## Notes
- The `bundle.contains()` guard is mandatory for backward compatibility. Without it, a missing key causes the field to silently remain as the constructor default (empty), which is indistinguishable from a load failure.
- This same pattern applies to any other per-hero field that mirrors a `Dungeon.*` singleton (e.g., `Dungeon.hero`, `Dungeon.depth` overrides, per-player stats). Always check whether a singleton you are per-hero-ifying is also serialized at the Dungeon level.
- `QuickSlot.storePlaceholders` / `restorePlaceholders` use their own internal key names inside the sub-bundle, so the outer key (`"quickslot"`) is the only thing `Hero` needs to manage.
- See also: `skills/spawnhero-quickslot-singleton-swap/SKILL.md` for the spawn-time singleton swap pattern (separate concern from serialization).
