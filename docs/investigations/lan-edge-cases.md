# LAN Multiplayer Edge Cases

Investigation date: 2026-05-31
Source files examined:
- `core/.../network/NetworkManager.java`
- `core/.../scenes/LanLobbyScene.java`
- `core/.../scenes/HeroSelectScene.java`
- `core/.../scenes/GameScene.java`
- `core/.../actors/hero/Hero.java`
- `core/.../windows/WndPeerDisconnected.java`
- `core/.../windows/WndHeroClaim.java`
- `core/.../windows/WndLANMenu.java`
- `docs/plans/lan-hotspot-multiplayer.md`

---

## Flow 1: Starting a New LAN Game

### Happy Path

1. Host taps "LAN Game" → "Host Room" in `WndLANMenu.onHostClicked()`, sets `NetworkManager.playerName`, calls `NetworkManager.hostGame(7777)`, switches to `LanLobbyScene`.
2. `hostGame()` opens a `ServerSocket` on port 7777, sets `isHost=true`, `connectedPlayerCount=1`, starts the `network-accept-loop` thread (`acceptClientsLoop()`), and starts the UDP discovery broadcast via `startDiscoveryBroadcast()`.
3. Clients discover the room in `LanRoomListScene`, tap a row, and `NetworkManager.joinGame()` is called. The client immediately sends a `NAME_ANNOUNCE` packet.
4. The host's `acceptClientsLoop()` reads the name (2-second timeout), assigns `newPlayerIndex = connectedPlayerCount - 1`, increments `connectedPlayerCount`, and broadcasts `PLAYER_JOINED` to every connected socket (including the new one).
5. `LanLobbyScene` updates slot labels and enables the "Start Game" button when `total >= 2`.
6. Host taps "Start Game" (`onStartTapped()`): generates seed, calls `NetworkManager.sendStart()`, sets `gameStarted=true`, switches to `HeroSelectScene`.

---

### Edge Cases

**EC-1.1 — Player joins then leaves before host starts**

Status: PARTIAL

In `acceptClientsLoop()` the new socket is added to `peerSockets`, `ins`, `outs`. There is no listener watching each peer socket for a pre-game closure. If the TCP connection drops, the host only learns about it on the next write (e.g., the `PLAYER_JOINED` broadcast or the eventual `START` send). `connectedPlayerCount` is never decremented, so the slot stays listed as occupied and the `Start` button may be enabled for a player who is already gone. When `sendStart()` iterates `outs` and writes to the dead socket, `IOException` propagates up to `onStartTapped()`, which re-enables Start for a retry — but the ghost slot count is not corrected and the slot label stays populated.

**EC-1.2 — Same-name collision (how we know the same player rejoined vs a different device)**

Status: UNHANDLED

Player identity is matched entirely by display name (`NetworkManager.playerName`). In `LanLobbyScene.startClientListenerThread()` at line 297–302, the client finds its own `localPlayerIndex` by comparing the announced name against the one stored at each slot. There is no device identifier, UUID, or session token. Two devices with the same name will both match on the first slot that holds that name, and both will think they are the same player. In the rejoin flow (`openRejoinRoom()`), any device can claim any slot in `WndHeroClaim` regardless of who originally held it.

**EC-1.3 — Removing a player from the lobby before the game starts**

Status: UNHANDLED

There is no "kick" button on the host's `LanLobbyScene`. The host cannot remove a connected player from the lobby. The only mechanism to reduce the player count is for the client to drop the TCP connection voluntarily, and even then `connectedPlayerCount` is not decremented (see EC-1.1).

**EC-1.4 — Host leaves the lobby (host cancels or crashes)**

Status: PARTIAL

