# Plan: LAN/Hotspot Multiplayer (Lockstep Turn-Based)

## System Intent

Add LAN/hotspot multiplayer so 2–4 devices can play together without any server. On the home screen, "LAN Game" appears alongside the existing player-count selector. One device hosts a room; others join by entering the host IP. The room shows connected players in real time (max 4). Once all players join, each device goes through hero selection independently. The host starts the game when everyone has selected a hero. Both/all devices run the identical simulation using a shared RNG seed — only player actions are exchanged over the network each turn. Player count is determined dynamically by how many players actually join, not pre-configured by the host.

**Key design principle — local hero always in focus:** In LAN mode the camera, FOV, and UI are always pinned to the local hero. The remote hero's turns run silently in the background. `Dungeon.hero` stays fixed to the local hero for the entire session; a new `Dungeon.localHero` reference is introduced to make this explicit and to gate camera/UI updates.

## Architecture

```mermaid
sequenceDiagram
    participant H as Host Device
    participant N as TCP Socket (LAN)
    participant C1 as Client 1
    participant C2 as Client 2..4

    H->>H: Tap "LAN Game" → "Host Room"
    H->>H: Open ServerSocket on port 7777
    H->>H: Show LAN Lobby (IP address, waiting for players)
    C1->>N: Connect(hostIP:7777)
    H->>C1: PLAYER_JOINED { playerIndex:1, currentPlayers:2 }
    H->>H: Lobby updates — shows 2 players connected
    C2->>N: Connect(hostIP:7777)
    H->>C2: PLAYER_JOINED { playerIndex:2, currentPlayers:3 }
    Note over H,C1,C2: Max 4 players; host taps "Start" when ready

    H->>N: START { playerCount, seed }
    C1->>C1: Hero Select (slot 1) — single-hero select, playerCount=1
    C2->>C2: Hero Select (slot 2) — single-hero select, playerCount=1
    H->>H: Hero Select (slot 0) — single-hero select, playerCount=1
    C1->>N: HERO_READY { heroClass }
    C2->>N: HERO_READY { heroClass }
    H->>H: Wait for all HERO_READY
    H->>N: HANDSHAKE { seed, heroClasses[], playerCount }
    Note over H,C1,C2: All initialize Dungeon with same seed

    loop Each Turn
        H->>N: ACTION { heroId:0, ... }
        C1->>N: ACTION { heroId:1, ... }
        C2->>N: ACTION { heroId:2, ... }
        Note over H,C1,C2: All receive all actions, simulate identically
        alt Every 10 turns
            H->>N: STATE_HASH { turn, hash }
            Note over H,C1,C2: If hashes differ → desync recovery
        end
    end

    H->>H: Save game (host only)
```

## Flows

### Flow 1: `networkLayerInit`
**What:** Create a `NetworkManager` singleton that wraps Java TCP sockets (no new dependencies — java.net is available on Android + desktop via LibGDX).

**Entry:** App starts  
**Exit:** `NetworkManager` is initialized and ready to host or join

**Steps:**
1. Create `NetworkManager` class in `core/src/main/java/.../network/NetworkManager.java`
2. Fields: `ServerSocket serverSocket`, `Socket peerSocket`, `DataInputStream in`, `DataOutputStream out`, `boolean isHost`, `int localPlayerIndex`
3. Add a `static boolean lanMode = false` flag (set to `true` when a LAN session starts; used throughout the codebase to gate LAN-specific behaviour)
4. `hostGame(int port)` — opens `ServerSocket`, blocks until one client connects, sets `isHost = true`
5. `joinGame(String ip, int port)` — opens `Socket` to host, sets `isHost = false`
6. `sendAction(HeroAction action, int heroId)` — serialize and write to stream
7. `receiveActionAsync(Callback onReceived)` — non-blocking: starts a background reader thread that calls the callback when a packet arrives, then calls `Actor.process()` notify to wake the actor thread (mirrors the touch-input wake pattern)
8. `sendHash(long hash, int turn)` / `receiveHash()` — for desync detection
9. `disconnect()` — close all streams and sockets cleanly; use `Socket.setSoTimeout()` on all reads so `SocketTimeoutException` signals disconnection cleanly rather than blocking indefinitely

