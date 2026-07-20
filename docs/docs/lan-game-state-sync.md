# LAN Game State Sync — Headless Test Infrastructure

## Overview

`LanGameStateSyncTest` and related test classes run real `Hero.act()` execution in headless JUnit tests (no OpenGL, no `GameScene`, no sprite context). The tests compare game state across three execution modes to verify synchronization:

- **Pass-and-play** (`lanMode=false`): both heroes act locally on one device
- **LAN host** (`lanMode=true, isHost=true, localPlayerIndex=0`): hero0 acts locally, hero1 receives actions via socket
- **LAN client** (`lanMode=true, isHost=false, localPlayerIndex=1`): hero1 acts locally, hero0 receives actions via socket

## Components

### MinimalLevel

A stub `Level` implementation that:
- Pre-fills all arrays (passable, heroFOV, visited, mapped) to `true`
- Skips level generation — no mobs, items, transitions
- Used across all headless LAN tests

### TestHero

Extends `Hero` with sprite-free overrides:
- `moveSprite(from, to)` — returns `true` immediately (pos already updated by `Char.move()`)
- `checkVisibleMobs()` — no-op (no sprite.turnTo calls)

### SyncTestMob / TestMob (in LanPassPlaySyncTest / LanMobDeathSyncTest)

Extends `Mob` to bypass `sprite.die()` in headless mode:
- Overrides `die()` to call `destroy()` directly instead of via `super.die()`  
- All state logic (EXP, loot, talent checks, Statistics) runs through real production code

### GameState

Snapshot of observable state captured after each action sequence:
- `pos0, pos1` — hero positions
- `time0, time1` — actor time (from `Actor.getTimeForTesting()`)
- `hp0, hp1` — hero HP

## Socket Infrastructure

Uses real TCP loopback sockets (same as `LanRealSocketTest`):

```
serverSocket.accept() → hostSideSocket
new Socket(port)      → clientSocket

hostOut   → clientIn   (host sends to client)
clientOut → hostIn     (client sends to host)

NetworkManager.injectHostStreamsForTesting(hostIn, hostOut)
NetworkManager.injectClientStreamsForTesting(clientIn, clientOut)
```

**HOST perspective** (`isHost=true`, `localPlayerIndex=0`):
- Local hero acts: `hero.curAction = new HeroAction.Move(dst); hero.act();`
- Remote hero: call `NetworkManager.receiveActionAsync(remoteHero)`, send packet via `clientOut`, then call `hero.act()`

**CLIENT perspective** (`isHost=false`, `localPlayerIndex=1`):
- Local hero acts directly
- Remote hero: call `NetworkManager.receiveActionAsync(remoteHero)`, send packet via `hostOut`

## Null-Safety Fixes (Production Code)

The following production null guards were required to run `Hero.act()` headlessly:

### GameScene / AttackIndicator
- `GameScene.ready()` — `if (scene == null) return;`
- `GameScene.selectCell()` — `if (cellSelector == null) return;`
- `AttackIndicator.updateState()` — `if (instance == null) return;`
- `AttackIndicator.target()` — `if (target == null || instance == null) return;`

### PixelScene / Camera
- `PixelScene.shake()` — `if (Camera.main == null) return;`

### Char.java
- `Char.moveSprite()` — `if (sprite == null) return true;` at top
- `Char.die()` — `if (sprite != null) sprite.die();`
- `Char.damage()` (invulnerable) — `if (sprite != null) sprite.showStatus(...)`
- `Char.damage()` (BrokenSeal shield) — `if (sprite != null) sprite.showStatusWithIcon(...)`
- `Char.damage()` (Grim tracker) — `if (sprite != null) sprite.emitter().burst(...)`
- `Char.attack()` (bloodBurst) — `if (enemy.sprite != null && sprite != null)`

### Hero.java
- `actInteract()` — `if (sprite != null) sprite.turnTo(...)`
- `actPickUp()` — `if (heap.sprite != null) heap.sprite.drop()`
- `actOpenChest()` — `if (sprite != null) sprite.operate(dst); else onOperateComplete()`
- `actUnlock()` — `if (sprite != null) sprite.operate(doorCell); else onOperateComplete()`
- `actAttack()` — `if (sprite != null) sprite.attack(pos); else onAttackComplete()`
- `actMine()` — callback extracted, then `if (sprite != null) sprite.attack(dst, cb); else cb.call()`

### Gold.java
- `doPickUp()` — `if (hero.sprite != null) hero.sprite.showStatusWithIcon(...)`

### Mob.java
- `rollToDropLoot()` — guard all `Heap.sprite.drop()` calls with `if (heap.sprite != null)`
- `rollToDropLoot()` (RingOfWealth) — guard `showFlareForBonusDrop(sprite)` with `if (sprite != null)`
- `rollToDropLoot()` (Lucky) — guard similarly
- `die()` wraith spawn — `Emitter wrCellEmitter = CellEmitter.get(pos); if (wrCellEmitter != null) wrCellEmitter.burst(...)`

### CellEmitter.java
- All static methods (`get`, `center`, `floor`, `bottom`) return `null` when `GameScene.emitter()` returns null

## Action Flows

### Attack flow (headless)
1. `actAttack()` sets `attackTarget`
2. `if (sprite != null) sprite.attack(pos); else onAttackComplete();`
3. `onAttackComplete()` → `attack(attackTarget)` → `Char.attack()` → `enemy.damage()`
4. If mob dies: `Mob.die()` → `Mob.destroy()` → EXP to `killerHero`
5. `curAction = null`

### Pickup flow (headless)
1. `actPickUp()` finds heap at `pos`
2. `item.doPickUp(hero)` — e.g. `Gold.doPickUp()` increments `Dungeon.gold`, calls `hero.spendAndNext()`
3. If can't pick up: `if (heap.sprite != null) heap.sprite.drop()` (no-op headlessly)

### Move flow (headless)
1. `actMove()` → `getCloser(dst)` → `move(step)` → `Char.move()` sets `pos`
2. `TestHero.moveSprite()` returns `true` immediately
3. `spendAndNext()` → `Actor.spend(time)`

## NetworkManager Packet Format

For ACTION packets sent via socket:
```
writeByte(PacketType.ACTION)  // 0x01
writeInt(heroId)              // 0 or 1
writeByte(ActionType.MOVE)    // or ATTACK, PICKUP, etc.
writeInt(targetPos)           // destination cell
```

## Key Files

- `core/src/test/java/com/shatteredpixel/shatteredpixeldungeon/lan/LanGameStateSyncTest.java`
- `core/src/test/java/com/shatteredpixel/shatteredpixeldungeon/lan/LanPassPlaySyncTest.java`
- `core/src/test/java/com/shatteredpixel/shatteredpixeldungeon/lan/LanMobDeathSyncTest.java`
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java`
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/Char.java`
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/network/NetworkManager.java`
