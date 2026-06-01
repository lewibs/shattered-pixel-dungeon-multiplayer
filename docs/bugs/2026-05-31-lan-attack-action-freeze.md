# Bug: LAN Freeze on Attack Action (actAttack returns false without clearing curAction)

**Date**: 2026-05-31
**Slug**: lan-attack-action-freeze
**Status**: Fixed

---

## Failure Signature

Game freezes permanently when a remote LAN hero performs an attack. After the attack animation starts (`sprite.attack()`), the actor loop spins infinitely re-entering `actAttack()` without making progress.

---

## Reproduction Steps

1. Start a 2-player LAN game.
2. Remote player (hero index 1) moves adjacent to an enemy.
3. Remote player attacks the enemy.
4. Game freezes on both clients — the actor loop never advances past the attack turn.

---

## Expected Behavior

After `actAttack()` completes (animation fires, `onAttackComplete` callback is scheduled), the remote hero's turn ends and the next actor (enemy or other hero) proceeds.

---

## Actual Behavior

The actor loop re-enters the remote hero's `act()` indefinitely:
- `actAttack()` calls `sprite.attack()` and returns `false` (animation async)
- `actResult = false` at line 1101 triggers `next()` for the remote hero
- But `curAction` is still `HeroAction.Attack` (was not cleared)
- Next `act()` call: `curAction != null` → skips `receiveActionAsync()` → no wait
- Falls through to `actAttack()` again → returns `false` again → infinite loop

---

## Environment

- Java/LibGDX game (Shattered Pixel Dungeon Multiplayer)
- Affects all attack types for remote LAN heroes
- Reproducible 100% of the time on any attack

---

## Root Cause

Two separate issues compound:

**Issue 1** (`Hero.java` line 1101):
```java
if (!actResult && NetworkManager.lanMode && Dungeon.heroes != null) {
```
The `!actResult` check fires for **any** false return from `act()`, including `actAttack()` returning false while the animation is still playing and `curAction` is still set. The intent was to only call `next()` after `ready()` had run (which clears `curAction`). Adding `&& curAction == null` restricts the `next()` call to the case where `ready()` actually cleared `curAction`.

**Issue 2** (`Hero.java` line 1623):
`actAttack()` calls `sprite.attack()` and returns `false` without clearing `curAction`. This means on the next `act()` invocation the stale `HeroAction.Attack` action is still present. Adding `curAction = null` before the `return false` ensures the next `act()` sees a clean state (the attack completes via the `onAttackComplete` callback, not by re-entering `actAttack()`).

---

## Fix Summary

**Change 1** — `Hero.java` line 1101: Add `curAction == null` guard:
```java
// Before:
if (!actResult && NetworkManager.lanMode && Dungeon.heroes != null) {

// After:
if (!actResult && curAction == null && NetworkManager.lanMode && Dungeon.heroes != null) {
```

**Change 2** — `Hero.java` line ~1623: Clear `curAction` before returning false after `sprite.attack()`:
```java
sprite.attack( attackTarget.pos );
curAction = null;   // ← added
return false;
```

---

## Verification

- Test: `LanAttackActionFreezeTest` — all tests pass after fix.
- Confirmed failing before fix, passing after fix.
- All other LAN tests pass (no regressions).
