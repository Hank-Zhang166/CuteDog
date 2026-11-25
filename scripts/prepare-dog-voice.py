#!/usr/bin/env python3
"""Build the bundled Mandarin neural voice from pinned, Apache-licensed Kokoro assets."""
from array import array
from concurrent.futures import ThreadPoolExecutor
import hashlib
from pathlib import Path
import shutil
import struct
import sys
import tempfile
import urllib.request

REVISION = "1355334be009ccce397060ee639a874137a128fa"
URL = f"https://huggingface.co/csukuangfj/kokoro-int8-multi-lang-v1_1/resolve/{REVISION}/"
INPUTS = {
    "model.int8.onnx": "05d793700b0b507f760c183c0e39bdd6a97b43d32fdc7bf7b54a5179f1b11222",
    "voices.bin": "e64a5a581d8c2a350d848f51c3121657cd83aa07ed6109172177345874a7244c",
    "lexicon-zh.txt": "fea060803db3e59193987f1a3436f56917bda09f3b3595b6588e46ae60cebf93",
    "lexicon-us-en.txt": "7daaab53a181be9885b853a8582bf1838186317e5dadacbcef9c426d6fa0da14",
    "tokens.txt": "931ab2df2400cd65d580a22402024c2347ced8ae9ea300e545144b1aacc48e14",
    "LICENSE": "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30",
}


def file_hash(path):
    with path.open("rb") as reader:
        return hashlib.file_digest(reader, "sha256").hexdigest()


def materialize(cache, name):
    path = cache / name
    if path.is_file() and file_hash(path) == INPUTS[name]:
        return path
    with tempfile.NamedTemporaryFile(dir=cache, delete=False) as temp:
        partial = Path(temp.name)
    try:
        request = urllib.request.Request(URL + name, headers={"User-Agent": "CuteDog-voice-build"})
        with urllib.request.urlopen(request, timeout=90) as response, partial.open("wb") as writer:
            shutil.copyfileobj(response, writer)
        if file_hash(partial) != INPUTS[name]:
            raise ValueError("Voice download checksum mismatch: " + name)
        partial.replace(path)
    finally:
        partial.unlink(missing_ok=True)
    return path


def build(cache, destination):
    cache.mkdir(parents=True, exist_ok=True)
    with ThreadPoolExecutor(max_workers=4) as pool:
        list(pool.map(lambda name: materialize(cache, name), INPUTS))
    out = destination / "dogvoice"
    out.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(cache / "model.int8.onnx", out / "kokoro.onnx")
    shutil.copyfile(cache / "LICENSE", out / "LICENSE-Kokoro.txt")
    for notice in (Path(__file__).resolve().parent / "licenses").glob("*.txt"):
        shutil.copyfile(notice, out / notice.name)
    (out / "NOTICE.txt").write_text(
        "Kokoro v1.1-zh, by hexgrad. The original Chinese professional training data was granted by LongMaoData.\n"
        "Model https://huggingface.co/hexgrad/Kokoro-82M-v1.1-zh\n"
        f"ONNX conversion and pronunciation data by k2-fsa/sherpa-onnx, revision {REVISION}.\n"
        "Pronunciation data derived by k2-fsa/sherpa-onnx (Copyright 2025 Xiaomi Corp.) using Misaki "
        "(Apache-2.0) and python-pinyin / pinyin-data / phrase-pinyin-data (MIT).\n"
        "CuteDog combines three released Mandarin style embeddings (IDs 11, 23 and 27).\n"
        "Kokoro weights are licensed under Apache-2.0. ONNX Runtime is licensed under MIT.\n",
        encoding="utf-8", newline="\n")
    tokens = {symbol: int(token) for symbol, token in
              (line.rsplit(" ", 1) for line in (cache / "tokens.txt").read_text(encoding="utf-8").splitlines())}
    entries = {}
    for name in ("lexicon-zh.txt", "lexicon-us-en.txt"):
        for line in (cache / name).read_text(encoding="utf-8").splitlines():
            word, *phones = line.split()
            if name == "lexicon-us-en.txt" and len(word) > 14:
                continue
            if phones and all(p in tokens for p in phones):
                entries.setdefault(word.lower(), bytes(tokens[p] for p in phones))
    with (out / "lexicon.bin").open("wb") as writer:
        writer.write(struct.pack(">I", len(entries)))
        for word, ids in sorted(entries.items(), key=lambda item: item[0].encode("utf-16-be")):
            key = word.encode("utf-8")
            writer.write(struct.pack(">HB", len(key), len(ids)))
            writer.write(key)
            writer.write(ids)
    total = 510 * 256
    blend = array("f", [0]) * total
    with (cache / "voices.bin").open("rb") as reader:
        for speaker, weight in ((11, 0.38), (23, 0.37), (27, 0.25)):
            reader.seek(speaker * total * 4)
            style = array("f")
            style.fromfile(reader, total)
            if sys.byteorder != "little":
                style.byteswap()
            for i, value in enumerate(style):
                blend[i] += value * weight
    if sys.byteorder != "little":
        blend.byteswap()
    (out / "style.bin").write_bytes(blend.tobytes())


if __name__ == "__main__":
    build(Path(sys.argv[1]), Path(sys.argv[2]))
