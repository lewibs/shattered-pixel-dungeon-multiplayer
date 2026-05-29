# Code Review Issues

## Critical Issues Found

### Issue 1: Thread Safety - Dungeon.hero Requires Synchronization

**Severity**: HIGH  
**Location**: `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/Dungeon.java:185`  
**Current Code**:
```java
public static Hero hero;
```

**Problem**:
- Hero.act() sets `Dungeon.hero = this` on the actor thread
- UI, camera, input, and render components read `Dungeon.hero` on the render/main thread
- No synchronization or volatile keyword, creating a data race
- In multi-hero scenarios, the swap happens frequently: each hero's turn swaps the singleton
- Possible issues: stale reads, torn reads, inconsistent state between camera and UI

**Impact**: 
- Camera might follow wrong hero briefly
- UI might display data from previous hero
- Input might be routed to wrong hero
- In worst case, causes flickering or crashes

**Recommendation**: 
Add `volatile` keyword:
```java
public static volatile Hero hero;
```
This ensures visibility across threads without heavy synchronization overhead.

---

### Issue 2: Null Safety - Dungeon.heroes Can Be Null in Actor.init()

**Severity**: MEDIUM  
**Location**: `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/Actor.java:196`  
**Current Code**:
```java
public static void init() {
    int n = Dungeon.heroes.size();  // NPE if heroes is null
    for (int i = 0; i < n; i++) {
        Hero h = Dungeon.heroes.get(i);
        ...
    }
}
```

**Problem**:
- Actor.init() assumes Dungeon.heroes is initialized
- If called before Dungeon.init() or after Dungeon.hero = null (cleanup), heroes could be null
- Results in NullPointerException

**Current Safety**: 
- Call order: Dungeon.init() → newLevel() → Actor.init()
- Also loadGame() → loadLevel() → Actor.init()
- This relies on discipline; no defensive programming

**Recommendation**:
Add defensive check:
```java
public static void init() {
    if (Dungeon.heroes == null || Dungeon.heroes.isEmpty()) {
        throw new IllegalStateException("Dungeon.heroes not initialized");
    }
    int n = Dungeon.heroes.size();
    ...
}
```

---

### Issue 3: Corrupted Save Handling - Null Hero in List

**Severity**: MEDIUM  
**Location**: `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/Dungeon.java:816-828`  
**Current Code**:
```java
hero = null;
hero = (Hero)bundle.get( HERO );
if (bundle.contains("heroes")) {
    heroes = new ArrayList<>();
    for (com.watabou.utils.Bundlable b : bundle.getCollection("heroes")) {
        heroes.add((Hero) b);  // Could be null for corrupted saves
    }
} else {
    heroes = new ArrayList<>();
    heroes.add(hero);  // Could add null if HERO was null in bundle
}
hero = heroes.get(0);  // Sets hero to null if first element is null
```

**Problem**:
- If old save file is missing HERO key and "heroes" key, hero becomes null
- Then heroes = [null]
- This causes NullPointerException anywhere Dungeon.hero is used
- No defensive checks in the deserialization path

**Recommendation**:
```java
hero = (Hero)bundle.get( HERO );
if (bundle.contains("heroes")) {
    heroes = new ArrayList<>();
    for (com.watabou.utils.Bundlable b : bundle.getCollection("heroes")) {
        Hero h = (Hero) b;
        if (h != null) {
            heroes.add(h);
        }
    }
    if (heroes.isEmpty()) {
        throw new RuntimeException("Save file corrupted: no valid heroes found");
    }
} else {
    if (hero == null) {
        throw new RuntimeException("Save file corrupted: no hero found");
    }
    heroes = new ArrayList<>();
    heroes.add(hero);
}
hero = heroes.get(0);
```

---

### Issue 4: Unchecked Cast in Bundle Deserialization

**Severity**: LOW  
**Location**: `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/Dungeon.java:820-821`  
**Current Code**:
```java
for (com.watabou.utils.Bundlable b : bundle.getCollection("heroes")) {
    heroes.add((Hero) b);  // Unchecked cast - ClassCastException possible
}
```

