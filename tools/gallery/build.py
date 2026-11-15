"""Builds the biome gallery (docs/biomes/) from the specs, the place catalogue and the images in docs/biomes/img/.

For every overworld site biome: docs/biomes/<id>.md with the real place (satellite image), the in-game map (top-down
render of the generated site) and an in-game view (shader screenshot), plus an index table in docs/biomes/README.md.

Images (made by the docs tooling, see docs/biomes/README.md "How the images are made"):
  img/<id>-satellite.jpg   Sentinel-2 cloudless (EOX) or NASA GIBS, framed on the catalogued point
  img/<id>-map.jpg         top-down render of the site in the seed-0 world (-Dbiomesmod.maps)
  img/<id>-view.jpg        in-game screenshot (Iris + Bliss shaders, Sodium, Voxy)
Sources and licences per satellite image come from tools/gallery/satellite.json.

usage: python -I tools/gallery/build.py
"""
import json
import os

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(REPO, 'docs', 'biomes')
IMG = os.path.join(OUT, 'img')

SOURCES = {
    'eox2016': ('Sentinel-2 cloudless 2016 by EOX IT Services GmbH (contains modified Copernicus Sentinel data 2016)',
                'https://s2maps.eu', 'CC BY 4.0', 'https://creativecommons.org/licenses/by/4.0/'),
    'eox2024': ('Sentinel-2 cloudless 2024 by EOX IT Services GmbH (contains modified Copernicus Sentinel data 2024)',
                'https://s2maps.eu', 'CC BY-NC-SA 4.0', 'https://creativecommons.org/licenses/by-nc-sa/4.0/'),
    'gibs': ('NASA Worldview / GIBS (MODIS Terra corrected reflectance)', 'https://worldview.earthdata.nasa.gov', 'public domain',
             'https://www.earthdata.nasa.gov/engage/open-data-services-software-policies'),
}

TERRAIN = {
    'keep': 'original terrain, shaped by landforms',
    'plateau': 'flat plateau',
    'lift': 'raised land',
    'mountain': 'mountains, low land raised',
    'basin': 'flat low basin',
}


def target_y(site):
    """Default elevation mapping (config/biomesmod.json): 25.6 m per block below 2000 m; from 2000 m y >= 190, 40 m per block."""
    if 'elevation_m' in site:
        m = site['elevation_m']
        y = 63 + m / 25.6 if m < 2000 else max(190, 63 + 2000 / 25.6) + (m - 2000) / 40
        return round(min(300, max(0, y)))
    return site.get('target_y')


# Every picture in the gallery tables is shown in one frame of this size (4:3, between the square maps and the 16:9
# views, so each loses an eighth on two sides at most).
THUMB_W, THUMB_H = 240, 180


def img_tag(path, alt):
    return f'<img src="{path}" alt="{alt}" width="{THUMB_W}" height="{THUMB_H}">'


def thumb(bid, kind):
    """A thumbnail of img/<bid>-<kind>.jpg for the tables: centre crop to the common frame, stored at twice the shown
    size. Returns its path relative to the page."""
    from PIL import Image
    src = os.path.join(OUT, 'img', f'{bid}-{kind}.jpg')
    rel = f'img/thumb/{bid}-{kind}.jpg'
    dst = os.path.join(OUT, rel)
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    im = Image.open(src).convert('RGB')
    w, h = im.size
    if w * THUMB_H > h * THUMB_W:  # wider than the frame: trim the sides
        cw, ch = h * THUMB_W // THUMB_H, h
    else:  # taller: trim top and bottom
        cw, ch = w, w * THUMB_H // THUMB_W
    x0, y0 = (w - cw) // 2, (h - ch) // 2
    im = im.crop((x0, y0, x0 + cw, y0 + ch)).resize((THUMB_W * 2, THUMB_H * 2), Image.LANCZOS)
    im.save(dst, quality=84, optimize=True)
    return rel


def load(path):
    with open(path, encoding='utf-8') as f:
        return json.load(f)


