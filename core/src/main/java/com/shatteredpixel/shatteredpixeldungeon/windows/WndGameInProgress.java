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
import com.shatteredpixel.shatteredpixeldungeon.GamesInProgress;
import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroSubClass;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.network.NetworkManager;
import com.shatteredpixel.shatteredpixeldungeon.scenes.InterlevelScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.LanLobbyScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.StartScene;
import com.shatteredpixel.shatteredpixeldungeon.sprites.HeroSprite;
import com.shatteredpixel.shatteredpixeldungeon.ui.ActionIndicator;
import com.shatteredpixel.shatteredpixeldungeon.ui.Icons;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.shatteredpixel.shatteredpixeldungeon.utils.DungeonSeed;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.watabou.noosa.Game;
import com.watabou.noosa.Image;

import java.util.Locale;

public class WndGameInProgress extends Window {
	
	private static final int WIDTH    = 120;
	
	private int GAP	  = 6;
	
	private float pos;
	
	public WndGameInProgress(final int slot){

		final GamesInProgress.Info info = GamesInProgress.check(slot);

		boolean isMultiplayer = info.heroClasses != null && info.heroClasses.size() > 1;

		if (isMultiplayer) {
			buildMultiplayerView(slot, info);
		} else {
			buildSinglePlayerView(slot, info);
		}
	}

	private void buildSinglePlayerView(final int slot, final GamesInProgress.Info info) {

		String className;
		if (info.subClass != HeroSubClass.NONE){
			className = info.subClass.title();
		} else {
			className = info.heroClass.title();
		}

		IconTitle title = new IconTitle();
		title.icon( HeroSprite.avatar(info.heroClass, info.armorTier) );
		title.label((Messages.get(this, "title", info.level, className)).toUpperCase(Locale.ENGLISH));
		title.color(Window.TITLE_COLOR);
		title.setRect( 0, 0, WIDTH, 0 );
		add(title);

		if (info.challenges > 0) GAP -= 2;

		pos = title.bottom() + GAP;

		if (info.challenges > 0) {
			RedButton btnChallenges = new RedButton( Messages.get(this, "challenges") ) {
				@Override
				protected void onClick() {
					Game.scene().add( new WndChallenges( info.challenges, false ) );
				}
			};
			btnChallenges.icon(Icons.get(Icons.CHALLENGE_COLOR));
			float btnW = btnChallenges.reqWidth() + 2;
			btnChallenges.setRect( (WIDTH - btnW)/2, pos, btnW , 18 );
			add( btnChallenges );

			pos = btnChallenges.bottom() + GAP;
		}

		pos += GAP;

		int strBonus = info.strBonus;
		if (strBonus > 0)           statSlot( Messages.get(this, "str"), info.str + " + " + strBonus );
		else if (strBonus < 0)      statSlot( Messages.get(this, "str"), info.str + " - " + -strBonus );
		else                        statSlot( Messages.get(this, "str"), info.str );
		if (info.shld > 0)  statSlot( Messages.get(this, "health"), info.hp + "+" + info.shld + "/" + info.ht );
		else                statSlot( Messages.get(this, "health"), (info.hp) + "/" + info.ht );
		statSlot( Messages.get(this, "exp"), info.exp + "/" + Hero.maxExp(info.level) );

		pos += GAP;
		statSlot( Messages.get(this, "gold"), info.goldCollected );
		statSlot( Messages.get(this, "depth"), info.maxDepth );
		if (info.daily) {
			if (info.dailyReplay) {
				statSlot(Messages.get(this, "replay_for"), "_" + info.customSeed + "_");
			} else {
				statSlot(Messages.get(this, "daily_for"), "_" + info.customSeed + "_");
			}
		} else if (!info.customSeed.isEmpty()){
			statSlot( Messages.get(this, "custom_seed"), "_" + info.customSeed + "_" );
		} else {
			statSlot( Messages.get(this, "dungeon_seed"), DungeonSeed.convertToCode(info.seed) );
		}

		pos += GAP;
		addContinueAndErase(slot, info);
	}

