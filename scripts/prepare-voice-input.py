"""Stage the pinned Apache-2.0 Vosk Mandarin model as a compressed, offline Android asset."""
import hashlib
import os
from pathlib import Path
import shutil
import sys
import tempfile
import urllib.request

NAME = "vosk-model-small-cn-0.22.zip"
URL = "https://alphacephei.com/vosk/models/" + NAME
SHA256 = "3af8b0e7e0f835ae9d414ce5df580237a3cfb08d586c9fbbb0f7ff29ad5b14ba"


def verify(path):
    with path.open("rb") as file:
        return hashlib.file_digest(file, "sha256").hexdigest() == SHA256


def main(cache, assets):
    cache.mkdir(parents=True, exist_ok=True)
    archive = cache / NAME
    if not archive.is_file() or not verify(archive):
        with tempfile.NamedTemporaryFile(dir=cache, delete=False) as file:
            temporary = Path(file.name)
        try:
            request = urllib.request.Request(URL, headers={"User-Agent": "CuteDog Android build"})
            with urllib.request.urlopen(request, timeout=90) as source, temporary.open("wb") as output:
                shutil.copyfileobj(source, output)
            if not verify(temporary):
                raise ValueError("Unexpected SHA-256 for the Vosk Mandarin model")
            os.replace(temporary, archive)
        finally:
            temporary.unlink(missing_ok=True)
    output = assets / "voice" / NAME
    output.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(archive, output)


if __name__ == "__main__":
    main(*map(Path, sys.argv[1:]))
