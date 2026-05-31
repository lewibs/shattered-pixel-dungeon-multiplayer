# NetworkManager

## Metadata

- System type: `service`

## System Intent

- What this is: `NetworkManager` is the static singleton that owns all TCP socket state for LAN multiplayer. It handles host/client connection setup, packet transmission (actions, hashes, hero-selection coordination), lobby management, and session teardown. All public fields and methods are static; there is no instance.
- Key responsibilities:
  - Opening a `ServerSocket` (host) or `Socket` (client) and managing `DataInputStream`/`DataOutputStream` per peer
  - Broadcasting and receiving typed packets: `ACTION`, `STATE_HASH`, `HANDSHAKE`, `HERO_READY`, `CLASS_CLAIMED`, `CLASS_UNCLAIMED`, `PLAYER_JOINED`, `PLAYER_LEFT`, `NAME_ANNOUNCE`, `NAME_REJECTED`, `KICK`, `HOST_DISCONNECTED`, and resume-mode variants
  - Tracking `localPlayerIndex` (which hero slot this device owns: 0 = host, 1..N = client)
  - Per-stream reader threads (EC-6.7): each remote hero gets its own `net-reader-action` thread reading from its own `DataInputStream` (`ins.get(heroIndex-1)`), controlled by `actionReaderRunningPerStream[]`
  - Per-stream write locks (EC-6.1): `outLocks` list prevents concurrent multi-byte writes from interleaving packets
  - Protocol version negotiation (EC-6.5): `PROTOCOL_VERSION` field in `HANDSHAKE` packet; mismatches throw `IOException`
  - Lobby player management (EC-1.1, EC-1.2, EC-1.3, EC-1.4, EC-6.10): `removeClient()`, `kickPlayer()`, `broadcastHostDisconnected()`
  - Desync detection (EC-6.4): host sends `STATE_HASH` every 10 turns; `DesyncDetectedSignal` fires on mismatch
  - Cleaning up all socket state and resetting all session fields via `cleanup()`

## Mermaid Diagram

```mermaid
flowchart TD
    hostGame["hostGame(port)\nServerSocket opened\nlanMode=true, isHost=true\nlocalPlayerIndex=0\naccept loop started"] --> AcceptLoop["acceptClientsLoop()\nfor each connecting client:\n  connectedPlayerCount++\n  read NAME_ANNOUNCE\n  send PLAYER_JOINED to all\n  fire onPlayerJoined callback"]

    joinGame["joinGame(ip, port)\nSocket opened\nlanMode=true, isHost=false\nsend NAME_ANNOUNCE immediately"] --> ClientRecv["LanLobbyScene reads PLAYER_JOINED\nsets NetworkManager.localPlayerIndex = playerIndex\n(first packet whose name matches own playerName)"]

    AcceptLoop --> GamePlay["In-game\nsendAction / receiveActionAsync\nsendHash / receiveHash\nsendItemIdentified"]
    ClientRecv --> GamePlay

    GamePlay --> SingleReader["net-reader-action thread\n(singleton — actionReaderRunning guard)\nreads ALL incoming packets:\n  ACTION → curAction\n  HASH → hashQueue\n  RESUME_HANDSHAKE → resyncQueue\n  ITEM_IDENTIFIED → render thread\n  CLASS_CLAIMED/UNCLAIMED → callbacks"]
    SingleReader --> HashQueue["hashQueue\nLinkedBlockingQueue<HashPacket>\nreceiveHash() drains with timeout"]
    SingleReader --> ResyncQueue["resyncQueue\nLinkedBlockingQueue<byte[]>\nreceiveResyncBundle() drains with timeout"]

    GamePlay --> Disconnect["disconnect()\nlanMode=false\ncleanup()"]
    Disconnect --> Cleanup["cleanup()\nclose all sockets\nclear peerSockets / ins / outs\nhashQueue.clear()\nresyncQueue.clear()\nreset: isHost=false\n connectedPlayerCount=0\n gameStarted=false\n localPlayerIndex=0\n playerNames=[4]null\n onPlayerJoined=null"]
```

## Flows

### Flow: `hostGame`
- Core files: `core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/network/NetworkManager.java`

#### Types

```txt
// Static fields set by hostGame()
lanMode: boolean       -- true
isHost: boolean        -- true
localPlayerIndex: int  -- 0 (host always owns slot 0)
connectedPlayerCount: int -- starts at 1 (host counts as player 0)
playerNames[0]: String -- set to NetworkManager.playerName
```

#### Paths

