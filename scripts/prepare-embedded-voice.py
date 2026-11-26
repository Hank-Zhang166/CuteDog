"""Fetch checksum-pinned Qwen sources on the build machine and stage the fixed Android voice."""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import hashlib
import os
import shutil
import subprocess
import sys
import tempfile
from urllib.request import Request, urlopen


REVISION = "b7ee2e8c7459c3bea99da23e3d178125a7d1713c"
MODEL_URL = f"https://huggingface.co/Serveurperso/Qwen3-TTS-GGUF/resolve/{REVISION}/"
SOURCES = (
    ("qwen-talker-0.6b-base-Q4_K_M.gguf", 628905056, "4b468ec7b1f62b90ef4ca316c0aa57deadfd54b2cf9651703ea753cedaf04226"),
    ("qwen-tokenizer-12hz-Q4_K_M.gguf", 254974752, "cf3788b4d50aaa665fb6e57c170396aae03a3555fea52d2b5d0cda902d658039"),
)
PACKAGED = (
    ("talker.gguf", 604877696, "68b0c1b091f668552f6c069a74a8fb5c1a73fd58ce3326f43e90e7c259338371"),
    ("codec.gguf", 177698144, "2940ea17c13966f18da4fd0b455e4924b0bebc349ac42a08545086c2e0635248"),
)


def verified(path, size, digest):
    if not path.is_file() or path.stat().st_size != size:
        return False
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest() == digest


def fetch(cache, source):
    name, size, digest = source
    dest = cache / name
    if verified(dest, size, digest):
        return dest
    with tempfile.NamedTemporaryFile(dir=cache, delete=False) as temp:
        path = Path(temp.name)
    try:
        req = Request(MODEL_URL + name, headers={"User-Agent": "CuteDog Android build"})
        with urlopen(req, timeout=180) as response, path.open("wb") as output:
            shutil.copyfileobj(response, output, 1024*1024)
        if not verified(path, size, digest):
            raise ValueError(f"Upstream voice file does not match its pinned size and SHA-256: {name}")
        os.replace(path, dest)
    finally:
        path.unlink(missing_ok=True)
    return dest


def prepare(cache, assets):
    cache.mkdir(parents=True, exist_ok=True)
    with ThreadPoolExecutor(max_workers=len(SOURCES)) as pool:
        sources = list(pool.map(lambda source: fetch(cache, source), SOURCES))
    assets.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=assets.parent) as name:
        stage = Path(name)
        subprocess.run([sys.executable, str(Path(__file__).with_name("compact-qwen-models.py")),
                        *map(str, sources), str(stage)], check=True)
        target = assets / "a-voice"
        target.mkdir(exist_ok=True)
        for file, size, digest in PACKAGED:
            if not verified(stage / file, size, digest):
                raise ValueError(f"Unexpected fixed-voice GGUF: {file}")
        for file, _, _ in PACKAGED:
            os.replace(stage / file, target / file)


if __name__ == "__main__":
    prepare(*map(Path, sys.argv[1:]))
