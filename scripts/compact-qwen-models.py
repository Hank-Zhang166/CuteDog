"""Build the two GGUFs used by CuteDog's fixed A voice from pinned Q4_K_M files.

Only the two reference-recording tensor groups are discarded. The GGUF metadata,
text vocabulary, retained tensor descriptors and quantized payloads are copied
verbatim, except for offsets that necessarily change in the smaller file.
"""
import argparse
import hashlib
import mmap
import os
from pathlib import Path
import struct
import tempfile


MODELS = (
    ("talker", "4b468ec7b1f62b90ef4ca316c0aa57deadfd54b2cf9651703ea753cedaf04226", "spk_enc."),
    ("codec", "cf3788b4d50aaa665fb6e57c170396aae03a3555fea52d2b5d0cda902d658039", "tok_enc."),
)

# GGUF scalar types other than STRING (8) and ARRAY (9).
SCALAR_BYTES = {0: 1, 1: 1, 2: 2, 3: 2, 4: 4, 5: 4, 6: 4, 7: 1, 10: 8, 11: 8, 12: 8}


def read_string(data):
    length, = struct.unpack("<Q", data.read(8))
    return data.read(length)


def skip_value(data, kind):
    if kind == 8:
        length, = struct.unpack("<Q", data.read(8))
        data.seek(length, os.SEEK_CUR)
    elif kind == 9:
        item_type, count = struct.unpack("<IQ", data.read(12))
        if item_type in SCALAR_BYTES:
            data.seek(count * SCALAR_BYTES[item_type], os.SEEK_CUR)
        else:
            for _ in range(count):
                skip_value(data, item_type)
    else:
        data.seek(SCALAR_BYTES[kind], os.SEEK_CUR)


def copy_span(data, output, start, length):
    data.seek(start)
    while length:
        chunk = data.read(min(2**20, length))
        if not chunk:
            raise ValueError("Truncated GGUF")
        output.write(chunk)
        length -= len(chunk)


def compact(source, output_path, digest, prefix):
    with source.open("rb") as stream, mmap.mmap(stream.fileno(), 0, access=mmap.ACCESS_READ) as data:
        if hashlib.sha256(data).hexdigest() != digest:
            raise ValueError(f"Unexpected upstream SHA-256 for {source}")
        magic, version, count, kv_count = struct.unpack("<4sIQQ", data.read(24))
        if magic != b"GGUF" or version != 3:
            raise ValueError("Expected a GGUF version 3 model")
        alignment = 32
        for _ in range(kv_count):
            key = read_string(data)
            kind, = struct.unpack("<I", data.read(4))
            if key == b"general.alignment" and kind == 4:
                alignment, = struct.unpack("<I", data.read(4))
            else:
                skip_value(data, kind)
        meta_end = data.tell()
        tensors = []
        for _ in range(count):
            start = data.tell()
            name = read_string(data).decode("ascii")
            ndim, = struct.unpack("<I", data.read(4))
            data.seek(ndim * 8 + 4, os.SEEK_CUR)
            end = data.tell()
            offset, = struct.unpack("<Q", data.read(8))
            tensors.append((name, start, end, offset))
        payload = (data.tell() + alignment - 1) // alignment * alignment
        offsets = sorted(t[3] for t in tensors)
        ends = dict(zip(offsets, offsets[1:] + [len(data) - payload]))
        kept = [entry for entry in tensors if not entry[0].startswith(prefix)]
        if len(kept) in (0, count):
            raise ValueError(f"Missing tensor group {prefix} in {source}")
        output_path.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(dir=output_path.parent, delete=False) as destination:
            temporary = Path(destination.name)
            try:
                destination.write(struct.pack("<4sIQQ", magic, version, len(kept), kv_count))
                copy_span(data, destination, 24, meta_end - 24)
                output_offset = 0
                for _, start, end, offset in kept:
                    copy_span(data, destination, start, end - start)
                    destination.write(struct.pack("<Q", output_offset))
                    output_offset += ends[offset] - offset
                destination.write(bytes(-destination.tell() % alignment))
                for _, _, _, offset in kept:
                    copy_span(data, destination, payload + offset, ends[offset] - offset)
                destination.close()
                os.replace(temporary, output_path)
            finally:
                destination.close()
                temporary.unlink(missing_ok=True)
        print(f"{output_path.name}: kept {len(kept)}/{count} tensors, {output_path.stat().st_size} bytes")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("talker", type=Path)
    parser.add_argument("codec", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    for name, required_hash, removed_prefix in MODELS:
        compact(getattr(args, name), args.output / (name + ".gguf"), required_hash, removed_prefix)
