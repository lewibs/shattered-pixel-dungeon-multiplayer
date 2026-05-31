# Flows Checklist
- [x] heroesArrayInit — modified (Dungeon.java)
- [x] heroSingletonSwap — modified (Hero.java)
- [x] mobTargetAllHeroes — modified (Mob.java)
- [x] actorInitAllHeroes — modified (Actor.java)
- [x] heroesSaveLoad — modified (Dungeon.java)
- [x] fovActiveHeroOnly — no change required

# Affected Docs Checklist
- [x] /home/lewibs/github/shattered-pixel-dungeon-multiplayer/docs/docs/turn-based-system.md — touches all flows

# Flows Checklist (broadcast-boss-upgrades)
- [x] tengusMaskSequentialChoice — modified
- [x] kingsCrownSequentialChoice — modified
- [x] tengusDieDropFix — modified

# Affected Docs Checklist (broadcast-boss-upgrades)
- [x] /home/lewibs/github/shattered-pixel-dungeon-multiplayer/docs/docs/boss-drops-and-upgrade-items.md — touches all three flows

# Flows Checklist (rankings-multi-hero-icons)
- [x] rankingsRecordMultiHeroData — created (Rankings.Record parallel arrays + submit population)
- [x] rankingsRowIconGrid — created (RankingsScene icon grid layout)

# Affected Docs Checklist (rankings-multi-hero-icons)
- [x] NEW — /home/lewibs/github/shattered-pixel-dungeon-multiplayer/docs/docs/rankings.md — no existing doc

# Flows Checklist (scale-food-spawns)
- [x] scaleFloorFoodDrops — created (RegularLevel.createItems())

# Affected Docs Checklist (scale-food-spawns)
- [x] NEW — /home/lewibs/github/shattered-pixel-dungeon-multiplayer/docs/docs/food-spawning.md — no existing doc

# Flows Checklist (fix-stair-descent-positioning)
- [x] setRelocationFlagOnTransition — modified (InterlevelScene.descend, InterlevelScene.ascend)

# Affected Docs Checklist (fix-stair-descent-positioning)
- [x] /home/lewibs/github/shattered-pixel-dungeon-multiplayer/docs/docs/turn-based-system.md — touches heroInitialPlacement flow and Multi-Hero Singleton-Swap Pattern section

# Flows Checklist (single-hero-death-handling)
- [x] singleHeroDeathGuard — created (Hero.die() non-last-hero path)
- [x] guardDungeonFail — created (Char.java Dungeon.fail() guard)

# Affected Docs Checklist (single-hero-death-handling)
- [x] /home/lewibs/github/shattered-pixel-dungeon-multiplayer/docs/docs/turn-based-system.md — touches singleHeroDeathGuard, guardDungeonFail (new flows + singleton-swap section update)

# Flows Checklist (follow-hero-button)
- [x] followButtonClick — created (Toolbar.java + FollowHeroBuff.java)
- [x] followHeroMovement — created (Hero.java + FollowHeroBuff.java)
- [x] toolbarFollowLayout — modified (Toolbar.java)

# Affected Docs Checklist (follow-hero-button)
- [x] /home/lewibs/github/shattered-pixel-dungeon-multiplayer/docs/docs/toolbar-ui.md — updated: btnFollow, followInformer, 3 new flows added

# Flows Checklist (hash-exchange-stream-race)
- [x] receiveActionAsync — modified (singleton guard + HASH/RESUME_HANDSHAKE queue routing)
- [x] receiveHash — modified (queue drain instead of direct stream read)
- [x] receiveResyncBundle — modified (queue drain instead of direct stream read)
- [x] cleanup — modified (hashQueue.clear() + resyncQueue.clear() added)

# Affected Docs Checklist (hash-exchange-stream-race)
- [x] /home/lewibs/github/shattered-pixel-dungeon-multiplayer/docs/docs/network-manager.md — updated: single-reader invariant documented, 3 new flows added, cleanup pseudocode updated

# Flows Checklist (lan-hero-select-sync — lockout bug fix)
- [x] lanHeroCardTap — modified (setSelectedHero sends CLASS_CLAIMED/UNCLAIMED; listeners wired in create())
- [x] lanHeroCardGrayOut — modified (isTaken() added; updateFade() uses isTaken() guard)
- [x] lanSelectConfirm — modified (non-blocking confirm; background thread polling; host Start button)

# Affected Docs Checklist (lan-hero-select-sync — lockout bug fix)
- [x] /home/lewibs/github/shattered-pixel-dungeon-multiplayer/docs/docs/hero-select-scene.md — updated: removed BUG annotations, updated all three flows to reflect implemented code, added isTaken()/updateFade() types and paths to lanHeroCardGrayOut

# Flows Checklist (hero-select-subtitle-fade-sync)
- [x] perPlayerHeroSelection.subtitleHideOnSelect — modified (portrait: subtitle.visible=false in setSelectedHero())
- [x] perPlayerHeroSelection.subtitleRestoreOnReset — modified (subtitle.visible=true in resetFade())

# Affected Docs Checklist (hero-select-subtitle-fade-sync)
- [x] /home/lewibs/github/shattered-pixel-dungeon-multiplayer/docs/docs/multiplayer-hero-selection-ui.md — updated: 2 new paths + pseudocode for subtitle visibility sync

# Flows Checklist (remove-HashDeterminismTest)
- [x] HashDeterminismTest — removed (referenced deleted GameScene.computeHash() from removed hash anti-cheat system)

# Affected Docs Checklist (remove-HashDeterminismTest)
- [x] /home/lewibs/github/shattered-pixel-dungeon-multiplayer/docs/bugs/2026-05-30-lan-hero-select-sync.md — updated: verification checkbox for test infra now marked done; noted HashDeterminismTest.java removed
