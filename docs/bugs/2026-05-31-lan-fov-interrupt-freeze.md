# Bug: LAN Freeze — Remote Hero Uses Shared heroFOV, Wrong checkVisibleMobs Fires interrupt()

**Date:** 2026-05-31
**Severity:** High — permanent LAN deadlock
**Status:** Fixed

---

## Failure Signature

P1 and P2 both have a hero. An enemy is visible from hero[0]'s position but NOT from hero[1]'s position. On P1's device, hero[1]'s `act()` calls `checkVisibleMobs()` using the shared `heroFOV` array (which reflects hero[0]'s POV). The enemy appears as a "new mob" to hero[1] → `interrupt()` fires → `curAction = null`. Hero[1] re-enters the LAN wait. P2's device already processed the walk without interruption and stopped sending. P1 waits forever.

---

## Root Cause

In `Hero.act()` at line 909:

```java
fieldOfView = Dungeon.level.heroFOV;
```

All heroes share the same `heroFOV` array reference. `Dungeon.observe()` only updates `heroFOV` for `Dungeon.hero` (always hero[0] on P1's device). When hero[1]'s `act()` runs:

1. Line 909 assigns `fieldOfView` → points at `heroFOV` (hero[0]'s view)
2. Line 918 calls `Dungeon.observe()` → updates `heroFOV` from hero[0]'s position
3. Line 925 calls `checkVisibleMobs()` → uses hero[1]'s `fieldOfView`, which IS `heroFOV` → sees enemies visible from hero[0]'s position

If a mob is in `heroFOV` (visible to hero[0]) but was not in hero[1]'s `visibleEnemies` list:
- `newMob = true` → `interrupt()` fires → `curAction = null`
- Hero[1] re-enters `lanActionLock.wait()` for a packet that P2 already sent → **deadlock**

---

## Impacted Files

- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/hero/Hero.java`

---

## Fix

Before calling `checkVisibleMobs()` for a remote hero, recompute `fieldOfView` specifically for that hero:

```java
if (NetworkManager.lanMode && Dungeon.heroes != null) {
    int remoteIdx = Dungeon.heroes.indexOf(this);
    if (remoteIdx != NetworkManager.localPlayerIndex) {
        if (fieldOfView == null || fieldOfView.length != Dungeon.level.length()) {
            fieldOfView = new boolean[Dungeon.level.length()];
        }
        Dungeon.level.updateFieldOfView(this, fieldOfView);
    }
}
checkVisibleMobs();
```

This gives hero[1] its own FOV array computed from its actual position, so `checkVisibleMobs()` only fires `interrupt()` for enemies hero[1] genuinely encounters.

---

## Verification

- Wrote `LanFovInterruptFreezeTest.java` with failing test (bug) and passing test (fix)
- All LAN tests pass after fix
