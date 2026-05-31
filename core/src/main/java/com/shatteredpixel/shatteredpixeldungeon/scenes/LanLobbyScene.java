/*
 * Pixel Dungeon
 * Copyright (C) 2012-2015 Oleg Dolya
 *
 * Shattered Pixel Dungeon
 * Copyright (C) 2014-2026 Evan Debenham
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 */

package com.shatteredpixel.shatteredpixeldungeon.scenes;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.GamesInProgress;
import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.network.NetworkManager;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.watabou.noosa.Camera;
import com.watabou.noosa.Game;
import com.watabou.utils.Callback;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Random;

/**
 * LanLobbyScene — displayed on both host and client after a LAN room is
 * created or joined.
 *
 * Host mode  (flow: lanLobbySceneHost): shows connected player slots, broadcasts
 *            a UDP discovery packet every 2 s, and enables Start when ≥ 2 players
 *            are connected. Tapping Start broadcasts a START packet and transitions
 *            all devices to HeroSelectScene.
 *
 * Client mode (flow: lanLobbySceneClient): shows slot list, waits for START packet
 *             from the host, then transitions to HeroSelectScene.
 */
public class LanLobbyScene extends PixelScene {

	private static final int MAX_PLAYERS = 4;
	private static final int SLOT_COUNT  = MAX_PLAYERS;

	// Resume mode: true if this lobby was opened from a LAN save slot
	public static boolean resumeMode = false;

	// UI references updated from background threads via Game.runOnRenderThread
	private RenderedTextBlock statusLabel;
	private RenderedTextBlock ipLabel;
	private RenderedTextBlock[] slotLabels = new RenderedTextBlock[SLOT_COUNT];
	private RedButton startBtn;
	// Stored so onPlayerJoined can use the correct max-width when updating labels
	private int labelWidth;

	// Whether this scene is still active (guards against stale background-thread callbacks)
	private volatile boolean active = true;
	// Set to true just before switching to HeroSelectScene so destroy() doesn't disconnect
	private boolean gameStarted = false;

