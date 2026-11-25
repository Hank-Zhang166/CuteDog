"""Bake the foundation poses with the renderer identified in native-provenance.json."""
from pathlib import Path
import sys

# Keep this directory first for the base renderer and share the motion sampler with the other rigs.
sys.path.insert(1, str(Path(__file__).resolve().parents[1]))
from bake_library import main

if __name__ == "__main__":
    main()