	private void buildMultiplayerView(final int slot, final GamesInProgress.Info info) {

		// Title: "N Players"
		IconTitle title = new IconTitle();
		title.icon( HeroSprite.avatar(info.heroClasses.get(0), info.armorTiers.get(0)) );
		title.label( Messages.get(this, "multiplayer_title", info.heroClasses.size()).toUpperCase(Locale.ENGLISH) );
		title.color(Window.TITLE_COLOR);
		title.setRect(0, 0, WIDTH, 0);
		add(title);

		pos = title.bottom() + GAP + GAP;

		// One row per hero: avatar on left, "Lvl N  ClassName" on right
		float rowH = 14f;
		for (int i = 0; i < info.heroClasses.size(); i++) {
			HeroClass cls = info.heroClasses.get(i);
			int tier      = info.armorTiers.get(i);
			int lvl       = info.heroLevels.get(i);

			Image avatar = HeroSprite.avatar(cls, tier);
			avatar.x = 2;
			avatar.y = pos + (rowH - avatar.height()) / 2f;
			PixelScene.align(avatar);
			add(avatar);

			RenderedTextBlock heroLabel = PixelScene.renderTextBlock(
					Messages.get(this, "hero_row", lvl, cls.title()), 7);
			heroLabel.setPos(avatar.x + avatar.width() + 4,
					pos + (rowH - heroLabel.height()) / 2f);
			PixelScene.align(heroLabel);
			add(heroLabel);

			pos += rowH + 2;
		}

		pos += GAP;

		// Cumulative gold across all heroes
		statSlot( Messages.get(this, "gold"), info.goldCollected );

		// Average hero level
		int totalLevels = 0;
		for (int lvl : info.heroLevels) totalLevels += lvl;
		int avgLevel = info.heroLevels.isEmpty() ? 1 : Math.round((float) totalLevels / info.heroLevels.size());
		statSlot( Messages.get(this, "avg_level"), avgLevel );

		statSlot( Messages.get(this, "depth"), info.maxDepth );

		// Dungeon seed
		if (!info.customSeed.isEmpty()) {
			statSlot( Messages.get(this, "custom_seed"), "_" + info.customSeed + "_" );
		} else {
			statSlot( Messages.get(this, "dungeon_seed"), DungeonSeed.convertToCode(info.seed) );
		}

		pos += GAP;
		addContinueAndErase(slot, info);
	}

	private void addContinueAndErase(final int slot, final GamesInProgress.Info info) {

		String buttonLabel = Messages.get(this, "continue");
		String buttonAction = "continue";

		// For LAN saves, show "Host" instead of "Continue"
		if (info.isMultiplayerSave) {
			buttonLabel = Messages.get(this, "host");
			buttonAction = "host";
		}

		RedButton cont = new RedButton(buttonLabel){
			@Override
			protected void onClick() {
				super.onClick();

				GamesInProgress.curSlot = slot;

				if (info.isMultiplayerSave) {
					// For LAN saves, host the game
					try {
						Dungeon.loadGame(slot);
						NetworkManager.hostGame(7777);
						hide();
						LanLobbyScene.resumeMode = true;
						ShatteredPixelDungeon.switchScene(LanLobbyScene.class);
					} catch (Exception e) {
						ShatteredPixelDungeon.reportException(e);
					}
				} else {
					// For single-player saves, continue the game normally
					Dungeon.hero = null;
					Dungeon.daily = Dungeon.dailyReplay = false;
					ActionIndicator.clearAction();
					InterlevelScene.mode = InterlevelScene.Mode.CONTINUE;
					ShatteredPixelDungeon.switchScene(InterlevelScene.class);
				}
			}
		};

		RedButton erase = new RedButton( Messages.get(this, "erase")){
			@Override
			protected void onClick() {
				super.onClick();

				ShatteredPixelDungeon.scene().add(new WndOptions(Icons.get(Icons.WARNING),
						Messages.get(WndGameInProgress.class, "erase_warn_title"),
						Messages.get(WndGameInProgress.class, "erase_warn_body"),
						Messages.get(WndGameInProgress.class, "erase_warn_yes"),
						Messages.get(WndGameInProgress.class, "erase_warn_no") ) {
					@Override
					protected void onSelect( int index ) {
						if (index == 0) {
							Dungeon.deleteGame(slot, true);
							ShatteredPixelDungeon.switchNoFade(StartScene.class);
						}
					}
				} );
			}
		};

		cont.icon(Icons.get(Icons.ENTER));
		cont.setRect(0, pos, WIDTH/2 - 1, 20);
		add(cont);

		erase.icon(Icons.get(Icons.CLOSE));
		erase.setRect(WIDTH/2 + 1, pos, WIDTH/2 - 1, 20);
		add(erase);

		resize(WIDTH, (int)cont.bottom() + 1);
	}
	
	private void statSlot( String label, String value ) {

		int size = 8;
		RenderedTextBlock txt;
		do {
			txt = PixelScene.renderTextBlock( label, size );
			size--;
		} while (txt.width() >= WIDTH * 0.55f);
		txt.setPos(0, pos + (6 - txt.height())/2);
		PixelScene.align(txt);
		add( txt );

		size = 8;
		do {
			txt = PixelScene.renderTextBlock( value, size );
			size--;
		} while (txt.width() >= WIDTH * 0.45f);
		txt.setPos(WIDTH * 0.55f, pos + (6 - txt.height())/2);
		PixelScene.align(txt);
		add( txt );
		
		pos += GAP + txt.height();
	}
	
	private void statSlot( String label, int value ) {
		statSlot( label, Integer.toString( value ) );
	}
}
