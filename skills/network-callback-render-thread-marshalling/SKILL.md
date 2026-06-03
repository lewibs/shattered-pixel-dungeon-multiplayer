---
name: network-callback-render-thread-marshalling
description: "Any LAN callback that mutates scene state or UI must be dispatched to the render thread via Game.runOnRenderThread(); direct mutation from the reader thread causes race conditions with the OpenGL draw loop."
user-invocable: false
---
## When to use
Whenever a `NetworkManager` callback (e.g. `onClassClaimedReceived`, `onClassUnclaimedReceived`, any packet handler) needs to:
- modify a UI component's state (`active`, `alpha`, `text`, etc.),
- mutate shared scene collections (e.g. `GamesInProgress.selectedClasses`), or
- trigger a scene transition (`Game.switchScene()`).

These callbacks fire on the background reader thread. The SPD render loop (libGDX/Noosa) is single-threaded and reads UI state on every frame. Writing from the reader thread without marshalling produces read/write races that manifest as flickering, stale state, or crash-level corruption.

## Steps
1. Register the callback in the scene's `create()` method (not in a static initializer or a background thread):
   ```java
   NetworkManager.onClassClaimedReceived = (playerIdx, cls) -> Game.runOnRenderThread(() -> {
       // safe to mutate scene/UI state here
       if (cls != null && !GamesInProgress.selectedClasses.contains(cls))
           GamesInProgress.selectedClasses.add(cls);
   });
   ```
2. Wrap the entire mutation body in `Game.runOnRenderThread(() -> { ... })`. Do not perform partial mutation outside the lambda.
3. Clear the callback in `onBackPressed()` or the scene's equivalent teardown so the lambda does not hold a stale reference to the old scene after a transition.
4. For state that must be polled over time (e.g. "has HERO_READY arrived?"), prefer the `update()` polling pattern (check an `AtomicBoolean` set by the reader thread, react on the render thread) over direct callback mutation. See `single-reader-thread-with-queue-routing` skill for the queue-based variant.

## Notes
- `Game.runOnRenderThread(Runnable)` queues the runnable for execution at the start of the next `Game.update()` tick, which is always on the GL/render thread. It is safe to call from any thread.
- The alternative — polling an `AtomicBoolean` in `update()` — is also correct and is used for the `lanReadyToStart` / `lanHandshakeReady` flags in HeroSelectScene. Use polling when the reaction involves complex UI work (text resize, scene switch); use `runOnRenderThread` when the mutation is a simple collection add/remove.
- Do NOT use `synchronized` on scene fields as a substitute for marshalling. The GL thread does not synchronize on scene objects, so a lock on the reader-thread side only provides half a barrier.
- Callbacks set on `NetworkManager` static fields persist across scene transitions unless explicitly cleared. Always nullify them in the scene teardown or in `NetworkManager.cleanup()`.
- This pattern was first applied for `CLASS_CLAIMED`/`CLASS_UNCLAIMED` handlers in `HeroSelectScene.create()` (LAN hero-select lockout fix). The same idiom applies to any future real-time lobby or in-game event broadcast that touches UI state.
