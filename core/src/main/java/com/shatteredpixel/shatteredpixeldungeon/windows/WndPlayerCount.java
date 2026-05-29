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

import com.shatteredpixel.shatteredpixeldungeon.GamesInProgress;
import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.scenes.HeroSelectScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.watabou.noosa.Game;

import java.util.ArrayList;

public class WndPlayerCount extends Window {

	private static final int WIDTH = 120;
	private static final int BTN_HEIGHT = 20;
	private static final int GAP = 2;
	private static final int TITLE_HEIGHT = 16;

	public WndPlayerCount() {
		super();

		RenderedTextBlock title = PixelScene.renderTextBlock(Messages.get(this, "title"), 12);
		title.hardlight(TITLE_COLOR);
		title.setPos(
				(WIDTH - title.width()) / 2,
				(TITLE_HEIGHT - title.height()) / 2
		);
		PixelScene.align(title);
		add(title);

		float pos = TITLE_HEIGHT;

		for (int count = 1; count <= 4; count++) {
			final int playerCount = count;
			String buttonText = playerCount == 1
					? Messages.get(this, "single_player", playerCount)
					: Messages.get(this, "multi_player", playerCount);

			RedButton btn = new RedButton(buttonText) {
				@Override
				protected void onClick() {
					super.onClick();
					GamesInProgress.playerCount = playerCount;
					GamesInProgress.selectedClasses = new ArrayList<>();
					GamesInProgress.currentPlayerSelecting = 0;
					hide();
					ShatteredPixelDungeon.switchScene(HeroSelectScene.class);
				}
			};

			btn.setRect(0, pos, WIDTH, BTN_HEIGHT);
			add(btn);

			pos += BTN_HEIGHT + GAP;
		}

		resize(WIDTH, (int)pos);
	}
}
