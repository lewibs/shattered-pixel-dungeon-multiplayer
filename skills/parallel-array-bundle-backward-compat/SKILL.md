---
name: parallel-array-bundle-backward-compat
description: "How to add parallel arrays to an existing Bundle-serialized record class while keeping old single-scalar saves loading cleanly."
user-invocable: false
---
## When to use
Any time a record or data class already stores a single scalar per-hero field (e.g. `heroClass`, `armorTier`, `herolevel` on `Rankings.Record`) and you need to extend it to store one value per hero for multiplayer runs. The old save files must continue to load without errors or data loss.

## Steps
1. Add three new array fields to the record class:
   ```java
   public HeroClass[] heroClasses;
   public int[]       armorTiers;
   public int[]       heroLevels;
   ```
2. Declare private string constants for the new bundle keys (keeps the key strings refactorable):
   ```java
   private static final String HERO_CLASSES = "heroClasses";
   private static final String ARMOR_TIERS  = "armorTiers";
   private static final String HERO_LEVELS  = "heroLevels";
   ```
3. In the submit/populate path, populate from `Dungeon.heroes` when available; otherwise wrap the existing single scalar in a length-1 array:
   ```java
   if (Dungeon.heroes != null && !Dungeon.heroes.isEmpty()) {
       rec.heroClasses = new HeroClass[Dungeon.heroes.size()];
       rec.armorTiers  = new int[Dungeon.heroes.size()];
       rec.heroLevels  = new int[Dungeon.heroes.size()];
       for (int i = 0; i < Dungeon.heroes.size(); i++) {
           rec.heroClasses[i] = Dungeon.heroes.get(i).heroClass;
           rec.armorTiers[i]  = Dungeon.heroes.get(i).tier();
           rec.heroLevels[i]  = Dungeon.heroes.get(i).lvl;
       }
   } else {
       rec.heroClasses = new HeroClass[]{ rec.heroClass };
       rec.armorTiers  = new int[]{ rec.armorTier };
       rec.heroLevels  = new int[]{ rec.herolevel };
   }
   ```
4. In `storeInBundle()`, serialize `HeroClass` enums as a `String[]` (their `.name()`) because `Bundle` does not have a native enum-array type; int arrays serialize natively:
   ```java
   if (heroClasses != null) {
       String[] classNames = new String[heroClasses.length];
       for (int i = 0; i < heroClasses.length; i++) classNames[i] = heroClasses[i].name();
       bundle.put( HERO_CLASSES, classNames );
       bundle.put( ARMOR_TIERS,  armorTiers );
       bundle.put( HERO_LEVELS,  heroLevels );
   }
   ```
5. In `restoreFromBundle()`, guard on `bundle.contains(HERO_CLASSES)` and fall back to wrapping the old scalar:
   ```java
   if (bundle.contains(HERO_CLASSES)) {
       String[] names = bundle.getStringArray(HERO_CLASSES);
       heroClasses = new HeroClass[names.length];
       for (int i = 0; i < names.length; i++) heroClasses[i] = HeroClass.valueOf(names[i]);
       armorTiers  = bundle.getIntArray(ARMOR_TIERS);
       heroLevels  = bundle.getIntArray(HERO_LEVELS);
   } else {
       // backward compat: old record had only the single scalar fields
       heroClasses = new HeroClass[]{ heroClass };
       armorTiers  = new int[]{ armorTier };
       heroLevels  = new int[]{ herolevel };
   }
   ```
6. Leave the original single scalar fields (`heroClass`, `armorTier`, `herolevel`) and their existing bundle keys untouched. They remain the canonical source for old records and for single-player UI code that only needs hero[0].

## Notes
- `bundle.contains()` guard is mandatory. Missing keys in old saves do not throw; they simply return null/0, which is silently wrong — always guard explicitly.
- `HeroClass` must be stored as `String[]` via `.name()` / `HeroClass.valueOf()`. Using `bundle.put(key, enumArray)` is not reliable in this codebase's Bundle implementation.
- After this change, UI code (e.g. `RankingsScene`) should read from `heroClasses[]` exclusively — `heroClass` (singular) is only for legacy single-hero code paths.
- The same pattern applies to any other `Rankings.Record`-style flat data class. See also `skills/per-hero-subbundle-serialization/SKILL.md` for the analogous pattern on `Hero.storeInBundle/restoreFromBundle`.
