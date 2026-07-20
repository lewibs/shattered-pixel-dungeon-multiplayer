# Mob Death LAN Desync System

## Metadata

- System type: `flow`

## System Intent

- What this is: The mob death flow in `Mob.java` that handles EXP awarding, loot dropping, and talent activation when a mob is killed. In LAN multiplayer, all these operations must use the killer hero (derived from the `cause` parameter) rather than `Dungeon.hero` (the local device's hero), because `Dungeon.hero` differs between devices.

## Mermaid Diagram

```mermaid
flowchart TD
  KillerAttacks["Killer hero attacks mob"] -->|mob.HP <= 0| CharDamage["Char.damage() calls mob.die(killer)"]
  CharDamage --> MobDie["Mob.die(cause)"]
  MobDie --> RollLoot["rollToDropLoot() — uses Dungeon.hero.lvl, Ring of Wealth, BOUNTY_HUNTER"]
  MobDie --> TalentCheck["LETHAL_MOMENTUM / LETHAL_HASTE check — uses Dungeon.hero"]
  MobDie --> SuperDie["super.die(cause) → Char.die() → destroy()"]
  SuperDie --> MobDestroy["Mob.destroy()"]
  MobDestroy --> MindVisionCheck["MindVision check — uses Dungeon.hero"]
  MobDestroy --> EXPGrant["earnExp() — uses Dungeon.hero.lvl, Dungeon.hero"]
  MobDestroy --> MonkEnergy["MonkEnergy — uses Dungeon.hero.subClass"]

  LAN["LAN Mode"] -->|Dungeon.hero = local hero| HostDevice["Host: Dungeon.hero = hero[0]"]
  LAN -->|Dungeon.hero = local hero| ClientDevice["Client: Dungeon.hero = hero[1]"]
  HostDevice -->|hero[1] kills mob| Desync["DESYNC: EXP/loot/talents diverge"]
  ClientDevice -->|hero[1] kills mob| Desync
```

## Flows

### Flow: `mobDie`
- Test files: `core/src/test/java/com/shatteredpixel/shatteredpixeldungeon/lan/LanMobDeathSyncTest.java`
- Core files: `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Mob.java`

#### Types

```txt
MobDieInput {
  cause: Object  // Hero | Weapon | Weapon.Enchantment | Chasm.class | null
}

KillerResolution {
  killerHero: Hero  // resolved from cause; fallback to Dungeon.hero
}
```

#### Paths

| path | input | output | path-type | notes |
| --- | --- | --- | --- | --- |
| `mobDie.heroKill` | `cause instanceof Hero` | EXP to killer, talents on killer | happy path | killerHero = (Hero) cause |
| `mobDie.weaponKill` | `cause instanceof Weapon` | EXP to Dungeon.hero | partial fix needed | Item.curUser holds the actual hero |
| `mobDie.nonHeroKill` | `cause = null or class` | EXP to Dungeon.hero | fallback | OK in single-player |
| `mobDie.chasmKill` | `cause == Chasm.class` | EXP halved, then normal flow | special case | |

#### Pseudocode

```
Mob.die(cause):
  // BUG (pre-fix): killerHero = Dungeon.hero  ← different on each LAN device
  // FIX (post-fix): resolve killer from cause:
  if cause instanceof Hero:
      killerHero = (Hero) cause
  else:
      killerHero = Dungeon.hero  // fallback for single-player / non-hero kills

  rollToDropLoot(killerHero):    // uses killerHero.lvl for level cap check
  talentChecks(killerHero):      // LETHAL_MOMENTUM, LETHAL_HASTE

  super.die(cause) → Char.die() → destroy():
    EXP = killerHero.lvl <= maxLvl ? EXP : 0
    killerHero.earnExp(exp, ...)
    if killerHero.subClass == MONK: MonkEnergy.gainEnergy(...)

lootChance(killerHero):
  dropBonus = RingOfWealth.dropChanceMultiplier(killerHero)   // NOT Dungeon.hero
  BountyHunterTracker checks on killerHero                    // NOT Dungeon.hero
  return lootChance * dropBonus

rollToDropLoot(killerHero):
  if killerHero.lvl > maxLvl + 2: return  // skip loot for over-leveled killer
  Ring.getBuffedBonus(killerHero, RingOfWealth.Wealth.class)  // NOT Dungeon.hero
  RingOfWealth.tryForBonusDrop(killerHero, rolls)             // NOT Dungeon.hero
  SOUL_EATER talent check on killerHero                       // NOT Dungeon.hero
```

## Known Failure Modes

### LAN Desync on Mob Kill (FIXED in 2026-06-03)

- **Bug**: All `Dungeon.hero` references in `die()`, `destroy()`, `lootChance()`, and `rollToDropLoot()` use the local device's hero. In LAN mode, `Dungeon.hero = hero[0]` on host and `Dungeon.hero = hero[1]` on client. The same mob kill produces different EXP recipients, loot, and talent activations on each device.
- **Root cause**: `Dungeon.hero` is set by `Hero.activate()` which skips remote heroes (`if myIdx != NetworkManager.localPlayerIndex: return`). So `Dungeon.hero` is always the local player's hero, regardless of who actually made the kill.
- **Fix**: Add a `killerHero` field to `Mob`. In `die()`, resolve the killer hero from the `cause` parameter (if `cause instanceof Hero`) before calling `super.die()`. Use `killerHero` throughout `destroy()`, `rollToDropLoot()`, and `lootChance()`, falling back to `Dungeon.hero` for non-hero causes or single-player mode.
- **Bug file**: `docs/bugs/2026-06-03-lan-mob-death-dungeon-hero-desync.md`

## Logs

| Source | Location |
|--------|----------|
| Mob death | In-game GLog at `Mob.die()` line 918-919 (not visible in headless tests) |

## Deployment

- Mechanism: `local only`
- Deploy command:
  ```bash
  ./gradlew core:test --tests "com.shatteredpixel.shatteredpixeldungeon.lan.LanMobDeathSyncTest"
  ```
- Notes: Tests are headless (no sprites/GameScene). The MinimalLevel and TestMob stubs isolate the EXP-recipient and loot-level logic.