**Data format (simple binary protocol):**
```
ACTION packet:        [byte type=1] [int heroId] [byte actionType] [int targetPos]
HASH packet:          [byte type=2] [int turn]   [long hash]
HANDSHAKE:            [byte type=3] [long seed]  [int playerCount] [int[] heroClassOrdinals]
PLAYER_JOINED:        [byte type=4] [int playerIndex] [int currentPlayers]
START:                [byte type=5] [int playerCount] [long seed]
HERO_READY:           [byte type=6] [byte heroClassOrdinal]
SAVE_LOBBY_INFO:      [byte type=7] [int playerCount] [String[] heroNames] [byte[] heroClasses] [int[] heroHP]
RESUME_START:         [byte type=8] [int playerCount]
HERO_CLAIM:           [byte type=9] [int heroIndex]
RESUME_HANDSHAKE:     [byte type=10] [byte[] dungeonBundle] [int[] heroAssignments]
```

**Note on `SAVE_LOBBY_INFO`:** The per-hero HP values must be read directly from `Dungeon.heroes` at transmission time — `GamesInProgress.Info` only has a single `int hp` field for `Dungeon.hero` alone and cannot supply per-hero HP.

---

### Flow 2: `hostGameSetup`
**What:** Host opens a room from the title screen. The LAN Lobby screen shows connected players in real time (max 4). Host taps "Start" when ready — player count is however many joined.

**Entry:** User taps "LAN Game" → "Host Room" on title screen  
**Exit:** All connected players have selected heroes; host fires START; all devices enter dungeon

**Steps:**
1. Add "LAN Game" button to `TitleScene` alongside the existing player-count / New Game flow
2. Tapping "LAN Game" shows `WndLANMenu` with two options: "Host Room" / "Join Room"
3. "Host Room" → `NetworkManager.hostGame(7777)`, then open `LanLobbyScene`:
   - Shows device IP address prominently (`InetAddress.getLocalHost()` / `NetworkInterface` scan)
   - Shows a list of connected player slots (slot 0 = host, slots 1–3 = clients as they join), capped at 4
   - Shows "Waiting for players…" label and a "Start Game" button (enabled when ≥2 players connected)
4. Each time a new client connects: assign next available `playerIndex`, send them `PLAYER_JOINED { playerIndex, currentPlayers }`, broadcast updated player list to all connected clients, update `LanLobbyScene` list
5. Host taps "Start Game": generate `dungeonSeed`, broadcast `START { playerCount, seed }` to all clients
6. All devices (host + clients) transition to `HeroSelectScene` for their own slot only — each device runs a single-hero select (`GamesInProgress.playerCount = 1`, `GamesInProgress.currentPlayerSelecting = 0`). The pass-and-play multi-hero loop does not run in LAN mode.
7. Host waits to receive `HERO_READY { heroClass }` from each client slot; clients wait for `HANDSHAKE`
8. Once all `HERO_READY` packets received: host broadcasts final `HANDSHAKE { seed, heroClasses[], playerCount }` and all devices initialize the dungeon (Flow 4)

---

### Flow 3: `joinGameSetup`
**What:** Client taps "Join Room" from the LAN menu, enters host IP, lands in a waiting lobby until the host starts. Then goes through hero select.

**Entry:** User taps "LAN Game" → "Join Room" on title screen  
**Exit:** Hero selected and `HERO_READY` sent; waiting for host `HANDSHAKE`

**Steps:**
1. "Join Room" in `WndLANMenu` → show `WndJoinGame` dialog: IP address text field (manual entry; hint text shows example subnet)
2. `NetworkManager.joinGame(enteredIP, 7777)` — connect to host
3. Receive `PLAYER_JOINED { playerIndex, currentPlayers }` — store `localPlayerIndex`
4. Show `LanLobbyScene` (client view): displays player slots as they fill in, "Waiting for host to start…" label (no Start button)
5. Lobby updates as host sends further `PLAYER_JOINED` broadcasts (other clients joining)
6. On receive `START { playerCount, seed }`: store seed + player count, transition to `HeroSelectScene` for own slot only (single-hero select, same as host)
7. Hero select completes → send `HERO_READY { heroClass }` to host
8. Wait for `HANDSHAKE { seed, heroClasses[], playerCount }` from host → proceed to dungeon init (Flow 4)

