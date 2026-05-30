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
import com.watabou.input.PointerEvent;
import com.watabou.noosa.Game;
import com.watabou.noosa.TextInput;
import com.watabou.utils.DeviceCompat;

import java.io.IOException;

/**
 * WndLANMenu — choice dialog shown after tapping "LAN Game" in WndPlayerCount.
 * Presents a name input then "Host Room" / "Join Room" options.
 * Buttons are disabled until a name is entered.
 */
public class WndLANMenu extends Window {

	private static final int WIDTH        = 120;
	private static final int BTN_HEIGHT   = 18;
	private static final int INPUT_HEIGHT = 16;
	private static final int MARGIN       = 2;

	private TextInput nameInput;
	private RedButton btnHost;
	private RedButton btnJoin;
	private RenderedTextBlock errorLabel;
	private int resumeSlot;

	public WndLANMenu() { this(0); }

	public WndLANMenu(int resumeSlot) {
		super();
		this.resumeSlot = resumeSlot;

		float pos = MARGIN;

		// Title
		RenderedTextBlock title = PixelScene.renderTextBlock("LAN Game", 9);
		title.hardlight(TITLE_COLOR);
		title.setPos((WIDTH - title.width()) / 2f, pos);
		PixelScene.align(title);
		add(title);
		pos = title.bottom() + MARGIN * 2;

		// Prompt label
		RenderedTextBlock prompt = PixelScene.renderTextBlock("Your name:", 6);
		prompt.setPos(MARGIN, pos);
		PixelScene.align(prompt);
		add(prompt);
		pos = prompt.bottom() + MARGIN;

		// Name text input — same pattern as WndTextInput
		int textSize = (int) PixelScene.uiCamera.zoom * 9;
		nameInput = new TextInput(Chrome.get(Chrome.Type.TOAST_WHITE), false, textSize) {
			@Override
			public void onChanged() {
				super.onChanged();
				boolean hasName = !getText().trim().isEmpty();
				btnHost.enable(hasName);
				btnJoin.enable(hasName);
			}

			@Override
			public void enterPressed() {
				if (!getText().trim().isEmpty()) onHostClicked();
			}
		};
		add(nameInput);
		// placeholder — will be repositioned after resize()
		nameInput.setRect(MARGIN, pos, WIDTH - MARGIN * 2, INPUT_HEIGHT);
		pos += INPUT_HEIGHT + MARGIN * 2;

		// Host button — disabled until name entered
		btnHost = new RedButton("Host Room") {
			@Override protected void onClick() { super.onClick(); onHostClicked(); }
		};
		btnHost.setRect(MARGIN, pos, WIDTH - MARGIN * 2, BTN_HEIGHT);
		btnHost.enable(false);
		add(btnHost);
		pos += BTN_HEIGHT + MARGIN;

		// Join button — disabled until name entered
		btnJoin = new RedButton("Join Room") {
			@Override protected void onClick() { super.onClick(); onJoinClicked(); }
		};
		btnJoin.setRect(MARGIN, pos, WIDTH - MARGIN * 2, BTN_HEIGHT);
		btnJoin.enable(false);
		add(btnJoin);
		pos += BTN_HEIGHT + MARGIN;

		// Error label
		errorLabel = PixelScene.renderTextBlock(6);
		errorLabel.text("", WIDTH);
		errorLabel.setPos(MARGIN, pos);
		add(errorLabel);

		// resize() BEFORE final textInput layout — window camera must be set up first
		resize(WIDTH, (int)(pos + errorLabel.height() + MARGIN));

		// Reposition text input now that the camera is ready
		nameInput.setRect(MARGIN, nameInput.top(), nameInput.width(), INPUT_HEIGHT);

		// Push window up to make room for soft keyboard
		if (!DeviceCompat.hasHardKeyboard()) {
			offset(0, -(int)(Game.height / (4 * camera.zoom)));
			boundOffsetWithMargin(0);
		}

		PointerEvent.clearKeyboardThisPress = false;
	}

	@Override
	public void offset(int xOffset, int yOffset) {
		super.offset(xOffset, yOffset);
		if (nameInput != null) {
			nameInput.setRect(nameInput.left(), nameInput.top(), nameInput.width(), nameInput.height());
		}
	}

	protected void onHostClicked() {
		String name = nameInput.getText().trim();
		if (name.isEmpty()) return;
		NetworkManager.playerName = name;
		try {
			if (resumeSlot > 0) Dungeon.loadGame(resumeSlot);
			NetworkManager.hostGame(7777);
			hide();
			LanLobbyScene.resumeMode = (resumeSlot > 0);
			ShatteredPixelDungeon.switchScene(LanLobbyScene.class);
		} catch (IOException e) {
			showError("Could not open room: " + e.getMessage());
		}
	}

	protected void onJoinClicked() {
		String name = nameInput.getText().trim();
		if (name.isEmpty()) return;
		NetworkManager.playerName = name;
		hide();
		ShatteredPixelDungeon.switchScene(LanRoomListScene.class);
	}

	protected void showError(String message) {
		errorLabel.text(message, WIDTH);
		errorLabel.hardlight(0xFF4444);
		PixelScene.align(errorLabel);
		resize(WIDTH, (int)(errorLabel.top() + errorLabel.height() + MARGIN));
	}

	@Override
	public void onBackPressed() {
		// Prevent accidentally closing while typing
	}
}
