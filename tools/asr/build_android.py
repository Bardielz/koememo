"""Build the phone's ARM64 ASR worker from pinned upstream artifacts. No model weights are bundled."""
import argparse
import hashlib
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tarfile
import tempfile
import urllib.request

LLAMA = "b12830"
FUN = "5df2b86c73d19fb9afca24411e4a052d9113e619"
ARCHIVE_SHA = "a780c3445e6dc1f18f41526b3acd83b3d5ef35fe931dd6bafec5fa6dba9ed066"
FUN_FILES = {
    "runtime/llama.cpp/funasr-cli/funasr-cli.cpp": "13ec9247da7ca04668410d9dc9bd6eb539aeb23969a7d210f79b29bdc5849302",
    "runtime/llama.cpp/funasr-common/funasr_audio.h": "9a807d7a4c40f04d4c70f9f06553151fc921aa113b37e12cacc2350df79ef200",
    "runtime/llama.cpp/funasr-common/funasr_vad.h": "f4f42c701a15bde605fa36c55f8b112c724ffbdbfd6754c67a362b0da49979dd",
    "runtime/llama.cpp/funasr-common/miniaudio.h": "ac7af4de748b7e26b777f37e01cee313a308a7296a3eb080e2906b320cc55c89",
    "LICENSE": "c71d239df91726fc519c6eb72d318ec65820627232b2f796219e87dcf35d0ab4",
}


def artifact(cache, url, expected):
    cache.mkdir(parents=True, exist_ok=True)
    dest = cache / expected
    if dest.exists() and hashlib.sha256(dest.read_bytes()).hexdigest() == expected:
        return dest
    with tempfile.NamedTemporaryFile(dir=cache, delete=False) as tmp:
        tmp_path = Path(tmp.name)
    try:
        with urllib.request.urlopen(url, timeout=120) as response, tmp_path.open("wb") as out:
            shutil.copyfileobj(response, out)
        if hashlib.sha256(tmp_path.read_bytes()).hexdigest() != expected:
            raise ValueError(f"Checksum mismatch for {url}")
        os.replace(tmp_path, dest)
    finally:
        tmp_path.unlink(missing_ok=True)
    return dest


def build(ndk, output, cache):
    src = Path(__file__).resolve().parents[2] / "apps/phone/android/src/main/cpp"
    host = {"Darwin": "darwin-x86_64", "Linux": "linux-x86_64", "Windows": "windows-x86_64"}[platform.system()]
    tools = ndk / "toolchains/llvm/prebuilt" / host / "bin"
    compiler = tools / ("aarch64-linux-android30-clang++.cmd" if os.name == "nt" else "aarch64-linux-android30-clang++")
    if not compiler.is_file():
        raise RuntimeError(f"Android NDK arm64 compiler missing at {compiler}")
    archive = artifact(cache, f"https://github.com/ggml-org/llama.cpp/releases/download/{LLAMA}/llama-{LLAMA}-bin-android-arm64-snapdragon.tar.gz", ARCHIVE_SHA)
    with tempfile.TemporaryDirectory() as tmp:
        work = Path(tmp)
        lib = work / "arm64-v8a"
        lib.mkdir()
        notices = work / "notices"
        notices.mkdir()
        with tarfile.open(archive) as tar:
            for entry in tar:
                name = Path(entry.name)
                if not entry.isfile():
                    continue
                if name.parent.name == "include" and name.suffix == ".h":
                    dest = work / name.name
                elif name.parent.name == "lib" and name.suffix == ".so" and (name.name.startswith("libggml") or name.name in ("libllama.so", "libmtmd.so")):
                    dest = lib / name.name
                elif name.name == "LICENSE":
                    dest = notices / "llama.cpp.txt"
                else:
                    continue
                with tar.extractfile(entry) as inp, dest.open("wb") as out:
                    shutil.copyfileobj(inp, out)
        for path, digest in FUN_FILES.items():
            file = artifact(cache, f"https://raw.githubusercontent.com/QwenAudio/Fun-ASR/{FUN}/{path}", digest)
            shutil.copyfile(file, notices / "Fun-ASR.txt" if path == "LICENSE" else work / Path(path).name)
        subprocess.run(["git", "apply", "--no-index", str(src / "funasr-android.patch")], cwd=work, check=True)
        subprocess.run([compiler, "-std=c++17", "-O2", "-static-libstdc++", "-I", work, src / "transcribe.cpp",
                        "-L", lib, "-Wl,-rpath,$ORIGIN", "-Wl,-z,max-page-size=16384", "-lllama", "-lmtmd",
                        "-lggml", "-lggml-base", "-lggml-cpu", "-ldl", "-llog", "-o", lib / "libkoe_asr.so"], check=True)
        for file in lib.glob("*.so"):
            if not file.name.startswith("libggml-htp-"):
                subprocess.run([tools / ("llvm-strip.exe" if os.name == "nt" else "llvm-strip"), "--strip-unneeded", file], check=True)
        shutil.copytree(lib, output / "jni/arm64-v8a", dirs_exist_ok=True)
        shutil.copytree(notices, output / "assets/notices", dirs_exist_ok=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for arg in ("ndk", "output", "cache"):
        parser.add_argument("--" + arg, required=True, type=Path)
    args = parser.parse_args()
    build(args.ndk, args.output, args.cache)