---

### Flow 4: `dungeonInit`
**What:** Both devices initialize the dungeon with the identical seed so their simulations start in the same state.

**Entry:** Both players ready (hero selected)  
**Exit:** Both devices show the dungeon, turn 0

**Steps:**
1. Set `Dungeon.seed` to the received seed value on both devices
2. Populate `GamesInProgress.selectedClasses` with the full hero class list from the handshake on both devices — this must happen **before** `InterlevelScene` transitions, as `Dungeon.init()` reads `selectedClasses` to spawn all heroes
3. Call `Dungeon.init()` — this calls `Generator.fullReset()` then `Random.pushGenerator(seed + 1)` (note: the generator uses `seed + 1`, not `seed` directly — both devices must use this same offset)
4. Set `Dungeon.localHero = Dungeon.heroes.get(NetworkManager.localPlayerIndex)` immediately after heroes are spawned
5. Set `Dungeon.hero = Dungeon.localHero` — pin the singleton to the local hero for the session
6. Both render the dungeon; each device shows only its own local hero's FOV

**RNG note:** `Random.java` uses `java.util.Random` seeded via `pushGenerator(long seed)` with deterministic MX3 scramble — identical seed produces identical sequence on both devices.

---

### Flow 5: `turnSync` (core loop)
**What:** Each turn, both devices submit their hero's action, receive the peer's action, then both simulate the full turn identically.

**Entry:** Actor queue reaches a hero's turn  
**Exit:** Turn resolved, both devices show same state

**Steps:**
1. In `Hero.act()`, after all existing guards (isAlive, WaitingToFall, activate, heroFOV, observe, checkVisibleMobs, paralysed, FollowHeroBuff), check `NetworkManager.lanMode`:
   - If **local hero** (`this == Dungeon.localHero`): wait for player input as normal (existing `ready()` / `curAction` pattern). When input arrives, call `NetworkManager.sendAction(curAction, heroId)` before dispatching.
   - If **remote hero**: `act()` returns `false` (same as a hero waiting for input). A background network-reader thread calls `NetworkManager.receiveActionAsync()`, sets `curAction` on the remote hero, then calls `notify()` on the actor thread to wake it — mirroring exactly how touch input wakes the actor thread for local heroes.
2. Both heroes now have their `curAction` set — simulation proceeds identically on both devices
3. Mob AI, item effects, level transitions all run from the same deterministic RNG → same result

**`activate()` gate:** `Hero.activate()` currently reassigns `Dungeon.hero`, pans the camera, and refreshes the UI. In LAN mode this must be gated: only update camera/UI if `this == Dungeon.localHero`. Remote hero turns must not hijack the local player's view.

**`Level.heroFOV` gate:** `heroFOV` is a single shared `boolean[]`. When `Dungeon.observe()` runs during a remote hero's turn it must not overwrite the local hero's visibility. Solution: `Dungeon.observe()` (and `updateFieldOfView`) should only update `heroFOV` when `this == Dungeon.localHero`; remote hero FOV updates write to a separate throwaway array.

**`FollowHeroBuff` in LAN:** Works identically to pass-and-play. The buff generates a `HeroAction.Move` locally during the local hero's turn; that action is transmitted as a normal move packet. The remote device receives a move action and has no knowledge of the buff. No special handling needed.

**Turn ordering:** Heroes act in actor queue order (already handled by priority system). Remote hero's `act()` returns `false` and sleeps the actor thread until the network packet arrives — identical to how local hero waits for touch input.

---

### Flow 6: `desyncDetection`
**What:** Every 10 turns, exchange a hash of key game state. If hashes differ, pause and resync.

**Entry:** `(int)Actor.now() % 10 == 0` after turn resolution  
**Exit:** Hashes match (continue) or mismatch (trigger recovery)

