#!/usr/bin/env bash
# Rebuild docs/evidence.pdf from docs/evidence.html.
#
#   docs/render_evidence_pdf.sh
#
# The PDF is A4, two pages, no browser header or footer. Any Chromium will do;
# this prefers whatever is installed and fails loudly if none is found. The
# output is committed so judges never have to run a build.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SOURCE="$HERE/evidence.html"
TARGET="$HERE/evidence.pdf"

for browser in \
  "/Applications/Brave Browser.app/Contents/MacOS/Brave Browser" \
  "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" \
  "/Applications/Chromium.app/Contents/MacOS/Chromium" \
  "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge"
do
  [ -x "$browser" ] || continue
  echo "rendering with $(basename "$browser")"
  "$browser" --headless --disable-gpu --no-pdf-header-footer \
    --print-to-pdf="$TARGET" "file://$SOURCE" 2>/dev/null
  [ -s "$TARGET" ] || { echo "no PDF written" >&2; exit 1; }
  echo "wrote $TARGET ($(wc -c < "$TARGET" | tr -d ' ') bytes)"
  exit 0
done

echo "No Chromium browser found. Open docs/evidence.html and print to PDF (A4, no headers)." >&2
exit 1
