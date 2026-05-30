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

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.network.NetworkManager;
import com.shatteredpixel.shatteredpixeldungeon.scenes.LanLobbyScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.TitleScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;

import java.io.IOException;

/**
 * WndPeerDisconnected — window shown when a peer disconnects during gameplay.
 * Provides options to either rejoin the room or save and exit.
 */
public class WndPeerDisconnected extends Window {

	private static final int WIDTH = 120;
	private static final int GAP = 4;

	private Hero disconnectedHero;

	public WndPeerDisconnected(Hero hero) {
		super();
		this.disconnectedHero = hero;

		RenderedTextBlock title = PixelScene.renderTextBlock("Peer Disconnected", 12);
		title.hardlight(TITLE_COLOR);
		add(title);
		title.setPos(
				(WIDTH - title.width()) / 2f,
				(16 - title.height()) / 2f
		);
		PixelScene.align(title);

		float y = 20f;

		// Message
		String heroName = disconnectedHero.name() != null ? disconnectedHero.name() : "A player";
		String message = heroName + " disconnected.\nGame saved.";

		RenderedTextBlock msg = PixelScene.renderTextBlock(message, 6);
		msg.maxWidth(WIDTH - 8);
		msg.setPos(4, y);
		add(msg);
		y += msg.height() + GAP;

		// Open Rejoin Room button
		RedButton rejoinBtn = new RedButton("Open Rejoin Room") {
			@Override
			protected void onClick() {
				super.onClick();
				onRejoin();
			}
		};
		rejoinBtn.setRect(4, y, WIDTH - 8, 18f);
		add(rejoinBtn);
		y += 18f + GAP;

		// Save and Exit button
		RedButton exitBtn = new RedButton("Save and Exit") {
			@Override
			protected void onClick() {
				super.onClick();
				onQuit();
			}
		};
		exitBtn.setRect(4, y, WIDTH - 8, 18f);
		add(exitBtn);
		y += 18f + GAP;

		resize(WIDTH, (int)y);
	}

	private void onRejoin() {
		try {
			// Open rejoin room (host will re-open ServerSocket + broadcast)
			NetworkManager.openRejoinRoom();

			// Switch to LanLobbyScene in resume mode
			LanLobbyScene.resumeMode = true;
			ShatteredPixelDungeon.switchScene(LanLobbyScene.class);

			hide();
		} catch (IOException e) {
			GLog.n("Failed to open rejoin room: %s", e.getMessage());
		}
	}

	private void onQuit() {
		try {
			// Disconnect from network
			NetworkManager.disconnect();

			// Switch to title scene
			ShatteredPixelDungeon.switchScene(TitleScene.class);

			hide();
		} catch (Exception e) {
			GLog.n("Error during quit: %s", e.getMessage());
		}
	}
}
