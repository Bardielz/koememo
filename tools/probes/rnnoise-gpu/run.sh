#!/usr/bin/env bash
set -euo pipefail
out=${1:?Provide build directory}
input=${2:?Provide mono PCM16LE at 48 kHz, whole 480-sample frames}
adb="${ANDROID_HOME:?}/platform-tools/adb"
bytes=$(wc -c < "$input")
(( bytes > 0 && bytes % 960 == 0 )) || { echo "Incomplete PCM frame" >&2; exit 1; }
# adb uses ANDROID_SERIAL to select the requested watch.
remote=$("$adb" shell mktemp -d /data/local/tmp/koememo-rnnoise.XXXXXXXX | tr -d '\r')
case "$remote" in /data/local/tmp/koememo-rnnoise.*) ;; *) exit 1 ;; esac
trap '"$adb" shell rm -rf "$remote"' EXIT
"$adb" push "$out"/{pipeline,probe.jar,weights.bin,network.glsl} "$remote/"
"$adb" push "$input" "$remote/input.pcm"
"$adb" shell "cd $remote && chmod 755 pipeline && chmod 444 probe.jar"
cpu() { "$adb" shell "cd $remote && ./pipeline cpu input.pcm cpu.f32 $1"; }
gpu() { "$adb" shell "cd $remote && CLASSPATH=$remote/probe.jar app_process / dev.koememo.probes.RnGpu $remote input.pcm gpu.f32 $1"; }
cpu cpu.scores
gpu gpu.scores
for file in cpu.f32 gpu.f32 cpu.scores gpu.scores; do "$adb" pull "$remote/$file" "$out/$file"; done
python3 "$(dirname "$0")/verify.py" "$out"
# Measurements exclude tracing and EGL setup, and include CPU signal processing,
# read/write, GPU upload, dispatch, readback and the prototype's local IPC.
for first in cpu gpu cpu; do
  if [[ "$first" == cpu ]]; then cpu ""; gpu ""; else gpu ""; cpu ""; fi
done