| path | input | output | path-type | notes |
| --- | --- | --- | --- | --- |
| `hostGame.success` | `port` | ServerSocket opened; accept loop started; `lanMode=true` | happy path | |
| `hostGame.ioError` | `port` (unavailable) | `IOException` thrown; `lanMode=false` | error | |

---

### Flow: `joinGame`
- Core files: `NetworkManager.java`

#### Types

```txt
// Static fields set by joinGame()
lanMode: boolean       -- true
isHost: boolean        -- false
localPlayerIndex: int  -- NOT set here; set later by LanLobbyScene when PLAYER_JOINED received
```

#### Paths

| path | input | output | path-type | notes |
| --- | --- | --- | --- | --- |
| `joinGame.success` | `ip`, `port` | Socket opened; `NAME_ANNOUNCE` sent; `lanMode=true` | happy path | `localPlayerIndex` remains 0 until `LanLobbyScene.startClientListenerThread()` receives `PLAYER_JOINED` and sets it |
| `joinGame.ioError` | unreachable host | `IOException` thrown; `lanMode=false`; `cleanup()` called | error | |

---

### Flow: `cleanup`
- Core files: `NetworkManager.java`

#### Pseudocode

```
cleanup():
  close serverSocket (ignore IOException)
  for each s in peerSockets: close s (ignore IOException)
  close clientSocket (ignore IOException)
  peerSockets.clear()
  ins.clear()
  outs.clear()
  serverSocket = null
  clientSocket = null
  clientIn = null
  clientOut = null
  isHost = false
  connectedPlayerCount = 0
  gameStarted = false
  onPlayerJoined = null
  playerNames = new String[4]
  localPlayerIndex = 0        // CRITICAL — prevents stale index carrying into next session
  hashQueue.clear()           // drain pending hash packets so next session starts clean
  resyncQueue.clear()         // drain pending resync bundles so next session starts clean
  // actionReaderRunning resets to false via the thread's finally block when the thread exits
```

#### Paths

| path | input | output | path-type | notes |
| --- | --- | --- | --- | --- |
| `cleanup.normal` | called from `disconnect()` | all sockets closed; all session fields reset to defaults; `hashQueue` and `resyncQueue` cleared | happy path | Called automatically after any session end |
| `cleanup.staleIndex` | `localPlayerIndex` was 1 (client of prior LAN session) after cleanup | `localPlayerIndex` reset to 0 | guard | Without this reset, a subsequent solo game causes `GameScene.create()` to call `Dungeon.heroes.get(1)` on a 1-hero roster → `IndexOutOfBoundsException`. `GameScene.java:346` also clamps with `Math.min(localPlayerIndex, heroes.size()-1)` as a secondary guard. |
| `cleanup.queueDrain` | `hashQueue` or `resyncQueue` has stale entries from a prior session | both queues cleared; `receiveHash`/`receiveResyncBundle` in new session will not consume stale data | guard | Prevents phantom hash/resync responses leaking from one session into the next |

---

### Flow: `receiveActionAsync`
- Core files: `NetworkManager.java`

#### Types

```txt
actionReaderRunningPerStream: volatile boolean[]  -- per-stream guard, size MAX_PLAYERS; keyed by heroIndex-1
actionReaderRunning: volatile boolean             -- legacy global guard (also checked for backward compat)
streamIndex: int                                  -- heroIndex - 1 for host; always 0 for client

// EC-6.7: each remote hero has its own reader thread on its own stream
// Host: hero[1] → ins.get(0), hero[2] → ins.get(1), etc.
// Client: always reads from clientIn (single stream)
```

#### Paths

| path | input | output | path-type | notes |
| --- | --- | --- | --- | --- |
| `receiveActionAsync.guardReject` | called while `actionReaderRunningPerStream[streamIndex] == true` | immediate no-op return | guard | EC-6.7: per-stream guard; each remote hero can have its own active reader simultaneously |
| `receiveActionAsync.started` | per-stream guard false | `actionReaderRunningPerStream[streamIndex] = true`; `net-reader-action` thread starts | happy path | Thread reads from `ins.get(streamIndex)` (host) or `clientIn` (client) |
| `receiveActionAsync.action` | `ACTION` packet on stream | `remoteHero.curAction` set; `lanActionLock.notifyAll()` | happy path | |
| `receiveActionAsync.stateHash` | `STATE_HASH` packet on stream | local hash computed; if mismatch → `desyncDetectedSignal.dispatch()` | happy path | EC-6.4 desync detection |
| `receiveActionAsync.disconnect` | `IOException` on read | `peerDisconnectSignal` dispatched; `GameScene.notifyActorThread()`; thread exits; per-stream guard cleared | error | |