**Problem**:
- Bundle.getCollection() returns Bundlable objects
- Unchecked cast to Hero will fail if bundle is corrupted
- Results in ClassCastException instead of graceful error

**Recommendation**:
```java
for (com.watabou.utils.Bundlable b : bundle.getCollection("heroes")) {
    if (b instanceof Hero) {
        heroes.add((Hero) b);
    } else {
        throw new RuntimeException("Invalid hero in save file: " + b.getClass());
    }
}
```

---

### Issue 5: Save File Compatibility - Verify Round-Trip

**Severity**: LOW  
**Location**: `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/Dungeon.java:640-641`  
**Current Code**:
```java
bundle.put( HERO, hero );
bundle.put( "heroes", heroes ); // serialize all heroes; HERO kept for Hero.preview() compat
```

**Concern**:
- Both HERO and "heroes" keys are saved
- HERO points to heroes[0] initially
- But if hero is set to a different instance later in the game (not via act()), the keys become inconsistent
- This could cause issues if Hero.preview() reads HERO while other code reads "heroes"

**Recommendation**:
- Ensure HERO is always kept in sync with heroes[0], OR
- Add a comment documenting that HERO might diverge from heroes[0] and is for Hero.preview() only

---

## Summary of Findings

| Issue | Severity | Type | Status |
|-------|----------|------|--------|
| Thread Safety: Dungeon.hero | HIGH | RACE CONDITION | REQUIRES FIX |
| Null Safety: Dungeon.heroes | MEDIUM | NPE RISK | SHOULD FIX |
| Corrupted Save Handling | MEDIUM | CRASH RISK | SHOULD FIX |
| Unchecked Cast | LOW | EXCEPTION RISK | NICE TO FIX |
| Save Consistency | LOW | DATA CONSISTENCY | REVIEW NEEDED |


---

### Issue 6: Logic Error - surprisedBy() Hardcoded to Dungeon.hero

**Severity**: MEDIUM  
**Location**: `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Mob.java:1432-1437`  
**Current Code**:
```java
public boolean surprisedBy( Char enemy, boolean attacking ){
    return enemy == Dungeon.hero
            && (enemy.invisible > 0 || !enemySeen || (fieldOfView != null && fieldOfView.length == Dungeon.level.length() && !fieldOfView[enemy.pos]))
            && (!attacking || enemy.canSurpriseAttack());
}
```

**Problem**:
- This method checks if a mob is surprised by the enemy
- It ONLY returns true if enemy == Dungeon.hero
- In multi-hero scenario, if a mob is attacked by a non-active hero (heroes[1]), this will always return false
- This breaks surprise attack mechanics for non-active heroes

**Example**:
1. Hero 0 and Hero 1 are in the game
2. Actor loop runs Hero 0's turn (Dungeon.hero = heroes[0])
3. In next turn, Actor loop runs Hero 1's turn (Dungeon.hero = heroes[1])
4. If Hero 1 surprises a mob, the mob's surprisedBy(Hero 1) will return false because enemy != Dungeon.hero (which now points to Hero 1... wait, actually this works)

**RE-EVALUATION**:
Actually, on re-evaluation, this IS correct because:
- When Hero 1 acts, Dungeon.hero is set to heroes[1] FIRST
- So by the time any code checks surprisedBy(Hero 1, ...), Dungeon.hero IS Hero 1
- This is the whole point of the singleton swap pattern

**STATUS**: NOT AN ISSUE - The singleton swap makes this correct


---

### Issue 7: Amok Logic - Only Resets for Active Hero

**Severity**: LOW  
**Location**: `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/actors/mobs/Mob.java:314`  
**Current Code**:
```java
} else if (buff( Amok.class ) != null && enemy == Dungeon.hero) {
    newEnemy = true;  // Force new target if amoked and enemy is the active hero
```

**Analysis**:
- When a mob is amoked, it seeks a new enemy if its current target was the "hero"
- In multi-hero scenario, if the mob's previous target was Hero 0, but Hero 1 is now active, the check `enemy == Dungeon.hero` returns false
- The mob does NOT reset its target to Hero 1
- This is actually correct behavior: a mob should continue attacking its original target even if they're not the "active" hero

**Verdict**: NOT AN ISSUE - Correct implementation

