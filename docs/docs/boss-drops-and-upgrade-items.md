# Boss Drops and Upgrade Items

## Metadata

- System type: `flow`

## System Intent

- What this is: The mechanism by which bosses drop special progression items when killed, and the logic by which those items apply their effects to heroes when used.

## Mermaid Diagram

```mermaid
flowchart TD
  BossDie["Boss.die()"] -->|Dungeon.level.drop(item, pos)| Heap["Item Heap on Level"]
  Heap -->|Hero picks up / uses| Execute["Item.execute(hero, action)"]
  Execute -->|sets curUser = hero| Effect["Effect applied to curUser only"]
  BossDie -->|Dungeon.hero.belongings.getItem(...)| LloydsBeacon["LloydsBeacon.upgrade() on Dungeon.hero only"]
```

## Flows

### Flow: `bossDrop`

- Core files:
  - `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Goo.java`
  - `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Tengu.java`
  - `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DM300.java`
  - `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/DwarfKing.java`
  - `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/YogDzewa.java`
  - `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/LastLevel.java`

#### What each boss drops

| Boss | Drop(s) | Drop Point in Code |
|---|---|---|
| Goo (depth 5) | `WornKey` (1, always) + `GooBlob` (2–4, random) | `Goo.die()` |
| Tengu (depth 10) | `TengusMask` (1, only if `Dungeon.hero.subClass == HeroSubClass.NONE`) | `Tengu.die()` |
| DM300 (depth 15) | `MetalShard` (2–4, random) | `DM300.die()` |
| Dwarf King (depth 20) | `KingsCrown` (1, always) | `DwarfKing.die()` |
| Yog-Dzewa (depth 25) | none via `die()`; amulet spawned by `LastLevel.createItems()` | `LastLevel.java:169` |

The random quantity ranges for GooBlob and MetalShard use:
```java
// Goo / DM300 pattern:
int count = Random.chances(new float[]{0, 0, 6, 3, 1}); // 60% 2, 30% 3, 10% 4
```

#### LloydsBeacon implicit upgrade

Three bosses also silently upgrade the `LloydsBeacon` artifact if found in `Dungeon.hero`'s inventory:

```java
LloydsBeacon beacon = Dungeon.hero.belongings.getItem(LloydsBeacon.class);
if (beacon != null) {
    beacon.upgrade();
}
```

This search is performed against `Dungeon.hero` only — the current singleton pointer — not against all heroes in `Dungeon.heroes`. Bosses that do this: Tengu, DM300, DwarfKing.

### Flow: `itemActivation`

- Core files:
  - `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/TengusMask.java`
  - `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/KingsCrown.java`
  - `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/quest/GooBlob.java`
  - `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/quest/MetalShard.java`
  - `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Amulet.java`
  - `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Item.java`

#### Effect scope per item

All items in this system follow the same pattern: `Item.execute(Hero hero, String action)` sets `curUser = hero` at line 160, then delegates to item-specific methods. Effects are applied only to `curUser` (the hero who activated the item). There is no broadcast to other heroes.

| Item | Effect when activated | Scope |
|---|---|---|
| `TengusMask` | Opens `WndChooseSubclass`; on confirm calls `TengusMask.choose(HeroSubClass)` which sets `curUser.subClass` and calls `Talent.initSubclassTalents(curUser)` | `curUser` only |
| `KingsCrown` | Opens `WndChooseAbility`; on confirm calls `KingsCrown.upgradeArmor(hero, armor, ability)` which upgrades `hero`'s armor to `ClassArmor`, sets `hero.armorAbility`, calls `Talent.initArmorTalents(hero)` | `hero` argument (= whoever activated it) only |
| `GooBlob` | No activate action; used as crafting ingredient (ArcaneBomb, ElixirOfArcaneArmor, ElixirOfAquaticRejuvenation, CausticBrew) or sold for 30g each | Single hero who crafts/sells |
| `MetalShard` | No activate action; used as crafting ingredient (ShrapnelBomb, ReclaimTrap, WildEnergy) or sold for 50g each | Single hero who crafts/sells |
| `Amulet` | Triggers `AmuletScene` (game ending flow) when picked up by any hero | Whoever picks it up |

#### Pseudocode: TengusMask activation

```
TengusMask.execute(hero, "WEAR"):
  curUser = hero
  GameScene.show(new WndChooseSubclass(this, hero))

WndChooseSubclass → user confirms → TengusMask.choose(subclass):
  detach from curUser.belongings.backpack
  curUser.subClass = subclass
  Talent.initSubclassTalents(curUser)
```

#### Pseudocode: KingsCrown activation

