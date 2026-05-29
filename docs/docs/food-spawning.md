# Food Spawning

## Metadata

- System type: `flow`

## System Intent

- What this is: Controls how many food items are dropped onto each regular dungeon floor during level generation. In single-player the normal item pool (`Generator.random()`) never produces food — food comes from talents and Monk mob drops. For multiplayer, `RegularLevel.createItems()` appends one extra food item per additional player (i.e. `heroes.size() - 1` extra drops) so parties do not share a solo-sized food supply. Boss levels and special levels are unaffected because they do not call `RegularLevel.createItems()`.

## Mermaid Diagram

```mermaid
flowchart TD
  CreateItems["RegularLevel.createItems()\nexisting item loop runs (3–5 items)\n(food not in this pool)"]
  PlayerCount["playerCount = Dungeon.heroes.size()\nextraFood = playerCount - 1"]
  Loop{"extraFood > 0?"}
  Drop["drop(Generator.random(FOOD), randomDropCell())\nrepeated extraFood times"]
  Done["Level fully populated"]

  CreateItems --> PlayerCount --> Loop
  Loop -->|"yes"| Drop --> Loop
  Loop -->|"no"| Done
```

## Flows

### Global Types

```txt
StandardError {
  message: string
}
```

---

### Flow: `scaleFloorFoodDrops`

- Test files: N/A
- Core files:
  - `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/levels/RegularLevel.java`
  - `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/items/Generator.java`

#### Types

```txt
// Generator.Category.FOOD contains:
//   Food        — prob weight 4  (80%)
//   Pasty       — prob weight 1  (20%)
//   MysteryMeat — prob weight 0  (never spawned via this path)
//
// firstProb = 0, secondProb = 0 for FOOD in the normal item deck,
// so food never appears in Generator.random() without an explicit category argument.
//
// Extra food items are plain floor drops (not inside chests or skeletons).
// They are dropped at randomDropCell() — a random valid passable cell.
```

#### Paths

| path | input | output | path-type | notes |
| --- | --- | --- | --- | --- |
| `scaleFloorFoodDrops.singlePlayer` | `heroes.size() == 1` | extraFood = 0, no drops added | happy path | Identical to pre-multiplayer behavior |
| `scaleFloorFoodDrops.twoPlayers` | `heroes.size() == 2` | 1 extra food item dropped on floor | happy path | |
| `scaleFloorFoodDrops.fourPlayers` | `heroes.size() == 4` | 3 extra food items dropped on floor | happy path | |
| `scaleFloorFoodDrops.nullHeroes` | `Dungeon.heroes` null or empty | extraFood = 0, no drops | happy path | Defensive fallback treats as single-player |
| `scaleFloorFoodDrops.noValidCell` | `randomDropCell()` returns `-1` | drop skipped for that iteration | happy path | Cell check prevents crash on pathological floors |

#### Pseudocode

```
// RegularLevel.createItems() — appended after existing item loop:

int playerCount = (Dungeon.heroes != null && !Dungeon.heroes.isEmpty())
        ? Dungeon.heroes.size()
        : 1;
for (int i = 0; i < playerCount - 1; i++) {
    int cell = randomDropCell();
    if (cell != -1) {
        drop(Generator.random(Generator.Category.FOOD), cell);
    }
}
```

## Logs

| Source | Location |
|--------|----------|
| In-game log | No `GLog` entries — food drops silently like all floor loot |

## Deployment

- Mechanism: `local only`
- Deploy command:
  ```bash
  ./gradlew desktop:debug
  ```
- Notes: Single-player (`heroes.size() == 1`) is unchanged — the loop runs 0 times. Boss levels and special levels do not subclass `RegularLevel` and do not call this method, so they are unaffected. Food already on the floor from talents (`CACHED_RATIONS`) or Monk mob drops (8.3% chance) is independent of this path and is not modified.
