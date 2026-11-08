"""Copies the dev renders into the gallery as JPEG: maps from the map-mode server run, views from the screenshot director.

  versions/26.2/run/selfcheck/maps/<id>.png      -> docs/biomes/img/<id>-map.jpg   (512 x 512)
  versions/26.2/run/shots/screenshots/<id>.png   -> docs/biomes/img/<id>-view.jpg  (1280 x 720)

usage: python tools/gallery/collect.py   (needs Pillow)
"""
import os

from PIL import Image

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
IMG = os.path.join(REPO, 'docs', 'biomes', 'img')
MAPS = os.path.join(REPO, 'versions', '26.2', 'run', 'selfcheck', 'maps')
SHOTS = os.path.join(REPO, 'versions', '26.2', 'run', 'shots', 'screenshots')


def main():
    os.makedirs(IMG, exist_ok=True)
    n = 0
    for src, suffix, size in ((MAPS, 'map', (512, 512)), (SHOTS, 'view', (1280, 720))):
        if not os.path.isdir(src):
            continue
        for f in sorted(os.listdir(src)):
            if not f.endswith('.png'):
                continue
            im = Image.open(os.path.join(src, f)).convert('RGB')
            if im.size != size:
                im = im.resize(size, Image.LANCZOS)
            im.save(os.path.join(IMG, f'{f[:-4]}-{suffix}.jpg'), quality=86, optimize=True)
            n += 1
    print(f'{n} image(s) written to {os.path.relpath(IMG, REPO)}')


if __name__ == '__main__':
    main()
