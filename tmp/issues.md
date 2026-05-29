# Code Review Issues

- [x] [HIGH] TitleScene Play button still routes directly to HeroSelectScene instead of WndPlayerCount (core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/scenes/TitleScene.java)
- [x] [HIGH] Missing state reset on HeroSelectScene back button — onBackPressed() doesn't reset GamesInProgress.playerCount, selectedClasses, currentPlayerSelecting (core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/scenes/HeroSelectScene.java)
- [x] [MEDIUM] Subtitle not positioned in landscape mode — subtitle added but no explicit positioning in landscape layout section (core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/scenes/HeroSelectScene.java)
- [x] [MEDIUM] Hardcoded English "Player " text — use Messages.get() for localization instead (core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/scenes/HeroSelectScene.java)
- [x] [MINOR] HeroBtn checks selectedClasses without null safety — add null check (core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/scenes/HeroSelectScene.java)
