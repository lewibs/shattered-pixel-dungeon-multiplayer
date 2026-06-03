/*
 * Pixel Dungeon
 * Copyright (C) 2012-2015 Evan Debenham
 *
 * Shattered Pixel Dungeon
 * Copyright (C) 2014-2025 Evan Debenham
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

package com.shatteredpixel.shatteredpixeldungeon.actors.buffs;

import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.watabou.utils.Bundle;

/**
 * Marks a hero as following another hero. Each turn the follower's act()
 * calls getCloser(target.pos) directly — the same mechanism the DriedRose
 * ghost uses in DirectableAlly.Wandering.act().
 */
public class FollowHeroBuff extends Buff {

	private int targetHeroId = -1;

	public void setTargetHeroId(int id) {
		targetHeroId = id;
	}

	public Hero getTargetHero() {
		if (Dungeon.heroes == null || targetHeroId < 0 || targetHeroId >= Dungeon.heroes.size()) {
			return null;
		}
		return Dungeon.heroes.get(targetHeroId);
	}

	@Override
	public boolean act() {
		// Passive — Hero.act() drives the movement each turn.
		spend(TICK);
		return true;
	}

	@Override
	public void storeInBundle(Bundle bundle) {
		super.storeInBundle(bundle);
		bundle.put("targetHeroId", targetHeroId);
	}

	@Override
	public void restoreFromBundle(Bundle bundle) {
		super.restoreFromBundle(bundle);
		targetHeroId = bundle.getInt("targetHeroId");
	}
}
