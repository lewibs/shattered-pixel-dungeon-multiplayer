# Headless Hero.act() NPE on Sprite/GameScene/PixelScene Calls

## Metadata

- Date: `2026-06-03`
- Status: `resolved`
- Severity: `high`
- Related issue/ticket: `N/A`
- Owner: `lewibs`

## About

**Overview**:
`Hero.act()` and its helper methods (`actAttack`, `actPickUp`, `actOpenChest`, `actUnlock`, `actInteract`, `actMine`, `actAlchemy`) contain unconditional calls to `sprite.*`, `PixelScene.shake()`, `CellEmitter.*`, and `Splash.*` that crash with NPE when run in headless JUnit tests (no OpenGL/GameScene context). The goal is to add null-safety guards so the full action suite can run in tests, then add comprehensive integration tests for attack, pickup, chest, and multi-round sequences.

**Root Cause**:
- `Hero.actAttack()` line ~1636: `sprite.showStatusWithIcon(...)` — no null guard
- `Hero.actAttack()` line ~1641: `sprite.attack(attackTarget.pos)` — no null guard, also has async callback path
- `Hero.actPickUp()` line ~1328: `heap.sprite.drop()` — no null guard on heap.sprite
- `Hero.actOpenChest()` line ~1379: `PixelScene.shake(...)` — `Camera.main` is null headlessly
- `Hero.actOpenChest()` line ~1388: `sprite.operate(dst)` — no null guard
- `Hero.actUnlock()` line ~1444: `sprite.operate(doorCell)` — no null guard
- `Hero.actMine()` line ~1473: `sprite.attack(action.dst, cb)` — no null guard; callback never fires if sprite null
- `Hero.actMine()` line ~1499: `Dungeon.level.drop(gold,pos).sprite.drop()` — heap.sprite may be null headlessly
- `Hero.actMine()` line ~1538: `sprite.parent.add(...)` — no null guard
- `Hero.actInteract()` line ~1204: `sprite.turnTo(pos, ch.pos)` — no null guard
- `Char.moveSprite()` line ~313: `sprite.isVisible()` — already handled via TestHero override
- `Char.die()` line ~1097: `sprite.die()` — no null guard
- `Char.damage()` line ~832: `sprite.showStatus(...)` on invulnerable — no null guard

**Technical Questions**:
- For sprite calls WITH callbacks: `if (sprite != null) sprite.X(cb); else cb.call();`
- For pure visual calls: `if (sprite != null) sprite.X(...);`
- For `PixelScene.shake()`: guard with `if (Camera.main != null)`
- For `CellEmitter.*` and `Splash.*`: wrap in null check or try-catch (they reference visual systems)

**Resources**:
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java`
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/Char.java`
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/scenes/PixelScene.java`
- `core/src/test/java/com/shatteredpixel/shatteredpixeldungeon/lan/LanGameStateSyncTest.java`

## Steps to cause failure

1. Run `./gradlew core:test --tests "com.shatteredpixel.shatteredpixeldungeon.lan.LanGameStateSyncTest"`
2. New tests for `actAttack`, `actPickUp` NPE on `sprite.*` and `Camera.main` calls

## Reproduction Details

New tests will be added to `LanGameStateSyncTest`:
1. Attack test: hero0 attacks a TestMob until it dies — crashes at `sprite.attack(attackTarget.pos)` 
2. Pickup test: place Gold on floor, hero1 picks it up — crashes at `heap.sprite.drop()`

## Audit Log

| ID | Action | Note | Context |
| --- | --- | --- | --- |
| 1 | Create audit log | Initialize investigation | Hero.act() headless null-safety |
| 2 | Read Hero.java | Identified sprite call sites in actAttack, actPickUp, actOpenChest, actUnlock, actMine, actInteract | Lines 1204, 1328, 1379, 1388, 1444, 1473, 1499, 1536, 1636, 1641 |
| 3 | Read Char.java | Identified sprite.die() at line 1097, sprite.showStatus at line 832 | Char.die(), Char.damage() |
| 4 | Read PixelScene.java | shake() calls Camera.main which is null headlessly | Line 406-409 |
| 5 | Fixed Hero.java | null guards on sprite calls, callback pattern for actAttack/actOpenChest/actUnlock/actMine | All sprite.*() call sites |
| 6 | Fixed Char.java | null guards on sprite.die(), sprite.showStatus(), FloatingText static init guard | Camera.main check |
| 7 | Fixed Mob.java | null guards in rollToDropLoot(), die() | heap.sprite, CellEmitter |
| 8 | Fixed CellEmitter.java | guard GameScene.emitter() null in all static methods | get/center/floor/bottom |
| 9 | Fixed PixelScene.java | Camera.main null guard in shake() | Line 406 |
| 10 | Fixed AttackIndicator.java | instance null guard in target() and updateState() | static instance |
| 11 | Fixed Gold.java | hero.sprite null guard in doPickUp() | showStatusWithIcon |
| 12 | Fixed Messages.java | guard Gdx.app null in static initializer, headless fallback | SPDSettings.language() NPE |
| 13 | Fixed DeviceCompat.java | guard Gdx.app null in log() | GLog.i() → Mob.die() NPE |
| 14 | Fixed LanGameStateSyncTest.java | resetAll() recreates fresh heroes to prevent exp/level/HP accumulation | hero0.exp persisted across runs |
| 15 | All 8 tests pass | Full core:test suite green | BUILD SUCCESSFUL |

## Root Cause Summary

Seven distinct production null-safety gaps and one test setup error:

1. **Hero.java**: sprite callback calls without null checks — fixed with `if (sprite != null) sprite.X(); else callback.call()` pattern
2. **Char.java**: sprite.die()/showStatus() unconditional — fixed with null guards; FloatingText static init crashes when Camera.main is null
3. **Mob.java**: heap.sprite.drop() and CellEmitter calls unconditional in rollToDropLoot/die
4. **CellEmitter.java**: GameScene.emitter() returns null headlessly — all 4 static methods needed null check
5. **PixelScene.java**: Camera.main null in shake()
6. **Messages.java**: static initializer calls SPDSettings.language() → Gdx.app.getPreferences() which NPEs headlessly
7. **DeviceCompat.java**: log() calls Gdx.app.log() unconditionally — needed null check for Gdx.app
8. **LanGameStateSyncTest**: resetAll() reused same hero objects accumulating exp/level/HP between PP and HOST runs, causing exp mismatch

## Verification

- [x] Reproduced failure before fix
- [x] Reproduction test fails before fix
- [x] Root cause identified with evidence
- [x] Fix applied at source (no workaround-only patch)
- [x] Reproduction test passes after fix
- [x] Reproduction path now passes
- [x] Regression test added/updated
- [x] Verified no duplicate solved-bug log exists for same root cause
