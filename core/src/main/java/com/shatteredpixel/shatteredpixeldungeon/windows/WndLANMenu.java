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

package com.shatteredpixel.shatteredpixeldungeon.windows;

import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.network.NetworkManager;
import com.shatteredpixel.shatteredpixeldungeon.scenes.LanLobbyScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.LanRoomListScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;

import java.io.IOException;

/**
 * WndLANMenu — choice dialog shown after tapping "LAN Game" in WndPlayerCount.
 * Presents "Host Room" and "Join Room" options.
 *
 * Flow: wndLANMenu
 */
public class WndLANMenu extends Window {

	private static final int WIDTH     = 120;
	private static final int BTN_HEIGHT = 20;
	private static final int GAP        = 2;
	private static final int TITLE_HEIGHT = 16;

	// Error label shown when hostGame() fails (lanMenu.hostFail path)
	private RenderedTextBlock errorLabel;

	public WndLANMenu() {
		super();

		RenderedTextBlock title = PixelScene.renderTextBlock("LAN Game", 12);
		title.hardlight(TITLE_COLOR);
		title.setPos(
				(WIDTH - title.width()) / 2f,
				(TITLE_HEIGHT - title.height()) / 2f
		);
		PixelScene.align(title);
		add(title);

		float pos = TITLE_HEIGHT;

		// "Host Room" button — lanMenu.host path
		RedButton btnHost = new RedButton("Host Room") {
			@Override
			protected void onClick() {
				super.onClick();
				onHostClicked();
			}
		};
		btnHost.setRect(0, pos, WIDTH, BTN_HEIGHT);
		add(btnHost);
		pos += BTN_HEIGHT + GAP;

		// "Join Room" button — lanMenu.join path
		RedButton btnJoin = new RedButton("Join Room") {
			@Override
			protected void onClick() {
				super.onClick();
				onJoinClicked();
			}
		};
		btnJoin.setRect(0, pos, WIDTH, BTN_HEIGHT);
		add(btnJoin);
		pos += BTN_HEIGHT + GAP;

		// Error label (hidden until an error occurs)
		errorLabel = PixelScene.renderTextBlock(6);
		errorLabel.text("", WIDTH);
		errorLabel.setPos(0, pos);
		add(errorLabel);

		resize(WIDTH, (int)(pos + errorLabel.height()));
	}

	/**
	 * Called when the player taps "Host Room".
	 * Calls NetworkManager.hostGame(7777), then transitions to LanLobbyScene.
	 * On IOException, shows an error toast and keeps the dialog open (lanMenu.hostFail path).
	 */
	protected void onHostClicked() {
		try {
			NetworkManager.hostGame(7777);
			hide();
			ShatteredPixelDungeon.switchScene(LanLobbyScene.class);
		} catch (IOException e) {
			showToast("Could not open room: " + e.getMessage());
		}
	}

	/**
	 * Called when the player taps "Join Room".
	 * Hides this dialog and transitions to LanRoomListScene (lanMenu.join path).
	 */
	protected void onJoinClicked() {
		hide();
		ShatteredPixelDungeon.switchScene(LanRoomListScene.class);
	}

	/**
	 * Displays an error message inside the dialog (lanMenu.hostFail path).
	 */
	protected void showToast(String message) {
		errorLabel.text(message, WIDTH);
		errorLabel.hardlight(0xFF4444);
		PixelScene.align(errorLabel);
		// Expand window height to fit error text
		resize(WIDTH, (int)(errorLabel.top() + errorLabel.height() + GAP));
	}
}
