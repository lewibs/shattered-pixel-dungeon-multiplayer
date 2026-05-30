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

import com.shatteredpixel.shatteredpixeldungeon.Chrome;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.GamesInProgress;
import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.network.NetworkManager;
import com.shatteredpixel.shatteredpixeldungeon.scenes.LanLobbyScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.LanRoomListScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.watabou.noosa.TextInput;

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
	private static final int INPUT_HEIGHT = 20;

	// Error label shown when hostGame() fails (lanMenu.hostFail path)
	private RenderedTextBlock errorLabel;

	// Text input field for player name
	private TextInput nameInput;

	// Resume slot (non-zero if opened from a LAN save slot)
	private int resumeSlot = 0;

	public WndLANMenu() {
		this(0);
	}

	public WndLANMenu(int resumeSlot) {
		super();
		this.resumeSlot = resumeSlot;

		RenderedTextBlock title = PixelScene.renderTextBlock("LAN Game", 12);
		title.hardlight(TITLE_COLOR);
		title.setPos(
				(WIDTH - title.width()) / 2f,
				(TITLE_HEIGHT - title.height()) / 2f
		);
		PixelScene.align(title);
		add(title);

		float pos = TITLE_HEIGHT;

		// Name input field
		int textSize = (int)PixelScene.uiCamera.zoom * 9;
		nameInput = new TextInput(Chrome.get(Chrome.Type.TOAST_WHITE), false, textSize) {
			@Override
			public void enterPressed() {
				// Trigger host action on enter
				onHostClicked();
			}
		};
		nameInput.setRect(0, pos, WIDTH, INPUT_HEIGHT);
		add(nameInput);
		pos += INPUT_HEIGHT + GAP;

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
	 * If resumeSlot > 0: load the save, then start networking in resume mode.
	 * Otherwise: start networking fresh.
	 * Calls NetworkManager.hostGame(7777), then transitions to LanLobbyScene.
	 * On IOException, shows an error toast and keeps the dialog open (lanMenu.hostFail path).
	 */
	protected void onHostClicked() {
		try {
			// Read the player name from the input field
			String inputName = nameInput.getText().trim();
			String name = inputName.isEmpty() ? "Player" : inputName;
			NetworkManager.playerName = name;

			// If resuming, load the game first
			if (resumeSlot > 0) {
				Dungeon.loadGame(resumeSlot);
			}

			NetworkManager.hostGame(7777);
			hide();
			LanLobbyScene.resumeMode = (resumeSlot > 0);
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
		// Read the player name from the input field
		String inputName = nameInput.getText().trim();
		String name = inputName.isEmpty() ? "Player" : inputName;
		NetworkManager.playerName = name;

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
