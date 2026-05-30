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

import com.shatteredpixel.shatteredpixeldungeon.Badges;
import com.shatteredpixel.shatteredpixeldungeon.Chrome;
import com.shatteredpixel.shatteredpixeldungeon.GamesInProgress;
import com.shatteredpixel.shatteredpixeldungeon.SPDSettings;
import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroSubClass;
import com.shatteredpixel.shatteredpixeldungeon.journal.Journal;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.ui.Button;
import com.shatteredpixel.shatteredpixeldungeon.ui.ExitButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.Icons;
import com.shatteredpixel.shatteredpixeldungeon.ui.TitleBackground;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.shatteredpixel.shatteredpixeldungeon.ui.StyledButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.shatteredpixel.shatteredpixeldungeon.windows.IconTitle;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndGameInProgress;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndLANMenu;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndPlayerCount;
import com.watabou.noosa.BitmapText;
import com.watabou.noosa.Camera;
import com.watabou.noosa.Game;
import com.watabou.noosa.Image;
import com.watabou.noosa.NinePatch;
import com.watabou.utils.RectF;

import java.util.ArrayList;

public class StartScene extends PixelScene {
	
	private static final int SLOT_WIDTH = 120;
	private static final int SLOT_HEIGHT = 22;
	
	@Override
	public void create() {
		super.create();
		
		Badges.loadGlobal();
		Journal.loadGlobal();
		
		uiCamera.visible = false;

		int w = Camera.main.width;
		int h = Camera.main.height;
		RectF insets = getCommonInsets();

		TitleBackground BG = new TitleBackground(w, h);
		add( BG );

		w -= insets.left + insets.right;
		h -= insets.top + insets.bottom;
		
		ExitButton btnExit = new ExitButton();
		btnExit.setPos( insets.left + w - btnExit.width(), insets.top );
		add( btnExit );
		
		IconTitle title = new IconTitle( Icons.ENTER.get(), Messages.get(this, "title"));
		title.setSize(200, 0);
		title.setPos(
				insets.left + (w - title.reqWidth()) / 2f,
				insets.top + (20 - title.height()) / 2f
		);
		align(title);
		add(title);
		
		ArrayList<GamesInProgress.Info> games = GamesInProgress.checkAll();
		
		int slotCount = Math.min(GamesInProgress.MAX_SLOTS, games.size()+1);
		int slotGap = 10 - slotCount;
		int slotsHeight = slotCount*SLOT_HEIGHT + (slotCount-1)* slotGap;
		slotsHeight += 14;

		while (slotGap >= 2 && slotsHeight > (h-title.bottom()-2)){
			slotGap--;
			slotsHeight -= slotCount-1;
		}
		
		float yPos = insets.top + (h - slotsHeight + title.bottom() + 2)/2f - 4;
		yPos = Math.max(yPos, title.bottom()+2);
		float slotLeft = insets.left + (w - SLOT_WIDTH) / 2f;
		
		for (GamesInProgress.Info game : games) {
			SaveSlotButton existingGame = new SaveSlotButton();
			existingGame.set(game.slot);
			existingGame.setRect(slotLeft, yPos, SLOT_WIDTH, SLOT_HEIGHT);
			yPos += SLOT_HEIGHT + slotGap;
			align(existingGame);
			add(existingGame);
			
		}
		
		if (games.size() < GamesInProgress.MAX_SLOTS){
			SaveSlotButton newGame = new SaveSlotButton();
			newGame.set(GamesInProgress.firstEmpty());
			newGame.setRect(slotLeft, yPos, SLOT_WIDTH, SLOT_HEIGHT);
			yPos += SLOT_HEIGHT + slotGap;
			align(newGame);
			add(newGame);
		}
		
		GamesInProgress.curSlot = 0;

		String sortText = "";
		switch (SPDSettings.gamesInProgressSort()){
			case "level":
				sortText = Messages.get(this, "sort_level");
				break;
			case "last_played":
				sortText = Messages.get(this, "sort_recent");
				break;
		}

		StyledButton btnSort = new StyledButton(Chrome.Type.TOAST_TR, sortText, 6){
			@Override
			protected void onClick() {
				super.onClick();

				if (SPDSettings.gamesInProgressSort().equals("level")){
					SPDSettings.gamesInProgressSort("last_played");
				} else {
					SPDSettings.gamesInProgressSort("level");
				}

				ShatteredPixelDungeon.seamlessResetScene();
			}
		};
		btnSort.textColor(0xCCCCCC);

		if (yPos + 10 > Camera.main.height) {
			btnSort.setRect(slotLeft - btnSort.reqWidth() - 6, Camera.main.height - 14, btnSort.reqWidth() + 4, 12);
		} else {
			btnSort.setRect(slotLeft, yPos, btnSort.reqWidth() + 4, 12);
		}
		if (games.size() >= 2) add(btnSort);

		fadeIn();
		
	}

