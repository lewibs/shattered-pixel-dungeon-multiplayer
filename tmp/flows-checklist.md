# Flows Checklist — LAN-08-disconnect

## Flow 1: disconnectDetection
- [x] NetworkManager.receiveActionAsync() catches SocketTimeoutException and IOException
- [x] Dispatch PeerDisconnected signal on timeout/IO error
- [x] Actor.class.notifyAll() called to wake actor thread
- [x] NetworkManager.receiveHash() catches IOException and dispatches signal
- [x] Only dispatch signal if thread is not interrupted
- [x] Socket timeout is 30000ms on gameplay reads

## Flow 2: disconnectDialog
- [x] GameScene registers disconnect signal listener in create()
- [x] Signal listener pauses actor thread (Actor.keepActorThreadAlive = false)
- [x] Auto-save runs on host (Dungeon.saveAll())
- [x] WndPeerDisconnected shown on render thread
- [x] WndPeerDisconnected created with hero parameter
- [x] WndPeerDisconnected shows hero name in message
- [x] "Open Rejoin Room" button calls NetworkManager.openRejoinRoom()
- [x] "Save and Exit" button calls NetworkManager.disconnect()
- [x] Disconnect listener cleaned up in GameScene.destroy()

## Flow 3: reconnectFlow
- [x] NetworkManager.openRejoinRoom() re-opens ServerSocket on port 7777
- [x] openRejoinRoom() resumes UDP discovery broadcast
- [x] LanLobbyScene resumeMode flag can be set to true
- [x] Reuses lan-07 resume flow for hero claiming and RESUME_HANDSHAKE

## All Tests Must Pass
- [x] No compilation errors
- [x] All flows integrated end-to-end