If the host taps "Cancel" in `LanLobbyScene`, `active=false` and the scene switches to `StartScene`. `destroy()` calls `NetworkManager.disconnect()` which closes all sockets. The client's `startClientListenerThread()` will then get an `IOException` on its next `readByte()`. The catch block in the thread calls `statusLabel.text("Host disconnected: " + ...)` on the render thread — but only if `active` is still true. Because the scene is still alive on the client when the host closes, the error message is shown. However, there is no button to return to the room list from that error state; the client must navigate back manually. If the host device crashes outright (no orderly `disconnect()`), the client will block on `in.readByte()` indefinitely because `SOCKET_TIMEOUT_MS = 0` (no read timeout).

**EC-1.5 — Start pressed with 0 clients (host only)**

Status: HANDLED

`startBtn.enable(false)` is set on creation and only enabled inside `onPlayerJoined()` when `total >= 2`. The button is physically disabled until at least one client has joined, so the host cannot fire `START` with zero clients.

---

## Flow 2: Saved LAN Game Restart

### Happy Path

1. Host taps the LAN save slot → `WndLANMenu(resumeSlot)`. In `onHostClicked()`, `Dungeon.loadGame(resumeSlot)` is called, then `NetworkManager.hostGame(7777)`, then switches to `LanLobbyScene` with `resumeMode=true`.
2. Clients join; host's `acceptClientsLoop()` sends `SAVE_LOBBY_INFO` to each new client (name, class, HP from `Dungeon.heroes`).
3. Host taps "Start" → `broadcastResumeStart(playerCount)` → clients transition to `WndHeroClaim`.
4. Clients pick a hero and send `HERO_CLAIM { heroIndex }` → host reads claims via `waitForHeroClaims()`, serialises the dungeon via `broadcastResumeHandshake(bundleBytes, heroAssignments)`.
5. Clients deserialise in `WndHeroClaim.waitForResumeHandshake()`, call `Dungeon.loadGame(bundle)`, set `Dungeon.hero = Dungeon.heroes.get(myIndex)`, switch to `InterlevelScene.CONTINUE`.

---

### Edge Cases

**EC-2.1 — Do players only pick from existing heroes?**

Status: PARTIAL

`WndHeroClaim` shows only the heroes present in the loaded `Dungeon.heroes` list (names/classes/HP passed via `SAVE_LOBBY_INFO`). Players cannot create a new hero. However, there is no validation that the count of connecting clients matches the number of save slots. If 3 clients join but the save has 2 heroes, `waitForHeroClaims(clientCount)` reads one claim per client sequentially from `ins.get(i)`. The third client's stream will never yield a `HERO_CLAIM` if the UI only shows 2 hero cards, and the host will block indefinitely on `ins.get(2).readByte()`.

**EC-2.2 — New player (not in the original save) tries to join**

Status: UNHANDLED

Anyone with the app who can reach port 7777 can join the rejoin room. There is no session token, shared secret, or save-slot membership check. A completely new device can join, pick any hero including the host's, and claim it. The host claims "whichever index is left" implicitly, but that logic is not implemented in code — `waitForHeroClaims()` only collects client claims. The host has no explicit claim step, so if a stranger claims the hero the host expected to control, the host ends up with no hero assignment.

**EC-2.3 — A player from the original save is absent at restart**

Status: UNHANDLED

