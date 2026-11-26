#!/usr/bin/env bash
set -euo pipefail
# Builds disposable shell probe files outside the checkout.
src=$(cd "$(dirname "$0")" && pwd)
out=${1:?Provide an output directory}
mkdir -p "$out"
out=$(cd "$out" && pwd)
archive="$out/rnnoise-v0.1.tar.gz"
if [[ ! -f "$archive" ]]; then
  curl -fL https://codeload.github.com/xiph/rnnoise/tar.gz/refs/tags/v0.1 -o "$archive"
fi
echo "488f6d6eb5f021f18e14fd194150b264bd1798b24f3ac8d54a778ab95aebff61  $archive" | shasum -a 256 -c -
tar -xzf "$archive" -C "$out"
rn="$out/rnnoise-0.1"
python3 "$src/model.py" "$rn" "$out"
if [[ -n "${ANDROID_CC:-}" ]]; then
  compiler=("$ANDROID_CC")
else
  compiler=("${ZIG:-zig}" cc -target arm-linux-musleabihf -mcpu=cortex_a7 -static)
fi
"${compiler[@]}" -O3 -I"$rn/include" -I"$rn/src" "$src/pipeline.c" \
  "$rn/src/"{rnn,rnn_data,pitch,kiss_fft,celt_lpc}.c -lm -o "$out/pipeline"
sdk="${ANDROID_HOME:?Set ANDROID_HOME}"
platform="$sdk/platforms/android-35/android.jar"
mkdir -p "$out/classes"
"$JAVA_HOME/bin/javac" --release 8 -cp "$platform" -d "$out/classes" "$src/RnGpu.java"
(cd "$out/classes" && zip -q -r "$out/classes.jar" dev)
"$sdk/build-tools/35.0.0/d8" --min-api 30 --lib "$platform" --output "$out/probe.jar" "$out/classes.jar"
