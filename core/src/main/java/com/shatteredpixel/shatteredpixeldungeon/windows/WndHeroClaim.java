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
import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.network.NetworkManager;
import com.shatteredpixel.shatteredpixeldungeon.scenes.InterlevelScene;
import com.shatteredpixel.shatteredpixeldungeon.scenes.PixelScene;
import com.shatteredpixel.shatteredpixeldungeon.ui.RedButton;
import com.shatteredpixel.shatteredpixeldungeon.ui.RenderedTextBlock;
import com.shatteredpixel.shatteredpixeldungeon.ui.Window;
import com.shatteredpixel.shatteredpixeldungeon.utils.GLog;
import com.watabou.utils.Bundle;
import com.watabou.utils.FileUtils;

import java.io.IOException;

/**
 * WndHeroClaim — window shown to players in resume mode to select their hero.
 * Displays hero cards and allows player to tap one, then sends HERO_CLAIM to host.
 */
public class WndHeroClaim extends Window {

	private static final int WIDTH = 120;
	private static final int GAP = 2;

	private String[] heroNames;
	private HeroClass[] heroClasses;
	private int[] heroHP;
	private int selectedIndex = -1;
	private RedButton claimBtn;
	private RenderedTextBlock errorMsg;

	public WndHeroClaim(String[] heroNames, HeroClass[] heroClasses, int[] heroHP) {
		super();
		this.heroNames = heroNames;
		this.heroClasses = heroClasses;
		this.heroHP = heroHP;

		RenderedTextBlock title = PixelScene.renderTextBlock("Select Hero", 12);
		title.hardlight(TITLE_COLOR);
		add(title);
		title.setPos(
				(WIDTH - title.width()) / 2f,
				(16 - title.height()) / 2f
		);
		PixelScene.align(title);

		float y = 20f;

		// Create hero selection buttons
		for (int i = 0; i < heroNames.length; i++) {
			int idx = i;
			RenderedTextBlock heroText = PixelScene.renderTextBlock(
					heroNames[i] + " (" + heroClasses[i].title() + ")" + " HP:" + heroHP[i],
					6
			);
			heroText.setPos(4, y);
			add(heroText);

			RedButton selectBtn = new RedButton("Select") {
				@Override
				protected void onClick() {
					selectedIndex = idx;
					claimBtn.enable(true);
					// Visual feedback: disable other buttons
					super.onClick();
				}
			};
			selectBtn.setRect(WIDTH - 40, y, 36, 14);
			add(selectBtn);

			y += 16 + GAP;
		}

		// Error message (hidden initially)
		errorMsg = PixelScene.renderTextBlock(6);
		errorMsg.text("", WIDTH);
		errorMsg.setPos(4, y);
		add(errorMsg);
		y += errorMsg.height() + 4f;

		// Claim button (disabled until selection)
		claimBtn = new RedButton("Claim Hero") {
			@Override
			protected void onClick() {
				super.onClick();
				onClaimClicked();
			}
		};
		claimBtn.setRect(4, y, WIDTH - 8, 18f);
		claimBtn.enable(false);
		add(claimBtn);
		y += 18f + GAP;

		resize(WIDTH, (int)y);
	}

	private void onClaimClicked() {
		if (selectedIndex < 0) {
			return;
		}

		try {
			// EC-6.8: use public accessor instead of reflection
			java.io.DataOutputStream out = NetworkManager.getClientOut();
			if (out == null) {
				showError("Network error: not connected");
				return;
			}

			out.writeByte(NetworkManager.PacketType.HERO_CLAIM);
			out.writeInt(selectedIndex);
			out.flush();

			GLog.p("Claiming hero %d", selectedIndex);

			// Wait for RESUME_HANDSHAKE in background
			waitForResumeHandshake();
		} catch (IOException e) {
			GLog.n("Error sending HERO_CLAIM: %s", e.getMessage());
			showError("Could not claim hero: " + e.getMessage());
		} catch (Exception e) {
			GLog.n("Error accessing network stream: %s", e.getMessage());
			showError("Network error: " + e.getMessage());
		}
	}

	private void showError(String message) {
		errorMsg.text(message, WIDTH);
		errorMsg.hardlight(0xFF4444);
		PixelScene.align(errorMsg);
	}

	private void waitForResumeHandshake() {
		new Thread(() -> {
			try {
				// EC-6.8: use public accessor instead of reflection
				java.io.DataInputStream in = NetworkManager.getClientIn();
				if (in == null) { GLog.n("clientIn is null, cannot wait for RESUME_HANDSHAKE"); return; }

				byte type = in.readByte();
				if (type == NetworkManager.PacketType.RESUME_HANDSHAKE) {
					// Read bundle
					int bundleLength = in.readInt();
					byte[] bundleBytes = new byte[bundleLength];
					in.readFully(bundleBytes);

					// Read hero assignments
					int assignmentCount = in.readInt();
					int[] assignments = new int[assignmentCount];
					for (int i = 0; i < assignmentCount; i++) {
						assignments[i] = in.readInt();
					}

					GLog.p("Resume handshake received");

					// Process on render thread
					Dungeon.hero = null; // Will be set below
					try {
						// Deserialize bundle and load game
						Bundle bundle = FileUtils.bundleFromBytes(bundleBytes);
						Dungeon.loadGame(bundle);

						// Set the player's hero
						int myIndex = assignments[NetworkManager.localPlayerIndex];
						Dungeon.hero = Dungeon.heroes.get(myIndex);

						// Switch to game scene on render thread
						ShatteredPixelDungeon.switchScene(InterlevelScene.class);
						InterlevelScene.mode = InterlevelScene.Mode.CONTINUE;
					} catch (IOException e) {
						GLog.n("Error loading game: %s", e.getMessage());
					}
				}
			} catch (IOException e) {
				GLog.n("Error waiting for RESUME_HANDSHAKE: %s", e.getMessage());
			}
		}, "net-wait-resume-handshake").start();
	}
}