If the 2-player save has heroes A and B but only the host shows up (no client joins), the host can still tap "Start" once the button is enabled (requires `total >= 2`). Since the button only enables at 2+ players, the game cannot start solo through the resume lobby. But if the host force-starts with 1 client who claims hero A, hero B (the original second player's hero) has no assigned `localPlayerIndex`. In `Hero.act()` the check at line 963–965 compares `Dungeon.heroes.indexOf(this)` against `NetworkManager.localPlayerIndex`. Hero B's index will not match any connected player's `localPlayerIndex`, so it will block forever in `lanActionLock.wait(5000)` until the 5-second timeout, then `return false` each turn, effectively skipping hero B's turns silently.

**EC-2.4 — Hero state (inventory, position) on rejoin**

Status: HANDLED

The entire dungeon state is serialised into `bundleBytes` in `broadcastResumeHandshake()` and each client deserialises it via `FileUtils.bundleFromBytes(bundleBytes)` then `Dungeon.loadGame(bundle)` in `WndHeroClaim.waitForResumeHandshake()`. Items, position, buffs, and inventory are fully preserved. There is one caveat: `Dungeon.hero` is manually re-assigned to `Dungeon.heroes.get(myIndex)` after loading. If `myIndex` is out of range (e.g., a client received an invalid `heroAssignments` entry), this will throw an `IndexOutOfBoundsException` caught only by the outer generic `catch (IOException e)` which will silently log and leave `Dungeon.hero = null`, crashing the subsequent scene switch.

---

## Flow 3: Mid-Game Host Disconnect

### Happy Path

The host's `net-ping-sender` thread sends `PING` every 5 seconds. If the host's write fails, `startPingSender()` breaks. The client's `receiveActionAsync()` reader thread (`net-reader-action`) encounters an `IOException` on its next `readByte()` (since `SOCKET_TIMEOUT_MS = 0`, this blocks until the TCP stack detects the dead connection). The reader dispatches `peerDisconnectSignal`, which `GameScene` catches in its signal listener (registered in `GameScene.create()` at line 236). The signal handler sets `Actor.keepActorThreadAlive = false`, auto-saves if host, and shows `WndPeerDisconnected`.

---

### Edge Cases

**EC-3.1 — What currently happens when the host's socket closes**

Status: PARTIAL

On the client side, `receiveActionAsync()` reads from `clientIn`. When the TCP connection dies, the next `in.readByte()` throws `IOException`. The reader thread dispatches `peerDisconnectSignal.dispatch(new PeerDisconnected(remoteHero))` and calls `GameScene.notifyActorThread()`. `GameScene`'s signal listener sets `keepActorThreadAlive = false`, auto-saves (client skips — only host saves), and shows `WndPeerDisconnected`. The critical gap: there is no read timeout (`SOCKET_TIMEOUT_MS = 0`). The TCP stack may take 10–15 minutes to deliver the `ECONNRESET` if the host machine is hard-killed with no RST. Until that happens the client is frozen, waiting for the packet that will never come.

**EC-3.2 — Is there a host-migration mechanism?**

Status: UNHANDLED

There is no host-migration mechanism. The plan (`lan-08-disconnect.md`) describes the concept as a future flow but it is not implemented in any source file. All game state lives exclusively on the host (`Dungeon.saveAll()` is only called when `isHostMode()` is true). There is no peer-to-peer consensus or client-side authoritative state.

**EC-3.3 — Does the client freeze, crash, or show an error?**

Status: PARTIAL

If the TCP RST arrives promptly, the `IOException` is caught, `WndPeerDisconnected` is shown, and the game is paused cleanly (actor thread stopped). If the host disappears without a TCP RST (e.g., process killed, WiFi cut), the client freezes indefinitely at the `lanActionLock.wait(5000)` call in `Hero.act()` (line 969) because the wait has a 5-second timeout, after which `curAction == null` returns `false` — the turn is skipped and the wait restarts on the next turn. This produces a "zombie" game that loops every 5 seconds rather than freezing hard. The `peerDisconnectSignal` is never fired because the reader thread never wakes up. The game does not crash but is unplayable.

**EC-3.4 — Can the session be recovered?**

Status: PARTIAL

`WndPeerDisconnected` only shows on the surviving clients as a "Peer Disconnected" modal with "Open Rejoin Room" and "Save and Exit". The "Open Rejoin Room" option calls `NetworkManager.openRejoinRoom()` — but this is designed for the **host** to reopen a room. A client clicking "Open Rejoin Room" after the host disconnected will call `openRejoinRoom()`, which creates a new `ServerSocket` on port 7777, sets `isHost=true`, and transitions back to `LanLobbyScene`. The client has now promoted itself to host with a stale `Dungeon` state in memory (no save was written on the client). The revived session has no saved game to hand off and the new "host" has no authoritative save.

---

## Flow 4: Mid-Game Non-Host Disconnect

### Happy Path

The `net-ping-sender` thread on the host detects a write failure to the disconnected client's `DataOutputStream`, breaks out of its loop, and the `net-reader-action` reader thread catches the `IOException`, dispatches `peerDisconnectSignal`, and `GameScene` pauses the actor thread and shows `WndPeerDisconnected`.

---

### Edge Cases

**EC-4.1 — What currently happens when a client's socket closes**

Status: PARTIAL

`receiveActionAsync()` on the host reads only from `ins.get(0)` (the first client stream). In a 3+ player game the host has `ins` with multiple entries, but the reader always reads from slot 0 regardless of which remote hero's turn it is. When a non-index-0 client disconnects, the reader thread never touches that client's stream, so the `IOException` from the dead socket is only discovered when the `net-ping-sender` attempts a write to that client's `DataOutputStream`. Ping fires every 5 seconds, so detection is delayed up to 5 seconds. Once ping fires the `IOException` propagates through `startPingSender()`, which logs a warning and breaks — but it does **not** dispatch `peerDisconnectSignal`. The signal is only dispatched by the reader thread on `IOException`. So a non-index-0 client disconnect is detected by ping but the signal is never fired, meaning `WndPeerDisconnected` is never shown and the game does not pause. The actor thread will eventually try to send an action to the dead client's socket, fail silently in `sendAction()`'s try-catch, and continue.

**EC-4.2 — Does the host's game freeze waiting for the missing player's turn?**

Status: UNHANDLED

`receiveActionAsync()` always reads from `ins.get(0)`. For a 2-player game this is correct. For 3+ players, when it is the turn of the hero controlled by client index 1 or 2, the `Hero.act()` remote branch calls `receiveActionAsync(this)` which still reads from `ins.get(0)` (client 0's stream). If client 0 has no pending action packet on its stream (their turn already passed), the reader blocks on `in.readByte()` indefinitely — effectively freezing that hero's turn. This is an architectural limitation: the current reader does not multiplex across multiple client streams.

**EC-4.3 — Is the disconnected hero removed from the actor queue?**

Status: UNHANDLED

There is no `Hero.removeFromGame()` method as described in the plan (`lan-08-disconnect.md` says "a new `Hero.removeFromGame()` method is needed"). When a peer disconnects, `WndPeerDisconnected` offers "Open Rejoin Room" and "Save and Exit" — there is no "Continue Solo" option to remove the dead hero from `Actor.all`. The hero remains in the actor queue. On its next turn, `Hero.act()` enters the remote action wait block (`lanActionLock.wait(5000)`), times out after 5 seconds, and `return false` causing that hero's turn to be silently skipped. The game continues but every 5 seconds the actor thread stalls on the dead hero's turn.

**EC-4.4 — What happens to the disconnected hero's items/corpse?**

Status: UNHANDLED

Because there is no `removeFromGame()` path, the disconnected hero's inventory is not dropped, and the hero is not killed. The character stays alive in the simulation (occupying their cell, affecting mob AI pathfinding, blocking doors). The host's `Dungeon.saveAll()` saves the session including the live disconnected hero. If the session is resumed, the hero will be in the save, available to be claimed again.

---

## Flow 5: Rejoin / Reconnect

### Happy Path

A disconnected player taps "Open Rejoin Room" in `WndPeerDisconnected`. The host calls `NetworkManager.openRejoinRoom()`, which closes the old `ServerSocket`, opens a new one on port 7777, sets `gameStarted=false`, starts `acceptClientsLoop()`, and calls `startDiscoveryBroadcast("Rejoin Game", currentPlayers)`. The rejoining client discovers the room, connects, and the lobby flow continues.

---

### Edge Cases

**EC-5.1 — Is there any reconnection protocol?**

Status: UNHANDLED

There is no reconnection protocol. When a client reconnects via the rejoin room it goes through the full resume lobby flow — `SAVE_LOBBY_INFO`, `RESUME_START`, `WndHeroClaim`, `HERO_CLAIM`, `RESUME_HANDSHAKE`. There is no "reconnect and resume at current turn" path. The game state is re-serialised and re-transmitted from the host's current in-memory `Dungeon` state.

**EC-5.2 — How would the game sync state to a rejoining client?**

Status: PARTIAL

The sync mechanism is the full `RESUME_HANDSHAKE` bundle (`broadcastResumeHandshake()`), which serialises the entire dungeon to bytes and sends it over TCP. The client fully replaces its local state with the received bundle. This works in principle but is heavyweight and has no integrity check (no hash, no length-prefixed verification beyond the raw `bundleLength` field in the packet header).

**EC-5.3 — What session/player identity is used to match a reconnecting device to their hero?**

Status: UNHANDLED

Identity is solely the player's display name (`NetworkManager.playerName`). In `LanLobbyScene.startClientListenerThread()` the client matches its own `localPlayerIndex` by comparing the name sent in `PLAYER_JOINED` against `NetworkManager.playerName`. In the rejoin flow, the hero selection is manual (`WndHeroClaim`) — the client picks any available hero. Nothing prevents a different person from rejoining with the original player's name and stealing their hero, or the same player rejoining but accidentally picking a different hero.

---

## Flow 6: Other LAN Edge Cases

**EC-6.1 — Partial packet writes / interleaved writes from concurrent threads**

Status: UNHANDLED

Multiple threads write to the same `DataOutputStream` concurrently. The `net-ping-sender` thread writes `PING` bytes while the actor thread may simultaneously be in `sendAction()` writing `ACTION` packets. `DataOutputStream.writeByte()` and `writeInt()` are not atomic as a group. If a ping write interleaves between the `writeByte(ACTION)` and `writeInt(heroId)` calls, the remote reader will read `ACTION` then consume the `PING` byte as `heroId`'s first byte, corrupting the stream permanently. No `synchronized` block guards multi-byte writes in `sendAction()`, `sendItemIdentified()`, or `startPingSender()`.

**EC-6.2 — PING timeout detection is write-only, not read-only**

Status: PARTIAL

Disconnect detection relies on ping **writes** failing (`IOException` in `startPingSender()`). However, `startPingSender()` breaking does not dispatch `peerDisconnectSignal` — it only logs a warning and exits (`GLog.w("Ping failed — peer disconnected")`). The actual signal is only dispatched by the reader thread in `receiveActionAsync()` when a read fails. In a scenario where data stops arriving (remote app frozen, not dead socket), writes may succeed indefinitely (TCP buffers absorb them), `startPingSender()` never detects the issue, the reader never gets an `IOException`, and the game freezes waiting for an action that never comes.

**EC-6.3 — Hero count mismatch between host and client**

Status: UNHANDLED

In the new-game flow, the host sends `HANDSHAKE { seed, heroClasses[], playerCount }`. The client reads `playerCount` and trusts it absolutely to size `heroClasses[]`. If the packet is corrupted or the host sends a `playerCount` larger than the actual number of class bytes that follow, `HeroClass.values()[classOrdinal]` in `waitForHandshake()` will throw `ArrayIndexOutOfBoundsException` if `classOrdinal >= HeroClass.values().length`, or silently succeed with a garbage class ordinal. There is no sanity check comparing the received `playerCount` against `NetworkManager.getConnectedPlayerCount()` on the client.

**EC-6.4 — Seed mismatch / divergent RNG**

Status: UNHANDLED

The plan defines a `HASH` packet (type `0x02`) for desync detection every 10 turns but it is never sent in any production code path. `PacketType.HASH` is declared in `NetworkManager` but no call site exists for it. The game has no live desync detection. If two devices diverge (e.g., due to a non-deterministic item effect, a platform-specific `Random` difference, or the partial-write corruption in EC-6.1), they will silently disagree on mob positions and game state with no recovery trigger.

**EC-6.5 — Version mismatch between host and client**

Status: UNHANDLED

The `HANDSHAKE` packet carries only `seed` and `heroClasses[]`. There is no version field. A client on a different app version will connect, exchange `HANDSHAKE`, and begin the game. If the game logic differs between versions, immediate desync is guaranteed, but there is no detection (see EC-6.4) and no rejection.

**EC-6.6 — Two devices claiming to be host (split-brain)**

Status: UNHANDLED

`NetworkManager.isHost` is a static flag set by `hostGame()`. Nothing prevents two devices from both calling `hostGame(7777)` — they will each open a separate `ServerSocket` on port 7777 and both broadcast UDP discovery with `isHost=true`. If device A and device B are both hosting and device C connects to A while device D connects to B, two independent sessions exist on the same LAN with no awareness of each other. The UDP discovery packet format `"SPD-MP|hostIP|tcpPort|roomName|..."` distinguishes rooms by IP but the client has no mechanism to detect that the game it joined is not the one it expected.

**EC-6.7 — `receiveActionAsync` only reads from `ins.get(0)` regardless of player count**

Status: UNHANDLED

Described partially in EC-4.2. `receiveActionAsync()` hard-codes `DataInputStream in = isHost ? ins.get(0) : clientIn`. In a 3- or 4-player game, the host has `ins.get(0)`, `ins.get(1)`, `ins.get(2)` for clients 1, 2, 3. All remote heroes' turns are serviced by reading from `ins.get(0)` only. When hero at index 2 needs to act, the reader still reads client 0's stream, not client 1's. This means the action for hero 2 can only arrive if client 1 (index 0 in `ins`) somehow sends it — which is impossible. 3-player and 4-player games will deadlock on the second remote hero's turn.

**EC-6.8 — `WndHeroClaim` accesses private NetworkManager fields via reflection**

Status: PARTIAL

`WndHeroClaim.onClaimClicked()` and `waitForResumeHandshake()` use `NetworkManager.class.getDeclaredField("clientOut")` and `getDeclaredField("clientIn")` with `setAccessible(true)`. This will throw `SecurityException` on Android runtimes that enforce access restrictions (e.g., Android 9+ with strict hidden API policy). It also fails silently with only a `GLog.n()` message if reflection is blocked, leaving the client in the hero-claim screen with no way to proceed.

**EC-6.9 — `acceptClientsLoop` has no upper bound on accepted connections**

Status: UNHANDLED

`acceptClientsLoop()` loops while `lanMode && isHost && serverSocket != null` with no cap on `connectedPlayerCount`. Despite `MAX_PLAYERS = 4` being declared in `LanLobbyScene`, the `acceptClientsLoop` in `NetworkManager` never checks it. A fifth or sixth device can connect, will be assigned `playerIndex = 4` or `5`, and the `playerNames[newPlayerIndex]` write at `NetworkManager.java:447` will throw `ArrayIndexOutOfBoundsException` because `playerNames` is fixed at length 4. This will crash the accept-loop thread silently (the exception is caught by the outer `catch (IOException e)` — actually it would be an `ArrayIndexOutOfBoundsException` which is **not** `IOException` and will propagate uncaught, killing the thread).

**EC-6.10 — `onStartTapped()` uses stale `connectedPlayerCount` if a client left after join**

Status: UNHANDLED

`LanLobbyScene.onStartTapped()` calls `NetworkManager.getConnectedPlayerCount()` which returns the static `connectedPlayerCount` field. This count only ever increments (in `acceptClientsLoop()`), never decrements. If a client disconnected after joining, the count is stale, `sendStart()` is called with a higher `playerCount` than there are live sockets, and the iteration over `outs` in `sendStart()` will write to the dead socket and throw `IOException`, triggering the retry logic in `onStartTapped()` — but the ghost slot in `outs` is never removed, so each retry attempt will fail on the same dead socket.