**Hash inputs** (fast, deterministic):
```java
long hash = Dungeon.seed
    ^ (long)Dungeon.hero.HP << 32   // Dungeon.hero is pinned to local hero — stable
    ^ (long)(int)Actor.now()         // cast to int before use; Actor.now() is a float
    ^ Dungeon.level.feeling.ordinal()
    ^ Arrays.hashCode(mobPositions());  // sorted mob cell array
```

**Note:** `Dungeon.hero` is pinned to the local hero for the session, so `Dungeon.hero.HP` consistently reflects the local hero's HP on each device — no aggregation needed.

**Note:** `Actor.now()` returns a `float` — use `(int)Actor.now() % 10 == 0` for the trigger condition, not `Actor.now() % 10 == 0`.

**Steps:**
1. After turn resolution, if `(int)Actor.now() % 10 == 0`: compute hash, send `HASH` packet
2. Receive peer's hash (with timeout)
3. If match: continue
4. If mismatch: show "Resyncing..." overlay, host serializes full `Dungeon` bundle, sends to client
5. Client deserializes and replaces its state — **host state is authoritative**
6. Resume from current turn

---

### Flow 7: `hostSaveAndLoad`
**What:** Only the host saves the game. Clients have no local save. Continuing a saved LAN game re-uses the same lobby flow — host opens a room, clients join, then each player picks which saved hero they want to control.

**Entry (save):** Session ends (quit, death, floor transition)  
**Entry (load):** User taps a multiplayer save slot on the title screen  
**Exit (save):** Save written on host, skipped on clients  
**Exit (load):** All players have claimed a hero; game resumes from saved state

#### Saving
1. On `GameScene.pause()` / quit: if `isHost`, call existing `Dungeon.saveAll()` as normal — the existing save format already serializes all heroes under the `"heroes"` key in the bundle
2. If client: skip save entirely — no local save file is written
3. Add `GamesInProgress.isMultiplayerSave = true` flag so the title screen can distinguish LAN saves from single-player saves; persist it in `GamesInProgress.Info`

#### Continuing a saved LAN game
4. Saved LAN slots appear in the existing save-slot list with a "LAN" badge; tapping one shows `WndLANMenu` ("Host Room" / "Join Room") instead of launching directly
5. **Host path:**
   a. Load save via `Dungeon.loadGame()` — full state is in memory on host only (existing multi-hero restore already handles the `"heroes"` collection)
   b. Open `ServerSocket` on port 7777; enter `LanLobbyScene` (same UI as new game — shows IP, player slots, Start button)
   c. Each client that connects receives `SAVE_LOBBY_INFO { playerCount, heroNames[], heroClasses[], heroHP[] }` — HP values read directly from `Dungeon.heroes` at transmission time (not from `GamesInProgress.Info`, which only has a single `int hp` for `Dungeon.hero`)
   d. Host taps "Start" once enough players are connected (≥2, ≤ saved hero count)
   e. Broadcast `RESUME_START { playerCount }` — clients transition to hero-claim screen
   f. Collect `HERO_CLAIM { heroIndex }` from each client; host claims whichever index is left (or picks first)
   g. Serialize the full dungeon bundle to bytes — requires a new `public static byte[] FileUtils.bundleToBytes(Bundle bundle)` wrapper, since the existing `bundleToStream` is `private static` and not accessible outside the class
   h. Broadcast `RESUME_HANDSHAKE { fullDungeonBundle, heroAssignments[] }` — clients deserialize and replace local state
   i. Set `Dungeon.localHero = Dungeon.heroes.get(assignedIndex)` on each device; pin `Dungeon.hero = Dungeon.localHero`
   j. All devices show the dungeon from the saved position; each controls their claimed hero

6. **Client path:**
   a. Show `WndJoinGame` IP entry dialog; connect to host
   b. Receive `SAVE_LOBBY_INFO` — enter `LanLobbyScene` (waiting view, same as new game)
   c. On `RESUME_START`: transition to `WndHeroClaim` — shows each saved hero (name, class, HP, depth) as a selectable card; client picks one
   d. Send `HERO_CLAIM { heroIndex }` to host; wait for `RESUME_HANDSHAKE`
   e. Deserialize received dungeon bundle; set `Dungeon.localHero` and pin `Dungeon.hero`; game starts at saved position controlling claimed hero

