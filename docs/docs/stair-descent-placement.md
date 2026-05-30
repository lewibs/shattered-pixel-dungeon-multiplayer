# System: stair-descent-placement

## Overview

`Dungeon.placeHeroesNearEntrance()` is the multiplayer hero-placement routine that runs when a party descends stairs (or ascends, or returns) to a new level. It places heroes[1..N] adjacent to the entrance cell so the party spawns near each other instead of retaining stale positions from the previous floor.

## Key Files

- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/Dungeon.java` — contains `placeHeroesNearEntrance()` (package-private, lines ~563–587) and `switchLevel()` (lines ~497–556)
- `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/scenes/InterlevelScene.java` — `descend()`, `ascend()`, `fall()`, `returnTo()` all set `Dungeon.heroesNeedInitialPlacement = true` when `heroes.size() > 1`
- `core/src/test/java/com/shatteredpixel/shatteredpixeldungeon/StairDescentPlacementTest.java` — unit tests

## Flow

```
InterlevelScene.descend() / ascend() / fall()
  └─ Dungeon.heroesNeedInitialPlacement = true      (if heroes.size() > 1)
  └─ Dungeon.switchLevel(level, entrancePos)
        └─ hero.pos = entrancePos                   (hero[0] always placed first)
        └─ if heroesNeedInitialPlacement:
              placeHeroesNearEntrance(level, entrancePos, heroes)
        └─ Actor.init()                              (AFTER placement)
```

## `placeHeroesNearEntrance` Logic

```java
static void placeHeroesNearEntrance(Level level, int entrancePos, ArrayList<Hero> heroes) {
    HashSet<Integer> occupied = new HashSet<>();
    occupied.add(entrancePos);                   // hero[0] is already there
    for (int i = 1; i < heroes.size(); i++) {
        Hero h = heroes.get(i);
        // Falling heroes already have a fall-cell — skip
        if (h.buff(Chasm.WaitingToFall.class) != null
                || h.buff(Chasm.Falling.class) != null) continue;

        int placed = -1;
        for (int offset : PathFinder.NEIGHBOURS8) {
            int candidate = entrancePos + offset;
            if (candidate >= 0 && candidate < level.length()
                    && level.passable[candidate]
                    && !occupied.contains(candidate)) {
                placed = candidate;
                break;
            }
        }
        int heroPos = (placed != -1) ? placed : entrancePos; // fallback = stack at entrance
        h.pos = heroPos;
        occupied.add(heroPos);
    }
}
```

**Key design decisions:**
1. Uses a local `HashSet<Integer> occupied` instead of `Actor.findChar()` because `Actor.init()` has not run yet at placement time — `findChar()` would return `null` for every cell.
2. Iterates `PathFinder.NEIGHBOURS8` (8 surrounding cells) for each hero in turn, claiming the first free passable cell and adding it to `occupied`.
3. Falling heroes (`Chasm.WaitingToFall` or `Chasm.Falling`) are skipped — their positions are managed separately by `InterlevelScene` via `fallPlacements` map.
4. If no adjacent free cell exists the hero falls back to `entrancePos` (stacks with hero[0]).

## `PathFinder.NEIGHBOURS8`

Set by `PathFinder.setMapSize(width, height)`:

```
NEIGHBOURS8 = {-width-1, -width, -width+1, -1, +1, +width-1, +width, +width+1}
```

These are 1D array offsets. The bounds check `candidate >= 0 && candidate < level.length()` prevents out-of-array access but **does not prevent grid row-wrapping**: if `entrancePos` is at column 0, offset `-1` produces a cell on the row above (column `width-1`), which passes the bounds check but is not geometrically adjacent. In practice, dungeon entrances are never placed at column 0 (they are always in the interior), so this is not a live bug — but it is an untested edge case.

## State Flag: `heroesNeedInitialPlacement`

- Declared in `Dungeon.java` as `public static boolean heroesNeedInitialPlacement = false`.
- Set to `true` in `InterlevelScene` before calling `switchLevel()`, only when `heroes.size() > 1`.
- Consumed and reset to `false` at the top of `switchLevel()`.
- Only set on stair descent/ascent/fall/returnTo — NOT on initial game start (that path has a separate placement at game initialization time: `newGame()` sets it when `heroes.size() > 1`).

## Falling Heroes — Interaction with `descend()`

In `InterlevelScene.descend()`:
1. Heroes with `WaitingToFall` have their fall-cell computed via `level.fallCell()`, then `WaitingToFall` is detached and `Chasm.Falling` is attached.
2. `switchLevel()` runs — `placeHeroesNearEntrance()` sees the `Falling` buff and skips those heroes.
3. After `switchLevel()` returns, the `fallPlacements` map overrides positions for all falling heroes.

In `InterlevelScene.fall()`:
- Similar pattern but the primary hero (who triggered the fall) is also in `fallingHeroes`.
- All falling heroes get `Chasm.Falling` before `switchLevel()`.
- After `switchLevel()`, `fallPlacements` overrides all fall positions.

## Test Coverage

`StairDescentPlacementTest` covers:
- `SingleHero` — no-op
- `TwoHeroes` — adjacent, distinct, passable
- `ThreeHeroes` — all distinct, adjacent, passable (regression for original bug)
- `FourHeroes` — all distinct, adjacent, passable
- `ConstrainedLayout` — one passable neighbour (hero[2] stacks at entrance), corner entrance
- `FallingHeroes` — `WaitingToFall` and `Falling` heroes are not overwritten, non-falling hero in a mixed party still gets a slot
- `OriginalFarApartBehavior` — stale positions are overwritten after placement
- `Regression` — explicit proof that the pre-fix stacking bug is resolved

## Original Bug (Fixed in commit 420320782)

Before the fix, the loop used `Actor.findChar(candidate)` to check if a cell was occupied. Since `Actor.init()` had not run yet, `findChar()` returned `null` for every cell. All heroes saw every cell as free and all landed on the same first passable neighbour of the entrance.

**Fix:** replaced `Actor.findChar()` with a local `HashSet<Integer> occupied`, populated as each hero claims a cell.
