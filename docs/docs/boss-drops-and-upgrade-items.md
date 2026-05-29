# Boss Drops and Upgrade Items

## Metadata

- System type: `flow`

## System Intent

- What this is: The mechanism by which bosses drop special progression items when killed, and the logic by which those items apply their effects to heroes when used. In multiplayer, `TengusMask` and `KingsCrown` chain selection windows sequentially for every eligible hero after the first hero activates — only one physical item needs to be picked up.

## Mermaid Diagram

```mermaid
flowchart TD
  BossDie["Boss.die()"] -->|Dungeon.level.drop(item, pos)| Heap["Item Heap on Level"]
  Heap -->|Hero picks up / uses| Execute["Item.execute(hero, action)"]
  Execute -->|sets curUser = hero\npopulates pendingHeroes| Effect["Effect applied to curUser"]
  Effect -->|showNextPending()| Chain{"pendingHeroes\nempty?"}
  Chain -->|"no"| NextWindow["WndChoose* shown for next hero"]
  NextWindow --> Chain
  Chain -->|"yes"| Done["All eligible heroes upgraded"]
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
| Tengu (depth 10) | `TengusMask` (1, if ANY hero has `subClass == NONE`) | `Tengu.die()` |
| DM300 (depth 15) | `MetalShard` (2–4, random) | `DM300.die()` |
| Dwarf King (depth 20) | `KingsCrown` (1, always) | `DwarfKing.die()` |
| Yog-Dzewa (depth 25) | none via `die()`; amulet spawned by `LastLevel.createItems()` | `LastLevel.java:169` |

The random quantity ranges for GooBlob and MetalShard use:
```java
// Goo / DM300 pattern:
int count = Random.chances(new float[]{0, 0, 6, 3, 1}); // 60% 2, 30% 3, 10% 4
```

#### Tengu.die() drop condition

`Tengu.die()` now iterates all heroes to decide whether to drop the mask:

```java
boolean anyNeedsSubclass = false;
for (Hero h : Dungeon.heroes) {
    if (h.subClass == HeroSubClass.NONE) { anyNeedsSubclass = true; break; }
}
if (anyNeedsSubclass) {
    Dungeon.level.drop(new TengusMask(), pos).sprite.drop();
}
```

Previously this checked `Dungeon.hero.subClass` only (the current singleton pointer), which caused the mask to be skipped in multiplayer if hero[0] already had a subclass.

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

`Item.execute(Hero hero, String action)` sets `curUser = hero` then delegates to item-specific methods. For `TengusMask` and `KingsCrown`, activation chains a sequential selection window across all eligible heroes via `pendingHeroes` / `showNextPending()`. Other items apply effects only to the activating hero.

| Item | Effect when activated | Multiplayer scope |
|---|---|---|
| `TengusMask` | Opens `WndChooseSubclass` for first hero; chains to all other heroes with `subClass == NONE` via `showNextPending()` | All eligible heroes (sequential) |
| `KingsCrown` | Opens `WndChooseAbility` for first hero; chains to all other heroes via `showNextPending()` | All heroes (sequential) |
| `GooBlob` | No activate action; used as crafting ingredient or sold for 30g each | Single hero who crafts/sells |
| `MetalShard` | No activate action; used as crafting ingredient or sold for 50g each | Single hero who crafts/sells |
| `Amulet` | Triggers `AmuletScene` (game ending flow) when picked up | Whoever picks it up |

#### Pseudocode: TengusMask activation

```
TengusMask.execute(hero, "WEAR"):
  curUser = hero
  pendingHeroes.clear()
  for each h in Dungeon.heroes:
    if h != hero && h.subClass == HeroSubClass.NONE:
      pendingHeroes.add(h)
  GameScene.show(new WndChooseSubclass(this, hero))

WndChooseSubclass → user confirms → TengusMask.choose(subclass):
  detach from curUser.belongings.backpack
  curUser.subClass = subclass
  Talent.initSubclassTalents(curUser)
  // visual effects, sounds, GLog
  showNextPending()

TengusMask.showNextPending():
  // skip heroes who already have a subclass (e.g. picked one up mid-run)
  while !pendingHeroes.isEmpty() && pendingHeroes[0].subClass != NONE:
    pendingHeroes.remove(0)
  if pendingHeroes.isEmpty(): return
  next = pendingHeroes.remove(0)
  Item.curUser = next
  GameScene.show(new WndChooseSubclass(new TengusMask(), next))
```

#### Pseudocode: KingsCrown activation

```
KingsCrown.execute(hero, "WEAR"):
  curUser = hero
  pendingHeroes.clear()
  for each h in Dungeon.heroes:
    if h != hero: pendingHeroes.add(h)
  if hero.belongings.armor() != null:
    GameScene.show(new WndChooseAbility(this, hero.belongings.armor(), hero))
  else:
    GLog.w("naked" message)

WndChooseAbility → user confirms → KingsCrown.upgradeArmor(hero, armor, ability):
  detach from hero.belongings.backpack
  classArmor = ClassArmor.upgrade(hero, armor)
  hero.belongings.armor = classArmor  // if worn
  hero.armorAbility = ability
  Talent.initArmorTalents(hero)
  // visual effects, sounds
  showNextPending()

KingsCrown.showNextPending():
  if pendingHeroes.isEmpty(): return
  next = pendingHeroes.remove(0)
  GameScene.show(new WndChooseAbility(new KingsCrown(), next.belongings.armor(), next))
```

#### Single-player behavior

In single-player, `Dungeon.heroes` contains exactly one hero. `pendingHeroes` stays empty after the activating hero is excluded, and `showNextPending()` is a no-op. Behavior is identical to the pre-multiplayer code path.

### Flow: `multiplayerDropNotes`

#### How one item serves all heroes

`KingsCrown.unique = true` and `TengusMask.unique = true` remain unchanged — only one physical item is dropped. The sequential chain (`pendingHeroes` / `showNextPending()`) handles all other heroes without requiring additional item instances in inventory. The `new TengusMask()` / `new KingsCrown()` passed to subsequent windows are transient callback carriers, not inventory items.

#### KingsCrown naked-hero edge case

`KingsCrown.showNextPending()` passes `next.belongings.armor()` directly to `WndChooseAbility`. If a pending hero has no armor, `WndChooseAbility` receives `null` for the armor argument; the window already handles this at line 126 (confirmed in plan notes).

#### LloydsBeacon — still single-hero

`LloydsBeacon.upgrade()` in `Tengu.die()`, `DM300.die()`, and `DwarfKing.die()` still uses `Dungeon.hero.belongings.getItem(...)` only. Other heroes' beacons are not upgraded.

## Logs

| Source | Location |
|--------|----------|
| Boss death | `GLog` calls in each boss `die()` method via `yell()` |
| Item use | `GLog.p(Messages.get(this, "used"))` in `TengusMask.choose()`, `GLog.p(...)` in `KingsCrown.upgradeArmor()` — fires once per hero in the chain |

## Deployment

- Mechanism: `local only` (Android/desktop libGDX game; no server deploy)
- Deploy command:
  ```bash
  ./gradlew desktop:run   # local desktop run
  ./gradlew android:assembleDebug   # Android APK
  ```
- Notes: Single-player is completely unaffected — `pendingHeroes` stays empty and `showNextPending()` is a no-op.
