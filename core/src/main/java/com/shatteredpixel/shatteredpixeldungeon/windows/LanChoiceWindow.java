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

/**
 * A window whose outcome is one of a fixed, deterministically-ordered set of
 * choices, raised while a LAN-synced action executes. On the owning device the
 * window shows normally and the picked index is broadcast (OPTION_CHOICE); on
 * remote devices GameScene.show parks the window unseen and the packet resolves
 * it via {@link #selectLanChoice(int)}. The choice list must be derived from
 * synced simulation state only, so an index means the same thing on every device.
 */
public interface LanChoiceWindow {

	/** Apply choice {@code choice} exactly as if its button had been clicked (-1 = cancelled). */
	void selectLanChoice(int choice);
}
