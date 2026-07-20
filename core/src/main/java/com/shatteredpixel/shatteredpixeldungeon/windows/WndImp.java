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
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.Imp;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.quest.DwarfToken;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSprite;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;

public class WndImp extends Window {
	
	private static final int WIDTH      = 120;
	private static final int BTN_HEIGHT = 20;
	private static final int GAP        = 2;

	com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero hero;

	//LAN: reward choice broadcast — see WndWandmaker
	private boolean lanChoiceSent = false;
	private void sendLanChoice(int idx){
		if (com.shatteredpixel.shatteredpixeldungeon.network.NetworkManager.lanMode && !lanChoiceSent){
			lanChoiceSent = true;
			com.shatteredpixel.shatteredpixeldungeon.network.NetworkManager.sendOptionChoice(idx);
		}
	}

	@Override
	public void hide() {
		sendLanChoice(-1);
		super.hide();
	}

	public WndImp( final Imp imp, final DwarfToken tokens,
	               final com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero hero ) {
		
		super();
		this.hero = hero;
		
		IconTitle titlebar = new IconTitle();
		titlebar.icon( new ItemSprite( tokens.image(), null ) );
		titlebar.label( Messages.titleCase( tokens.name() ) );
		titlebar.setRect( 0, 0, WIDTH, 0 );
		add( titlebar );
		
		RenderedTextBlock message = PixelScene.renderTextBlock( Messages.get(this, "message"), 6 );
		message.maxWidth(WIDTH);
		message.setPos(0, titlebar.bottom() + GAP);
		add( message );
		
		RedButton btnReward = new RedButton( Messages.get(this, "reward") ) {
			@Override
			protected void onClick() {
				takeReward( imp, tokens, Imp.Quest.reward );
			}
		};
		btnReward.setRect( 0, message.top() + message.height() + GAP, WIDTH, BTN_HEIGHT );
		add( btnReward );
		
		resize( WIDTH, (int)btnReward.bottom() );
	}
	
	private void takeReward( Imp imp, DwarfToken tokens, Item reward ) {

		sendLanChoice(0);
		hide();

		if (com.shatteredpixel.shatteredpixeldungeon.network.NetworkManager.lanMode) {
			com.shatteredpixel.shatteredpixeldungeon.network.NetworkManager.resolveLocalChoice(
					() -> performReward(hero, imp, 0));
		} else {
			performReward(hero, imp, 0);
		}
	}

	/** Applies the quest reward — identical on the choosing and remote devices. */
	public static void performReward( com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero hero,
	                                  Imp imp, int idx ) {
		if (idx != 0) return;

		DwarfToken tokens = hero.belongings.getItem( DwarfToken.class );
		if (tokens != null) tokens.detachAll( hero.belongings.backpack );

		Item reward = Imp.Quest.reward;
		if (reward == null) return;

		reward.identify(false);
		if (reward.doPickUp( hero )) {
			GLog.i( Messages.capitalize(Messages.get(hero, "you_now_have", reward.name())) );
		} else {
			Dungeon.level.dropAndShow( reward, imp.pos );
		}
		
		imp.flee();
		
		Imp.Quest.complete();
	}
}
