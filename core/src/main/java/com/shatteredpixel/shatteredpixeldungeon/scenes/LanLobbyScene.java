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

	// Whether this scene is still active (guards against stale background-thread callbacks)
	private volatile boolean active = true;

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
				slotLabels[i].text(NetworkManager.isHost() ? "Slot 1: Host (You)" : "Slot 1: Host", (int) contentWidth);
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
		// Update the slot label for this player (playerIndex is 0-based; 0 = host)
		int slot = playerIndex; // slot 0 = host already filled
		if (slot > 0 && slot < SLOT_COUNT) {
			slotLabels[slot].text("Slot " + (slot + 1) + ": Player " + (slot + 1),
					(int) slotLabels[slot].width());
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

		long seed = new Random().nextLong();
		int playerCount = NetworkManager.getConnectedPlayerCount();
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
			if (in == null) return;

			while (active && NetworkManager.lanMode) {
				try {
					byte type = in.readByte();
					if (type == NetworkManager.PacketType.PLAYER_JOINED) {
						int playerIndex = in.readInt();
						int total       = in.readInt();
						Game.runOnRenderThread(new Callback() {
							@Override
							public void call() {
								if (active) onPlayerJoined(playerIndex, total);
							}
						});
					} else if (type == NetworkManager.PacketType.START) {
						int playerCount = in.readInt();
						long seed       = in.readLong();
						Game.runOnRenderThread(new Callback() {
							@Override
							public void call() {
								if (active) onStartReceived(playerCount, seed);
							}
						});
						break; // done listening once START is received
					}
				} catch (IOException e) {
					if (active) {
						Game.runOnRenderThread(new Callback() {
							@Override
							public void call() {
								if (active) statusLabel.text("Host disconnected", 200);
							}
						});
					}
					break;
				}
			}
		}, "lan-lobby-client-listener").start();
	}

	@Override
	public void destroy() {
		// Stop background threads and clear callbacks
		active = false;
		NetworkManager.onPlayerJoined = null;
		super.destroy();
	}
}
