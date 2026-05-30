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

import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.network.NetworkManager;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.watabou.noosa.Camera;
import com.watabou.noosa.Game;
import com.watabou.utils.Callback;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * LanRoomListScene — client-side room browser populated by UDP discovery broadcasts
 * from LAN hosts (flow: udpRoomDiscovery).
 *
 * Listens on UDP port 7778 for packets whose gameId == "SPD-MP". Each discovered
 * host is shown as a tappable row. Tapping a row calls NetworkManager.joinGame()
 * and transitions to LanLobbyScene. Rooms not heard from for > 6 s are removed.
 */
public class LanRoomListScene extends PixelScene {

	// Stale timeout: if no packet from a host for 6 s, remove its entry
	private static final long STALE_TIMEOUT_MS = 6000L;

	// Layout constants
	private static final float CONTENT_WIDTH_MAX = 200f;
	private static final float MARGIN = 6f;
	private static final float ROW_HEIGHT = 18f;
	private static final float ROW_GAP    = 2f;

	// UI fields
	private RenderedTextBlock statusLabel;
	private RenderedTextBlock errorLabel;

	// Room entries: key = hostIP, value = discovery packet + last-seen timestamp
	private final Map<String, RoomEntry> rooms = new HashMap<>();
	// Ordered list of tappable room buttons currently shown
	private final ArrayList<RedButton> roomButtons = new ArrayList<>();

	// Y position where room buttons start (after static header elements)
	private float roomListY;
	private float contentX;
	private float contentWidth;

	// Guards stale callbacks after the scene is destroyed
	private volatile boolean sceneActive = true;

	// Background UDP socket — closed in destroy()
	private DatagramSocket udpSocket;

	@Override
	public void create() {
		super.create();

		uiCamera.visible = false;

		int w = Camera.main.width;
		int h = Camera.main.height;

		contentWidth = Math.min(w - MARGIN * 2, CONTENT_WIDTH_MAX);
		contentX = (w - contentWidth) / 2f;
		float y = MARGIN;

		// Title
		RenderedTextBlock title = renderTextBlock("Join LAN Game", 12);
		title.hardlight(Window.TITLE_COLOR);
		title.setPos(contentX + (contentWidth - title.width()) / 2f, y);
		align(title);
		add(title);
		y += title.height() + 6f;

		// Status label ("Looking for rooms...")
		statusLabel = renderTextBlock(6);
		statusLabel.text("Looking for rooms...", (int) contentWidth);
		statusLabel.setPos(contentX, y);
		align(statusLabel);
		add(statusLabel);
		y += statusLabel.height() + 4f;

		// Reserve space for room rows — dynamic buttons added/removed in refreshRoomList()
		roomListY = y;

		// Error label (hidden until an error occurs)
		errorLabel = renderTextBlock(6);
		errorLabel.text("", (int) contentWidth);
		errorLabel.setPos(contentX, h - MARGIN - 20f - ROW_HEIGHT);
		errorLabel.hardlight(0xFF4444);
		add(errorLabel);

		// Back button
		RedButton btnBack = new RedButton("Back") {
			@Override
			protected void onClick() {
				super.onClick();
				sceneActive = false;
				ShatteredPixelDungeon.switchScene(com.shatteredpixel.shatteredpixeldungeon.scenes.StartScene.class);
			}
		};
		btnBack.setRect(contentX, h - MARGIN - ROW_HEIGHT, contentWidth / 2f, ROW_HEIGHT);
		add(btnBack);

		// Start the UDP listener thread on port 7778
		startUdpListener();
	}

	// -------------------------------------------------------------------------
	// UDP discovery listener
	// -------------------------------------------------------------------------

	/**
	 * Starts a background thread that listens on UDP port 7778 for discovery packets.
	 * Each valid "SPD-MP" packet triggers onPacketReceived() on the render thread.
	 * Stale rooms (> 6 s without a packet) are also pruned periodically.
	 *
	 * Flow: udpRoomDiscovery — udp.client-listen
	 */
	private void startUdpListener() {
		new Thread(() -> {
			try {
				udpSocket = new DatagramSocket(NetworkManager.UDP_DISCOVERY_PORT);
				udpSocket.setSoTimeout(1000); // 1-second read timeout so we can prune stale rooms

				byte[] buf = new byte[512];
				while (sceneActive) {
					DatagramPacket pkt = new DatagramPacket(buf, buf.length);
					try {
						udpSocket.receive(pkt);
						String payload = new String(pkt.getData(), 0, pkt.getLength(), StandardCharsets.UTF_8);
						UDPDiscoveryPacket parsed = UDPDiscoveryPacket.fromPayload(payload);
						if (parsed != null) {
							Game.runOnRenderThread(new Callback() {
								@Override
								public void call() {
									if (sceneActive) onPacketReceived(parsed);
								}
							});
						}
					} catch (SocketTimeoutException e) {
						// Normal — prune stale rooms on render thread
						Game.runOnRenderThread(new Callback() {
							@Override
							public void call() {
								if (sceneActive) pruneStaleRooms();
							}
						});
					}
				}
			} catch (IOException e) {
				if (sceneActive) {
					Game.runOnRenderThread(new Callback() {
						@Override
						public void call() {
							if (sceneActive) showToast("UDP error: " + e.getMessage());
						}
					});
				}
			}
		}, "udp-room-listener").start();
	}

	// -------------------------------------------------------------------------
	// Packet handling
	// -------------------------------------------------------------------------

