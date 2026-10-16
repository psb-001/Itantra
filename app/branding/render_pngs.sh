#!/bin/bash
#
# Re-render the legacy launcher PNGs from the SVG masters.
#
# Why a script: the pre-API-26 icons are raster images, and hand-exporting them is how a
# project ends up with a 48px icon that no longer matches the 192px one. This renders every
# density from the same two SVGs, at native size, every time.
#
# sips is macOS's own rasteriser and — unlike Quick Look thumbnails — it preserves the
# transparent background, which is what keeps launchers and lint happy (the tile must not
# fill its full square). Run after editing icon.svg or icon-round.svg:
#
#   ./render_pngs.sh
#
set -euo pipefail
cd "$(dirname "$0")"

res=../app/src/main/res
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

render() { # svg  size  output
  # The masters declare width/height 512; swap in the target size and let CoreGraphics
  # rasterise at native resolution (no downscaling, so no resampling artefacts).
  sed "s/width=\"512\" height=\"512\"/width=\"$2\" height=\"$2\"/" "$1" > "$tmp/at.svg"
  sips -s format png "$tmp/at.svg" --out "$3" >/dev/null
}

for pair in "mdpi 48" "hdpi 72" "xhdpi 96" "xxhdpi 144" "xxxhdpi 192"; do
  set -- $pair
  render icon.svg       "$2" "$res/mipmap-$1/ic_launcher.png"
  render icon-round.svg "$2" "$res/mipmap-$1/ic_launcher_round.png"
done

render icon.svg 512 icon-512.png

echo "rendered 5 densities × {square, round} + icon-512.png"
