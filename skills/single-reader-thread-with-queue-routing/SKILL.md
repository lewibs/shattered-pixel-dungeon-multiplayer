---
name: single-reader-thread-with-queue-routing
description: "When multiple callers need data from the same DataInputStream, enforce a singleton reader thread and route secondary packet types through LinkedBlockingQueues instead of letting callers read the stream directly."
user-invocable: false
---
## When to use
Any time a persistent background thread already owns a `DataInputStream` for the primary packet type (e.g. ACTION packets during gameplay) and other code paths also need to receive packets from the same stream (e.g. HASH for turn verification, RESUME_HANDSHAKE for resync). Without this pattern, each new caller spawns its own read loop, and multiple threads reading bytes from the same stream produce corrupted packet framing (the "gray square" / stuck-hero symptom in this codebase).

## Steps
1. Declare a `volatile boolean` singleton guard (e.g. `actionReaderRunning`) for the reader thread. Wrap the thread-start site in a `synchronized` block that checks and sets the flag atomically; return immediately if already true.
2. In the `finally` block of the reader thread, reset the flag to `false` so a reconnect or level transition can start a fresh reader.
3. For every secondary packet type that arrives interleaved with the primary type, declare a `LinkedBlockingQueue<PayloadType>` (e.g. `hashQueue`, `resyncQueue`). These queues are `final` class-level fields.
4. Inside the reader loop, handle secondary packet types by reading their payload bytes and calling `queue.offer(payload)`. Never leave bytes unread — partial reads corrupt the framing for all subsequent packets.
5. Replace any direct `in.readXxx()` calls in `receiveHash()`, `receiveResyncBundle()`, or similar methods with `queue.poll(TIMEOUT, TimeUnit.MILLISECONDS)`. Convert `InterruptedException` to `IOException` and propagate; convert a `null` poll result (timeout) to a `SocketTimeoutException`.
6. In `cleanup()`, call `queue.clear()` for every queue added in step 3 so stale payloads from the previous session do not bleed into the next one.

## Notes
- The failure mode is non-obvious: the gray-square / indefinitely-waiting-hero symptom in SPD-MP was caused by a second `receiveHash()` call spawning its own reader thread that consumed ACTION bytes meant for the first thread. The packet stream becomes desynchronized with no exception thrown.
- `Hero.act()` calls `receiveActionAsync()` every turn when `curAction == null`, so the guard must be inside a `synchronized` block — a plain `if (!flag) { flag = true; }` has a TOCTOU race.
- The queues must be `final` and initialized at declaration (not in `cleanup()`) because `cleanup()` only calls `clear()` on them. Re-assigning the field reference in cleanup would break any thread still holding a reference to the old queue.
- `waitForAllHeroReady()` and `waitForHandshake()` run during the hero-select phase before `receiveActionAsync()` starts, so they safely own per-client streams directly at that point. The single-reader invariant only applies once the gameplay reader is running.
- Test seams (`isActionReaderRunning()`, `setActionReaderRunningForTesting()`, `resetActionReaderForTesting()`) are the recommended way to unit-test the guard logic without live sockets.