	@Override
	protected void onBackPressed() {
		ShatteredPixelDungeon.switchNoFade( TitleScene.class );
	}
	
	private static class SaveSlotButton extends Button {

		private NinePatch bg;

		private final ArrayList<Image> heroImages   = new ArrayList<>();
		private RenderedTextBlock name;
		private RenderedTextBlock lastPlayed;
		private RenderedTextBlock lanBadge;

		private Image     steps;
		private BitmapText depth;
		private final ArrayList<Image>      classIcons  = new ArrayList<>();
		private final ArrayList<BitmapText> levelTexts  = new ArrayList<>();

		private int slot;
		private boolean newGame;
		private int heroCount;

		@Override
		protected void createChildren() {
			super.createChildren();
			bg = Chrome.get(Chrome.Type.TOAST_TR);
			add(bg);
			name = PixelScene.renderTextBlock(9);
			add(name);
			lastPlayed = PixelScene.renderTextBlock(6);
			add(lastPlayed);
			lanBadge = PixelScene.renderTextBlock(6);
			add(lanBadge);
		}

		public void set(int slot) {
			this.slot = slot;
			GamesInProgress.Info info = GamesInProgress.check(slot);
			newGame = info == null;

			for (Image img : heroImages)   remove(img);  heroImages.clear();
			for (Image img : classIcons)   remove(img);  classIcons.clear();
			for (BitmapText t : levelTexts) remove(t);   levelTexts.clear();
			if (steps != null) { remove(steps); steps = null; }
			if (depth != null) { remove(depth); depth = null; }

			if (newGame) {
				heroCount = 0;
				name.text(Messages.get(StartScene.class, "new"));
			} else {
				heroCount = info.heroClasses.size();

				if (heroCount <= 1) {
					if (info.subClass != HeroSubClass.NONE) name.text(Messages.titleCase(info.subClass.title()));
					else                                     name.text(Messages.titleCase(info.heroClass.title()));
				} else {
					name.text(heroCount + " Players");
				}

				for (int i = 0; i < heroCount; i++) {
					HeroClass cls  = info.heroClasses.get(i);
					int       tier = info.armorTiers.get(i);
					Image sprite = new Image(cls.spritesheet(), 0, 15 * tier, 12, 15);
					add(sprite);
					heroImages.add(sprite);

					Image icon = new Image(Icons.get(cls));
					add(icon);
					classIcons.add(icon);

					BitmapText lvl = new BitmapText(PixelScene.pixelFont);
					if (i < info.heroLevels.size()) lvl.text(Integer.toString(info.heroLevels.get(i)));
					lvl.measure();
					add(lvl);
					levelTexts.add(lvl);
				}

				steps = new Image(Icons.get(Icons.STAIRS));
				add(steps);
				depth = new BitmapText(PixelScene.pixelFont);
				add(depth);

				long diff = Game.realTime - info.lastPlayed;
				if      (diff > 99L * 30 * 24 * 60 * 60_000) lastPlayed.text(" ");
				else if (diff < 60_000)                        lastPlayed.text(Messages.get(StartScene.class, "one_minute_ago"));
				else if (diff < 2 * 60 * 60_000)              lastPlayed.text(Messages.get(StartScene.class, "minutes_ago", diff / 60_000));
				else if (diff < 2 * 24 * 60 * 60_000)         lastPlayed.text(Messages.get(StartScene.class, "hours_ago",   diff / (60 * 60_000)));
				else if (diff < 2L * 30 * 24 * 60 * 60_000)  lastPlayed.text(Messages.get(StartScene.class, "days_ago",    diff / (24 * 60 * 60_000)));
				else                                           lastPlayed.text(Messages.get(StartScene.class, "months_ago", diff / (30L * 24 * 60 * 60_000)));

				depth.text(Integer.toString(info.depth));
				depth.measure();

				boolean challenged = info.challenges > 0;
				name.resetColor();       lastPlayed.resetColor();  depth.resetColor();
				for (BitmapText t : levelTexts) t.resetColor();
				if (challenged) {
					name.hardlight(Window.TITLE_COLOR);
					lastPlayed.hardlight(Window.TITLE_COLOR);
					depth.hardlight(Window.TITLE_COLOR);
					for (BitmapText t : levelTexts) t.hardlight(Window.TITLE_COLOR);
				}

				if      (info.daily && info.dailyReplay) steps.hardlight(1f, 0.5f, 2f);
				else if (info.daily)                     steps.hardlight(0.5f, 1f, 2f);
				else if (!info.customSeed.isEmpty())     steps.hardlight(1f, 1.5f, 0.67f);

				// Show LAN badge if this is a multiplayer save
				if (info.isMultiplayerSave) {
					lanBadge.text("LAN");
					lanBadge.hardlight(0x88CCFF);
				} else {
					lanBadge.text("");
				}
			}

			layout();
		}

