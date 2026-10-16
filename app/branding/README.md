# iTantra app icon — design source

The launcher icon in one place, so the vectors and the PNGs can always be redrawn from a
single drawing instead of being three copies that drift apart.

| File | What it is |
|---|---|
| `icon.svg` | Square master: navy ground + microphone with transmission waves. |
| `icon-round.svg` | Same drawing, clipped to a circle (pre-26 round icons). |
| `icon-512.png` | 512px export of `icon.svg` (README / deck use). |

The runtime files are derived, not edited by hand:

- `app/src/main/res/drawable/ic_launcher_{background,foreground,monochrome}.xml` — the SVG
  paths converted to Android vectors (`<rect>` becomes a two-arc path; everything else is
  copied verbatim). The monochrome layer is the same geometry in flat white.
- `app/src/main/res/mipmap-*/ic_launcher{,_round}.png` — rasterised at 48/72/96/144/192 px
  for devices below API 26, where adaptive icons do not exist and some launchers do not
  render vector icons.

Colours: ground `#142D4E`, microphone `#EFF5F8`, waves `#4DD0C4`. They match the first
placeholder icon's navy and off-white; the aqua is the signal accent.

Regenerate the PNGs after editing a master:

```bash
./render_pngs.sh     # renders every density + icon-512.png straight from the two SVGs
```

(`render_pngs.sh` uses `sips`, which rasterises SVG at native size and keeps the transparent
background. Do not use Quick Look / `qlmanage` for this — it bakes in a white background,
which makes the tile fill its whole square and is exactly what launcher-icon lint flags.)
Check the result at 48 px before committing.