```
KingsCrown.execute(hero, "WEAR"):
  curUser = hero
  if hero.belongings.armor() != null:
    GameScene.show(new WndChooseAbility(this, hero.belongings.armor(), hero))

WndChooseAbility → user confirms → KingsCrown.upgradeArmor(hero, armor, ability):
  detach from hero.belongings.backpack
  classArmor = ClassArmor.upgrade(hero, armor)
  hero.belongings.armor = classArmor   // if worn
  hero.armorAbility = ability
  Talent.initArmorTalents(hero)
```

### Flow: `multiplayerDropProblems`

#### Q1: Do boss upgrade items affect all heroes or just the activating hero?

Confirmed: **single hero only**. Every activation path leads through `curUser` (set to whichever hero picks up and uses the item) or through an explicit `hero` parameter passed from the activation handler. No activation logic iterates `Dungeon.heroes` or broadcasts state changes to other heroes.

Additionally:

- `Tengu.die()` (Tengu.java:212-214) uses `Dungeon.hero.subClass` (the current singleton, not all heroes) to decide whether to drop the mask at all. In multiplayer, if the active hero (`Dungeon.hero`) already has a subclass, no mask is dropped for any hero — even heroes that still need one.
- `LloydsBeacon.upgrade()` is called on the beacon found in `Dungeon.hero.belongings` only, so other heroes' beacons are not upgraded.

#### Q2: Feasibility of dropping N × playerCount items for each player

Yes, this is feasible. All drop logic is contained in a single place per boss: the `die()` method. No shared infrastructure is needed.

**Quantity control points by boss:**

| Boss | File | Line(s) to modify | Current logic |
|---|---|---|---|
| Goo — GooBlob count | `Goo.java` | ~293 | `int blobs = Random.chances(...)` |
| Tengu — TengusMask conditional | `Tengu.java` | ~214 | `if (Dungeon.hero.subClass == HeroSubClass.NONE)` single drop |
| DM300 — MetalShard count | `DM300.java` | ~580 | `int shards = Random.chances(...)` |
| DwarfKing — KingsCrown count | `DwarfKing.java` | ~562–565 | Always exactly 1 |
| LloydsBeacon upgrade | `Tengu.java`, `DM300.java`, `DwarfKing.java` | die() method | `Dungeon.hero.belongings.getItem(...)` once |

**Pattern to scale by player count:**

`Dungeon.heroes.size()` gives the live player count. `GamesInProgress.playerCount` (declared at `GamesInProgress.java:48`) holds the pre-game count and is kept in sync.

Example change for KingsCrown (drops exactly 1 today; needs 1 per player):
```java
// Current (DwarfKing.die(), ~line 562):
Dungeon.level.drop(new KingsCrown(), pos + Dungeon.level.width()).sprite.drop(pos);

// Multiplayer-aware replacement:
for (int p = 0; p < Dungeon.heroes.size(); p++) {
    Dungeon.level.drop(new KingsCrown(), pos + Dungeon.level.width()).sprite.drop(pos);
}
```

Example change for TengusMask (condition today checks only `Dungeon.hero`):
```java
// Current:
if (Dungeon.hero.subClass == HeroSubClass.NONE) {
    Dungeon.level.drop(new TengusMask(), pos).sprite.drop();
}

// Multiplayer-aware (drop one per hero who still lacks a subclass):
for (Hero h : Dungeon.heroes) {
    if (h.subClass == HeroSubClass.NONE) {
        Dungeon.level.drop(new TengusMask(), pos).sprite.drop();
    }
}
```

For LloydsBeacon, iterate all heroes:
```java
// Current (single hero):
LloydsBeacon beacon = Dungeon.hero.belongings.getItem(LloydsBeacon.class);
if (beacon != null) beacon.upgrade();

// Multiplayer-aware:
for (Hero h : Dungeon.heroes) {
    LloydsBeacon beacon = h.belongings.getItem(LloydsBeacon.class);
    if (beacon != null) beacon.upgrade();
}
```

GooBlob and MetalShard are already stackable crafting ingredients (no per-hero gating), so multiplying their count by `Dungeon.heroes.size()` in the existing `Random.chances` loop is sufficient.

**Important constraint:** `KingsCrown` and `TengusMask` are flagged `unique = true` in their item initializers. Items flagged `unique` may have singleton assumptions elsewhere (e.g. catalog tracking). Dropping N copies simultaneously should be verified for heap merging and catalog behavior before shipping.

## Logs

| Source | Location |
|--------|----------|
| Boss death | `GLog` calls in each boss `die()` method via `yell()` |
| Item use | `GLog.p(Messages.get(this, "used"))` in `TengusMask.choose()`, `GLog.p(...)` in `KingsCrown.upgradeArmor()` |

## Deployment

- Mechanism: `local only` (Android/desktop libGDX game; no server deploy)
- Deploy command:
  ```bash
  ./gradlew desktop:run   # local desktop run
  ./gradlew android:assembleDebug   # Android APK
  ```
- Notes: No CI deploy pipeline detected in repo for this feature area.
