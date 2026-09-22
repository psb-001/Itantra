#!/bin/zsh
# Fetch the six FLEURS languages we have not measured yet.
# gu_in and kn_in already exist locally but their tarballs are truncated (gzip -t fails),
# so they get re-downloaded from scratch.
set -u
FLEURS="/Users/prathameshbhujbal/scrapling/output/option_a_stt/fleurs"
BASE="https://huggingface.co/datasets/google/fleurs/resolve/main/data"
TMP="/tmp/fleurs_dl"
mkdir -p "$TMP"

for code in en_us ta_in te_in ml_in gu_in kn_in; do
  echo "=================== $code ==================="

  if [ ! -s "$FLEURS/${code}_test.tsv" ]; then
    curl -sL --max-time 120 "$BASE/$code/test.tsv" -o "$FLEURS/${code}_test.tsv" \
      && echo "tsv: $(wc -l < "$FLEURS/${code}_test.tsv") lines"
  else
    echo "tsv: already present"
  fi

  TAR="$TMP/$code.tar.gz"
  # always re-fetch: any existing local copy for gu/kn is known-truncated
  rm -f "$TAR"
  echo "downloading audio (resumable, up to 3 attempts)..."
  for attempt in 1 2 3; do
    curl -L --max-time 1800 --retry 2 --retry-delay 5 -C - "$BASE/$code/audio/test.tar.gz" -o "$TAR" 2>/dev/null
    if gzip -t "$TAR" 2>/dev/null; then
      echo "  gzip OK on attempt $attempt ($(du -h "$TAR" | cut -f1))"
      break
    fi
    echo "  attempt $attempt incomplete, resuming..."
  done

  if ! gzip -t "$TAR" 2>/dev/null; then
    echo "  !! FAILED to get a complete tarball for $code"
    continue
  fi

  rm -rf "$FLEURS/test-$code"
  mkdir -p "$FLEURS/test-$code"
  tar -xzf "$TAR" -C "$FLEURS/test-$code" --strip-components=1 2>/dev/null \
    || tar -xzf "$TAR" -C "$FLEURS/test-$code" 2>/dev/null
  echo "  extracted: $(ls "$FLEURS/test-$code" | wc -l | tr -d ' ') files"
  rm -f "$TAR"
done

echo
echo "=================== DONE ==================="
for code in en_us ta_in te_in ml_in gu_in kn_in; do
  n=$(ls "$FLEURS/test-$code" 2>/dev/null | wc -l | tr -d ' ')
  t=$([ -s "$FLEURS/${code}_test.tsv" ] && echo yes || echo no)
  echo "$code  tsv=$t  audio_files=$n"
done
