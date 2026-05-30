# Code Review Issues

- [x] [HIGH] TitleScene Play button still routes directly to HeroSelectScene instead of WndPlayerCount (core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/scenes/TitleScene.java)
- [x] [HIGH] Missing state reset on HeroSelectScene back button — onBackPressed() doesn't reset GamesInProgress.playerCount, selectedClasses, currentPlayerSelecting (core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/scenes/HeroSelectScene.java)
- [x] [MEDIUM] Subtitle not positioned in landscape mode — subtitle added but no explicit positioning in landscape layout section (core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/scenes/HeroSelectScene.java)
- [x] [MEDIUM] Hardcoded English "Player " text — use Messages.get() for localization instead (core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/scenes/HeroSelectScene.java)
- [x] [MINOR] HeroBtn checks selectedClasses without null safety — add null check (core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/scenes/HeroSelectScene.java)
- [x] [HIGH] Single-hero layout position regression — single-hero icons shifted 4px left and steps icon 6px left; for n==1 use original hardcoded positions (classIcon at x+width-16, steps at x+width-32) instead of grid calculation (core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/scenes/RankingsScene.java)
- [x] [MEDIUM] 2x2 grid (3-4 heroes) extends above shield — clamp izoneY to shield.y at minimum (core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/scenes/RankingsScene.java)
- [x] [MEDIUM] Latent NPE if heroClasses is null — add null guard before the icon loop in the Record row constructor (core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/scenes/RankingsScene.java)
- [x] [LOW] No protection against empty heroClasses in storeInBundle — add guard so the block is skipped when heroClasses is empty (core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/Rankings.java)
