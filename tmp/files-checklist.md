# Files Checklist — LAN-08-disconnect

## New Files to Create
- [x] /home/lewibs/github/shattered-pixel-dungeon-multiplayer/core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/windows/WndPeerDisconnected.java

## Files to Modify
- [x] /home/lewibs/github/shattered-pixel-dungeon-multiplayer/core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/network/NetworkManager.java
  - Added Signal import
  - Added PeerDisconnected event class
  - Added peerDisconnectSignal static field
  - Modified receiveActionAsync() to dispatch PeerDisconnected signal on timeout/IO error
  - Modified receiveHash() to accept Hero parameter and dispatch PeerDisconnected signal
  - Added openRejoinRoom() method for reconnection flow

- [x] /home/lewibs/github/shattered-pixel-dungeon-multiplayer/core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/scenes/GameScene.java
  - Added Signal import
  - Added WndPeerDisconnected import
  - Added disconnect signal listener registration in create() method
  - Added disconnect signal listener cleanup in destroy() method
  - Updated receiveHash() call to pass Dungeon.hero parameter

## Compilation Status
- [x] BUILD SUCCESSFUL - All Java files compile without errors
