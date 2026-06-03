---
name: save-bundle-preview-metadata
description: "Store denormalized per-hero metadata as parallel primitive arrays in the save bundle so GamesInProgress.preview() can show it on the save slot screen without deserializing full hero objects."
user-invocable: false
---
## When to use
When new per-hero data (class, level, armor tier, or any future scalar) needs to appear on the save slot screen (`GamesInProgress.preview()`), or any other place that reads the save bundle without fully loading the game. Full hero deserialization is too expensive and out-of-scope for preview; parallel arrays are the established pattern.

## Steps
1. In `Dungeon.saveGame()`, after all heroes are available, serialize the scalars as parallel arrays:
   ```java
   String[] heroClassNames = new String[heroes.size()];
   int[]    heroArmorTiers  = new int[heroes.size()];
   int[]    heroLevels      = new int[heroes.size()];
   for (int i = 0; i < heroes.size(); i++) {
       heroClassNames[i] = heroes.get(i).heroClass.name();
       heroArmorTiers[i] = heroes.get(i).tier();
       heroLevels[i]     = heroes.get(i).lvl;
   }
   bundle.put("heroClassNames", heroClassNames);
   bundle.put("heroArmorTiers",  heroArmorTiers);
   bundle.put("heroLevels",      heroLevels);
   ```

2. In `GamesInProgress.preview()`, read the arrays and populate the info object. Always guard with `bundle.contains(...)` for backwards compatibility with saves written before the arrays existed, and fall back to the single-hero legacy fields:
   ```java
   info.heroClasses = new ArrayList<>();
   info.armorTiers  = new ArrayList<>();
   info.heroLevels  = new ArrayList<>();
   if (bundle.contains("heroClassNames")) {
       String[] names  = bundle.getStringArray("heroClassNames");
       int[]    tiers  = bundle.getIntArray("heroArmorTiers");
       int[]    levels = bundle.contains("heroLevels") ? bundle.getIntArray("heroLevels") : new int[names.length];
       for (int i = 0; i < names.length; i++) {
           try {
               info.heroClasses.add(HeroClass.valueOf(names[i]));
               info.armorTiers.add(tiers[i]);
               info.heroLevels.add(i < levels.length ? levels[i] : info.level);
           } catch (IllegalArgumentException ignored) {}
       }
   }
   if (info.heroClasses.isEmpty()) {
       // Legacy single-player save or first-time migration
       info.heroClasses.add(info.heroClass);
       info.armorTiers.add(info.armorTier);
       info.heroLevels.add(info.level);
   }
   ```

3. In the save slot UI (e.g. `WndStartGame` or equivalent), iterate `info.heroClasses` (not `info.heroClass`) to render per-hero icons and levels.

## Notes
- Use `HeroClass.valueOf(name)` wrapped in a `try/catch IllegalArgumentException` so that unrecognized class names (from future additions or corrupted data) are silently skipped rather than crashing preview.
- Arrays added at different times may have different lengths in old saves; always check `i < levels.length` before indexing each array independently.
- The `HERO` bundle key (single hero) is kept unchanged for backwards compatibility with `Hero.preview()`. The new arrays are additive.
- Primitive int arrays are stored with `bundle.getIntArray(key)`. String arrays use `bundle.getStringArray(key)`. Both are part of the existing `Bundle` API in this codebase.
