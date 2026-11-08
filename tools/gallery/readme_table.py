"""Rewrites the biome table in README.md (between the table header and the next blank line) from specs/biomes.

usage: python -I tools/gallery/readme_table.py
"""
import json
import os

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def target_y(spec_site):
    """Default config mapping (config/biomesmod.json): 25.6 m per block below 2000 m, y >= 190 and 40 m per block above."""
    if 'elevation_m' in spec_site:
        m = spec_site['elevation_m']
        y = 63 + m / 25.6 if m < 2000 else max(190, 63 + 2000 / 25.6) + (m - 2000) / 40
        return round(min(300, max(0, y)))
    return spec_site.get('target_y')


def placement(d, specs):
    p = d.get('placement', {})
    if 'site' in p:
        s = p['site']
        t = s.get('terrain', 'keep')
        out = 'site, ' + t
        y = target_y(s)
        if t != 'keep' and y is not None:
            out += ' y=%d' % y
        if s.get('elevation_m'):
            out += ' (%d m)' % s['elevation_m']
        if s.get('ridges'):
            out += ', ridges'
        if s.get('zones'):
            out += ', %d zones' % len(s['zones'])
        if 'ocean' in s.get('zone', []) and len(s.get('zone', [])) == 1:
            out += ', island'
        return out
    if 'zone_of' in p:
        return 'zone of `%s`' % p['zone_of']
    if 'replace' in p:
        r = p['replace']
        return r[0].split(':')[1] + (' (+%d)' % (len(r) - 1) if len(r) > 1 else '')
    if 'end' in p:
        return 'end ' + p['end']
    return d.get('dimension', '')


def main():
    specs = {}
    for name in sorted(os.listdir(os.path.join(REPO, 'specs', 'biomes'))):
        with open(os.path.join(REPO, 'specs', 'biomes', name), encoding='utf-8') as f:
            d = json.load(f)
        specs[d['id']] = d
    rows = ['| Biome | Name | Dimension | Placement | Place n (docs/places.md) |', '|---|---|---|---|---|']
    for bid, d in specs.items():
        n = d.get('names', {})
        places = ', '.join(str(x) for x in d.get('places', [])) or '—'
        rows.append('| `%s` | %s / %s | %s | %s | %s |' % (bid, n.get('en_us', bid), n.get('ko_kr', ''), d.get('dimension'), placement(d, specs), places))
    path = os.path.join(REPO, 'README.md')
    with open(path, encoding='utf-8') as f:
        lines = f.read().split('\n')
    start = lines.index(rows[0])
    end = start
    while end < len(lines) and lines[end].startswith('|'):
        end += 1
    lines[start:end] = rows
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        f.write('\n'.join(lines))
    print('%d biomes' % len(specs))


if __name__ == '__main__':
    main()