	/**
	 * Called when a UDP datagram is received.
	 * Validates that packet.gameId == "SPD-MP", then adds or updates a room entry
	 * keyed by packet.hostIP and refreshes the list display.
	 *
	 * Flow: udpRoomDiscovery — udp.room-discovered
	 */
	public void onPacketReceived(UDPDiscoveryPacket packet) {
		if (!"SPD-MP".equals(packet.gameId)) return; // ignore packets from other games

		RoomEntry entry = rooms.get(packet.hostIP);
		if (entry == null) {
			entry = new RoomEntry();
			entry.packet = packet;
		} else {
			entry.packet = packet;
		}
		entry.lastSeenMs = System.currentTimeMillis();
		rooms.put(packet.hostIP, entry);

		refreshRoomList();
	}

	/**
	 * Removes rooms whose last-seen timestamp is older than STALE_TIMEOUT_MS.
	 *
	 * Flow: udpRoomDiscovery — udp.room-stale
	 */
	private void pruneStaleRooms() {
		long now = System.currentTimeMillis();
		boolean changed = false;
		Iterator<Map.Entry<String, RoomEntry>> it = rooms.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<String, RoomEntry> e = it.next();
			if (now - e.getValue().lastSeenMs > STALE_TIMEOUT_MS) {
				it.remove();
				changed = true;
			}
		}
		if (changed) refreshRoomList();
	}

	/**
	 * Rebuilds the visible room button list to match the current rooms map.
	 * Existing buttons are removed and new ones created for each entry.
	 */
	private void refreshRoomList() {
		// Remove old room buttons
		for (RedButton btn : roomButtons) {
			remove(btn);
		}
		roomButtons.clear();

		if (rooms.isEmpty()) {
			statusLabel.text("Looking for rooms...", (int) contentWidth);
		} else {
			statusLabel.text("Tap a room to join:", (int) contentWidth);
		}
		align(statusLabel);

		float y = roomListY + statusLabel.height() + 4f;

		for (Map.Entry<String, RoomEntry> e : rooms.entrySet()) {
			UDPDiscoveryPacket pkt = e.getValue().packet;
			final String hostIP = pkt.hostIP;

			String label = pkt.roomName + " (" + pkt.currentPlayers + "/" + pkt.maxPlayers + ")";
			RedButton btn = new RedButton(label) {
				@Override
				protected void onClick() {
					super.onClick();
					onRoomTapped(hostIP);
				}
			};
			btn.setRect(contentX, y, contentWidth, ROW_HEIGHT);
			add(btn);
			roomButtons.add(btn);
			y += ROW_HEIGHT + ROW_GAP;
		}
	}

	// -------------------------------------------------------------------------
	// Room tap / join
	// -------------------------------------------------------------------------

	/**
	 * Called when the player taps a room entry in the list.
	 * Calls NetworkManager.joinGame(hostIP, 7777); on success, switches to LanLobbyScene.
	 * On IOException, displays an error toast and remains on this scene.
	 *
	 * Flow: udpRoomDiscovery — udp.tap-join / udp.connect-fail
	 */
	public void onRoomTapped(String hostIP) {
		try {
			NetworkManager.joinGame(hostIP, 7777);
			sceneActive = false;
			ShatteredPixelDungeon.switchScene(LanLobbyScene.class);
		} catch (IOException e) {
			showToast("Could not connect: " + e.getMessage());
		}
	}

	/**
	 * Displays an error toast at the bottom of the scene.
	 *
	 * Flow: udpRoomDiscovery — udp.connect-fail
	 */
	protected void showToast(String message) {
		errorLabel.text(message, (int) contentWidth);
		align(errorLabel);
	}

	@Override
	public void destroy() {
		sceneActive = false;
		if (udpSocket != null && !udpSocket.isClosed()) {
			udpSocket.close();
		}
		super.destroy();
	}

	// -------------------------------------------------------------------------
	// Inner type: RoomEntry (internal bookkeeping)
	// -------------------------------------------------------------------------

	private static class RoomEntry {
		UDPDiscoveryPacket packet;
		long lastSeenMs;
	}

	// -------------------------------------------------------------------------
	// Inner type: UDPDiscoveryPacket
	// -------------------------------------------------------------------------

	/**
	 * Represents a UDP discovery broadcast packet sent by a LAN host every 2 s.
	 * Wire format (pipe-delimited UTF-8 string):
	 *   "SPD-MP|hostIP|tcpPort|roomName|currentPlayers|maxPlayers"
	 *
	 * Flow: udpRoomDiscovery
	 */
	public static class UDPDiscoveryPacket {

		/** Must equal "SPD-MP" for this game's packets. */
		public String gameId;

		/** IP address of the broadcasting host. */
		public String hostIP;

		/** TCP port the host is listening on (default 7777). */
		public int tcpPort;

		/** Human-readable room name (host device name or "Room"). */
		public String roomName;

		/** Number of players currently in the lobby. */
		public int currentPlayers;

		/** Maximum allowed players (default 4). */
		public int maxPlayers;

		public UDPDiscoveryPacket() {}

		/**
		 * Parses a pipe-delimited payload string into a UDPDiscoveryPacket.
		 * Returns null if the payload is malformed.
		 */
		public static UDPDiscoveryPacket fromPayload(String payload) {
			if (payload == null) return null;
			String[] parts = payload.split("\\|", -1);
			if (parts.length < 6) return null;
			try {
				UDPDiscoveryPacket pkt = new UDPDiscoveryPacket();
				pkt.gameId         = parts[0];
				pkt.hostIP         = parts[1];
				pkt.tcpPort        = Integer.parseInt(parts[2]);
				pkt.roomName       = parts[3];
				pkt.currentPlayers = Integer.parseInt(parts[4]);
				pkt.maxPlayers     = Integer.parseInt(parts[5]);
				return pkt;
			} catch (NumberFormatException e) {
				return null;
			}
		}
	}
}
