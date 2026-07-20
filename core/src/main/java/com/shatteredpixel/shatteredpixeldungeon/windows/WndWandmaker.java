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
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.npcs.Wandmaker;
import com.shatteredpixel.shatteredpixeldungeon.items.Item;
import com.shatteredpixel.shatteredpixeldungeon.items.quest.CorpseDust;
import com.shatteredpixel.shatteredpixeldungeon.items.quest.Embers;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.plants.Rotberry;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.sprites.ItemSprite;
import com.shatteredpixel.shatteredpixeldungeon.ui.ItemButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;

public class WndWandmaker extends Window {

	private static final int WIDTH		= 120;
	private static final int BTN_SIZE	= 32;
	private static final int BTN_GAP	= 5;
	private static final int GAP		= 2;

	Wandmaker wandmaker;
	Item questItem;
	com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero hero;

	//LAN: reward choices are broadcast (OPTION_CHOICE) so the remote copy of this
	//quest resolves identically; -1 releases the remote handler on dismissal
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

	public WndWandmaker( final Wandmaker wandmaker, final Item item,
	                     final com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero hero ) {
		
		super();

		this.wandmaker = wandmaker;
		this.questItem = item;
		this.hero = hero;
		
		IconTitle titlebar = new IconTitle();
		titlebar.icon(new ItemSprite(item.image(), null));
		titlebar.label(Messages.titleCase(item.name()));
		titlebar.setRect(0, 0, WIDTH, 0);
		add( titlebar );

		String msg = "";
		if (item instanceof CorpseDust){
			msg = Messages.get(this, "dust");
		} else if (item instanceof Embers){
			msg = Messages.get(this, "ember");
		} else if (item instanceof Rotberry.Seed){
			msg = Messages.get(this, "berry");
		}

		RenderedTextBlock message = PixelScene.renderTextBlock( msg, 6 );
		message.maxWidth(WIDTH);
		message.setPos(0, titlebar.bottom() + GAP);
		add( message );

		ItemButton btnWand1 = new ItemButton(){
			@Override
			protected void onClick() {
				if (hero.belongings.contains(questItem) && item() != null) {
					GameScene.show(new RewardWindow(item()));
				} else {
					hide();
				}
			}
		};
		btnWand1.item(Wandmaker.Quest.wand1);
		btnWand1.setRect( (WIDTH - BTN_GAP) / 2 - BTN_SIZE, message.top() + message.height() + BTN_GAP, BTN_SIZE, BTN_SIZE );
		add( btnWand1 );
		
		ItemButton btnWand2 = new ItemButton(){
			@Override
			protected void onClick() {
				if (hero.belongings.contains(questItem) && item() != null) {
					GameScene.show(new RewardWindow(item()));
				} else {
					hide();
				}
			}
		};
		btnWand2.item(Wandmaker.Quest.wand2);
		btnWand2.setRect( btnWand1.right() + BTN_GAP, btnWand1.top(), BTN_SIZE, BTN_SIZE );
		add(btnWand2);
		
		resize(WIDTH, (int) btnWand2.bottom());
	}
	
	private void selectReward( Item reward ) {

		if (reward == null){
			return;
		}

		int idx = reward == Wandmaker.Quest.wand1 ? 0 : 1;
		sendLanChoice(idx);
		hide();

		if (com.shatteredpixel.shatteredpixeldungeon.network.NetworkManager.lanMode) {
			com.shatteredpixel.shatteredpixeldungeon.network.NetworkManager.resolveLocalChoice(
					() -> performReward(hero, wandmaker, questItem, idx));
		} else {
			performReward(hero, wandmaker, questItem, idx);
		}
	}

	/** Applies the quest reward — identical on the choosing and remote devices. */
	public static void performReward( com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero hero,
	                                  Wandmaker wandmaker, Item questItem, int idx ) {
		Item reward = idx == 0 ? Wandmaker.Quest.wand1 : idx == 1 ? Wandmaker.Quest.wand2 : null;
		if (reward == null){
			return;
		}

		questItem.detach( hero.belongings.backpack );

		reward.identify(false);
		if (reward.doPickUp( hero )) {
			GLog.i( Messages.capitalize(Messages.get(hero, "you_now_have", reward.name())) );
		} else {
			Dungeon.level.dropAndShow( reward, wandmaker.pos );
		}

		wandmaker.yell( Messages.get(WndWandmaker.class, "farewell", Messages.titleCase(hero.name())) );
		wandmaker.destroy();

		if (wandmaker.sprite != null) wandmaker.sprite.die();

		Wandmaker.Quest.complete();
	}

	private class RewardWindow extends WndInfoItem {

		public RewardWindow( Item item ) {
			super(item);

			RedButton btnConfirm = new RedButton(Messages.get(WndSadGhost.class, "confirm")){
				@Override
				protected void onClick() {
					RewardWindow.this.hide();

					selectReward( item );
				}
			};
			btnConfirm.setRect(0, height+2, width/2-1, 16);
			add(btnConfirm);

			RedButton btnCancel = new RedButton(Messages.get(WndSadGhost.class, "cancel")){
				@Override
				protected void onClick() {
					hide();
				}
			};
			btnCancel.setRect(btnConfirm.right()+2, height+2, btnConfirm.width(), 16);
			add(btnCancel);

			resize(width, (int)btnCancel.bottom());
		}
	}

}