		@Override
		protected void layout() {
			super.layout();
			bg.x = x;  bg.y = y;
			bg.size(width, height);

			if (!heroImages.isEmpty()) {
				// grid dimensions: 1→1×1, 2→2×1, 3-4→2×2
				int cols = heroCount == 1 ? 1 : 2;
				int rows = (heroCount + cols - 1) / cols;
				float spriteScale = heroCount == 1 ? 1f : (heroCount == 2 ? 0.75f : 0.6f);
				float sw = 12 * spriteScale;
				float sh = 15 * spriteScale;
				float leftZoneW = cols * sw + (cols - 1);
				float leftZoneH = rows * sh + (rows - 1);
				float leftX = x + 4;
				float leftY = y + (height - leftZoneH) / 2f;

				for (int i = 0; i < heroImages.size(); i++) {
					Image img = heroImages.get(i);
					img.scale.set(spriteScale);
					img.x = leftX + (i % cols) * (sw + 1);
					img.y = leftY + (i / cols) * (sh + 1);
					align(img);
				}

				// right zone: steps icon then class-icon grid
				float iconScale = heroCount == 1 ? 1f : (heroCount == 2 ? 0.75f : 0.6f);
				float iw = 16 * iconScale;
				float ih = 16 * iconScale;
				int   icols = heroCount == 1 ? 1 : 2;
				int   irows = (heroCount + icols - 1) / icols;
				float izoneW = icols * iw + (icols - 1);
				float izoneH = irows * ih + (irows - 1);
				float izoneX = x + width - 4 - izoneW;
				float izoneY = y + (height - izoneH) / 2f;

				for (int i = 0; i < classIcons.size(); i++) {
					Image icon = classIcons.get(i);
					icon.scale.set(iconScale);
					float ix = izoneX + (i % icols) * (iw + 1);
					float iy = izoneY + (i / icols) * (ih + 1);
					icon.x = ix;  icon.y = iy;
					align(icon);

					BitmapText lvl = levelTexts.get(i);
					lvl.x = ix + (iw - lvl.width()) / 2f;
					lvl.y = iy + (ih - lvl.height()) / 2f + 1;
					align(lvl);
				}

				steps.x = izoneX - 18 + (16 - steps.width()) / 2f;
				steps.y = y + (height - steps.height()) / 2f;
				align(steps);
				depth.x = steps.x + (steps.width() - depth.width()) / 2f;
				depth.y = steps.y + (steps.height() - depth.height()) / 2f + 1;
				align(depth);

				float nameX = leftX + leftZoneW + 4;
				float nameRight = steps.x - 2;
				name.setPos(nameX, y + (height - name.height() - lastPlayed.height() - 2) / 2f);
				align(name);
				lastPlayed.setPos(nameX, name.bottom() + 2);

			} else {
				name.setPos(x + (width - name.width()) / 2f, y + (height - name.height()) / 2f);
				align(name);
			}

			// Position LAN badge in top-right corner if present
			if (!lanBadge.text().isEmpty()) {
				lanBadge.setPos(x + width - 4 - lanBadge.width(), y + 2);
				align(lanBadge);
			}
		}

		@Override
		protected void onClick() {
			GamesInProgress.Info info = GamesInProgress.check(slot);
			if (newGame) {
				GamesInProgress.selectedClass = null;
				GamesInProgress.curSlot = slot;
				ShatteredPixelDungeon.scene().add(new WndPlayerCount());
			} else if (info != null && info.isMultiplayerSave) {
				// For LAN saves, show WndLANMenu with resume option
				GamesInProgress.curSlot = slot;
				ShatteredPixelDungeon.scene().addToFront(new WndLANMenu(slot));
			} else {
				GamesInProgress.curSlot = slot;
				ShatteredPixelDungeon.scene().add(new WndGameInProgress(slot));
			}
		}
	}
}