def main():
    places = {p['n']: p for p in load(os.path.join(REPO, 'specs', 'places.json'))}
    extra = load(os.path.join(REPO, 'tools', 'gallery', 'places_extra.json'))
    sat = load(os.path.join(REPO, 'tools', 'gallery', 'satellite.json'))
    rows = []
    originals = []
    for name in sorted(os.listdir(os.path.join(REPO, 'specs', 'biomes'))):
        spec = load(os.path.join(REPO, 'specs', 'biomes', name))
        site = spec.get('placement', {}).get('site')
        if spec.get('dimension') != 'overworld' or not site:
            continue
        bid = spec['id']
        ps = [places[n] for n in spec.get('places', []) if n in places and 'duplicate_of' not in places[n]] or extra.get(bid, [])
        if not ps:
            # An original biome (no real place): map and view only.
            en, ko = spec['names']['en_us'], spec['names']['ko_kr']
            imgs = {k: f'img/{bid}-{k}.jpg' for k in ('map', 'view')}
            have = {k: os.path.exists(os.path.join(OUT, v)) for k, v in imgs.items()}
            originals.append(f'| **{en}**<br>{ko}<br>`{bid}` | ' + ' | '.join(
                f'[{img_tag(thumb(bid, k), k)}]({imgs[k]})' if have[k] else '—' for k in ('map', 'view')) + ' |')
            continue
        p = ps[0]
        en, ko = spec['names']['en_us'], spec['names']['ko_kr']
        terrain = site.get('terrain', 'keep')
        target = target_y(site)
        size = site.get('size', [0, 0])
        src = SOURCES[sat.get(bid, {}).get('source', 'eox2016')]
        imgs = {k: f'img/{bid}-{k}.jpg' for k in ('satellite', 'map', 'view')}
        have = {k: os.path.exists(os.path.join(OUT, v)) for k, v in imgs.items()}
        height = f', y={target}' if target and terrain != 'keep' else ''
        lines = [
            f'# {en} / {ko}',
            '',
            f'**Real place:** {p["name"]}, {p["country"]} ({p["lat"]:.4f}, {p["lon"]:.4f})',
            '',
            p.get('summary', ''),
            '',
            f'**In game:** `biomesmod:{bid}`, a site of {2 * size[0]}–{2 * size[1]} blocks across ({TERRAIN[terrain]}{height}). '
            f'Find the nearest one with `/biomesmod sites {bid}`.',
            '',
            '| Satellite (real place) | In-game map (top-down) | In-game view |',
            '|---|---|---|',
            '| ' + ' | '.join(f'![{k}]({imgs[k]})' if have[k] else '—' for k in ('satellite', 'map', 'view')) + ' |',
            '',
        ]
        view = sat.get(bid, {}).get('view_note')
        if view:
            lines += [view, '']
        lines += [
            f'<sub>Satellite: [{src[0]}]({src[1]}), {src[2]} ([licence]({src[3]})). '
            'In-game images: seed 0, Minecraft 26.2; the view uses Iris + Bliss shaders, Sodium and Voxy.</sub>',
            '',
            '[Back to the gallery](README.md)',
            '',
        ]
        with open(os.path.join(OUT, bid + '.md'), 'w', encoding='utf-8', newline='\n') as f:
            f.write('\n'.join(lines))
        cells = ' | '.join(f'[{img_tag(thumb(bid, k), k)}]({bid}.md)' if have[k] else '—' for k in ('satellite', 'map', 'view'))
        rows.append(f'| [**{en}**]({bid}.md)<br>{ko}<br><sub>{p["name"]}, {p["country"]}<br>{TERRAIN[terrain]}{height}</sub> | {cells} |')
    index = [
        '# Biome gallery',
        '',
        'Every real-place biome of the mod next to the place that inspired it: the real satellite image, a top-down map of '
        'the generated site (the in-game "satellite view") and an in-game view with shaders. Click a biome for its page and '
        'the full-size images. Every picture in the tables is cropped to the same 4:3 frame; the pages show them whole.',
        '',
        '| Biome / real place | Satellite (real place) | In-game map (top-down) | In-game view (Bliss shaders) |',
        '|---|---|---|---|',
    ] + rows + [
        '',
        '## Original biomes',
        '',
        'Biomes of the mod that are not modelled on one real place.',
        '',
        '| Biome | In-game map (top-down) | In-game view (Bliss shaders) |',
        '|---|---|---|',
    ] + originals + [
        '',
        '## How the images are made',
        '',
        '* **Satellite:** Sentinel-2 cloudless mosaics by EOX (2016: CC BY 4.0; 2024 where the 2016 mosaic is unusable: '
        'CC BY-NC-SA 4.0) or NASA GIBS MODIS imagery (public domain) where Sentinel-2 has no data, framed on the catalogued '
        'coordinate (see [places.md](../places.md)). Each page names its source.',
        '* **In-game map:** the site in the seed-0 world, rendered from the generated chunks with block map colours and hill '
        'shading (`./gradlew :26.2:runSelfCheck -Pmaps=<plan.json>`, plan from `tools/gallery/plan.py`).',
        '* **In-game view:** Minecraft 26.2 with Sodium, Iris (Bliss shaders) and Voxy LODs, taken in the same world by the dev '
        'screenshot director (`./gradlew :26.2:runShots -Pplan=shots.json -PclientMods=<dir>`), then `tools/gallery/collect.py`.',
        '',
    ]
    with open(os.path.join(OUT, 'README.md'), 'w', encoding='utf-8', newline='\n') as f:
        f.write('\n'.join(index))
    print(f'gallery: {len(rows)} biome page(s) in {os.path.relpath(OUT, REPO)}')


if __name__ == '__main__':
    main()