#### Pseudocode

```
receiveActionAsync(remoteHero):
  heroIndex = Dungeon.heroes.indexOf(remoteHero)
  streamIndex = isHost ? max(0, heroIndex - 1) : 0
  synchronized(NetworkManager.class):
    if actionReaderRunningPerStream[streamIndex]: return   // per-stream guard (EC-6.7)
    actionReaderRunningPerStream[streamIndex] = true
  start thread "net-reader-action":
    in = isHost ? ins.get(streamIndex) : clientIn
    loop while lanMode:
      type = in.readByte()
      if type == ACTION:
        heroId = in.readInt(); actionType = in.readByte(); targetPos = in.readInt()
        synchronized(remoteHero.lanActionLock):
          remoteHero.curAction = decodeAction(actionType, targetPos)
          remoteHero.lanActionLock.notifyAll()
      else if type == STATE_HASH:
        turn = in.readInt(); remoteHash = in.readLong()
        localHash = computeStateHash()
        if localHash != remoteHash: desyncDetectedSignal.dispatch(...)
      else if type == ITEM_IDENTIFIED: ...
      else if type == CLASS_CLAIMED / CLASS_UNCLAIMED: ...
      else if type == PING: // discard
      on IOException:
        peerDisconnectSignal.dispatch(remoteHero)
        GameScene.notifyActorThread(); break
    finally: actionReaderRunningPerStream[streamIndex] = false
```

---

### Flow: `receiveHash`
- Core files: `NetworkManager.java`

#### Types

```txt
hashQueue: LinkedBlockingQueue<HashPacket>  -- populated by net-reader-action thread
SOCKET_TIMEOUT_MS: 30000ms
```

#### Paths

| path | input | output | path-type | notes |
| --- | --- | --- | --- | --- |
| `receiveHash.success` | `hashQueue` has entry | `HashPacket` returned | happy path | Non-blocking from the caller's perspective; the queue blocks internally |
| `receiveHash.timeout` | queue empty for 30 s | `SocketTimeoutException` thrown; `peerDisconnectSignal` dispatched | error | Treated as peer disconnect |
| `receiveHash.interrupted` | thread interrupted | `IOException("receiveHash interrupted")` thrown | error | |

---

### Flow: `receiveResyncBundle`
- Core files: `NetworkManager.java`

#### Types

```txt
resyncQueue: LinkedBlockingQueue<byte[]>  -- populated by net-reader-action thread
SOCKET_TIMEOUT_MS: 30000ms
```

#### Paths

| path | input | output | path-type | notes |
| --- | --- | --- | --- | --- |
| `receiveResyncBundle.success` | `resyncQueue` has entry | `byte[]` bundle returned | happy path | |
| `receiveResyncBundle.timeout` | queue empty for 30 s | `SocketTimeoutException` thrown | error | |
| `receiveResyncBundle.interrupted` | thread interrupted | `IOException` thrown | error | |

---

### Flow: `heroSelectionCoordination`
- Core files: `NetworkManager.java`, `HeroSelectScene.java`

#### Types

```txt
PacketType.HERO_READY    = 6  -- client → host: "I chose this class"
PacketType.HANDSHAKE     = 3  -- host → all clients: seed + ordered class array
PacketType.CLASS_CLAIMED = 11 -- any device → all peers: "I am hovering this class"
PacketType.CLASS_UNCLAIMED = 13 -- any device → all peers: "I abandoned this class"
```

#### Paths

| path | input | output | path-type | notes |
| --- | --- | --- | --- | --- |
| `heroSel.clientReady` | `sendHeroReady(heroClass)` called on client | `HERO_READY` packet sent to host with class ordinal | happy path | Host does NOT broadcast HERO_READY to clients — doing so would corrupt the `waitForHandshake` stream reader |
| `heroSel.hostCollect` | `waitForAllHeroReady(playerCount)` | Background threads (one per client) read `HERO_READY`; when all received `heroReadyReceived=true`; `heroReadyCount` incremented atomically | happy path | Host pre-counts itself: `heroReadyCount` starts at 1 |
| `heroSel.handshake` | `sendHandshake(seed, classes)` | `HANDSHAKE` packet broadcast to all clients with seed + class ordinal array | happy path | |
| `heroSel.clientWait` | `waitForHandshake()` | Background thread reads `HANDSHAKE`; stores `HandshakePayload`; sets `handshakeReceived=true` | happy path | Thread also handles interleaved `CLASS_CLAIMED`/`CLASS_UNCLAIMED` packets before HANDSHAKE arrives |
| `heroSel.classClaimed` | `sendClassClaimed(playerIndex, heroClass)` | `CLASS_CLAIMED` packet sent to all peers | happy path | Host relays to other clients via `waitForAllHeroReady` reader threads |

