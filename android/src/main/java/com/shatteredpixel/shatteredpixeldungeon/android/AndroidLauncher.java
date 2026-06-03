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

package com.shatteredpixel.shatteredpixeldungeon.android;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.ViewConfiguration;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import java.io.PrintWriter;
import java.io.StringWriter;

import com.badlogic.gdx.Files;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.backends.android.AndroidApplication;
import com.badlogic.gdx.backends.android.AndroidApplicationConfiguration;
import com.badlogic.gdx.backends.android.AndroidAudio;
import com.badlogic.gdx.backends.android.AsynchronousAndroidAudio;
import com.badlogic.gdx.graphics.g2d.freetype.FreeType;
import com.badlogic.gdx.utils.GdxNativesLoader;
import com.shatteredpixel.shatteredpixeldungeon.SPDSettings;
import com.shatteredpixel.shatteredpixeldungeon.ShatteredPixelDungeon;
import com.shatteredpixel.shatteredpixeldungeon.services.news.News;
import com.shatteredpixel.shatteredpixeldungeon.services.news.NewsImpl;
import com.shatteredpixel.shatteredpixeldungeon.services.updates.UpdateImpl;
import com.shatteredpixel.shatteredpixeldungeon.services.updates.Updates;
import com.shatteredpixel.shatteredpixeldungeon.ui.Button;
import com.watabou.input.KeyEvent;
import com.watabou.noosa.Game;
import com.watabou.utils.FileUtils;

public class AndroidLauncher extends AndroidApplication {
	
	public static AndroidApplication instance;
	
	private static AndroidPlatformSupport support;
	
	@SuppressLint("SetTextI18n")
	@Override
	protected void onCreate (Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		try {
			GdxNativesLoader.load();
			FreeType.initFreeType();
		} catch (Exception e){
			GdxNativesLoader.disableNativesLoading = true;
			AndroidMissingNativesHandler.error = e;
			Intent intent = new Intent(this, AndroidMissingNativesHandler.class);
			startActivity(intent);
			finish();
			//let initialization continue for a moment so that we can set up things libGDX expects to be set up
		}

		// Show crash report from previous session if one was saved
		android.content.SharedPreferences crashPrefs = getSharedPreferences("PDMultiplayerCrash", MODE_PRIVATE);
		String savedCrash = crashPrefs.getString("crash_report", null);
		if (savedCrash != null) {
			crashPrefs.edit().remove("crash_report").apply();
			final String crashReport = savedCrash;
			runOnUiThread(new Runnable() {
				@Override
				public void run() {
					new AlertDialog.Builder(AndroidLauncher.this)
							.setTitle("Pixel Dungeon Multiplayer Has Crashed!")
							.setMessage("The game crashed since you last played. " +
									"If you could, please email this info to benjaminsl2000@gmail.com:\n\n" +
									crashReport)
							.setPositiveButton("OK", null)
							.show();
				}
			});
		}

		// Catch crashes on any thread and save the report for display on next launch
		final Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
		Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
			@Override
			public void uncaughtException(Thread thread, Throwable throwable) {
				try {
					StringWriter sw = new StringWriter();
					throwable.printStackTrace(new PrintWriter(sw));
					String version = Game.version != null ? Game.version : "unknown";
					String report = "version: " + version + "\n" + sw.toString();
					if (report.length() > 2000) report = report.substring(0, 2000) + "...";
					getSharedPreferences("PDMultiplayerCrash", MODE_PRIVATE).edit()
							.putString("crash_report", report)
							.apply();
				} catch (Throwable ignored) {}
				if (defaultHandler != null) defaultHandler.uncaughtException(thread, throwable);
			}
		});

		//there are some things we only need to set up on first launch
		if (instance == null) {

			instance = this;

			try {
				Game.version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
			} catch (PackageManager.NameNotFoundException e) {
				Game.version = "???";
			}
			try {
				Game.versionCode = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
			} catch (PackageManager.NameNotFoundException e) {
				Game.versionCode = 0;
			}

			Gdx.app = this;
			if (UpdateImpl.supportsUpdates()) {
				Updates.service = UpdateImpl.getUpdateService();
			}
			if (NewsImpl.supportsNews()) {
				News.service = NewsImpl.getNewsService();
			}

			FileUtils.setDefaultFileProperties(Files.FileType.Local, "");

			// grab preferences directly using our instance first
			// so that we don't need to rely on Gdx.app, which isn't initialized yet.
			// Note that we use a different prefs name on android for legacy purposes,
			// this is the default prefs filename given to an android app (.xml is automatically added to it)
			SPDSettings.set(instance.getPreferences("ShatteredPixelDungeon"));

		} else {
			instance = this;
		}

		//Shattered still overrides the back gesture behaviour, but we need to do it in a new way
		// (API added in Android 13, functionality enforced in Android 16)
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			//we post this to a runnable so that it's delayed and overrides
			// default GDX back handling, which only sends a key down event
			runnables.add(new Runnable() {
				@Override
				public void run() {
					getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, new OnBackInvokedCallback() {
						@Override
						public void onBackInvoked() {
							KeyEvent.addKeyEvent(new KeyEvent(Input.Keys.BACK, true));
							KeyEvent.addKeyEvent(new KeyEvent(Input.Keys.BACK, false));
						}
					});
				}
			});
		}

		AndroidApplicationConfiguration config = new AndroidApplicationConfiguration();
		config.depth = 0;

		//we manage this ourselves
		config.useImmersiveMode = false;
		
		config.useCompass = false;
		config.useAccelerometer = false;
		
		if (support == null) support = new AndroidPlatformSupport();
		else                 support.reloadGenerators();
		
		support.updateSystemUI();

		Button.longClick = ViewConfiguration.getLongPressTimeout()/1000f;
		
		initialize(new ShatteredPixelDungeon(support), config);
		
	}

	@Override
	public AndroidAudio createAudio(Context context, AndroidApplicationConfiguration config) {
		return new AsynchronousAndroidAudio(context, config);
	}

	@Override
	protected void onResume() {
		//prevents weird rare cases where the app is running twice
		if (instance != this){
			finishAndRemoveTask();
		}
		super.onResume();
	}

	@SuppressLint("GestureBackNavigation")
	@Override
	public void onBackPressed() {
		//do nothing, game should catch all back presses
	}

	@Override
	public void onWindowFocusChanged(boolean hasFocus) {
		super.onWindowFocusChanged(hasFocus);
		support.updateSystemUI();
	}
	
	@Override
	public void onMultiWindowModeChanged(boolean isInMultiWindowMode) {
		super.onMultiWindowModeChanged(isInMultiWindowMode);
		support.updateSystemUI();
	}
}