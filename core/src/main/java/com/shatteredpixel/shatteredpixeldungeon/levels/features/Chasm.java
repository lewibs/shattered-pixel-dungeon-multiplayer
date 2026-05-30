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

package com.shatteredpixel.shatteredpixeldungeon.levels.features;

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.Badges;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Bleeding;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Buff;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.Cripple;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.mobs.Mob;
import com.shatteredpixel.shatteredpixeldungeon.effects.Speck;
import com.shatteredpixel.shatteredpixeldungeon.items.potions.elixirs.ElixirOfFeatherFall;
import com.shatteredpixel.shatteredpixeldungeon.journal.Notes;
import com.shatteredpixel.shatteredpixeldungeon.levels.Level;
import com.shatteredpixel.shatteredpixeldungeon.levels.RegularLevel;
import com.shatteredpixel.shatteredpixeldungeon.levels.rooms.special.WeakFloorRoom;
import com.shatteredpixel.shatteredpixeldungeon.levels.traps.Trap;
import com.shatteredpixel.shatteredpixeldungeon.messages.Messages;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.InterlevelScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.sprites.MobSprite;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.shatteredpixel.shatteredpixeldungeon.windows.WndOptions;
import com.watabou.noosa.Game;
import com.watabou.noosa.Image;
import com.watabou.noosa.audio.Sample;
import com.watabou.utils.Bundle;
import com.watabou.utils.Callback;
import com.watabou.utils.Random;

public class Chasm implements Hero.Doom {

	public static boolean jumpConfirmed = false;
	private static int heroPos;

	// Overridable in tests to intercept the scene switch without a running libGDX context.
	// Production code leaves this null.
	static Runnable sceneSwitchOverride = null;
	
	public static void heroJump( final Hero hero ) {
		heroPos = hero.pos;
		Game.runOnRenderThread(new Callback() {
			@Override
			public void call() {
				GameScene.show(
						new WndOptions( new Image(Dungeon.level.tilesTex(), 176, 16, 16, 16),
								Messages.get(Chasm.class, "chasm"),
								Messages.get(Chasm.class, "jump"),
								Messages.get(Chasm.class, "yes"),
								Messages.get(Chasm.class, "no") ) {

							private float elapsed = 0f;

							@Override
							public synchronized void update() {
								super.update();
								elapsed += Game.elapsed;
							}

							@Override
							public void hide() {
								if (elapsed > 0.2f){
									super.hide();
								}
							}

							@Override
							protected void onSelect( int index ) {
								if (index == 0 && elapsed > 0.2f) {
									if (Dungeon.hero.pos == heroPos) {
										jumpConfirmed = true;
										hero.resume();
									}
								}
							}
						}
				);
			}
		});
	}
	
	public static void heroFall( Hero hero, int pos ) {

		jumpConfirmed = false;

		if (Sample.INSTANCE != null) Sample.INSTANCE.play( Assets.Sounds.FALLING );

		if (!hero.isAlive()) {
			if (hero.sprite != null) hero.sprite.visible = false;
			return;
		}

		hero.interrupt();

		if (shouldWaitForParty(hero)) {
			boolean fallIntoPit = isFallIntoPit(pos);
			if (fallIntoPit) Notes.remove(Notes.Landmark.DISTANT_WELL);
			WaitingToFall w = Buff.affect(hero, WaitingToFall.class);
			w.fallIntoPit = fallIntoPit;
			// Move hero off the map so they don't block any cell or appear in sight checks.
			hero.pos = -1;
			if (hero.sprite != null) hero.sprite.visible = false;
			// Tell the remaining players what happened and what they need to do
			if (com.badlogic.gdx.Gdx.app != null) {
				GLog.w( Messages.get(Chasm.class, "waiting_to_fall",
						hero.heroClass.title()) );
			}
			return;
		}

		// Single player, or all other alive heroes are also waiting — fall now
		Level.beforeTransition();
		InterlevelScene.mode = InterlevelScene.Mode.FALL;
		InterlevelScene.fallIntoPit = isFallIntoPit(pos);
		if (InterlevelScene.fallIntoPit) Notes.remove(Notes.Landmark.DISTANT_WELL);
		if (sceneSwitchOverride != null) sceneSwitchOverride.run();
		else Game.switchScene( InterlevelScene.class );
	}

