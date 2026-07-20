# LAN Lockstep Determinism

## The model

LAN multiplayer is a lockstep deterministic simulation: every device runs the
full game; only hero actions cross the network. If any part of the simulation
can produce different results on two devices, the games silently diverge
("falls out of sync") or a hero waits for a packet that never comes ("locks").

This document lists the determinism pillars added in 2026-07 and the invariants
future changes must respect.

## Pillar 1 — seeded simulation RNG (`Random.bindSimGenerator`)

Gameplay RNG used to come from the unseeded base generator: every combat roll,
mob AI decision, and respawn differed across devices. Now:

- `Dungeon.init()` binds a `Random.DeterministicRandom` (SplitMix64) seeded from
  the run seed when `NetworkManager.lanMode` is set. Non-LAN games are unchanged.
- Threads registered via `Random.registerSimThread()` draw from it: the actor
  thread (`GameScene`) and the `InterlevelScene` worker.
- Its state is one long, saved as `sim_rng_state` in the game bundle and
  restored on load/resume, so a resumed game continues the same roll sequence.
- Scoped `Random.pushGenerator(seed)` blocks still take precedence, and pushed
  generators are **per-thread-owned**: a render-thread push (tile variance) can
  no longer bleed into concurrent sim draws.
- Render/UI threads keep drawing from the unseeded base generator, so particles
  and sound-pitch variation never disturb the sim sequence.

### Sim context on the render thread

Some simulation resolves in sprite-animation callbacks on the render thread
while the actor loop is parked: melee `onAttackComplete`, `onOperateComplete`,
missile-throw completions, wand-bolt arrivals. Those dispatch points are wrapped
in `Random.enterSimContext()` / `exitSimContext()` (see `CharSprite.onComplete`,
`MissileSprite.onComplete`, `MagicMissile`), which routes their RNG to the sim
generator. **Any new animation-completion path that mutates game state must be
wrapped the same way.**

`Random.cosmeticFloat/cosmeticInt` always draw from the base generator — use
them for any randomness in code reachable from sim threads that is purely
audiovisual (e.g. `Emitter.start`'s random delay), especially behind per-device
visibility checks.

## Pillar 2 — deterministic ordering

- `Actor.all`, `Actor.chars`, `Level.mobs`, `Level.blobs` are Linked
  collections (insertion order, not identity-hash order).
- `Actor.process()` (and the `Actor.testActNext()` test seam) break ties on
  equal `(time, actPriority)` by **lower actor id**.
- `Char.buffs(Class)` returns a LinkedHashSet.

Never iterate a plain `HashSet`/`HashMap` while consuming RNG or mutating sim
state — order differs per JVM run.

## Pillar 3 — performed-action protocol (leader-sequenced since v3)

Senders no longer broadcast peer-to-peer: every atomic op is sequenced by the
leader (host) into one global commit stream and dispersed from there, with
pessimistic execution, acks, dedup, retransmit, and hash-checked commits with
snapshot self-heal. See `lan-commit-protocol.md` for the full protocol. The
*set* of ops is unchanged — each atomic op the hero actually performs, from
inside the `actXxx` methods / `getCloser()` via `Hero.lanSendPerformed()`:

- every movement **step** (adjacent cell), including chasm-fall steps
- attack / pickup / open-chest / unlock / mine / interact / transition at the
  moment they execute
- every rest tick (`Rest`, fullRest flag), searches (`Search`)
- item use: `UseItem(bag, slot, actionIdx)` — actionIdx indexes
  `item.actions(hero)`, `0xFF` = default action
- targeted item use: `UseItemAt(bag, slot, verb, cell)` — verbs ZAP / THROW /
  SHOOT (SpiritBow); the target cell rides in the packet
- shop ops: `ShopBuy(heapPos)`, `ShopSell(bag, slot, all)`

Remote heroes never pathfind (`getCloser` refuses non-adjacent targets for
remote LAN heroes) because pathfinding inputs (`visited[]`, fog) are per-device.
Remote heroes never prompt: chasm jumps are treated as confirmed.

### Interactive prompts (identify/upgrade scrolls, chains…)

Item effects that prompt mid-execution are handled by the choice protocol:

- Queued item executions set `NetworkManager.localItemExecution` /
  `remoteItemExecution` around `item.execute(...)`.
- `GameScene.selectItem/selectCell` park the listener on remote devices
  (`pendingRemoteItemSelector/CellListener`) and wrap it on the owning device so
  the resolved choice is broadcast (`ITEM_CHOICE` / `CELL_CHOICE` packets).
- The remote hero's wait loop breaks when a choice consumes its turn
  (time advanced without a new `curAction`), so mobs act in the same order as
  on the sender.

## Pillar 4 — no per-device state in the simulation

- Guide/lore/alchemy pages and Bones are skipped in LAN games (meta progression
  and local files differ per device and even shift the level-gen RNG stream).
- Tutorial door-hiding is disabled in LAN.
- Challenges ride in the HANDSHAKE (protocol v2, `NetworkManager.lanChallenges`);
  `Dungeon.init` reads them from there in LAN games.
- Level generation must never consult `Dungeon.hero` (it is the *local* hero and
  differs per device) — use `RegularLevel.heroesForGen()`.
- Simulation code must never key off `Dungeon.hero` either. Fixed instances:
  `Level.spawnMob` (spawn distance now measured to every hero),
  `Level.beforeTransition` (partial-turn spending & buff cleanup for all heroes),
  `Level.seal/unseal` (LockedFloor on all heroes), Rejuvenating Steps trample
  (keys off the walking hero). Audit any new `Dungeon.hero` reference in
  actors/levels/items code for this.
- Remote heroes must execute transmitted steps verbatim: `getCloser`'s adjacent
  occupancy guard is bypassed for remote LAN heroes because the owner may
  legitimately path onto a cell occupied by a char outside its FOV.

## Known gaps

- Alchemy is blocked in LAN (`Hero.actAlchemy`) until crafting is synced.
- Shop buyback window is not routed (regular buy/sell is).
- ~~The state-hash desync detector only reports; there is no resync/recovery
  mechanism.~~ Fixed in protocol v3: every ACTION commit is hash-checked at a
  shared sim point and a mismatched follower auto-resyncs from a leader
  snapshot (`lan-commit-protocol.md`).

## The master test

`core/src/test/java/.../lan/LanLockstepReplayTest.java` replays a scripted
2-hero game twice — as the host device, then (after a full static reset) as the
client device with hero0 fed the recorded packets over a real loopback socket —
on a real generated sewer level with live mob AI, and asserts the per-step state
fingerprint streams are identical. Run it after ANY change touching actors,
RNG, level gen, or the network protocol:

    ./gradlew :core:test --tests '*.lan.LanLockstepReplayTest'
