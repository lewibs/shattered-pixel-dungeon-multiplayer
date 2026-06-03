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
import com.shatteredpixel.shatteredpixeldungeon.scenes.TitleScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;

/**
 * WndHostDisconnected — shown on the client side when the host disconnects mid-game.
 * Clients cannot save; they can only exit.
 * Flow: midGameDisconnectHandling (EC-3, EC-5.1)
 */
public class WndHostDisconnected extends Window {

	private static final int WIDTH = 120;
	private static final int GAP = 4;

	public WndHostDisconnected() {
		super();

		RenderedTextBlock title = PixelScene.renderTextBlock("Host Disconnected", 12);
		title.hardlight(TITLE_COLOR);
		add(title);
		title.setPos(
				(WIDTH - title.width()) / 2f,
				(16 - title.height()) / 2f
		);
		PixelScene.align(title);

		float y = 20f;

		// Message: client cannot save, host has the save authority
		RenderedTextBlock msg = PixelScene.renderTextBlock(
				"The host disconnected.\nGame progress is saved on the host's device.", 6);
		msg.maxWidth(WIDTH - 8);
		msg.setPos(4, y);
		add(msg);
		y += msg.height() + GAP;

		// Exit button (no save for clients)
		RedButton exitBtn = new RedButton("Exit") {
			@Override
			protected void onClick() {
				super.onClick();
				onExit();
			}
		};
		exitBtn.setRect(4, y, WIDTH - 8, 18f);
		add(exitBtn);
		y += 18f + GAP;

		resize(WIDTH, (int)y);
	}

	private void onExit() {
		try {
			NetworkManager.disconnect();
			ShatteredPixelDungeon.switchScene(TitleScene.class);
			hide();
		} catch (Exception e) {
			GLog.n("Error during exit: %s", e.getMessage());
		}
	}
}