	@Override
	public void create() {
		super.create();

		uiCamera.visible = false;

		int w = Camera.main.width;
		int h = Camera.main.height;

		com.watabou.utils.RectF insets = getCommonInsets();
		float safeW = w - insets.left - insets.right;
		float safeH = h - insets.top  - insets.bottom;

		float margin = 4f;
		float contentWidth = Math.min(safeW - margin * 2, 200f);
		labelWidth = (int) contentWidth;
		float x0 = insets.left + (safeW - contentWidth) / 2f;
		float y = insets.top + margin;

		// Title
		RenderedTextBlock title = renderTextBlock("LAN Lobby", 12);
		title.hardlight(Window.TITLE_COLOR);
		title.setPos(x0 + (contentWidth - title.width()) / 2f, y);
		align(title);
		add(title);
		y += title.height() + 6f;

		// IP / status row (host shows IP; client shows connecting info)
		ipLabel = renderTextBlock(6);
		if (NetworkManager.isHost()) {
			ipLabel.text("Your IP: " + NetworkManager.getLocalIP(), (int) contentWidth);
		} else {
			ipLabel.text("Connected to host", (int) contentWidth);
		}
		ipLabel.setPos(x0, y);
		align(ipLabel);
		add(ipLabel);
		y += ipLabel.height() + 4f;

		// Player slot labels: slot 0 = host, slots 1–3 = clients
		for (int i = 0; i < SLOT_COUNT; i++) {
			slotLabels[i] = renderTextBlock(6);
			if (i == 0) {
				String hostName = NetworkManager.getPlayerName(0);
				if (NetworkManager.isHost()) {
					slotLabels[i].text("Slot 1: " + hostName + " (You)", (int) contentWidth);
				} else {
					slotLabels[i].text("Slot 1: " + hostName, (int) contentWidth);
				}
			} else {
				slotLabels[i].text("Slot " + (i + 1) + ": Empty", (int) contentWidth);
			}
			slotLabels[i].setPos(x0, y);
			align(slotLabels[i]);
			add(slotLabels[i]);
			y += slotLabels[i].height() + 2f;
		}

		y += 4f;

		// Status message
		statusLabel = renderTextBlock(6);
		if (NetworkManager.isHost()) {
			statusLabel.text("Waiting for players...", (int) contentWidth);
		} else {
			statusLabel.text("Waiting for host to start...", (int) contentWidth);
		}
		statusLabel.setPos(x0, y);
		align(statusLabel);
		add(statusLabel);
		y += statusLabel.height() + 6f;

		// Start button — host only, disabled until >= 2 players
		if (NetworkManager.isHost()) {
			startBtn = new RedButton("Start Game") {
				@Override
				protected void onClick() {
					super.onClick();
					onStartTapped();
				}
			};
			startBtn.setRect(x0, y, contentWidth, 18f);
			startBtn.enable(false); // disabled until >= 2 players joined
			add(startBtn);
			y += 20f;

			// Cancel button — closes socket and returns to title
			RedButton cancelBtn = new RedButton("Cancel") {
				@Override
				protected void onClick() {
					super.onClick();
					active = false;
					ShatteredPixelDungeon.switchScene(StartScene.class);
				}
			};
			cancelBtn.setRect(x0, y, contentWidth, 18f);
			add(cancelBtn);

			// Register callback to be notified when clients connect (host path)
			NetworkManager.onPlayerJoined = (playerIndex, total) ->
					Game.runOnRenderThread(new Callback() {
						@Override
						public void call() {
							if (active) onPlayerJoined(playerIndex, total);
						}
					});

			// Start UDP discovery broadcast so clients can find this room
			NetworkManager.startDiscoveryBroadcast("Room", NetworkManager.getConnectedPlayerCount());
		} else {
			// Client mode: start background thread to listen for PLAYER_JOINED / START packets
			startClientListenerThread();
		}
	}

	/**
	 * Called when a PLAYER_JOINED packet is received (host receives via NetworkManager callback;
	 * client receives via its own listener thread).
	 * Updates the slot list display; enables the Start button when total >= 2.
	 *
	 * Flow: lanLobbySceneHost / lanLobbySceneClient
	 */
	public void onPlayerJoined(int playerIndex, int total) {
		// Refresh ALL slots up to total — the roster was fully populated by the packet,
		// including slot 0 (host) which may have been null when create() ran.
		for (int slot = 0; slot < total && slot < SLOT_COUNT; slot++) {
			String name = NetworkManager.getPlayerName(slot);
			if (name == null || name.isEmpty()) name = "Player " + (slot + 1);
			boolean isLocal = (slot == NetworkManager.localPlayerIndex);
			slotLabels[slot].text("Slot " + (slot + 1) + ": " + name + (isLocal ? " (You)" : ""), labelWidth);
			align(slotLabels[slot]);
		}

		// Enable Start on host when >= 2 players are present
		if (NetworkManager.isHost() && startBtn != null && total >= 2) {
			startBtn.enable(true);
		}
	}

	/**
	 * Called when the host taps the Start button.
	 * Generates a random seed, sends START packet via NetworkManager,
	 * resets GamesInProgress state, and switches to HeroSelectScene.
	 *
	 * Flow: lanLobbySceneHost
	 */
	public void onStartTapped() {
		active = false;
		NetworkManager.onPlayerJoined = null; // unregister callback

		long seed = com.shatteredpixel.shatteredpixeldungeon.utils.DungeonSeed.randomSeed();
		int playerCount = NetworkManager.getConnectedPlayerCount();
		GLog.p("LAN lobby: host tapping Start, playerCount=%d", playerCount);
		try {
			NetworkManager.sendStart(playerCount, seed);
		} catch (IOException e) {
			// If sending fails, re-enable Start so host can retry
			active = true;
			NetworkManager.onPlayerJoined = (pi, total) ->
					Game.runOnRenderThread(new Callback() {
						@Override
						public void call() {
							if (active) onPlayerJoined(pi, total);
						}
					});
			if (startBtn != null) startBtn.enable(true);
			return;
		}

		Dungeon.seed = seed;
		GamesInProgress.playerCount = 1;
		GamesInProgress.selectedClasses = new ArrayList<>();
		GamesInProgress.currentPlayerSelecting = 0;
		gameStarted = true;
		ShatteredPixelDungeon.switchScene(HeroSelectScene.class);
	}