	// Package-private: true when at least one other alive, non-waiting hero exists (used in tests)
	static boolean shouldWaitForParty( Hero hero ) {
		if (Dungeon.heroes == null || Dungeon.heroes.size() <= 1) return false;
		for (Hero h : Dungeon.heroes) {
			if (h == hero) continue;
			if (!h.isAlive()) continue;
			if (h.buff(WaitingToFall.class) != null) continue;
			return true;
		}
		return false;
	}

	private static boolean isFallIntoPit( int pos ) {
		return Dungeon.level instanceof RegularLevel
				&& ((RegularLevel) Dungeon.level).room(pos) instanceof WeakFloorRoom;
	}

	@Override
	public void onDeath() {
		Badges.validateDeathFromFalling();

		Dungeon.fail( Chasm.class );
		GLog.n( Messages.get(Chasm.class, "ondeath") );
	}

	public static void heroLand( Hero hero ) {

		ElixirOfFeatherFall.FeatherBuff b = hero.buff(ElixirOfFeatherFall.FeatherBuff.class);
		
		if (b != null){
			hero.sprite.emitter().burst( Speck.factory( Speck.JET ), 20);
			b.processFall();
			return;
		}
		
		PixelScene.shake( 4, 1f );

		Dungeon.level.occupyCell(hero );
		Buff.prolong( hero, Cripple.class, Cripple.DURATION );

		//The lower the hero's HP, the more bleed and the less upfront damage.
		//Hero has a 50% chance to bleed out at 66% HP, and begins to risk instant-death at 25%
		Buff.affect( hero, Bleeding.class).set( Math.round(hero.HT / (6f + (6f*(hero.HP/(float)hero.HT)))), Chasm.class);
		hero.damage( Math.max( hero.HP / 2, Random.NormalIntRange( hero.HP / 2, hero.HT / 4 )), new Chasm() );
	}

	public static void mobFall( Mob mob ) {
		if (mob.isAlive()) {
			Buff.prolong(mob, Trap.HazardAssistTracker.class, Trap.HazardAssistTracker.DURATION);
			mob.die( Chasm.class );
		}
		
		if (mob.sprite != null) ((MobSprite)mob.sprite).fall();
	}
	
	public static class Falling extends Buff {

		{
			actPriority = VFX_PRIO;
		}

		@Override
		public boolean act() {
			heroLand((Hero) target);
			detach();
			return true;
		}
	}

	// Marks a hero who fell into a pit while other party members are still on this floor.
	// The scene switch is deferred until the rest of the party descends stairs (or also falls).
	// InterlevelScene.descend()/fall() detects this buff and places the hero at the pit landing
	// cell with a Chasm.Falling buff instead of at the stair entrance.
	public static class WaitingToFall extends Buff {

		public boolean fallIntoPit = false;

		{ actPriority = VFX_PRIO; }

		@Override
		public boolean act() {
			// Re-enforce the non-existent state every tick so it survives save/load.
			Hero h = (Hero) target;
			h.pos = -1;
			if (h.sprite != null) h.sprite.visible = false;
			spend(TICK);
			return true;
		}

		private static final String FALL_INTO_PIT = "fallIntoPit";

		@Override
		public void storeInBundle( Bundle bundle ) {
			super.storeInBundle(bundle);
			bundle.put(FALL_INTO_PIT, fallIntoPit);
		}

		@Override
		public void restoreFromBundle( Bundle bundle ) {
			super.restoreFromBundle(bundle);
			fallIntoPit = bundle.getBoolean(FALL_INTO_PIT);
		}
	}

}