**Conflict rule:** First `HERO_CLAIM` received by host wins that index; duplicates are rejected and client is prompted to pick another. Host always gets last pick implicitly after all clients have claimed.

---

### Flow 8: `disconnectHandling`
**What:** Handle one player disconnecting mid-game gracefully.

**Entry:** Socket read/write throws `IOException` or `SocketTimeoutException`  
**Exit:** Game pauses and offers options

**Steps:**
1. All `NetworkManager` reads use `Socket.setSoTimeout()` — `SocketTimeoutException` is treated as a disconnect signal rather than blocking the actor thread indefinitely
2. On disconnect: fire `Signal.PEER_DISCONNECTED`
3. `GameScene` listens for signal: pause actor thread, show `WndPeerDisconnected` dialog
4. Options: "Wait for reconnect" (keep socket open, poll) or "Continue solo" (remove remote hero from actor queue and `Dungeon.heroes`, revert to single-player turn loop) or "Quit to title"
5. "Wait for reconnect": host re-opens `ServerSocket`, client re-enters join flow, resync via Flow 6

**Note on "Continue solo":** Removing a live hero mid-game has no existing code path — `Hero.die()` handles dead-hero removal but not live disconnected hero removal. A new `Hero.removeFromGame()` method is needed that removes the hero from `Actor.all`, `Dungeon.heroes`, and cleans up sprites without triggering the death flow.

---

## Files to Create / Modify

| File | Change |
|------|--------|
| `core/.../network/NetworkManager.java` | New — TCP socket wrapper, `lanMode` flag, async receive pattern |
| `core/.../network/ActionPacket.java` | New — serializable action DTO |
| `core/.../scenes/TitleScene.java` | Add "LAN Game" button alongside player-count selector |
| `core/.../scenes/LanLobbyScene.java` | New — lobby screen (host: IP + player list + Start button; client: waiting view) |
| `core/.../windows/WndLANMenu.java` | New — "Host Room" / "Join Room" choice dialog |
| `core/.../windows/WndJoinGame.java` | New — IP address entry dialog |
| `core/.../windows/WndHeroClaim.java` | New — hero-picker for resuming a saved LAN game (shows saved hero cards) |
| `core/.../windows/WndPeerDisconnected.java` | New — disconnect options dialog |
| `core/.../actors/hero/Hero.java` | `act()` — remote action path; `activate()` — gate camera/UI to local hero only |
| `core/.../levels/Level.java` | `updateFieldOfView` — skip heroFOV update for remote heroes |
| `core/.../Dungeon.java` | Add `localHero` field; pin `Dungeon.hero = localHero` on init; accept external seed |
| `core/.../GamesInProgress.java` | Add `isMultiplayerSave` flag to `Info` |
| `SPD-classes/.../utils/FileUtils.java` | Add `public static byte[] bundleToBytes(Bundle bundle)` wrapper |
| `android/AndroidManifest.xml` | Add `INTERNET` + `ACCESS_WIFI_STATE` permissions |

## Dependencies

No new Gradle dependencies required. Uses:
- `java.net.ServerSocket` / `Socket` — available on Android API 21+ and desktop
- `java.io.DataInputStream` / `DataOutputStream` — for binary protocol
- Existing LibGDX `gdx-net` is optional; raw Java sockets are simpler and sufficient for LAN

## Stage Gate Checklist

- [ ] Draft plan reviewed
- [ ] Mermaid diagram approved  
- [ ] Flow 1 (NetworkManager) approved
- [ ] Flow 2 (host lobby + room setup) approved
- [ ] Flow 3 (join lobby + hero select) approved
- [ ] Flow 4 (dungeon init) approved
- [ ] Flow 5 (turn sync) approved
- [ ] Flow 6 (desync detection) approved
- [ ] Flow 7 (save/load + continue saved LAN game) approved
- [ ] Flow 8 (disconnect) approved
- [ ] Implementation complete
