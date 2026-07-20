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

package com.shatteredpixel.shatteredpixeldungeon.actors.hero;

import com.shatteredpixel.shatteredpixeldungeon.actors.Char;

public class HeroAction {
	
	public int dst;
	
	public static class Move extends HeroAction {
		public Move( int dst ) {
			this.dst = dst;
		}
	}
	
	public static class PickUp extends HeroAction {
		public PickUp( int dst ) {
			this.dst = dst;
		}
	}
	
	public static class OpenChest extends HeroAction {
		public OpenChest( int dst ) {
			this.dst = dst;
		}
	}
	
	public static class Buy extends HeroAction {
		public Buy( int dst ) {
			this.dst = dst;
		}
	}
	
	public static class Interact extends HeroAction {
		public Char ch;
		public Interact( Char ch ) {
			this.ch = ch;
		}
	}
	
	public static class Unlock extends HeroAction {
		public Unlock( int door ) {
			this.dst = door;
		}
	}
	
	public static class LvlTransition extends HeroAction {
		public LvlTransition(int stairs ) {
			this.dst = stairs;
		}
	}

	public static class Mine extends HeroAction {
		public Mine( int wall ) {
			this.dst = wall;
		}
	}
	
	public static class Alchemy extends HeroAction {
		public Alchemy( int pot ) {
			this.dst = pot;
		}
	}
	
	public static class Attack extends HeroAction {
		public Char target;
		public Attack( Char target ) {
			this.target = target;
		}
	}

	/**
	 * Use an item from the hero's inventory. bagOrdinal and slotIndex identify
	 * the item's location within the hero's Belongings (0 = backpack, slot = index
	 * in bag.items). Packed into the wire-protocol targetPos as
	 * (bagOrdinal << 16) | slotIndex so it fits the existing 4-byte field.
	 */
	public static class UseItem extends HeroAction {
		public static final int DEFAULT_ACTION = 0xFF;

		public final int bagOrdinal;
		public final int slotIndex;
		//index into item.actions(hero), or DEFAULT_ACTION for the default verb —
		//the list is derived from synced state, so the index means the same thing
		//on every device
		public final int actionIdx;
		public UseItem(int bagOrdinal, int slotIndex) {
			this(bagOrdinal, slotIndex, DEFAULT_ACTION);
		}
		public UseItem(int bagOrdinal, int slotIndex, int actionIdx) {
			this.bagOrdinal = bagOrdinal;
			this.slotIndex  = slotIndex;
			this.actionIdx  = actionIdx;
		}
	}

	/**
	 * Use an item AT a target cell — wand zaps and thrown items. The verb says
	 * how the item is applied. Packed into the wire-protocol targetPos as
	 * (bagOrdinal &lt;&lt; 28) | (slotIndex &lt;&lt; 18) | (verb &lt;&lt; 16) | cell.
	 */
	public static class UseItemAt extends HeroAction {
		public static final int VERB_ZAP   = 0;
		public static final int VERB_THROW = 1;
		public static final int VERB_SHOOT = 2;

		public final int bagOrdinal;
		public final int slotIndex;
		public final int verb;
		public final int cell;
		public UseItemAt(int bagOrdinal, int slotIndex, int verb, int cell) {
			this.bagOrdinal = bagOrdinal;
			this.slotIndex  = slotIndex;
			this.verb       = verb;
			this.cell       = cell;
			this.dst        = cell;
		}
	}

	/** Buy the single for-sale item on the heap at dst. Spends no time. */
	public static class ShopBuy extends HeroAction {
		public ShopBuy(int heapPos) {
			this.dst = heapPos;
		}
	}

	/**
	 * Sell an inventory item to the shop (all == false sells one of a stack).
	 * Packed as (bagOrdinal &lt;&lt; 28) | (slotIndex &lt;&lt; 18) | (all ? 1 : 0). Spends no time.
	 */
	public static class ShopSell extends HeroAction {
		public final int bagOrdinal;
		public final int slotIndex;
		public final boolean all;
		public ShopSell(int bagOrdinal, int slotIndex, boolean all) {
			this.bagOrdinal = bagOrdinal;
			this.slotIndex  = slotIndex;
			this.all        = all;
		}
	}

	/**
	 * Wait in place for one turn (fullRest=false) or begin resting until
	 * interrupted (fullRest=true). Routed through the action queue in LAN games so
	 * the turn is transmitted to peers — waiting used to spend time locally with no
	 * packet, freezing the remote simulation. fullRest rides in the wire-protocol
	 * targetPos field (0 or 1).
	 */
	public static class Rest extends HeroAction {
		public final boolean fullRest;
		public Rest(boolean fullRest) {
			this.fullRest = fullRest;
			this.dst = fullRest ? 1 : 0;
		}
	}

	/**
	 * Intentionally search surrounding cells (spends time). Routed through the
	 * action queue in LAN games for the same reason as Rest.
	 */
	public static class Search extends HeroAction {
		public Search() {
			this.dst = 0;
		}
	}
}
