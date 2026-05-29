---
name: hero-icon-grid-layout
description: "How to lay out 1–4 hero class icons in a compact 1×1/2×1/2×2 grid inside a fixed-width row widget, with per-icon level labels."
user-invocable: false
---
## When to use
Any time a UI row or button needs to show the full party of heroes (1–4) as class icons with level numbers inside a constrained horizontal zone. This pattern is used identically in `StartScene` (save-slot buttons) and `RankingsScene` (rankings rows) and should be reused verbatim for any new screen that displays party info.

## Steps
1. Replace the single `Image classIcon` field with a list, and add a matching list for level labels:
   ```java
   private ArrayList<Image>       classIcons  = new ArrayList<>();
   private ArrayList<BitmapText>  levelTexts  = new ArrayList<>();
   ```
2. When applying a new record (e.g. in `set()` or the constructor), clear and rebuild both lists:
   ```java
   for (Image img : classIcons) remove(img);  classIcons.clear();
   for (BitmapText t : levelTexts) remove(t); levelTexts.clear();

   int n = rec.heroClasses.length;
   float iconScale = n == 1 ? 1f : (n == 2 ? 0.75f : 0.6f);
   for (int i = 0; i < n; i++) {
       Image icon = new Image(Icons.get(rec.heroClasses[i]));
       add(icon);  classIcons.add(icon);

       BitmapText lvl = new BitmapText(PixelScene.pixelFont);
       lvl.text(Integer.toString(rec.heroLevels[i]));
       lvl.measure();
       add(lvl);   levelTexts.add(lvl);
   }
   ```
3. In `layout()`, compute the grid zone dimensions and position each icon + label:
   ```java
   int   n       = classIcons.size();
   int   icols   = n == 1 ? 1 : 2;
   int   irows   = (n + icols - 1) / icols;
   float iconScale = n == 1 ? 1f : (n == 2 ? 0.75f : 0.6f);
   float iw      = 16 * iconScale;
   float ih      = 16 * iconScale;
   float izoneW  = icols * iw + (icols - 1);          // total grid width
   float izoneX  = x + width - 4 - izoneW;            // right-aligned with 4px margin
   float izoneY  = referenceWidget.y + (16 - irows * ih - (irows - 1)) / 2f; // vertically centred on a 16px-tall reference

   for (int i = 0; i < classIcons.size(); i++) {
       Image icon = classIcons.get(i);
       icon.scale.set(iconScale);
       float ix = izoneX + (i % icols) * (iw + 1);
       float iy = izoneY + (i / icols) * (ih + 1);
       icon.x = ix;  icon.y = iy;
       align(icon);

       BitmapText lvl = levelTexts.get(i);
       lvl.x = ix + (iw - lvl.width()) / 2f;
       lvl.y = iy + (ih - lvl.height()) / 2f + 1;
       align(lvl);
   }
   ```
4. Shift any widget that previously occupied the right edge (e.g. steps/depth icon) leftward to make room:
   ```java
   steps.x = izoneX - 18 + (16 - steps.width()) / 2f;
   ```

## Notes
- Scale factors: 1.0 for solo, 0.75 for two heroes (2×1 row), 0.6 for three or four heroes (2×2 grid). Do not change these — they are tuned to fit within standard row heights.
- The `+1` pixel gap between icons (`(iw + 1)`) is intentional and matches the save-slot grid.
- `align(icon)` and `align(lvl)` are required after setting `x`/`y` in `PixelScene` subclasses — without them, sub-pixel positions cause blurring on low-density screens.
- The `referenceWidget` for vertical centering is typically the shield/depth badge on rankings rows or the hero sprite frame on save-slot buttons — use whichever 16px-tall element is the visual anchor of the row.
- This exact math is duplicated in `StartScene` and `RankingsScene`. If a third screen needs it, extract to a static helper method in a shared util class.