---

### Flow: `lobbyPlayerManagement`
- Core files: `NetworkManager.java`, `LanLobbyScene.java`

#### Overview

Implements EC-1.1, EC-1.2, EC-1.3, EC-1.4, EC-6.10: correct lobby slot management when clients drop, are rejected, or are kicked, and when the host leaves.

#### New Packet Types

| Packet | Value | Direction | Purpose |
|--------|-------|-----------|---------|
| `PLAYER_LEFT` | 17 | Host → Clients | A player was removed from the lobby |
| `NAME_REJECTED` | 18 | Host → Client | Duplicate name; client must pick another |
| `KICK` | 19 | Host → Client | Client kicked by host |
| `HOST_DISCONNECTED` | 20 | Host → Clients | Host leaving the lobby |

#### Key Methods

- `removeClient(int index)` — synchronized; closes socket, compacts `peerSockets`/`ins`/`outs`/`outLocks`/`playerNames`, decrements `connectedPlayerCount`, broadcasts `PLAYER_LEFT`
- `kickPlayer(int playerSlot)` — writes `KICK` to target client then calls `removeClient()`
- `broadcastHostDisconnected()` — writes `HOST_DISCONNECTED` to all connected clients; called from `LanLobbyScene.destroy()` when `!gameStarted && isHost`

#### Duplicate Name Rejection (EC-1.2 / EC-5.3)

In `acceptClientsLoop()`, after reading `NAME_ANNOUNCE`, if the announced name matches any `playerNames[0..connectedPlayerCount-2]` (case-insensitive), the host sends `NAME_REJECTED` and closes the socket without adding the client to the lists.

---

### Flow: `protocolHardening`
- Core files: `NetworkManager.java`

#### Overview

Implements EC-6.1 (write locks), EC-6.3 (handshake validation), EC-6.4 (desync detection), EC-6.5 (version field), EC-6.8 (accessor methods).

#### Key Changes

- **EC-6.1**: `outLocks` list (one `Object` per client in `outs`); `clientOutLock` for client mode. Every multi-byte write is wrapped in `synchronized(lock)`.
- **EC-6.3**: In `waitForHandshake()`, `playerCount` is validated `>= 1 && <= MAX_PLAYERS`; each `classOrdinal` is validated `< HeroClass.values().length`.
- **EC-6.5**: `PROTOCOL_VERSION = 1` written before `seed` in `sendHandshake()`; read and compared in `waitForHandshake()` — mismatch throws `IOException("Protocol version mismatch: ...")`.
- **EC-6.4**: `sendStateHash(int turn, long hash)` sends `STATE_HASH` packet; `computeStateHash()` hashes level map XOR hero positions XOR hero HP. Host calls this every 10 turns from `Hero.act()`.
- **EC-6.8**: `getClientIn()` and `getClientOut()` public accessors replace reflection in `WndHeroClaim`.

---

## Logs

| Source | Location |
|--------|----------|
| Host game | `GLog.p("Hosting game on port %d", port)` |
| Client connect | `GLog.p("Connected to host at %s:%d as \"%s\"", ...)` |
| Client joined | `GLog.p("Client \"%s\" connected, total players: %d", ...)` |
| Client removed | `GLog.p("Removed client at index %d, connectedPlayerCount=%d", ...)` |
| Duplicate name rejected | `GLog.w("Rejected client with duplicate name: %s", ...)` |
| Desync detected | `GLog.n("DESYNC DETECTED at turn %d: local=%d remote=%d", ...)` |
| HERO_READY sent | `GLog.p("HERO_READY sent to host: %s", ...)` |
| HANDSHAKE sent | `GLog.p("HANDSHAKE sent: %d players, seed %d", ...)` |
| Handshake received | `GLog.p("Handshake received — %d players, seed %d", ...)` |
| Disconnect | `GLog.w("Disconnected from network")` |
| Network errors | `GLog.n(...)` in all send/receive methods |

## Deployment

- Mechanism: `local only` (Android app, desktop via libGDX)
- Deploy command:
  ```bash
  ./gradlew desktop:run
  ```
- Notes: LAN mode is activated by `hostGame()` or `joinGame()`. Solo play is fully unaffected — all network paths are guarded by `if (!lanMode) return`. `cleanup()` is called by `disconnect()` and also on failed `joinGame()`. `localPlayerIndex` is always 0 for the host and 1..N for clients; it is reset to 0 by `cleanup()` on session end.
