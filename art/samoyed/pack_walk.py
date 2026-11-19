"""Pack generated walk sprites without repainting them; create an honest frame preview.

Requires Pillow. Run from repository root: python3 art/samoyed/pack_walk.py
The irregular generated source sheet is normalized to four 512px cells.
"""
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
ART = ROOT / 'art' / 'samoyed'
source = Image.open(ART / 'walk-source.png').convert('RGBA')
assert source.size == (2172, 724), source.size
# Split at actual transparent gaps, retaining the puppy's entire paws and tail.
boxes = [(0, 558), (558, 1058), (1058, 1644), (1644, 2172)]
anchors = [0, 539, 1078, 1617]
atlas = Image.new('RGBA', (2048, 512))
frames = []
for index, ((left, right), anchor) in enumerate(zip(boxes, anchors)):
    crop = source.crop((left, 100, right, 580))
    crop = crop.resize((round(crop.width * .82), round(crop.height * .82)), Image.Resampling.LANCZOS)
    frame = Image.new('RGBA', (512, 512))
    frame.alpha_composite(crop, (round((left-anchor) * .82 + 30), 50))
    atlas.alpha_composite(frame, (index * 512, 0))
    # Solid preview background only; runtime asset remains truly transparent.
    preview = Image.new('RGBA', (512, 512), '#eee8df')
    preview.alpha_composite(frame)
    frames.append(preview.convert('RGB'))
target = ROOT / 'app' / 'src' / 'main' / 'assets' / 'pet' / 'walk.png'
target.parent.mkdir(parents=True, exist_ok=True)
atlas.save(target, optimize=True)
frames[0].save(ART / 'walk-preview.gif', save_all=True, append_images=frames[1:], duration=180, loop=0, disposal=2)
print(target)