	/**
	 * Called on the client when a START packet is received from the host.
	 * Stores seed and playerCount, resets GamesInProgress state, and switches to HeroSelectScene.
	 *
	 * Flow: lanLobbySceneClient
	 */
	public void onStartReceived(int playerCount, long seed) {
		active = false;
		Dungeon.seed = seed;
		GamesInProgress.playerCount = 1;
		GamesInProgress.selectedClasses = new ArrayList<>();
		GamesInProgress.currentPlayerSelecting = 0;
		gameStarted = true;
		ShatteredPixelDungeon.switchScene(HeroSelectScene.class);
	}

	/**
	 * Client mode: starts a background thread that reads PLAYER_JOINED and START packets
	 * from the host connection and dispatches them to the appropriate handlers on the
	 * render thread.
	 *
	 * Flow: lanLobbySceneClient
	 */
	private void startClientListenerThread() {
		new Thread(() -> {
			DataInputStream in = NetworkManager.getClientInput();
			if (in == null) {
				GLog.w("LAN lobby: clientIn is null, listener not started");
				return;
			}
			GLog.p("LAN lobby: client listener started");

			boolean myIndexFound = false;

			while (active && NetworkManager.lanMode) {
				try {
					byte type = in.readByte();
					GLog.p("LAN lobby: received packet type=%d", type);
					if (type == NetworkManager.PacketType.PLAYER_JOINED) {
						int playerIndex = in.readInt();
						int total       = in.readInt();
						int nameCount = in.readInt();
						for (int k = 0; k < nameCount; k++) {
							String name = in.readUTF();
							NetworkManager.setPlayerName(k, name);
						}
						if (!myIndexFound) {
							String newName = NetworkManager.getPlayerName(playerIndex);
							if (newName != null && newName.equals(NetworkManager.playerName)) {
								NetworkManager.localPlayerIndex = playerIndex;
								myIndexFound = true;
							}
						}
						Game.runOnRenderThread(new Callback() {
							@Override
							public void call() {
								if (active) onPlayerJoined(playerIndex, total);
							}
						});
					} else if (type == NetworkManager.PacketType.START) {
						int playerCount = in.readInt();
						long seed       = in.readLong();
						GLog.p("LAN lobby: START received, active=%b", active);
						Game.runOnRenderThread(new Callback() {
							@Override
							public void call() {
								GLog.p("LAN lobby: onStartReceived on render thread, active=%b", active);
								if (active) onStartReceived(playerCount, seed);
							}
						});
						break;
					} else {
						GLog.w("LAN lobby: unknown packet type=%d, aborting listener", type);
						break;
					}
				} catch (IOException e) {
					GLog.n("LAN lobby: IOException in listener: %s", e.getMessage());
					if (active) {
						Game.runOnRenderThread(new Callback() {
							@Override
							public void call() {
								if (active) statusLabel.text("Host disconnected: " + e.getMessage(), 200);
							}
						});
					}
					break;
				}
			}
			GLog.p("LAN lobby: client listener exited (active=%b lanMode=%b)", active, NetworkManager.lanMode);
		}, "lan-lobby-client-listener").start();
	}

	@Override
	public void destroy() {
		// Stop background threads and clear callbacks
		active = false;
		NetworkManager.onPlayerJoined = null;
		// If game hasn't started yet, fully close sockets so port 7777 is freed
		if (!gameStarted) {
			NetworkManager.disconnect();
		}
		super.destroy();
	}
}
