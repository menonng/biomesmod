"""Builds the docs image plans from a full 26.2 self-check log (seed 0):

  sites.json   site centre / size / terrain / landmarks per site biome
  maps.json    top-down map plan for the dev server's map mode (-Pmaps=maps.json)
  shots.json   camera plan for the screenshot director (overrides from tools/gallery/shots_override.json)

usage: python -I tools/gallery/plan.py <selfcheck.log> <outdir>
"""
import json
import math
import os
import re
import sys

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SITE_RE = re.compile(r"biomesmod:([a-z_]+) \[minecraft:overworld\] OK - site 1 of \d+ within \d+ blocks at (-?\d+), (-?\d+) \(core (\d+) \+ ramp (\d+)\)")
LF_RE = re.compile(r"biomesmod:([a-z_]+) \[minecraft:overworld\] landform ([a-z_]+): .*?-> (\d+) placed at ([-\d,; ]+)")

# Camera distance and height for a landmark of each shape.
LANDMARK = {'monolith': (150, 25), 'cone': (330, 45), 'caldera': (300, 30), 'hot_spring': (90, 14), 'geyser': (70, 12), 'cascade': (130, 8),
            'glacial_lake': (230, 30), 'pillar_forest': (120, 26), 'butte': (190, 24), 'sea_stack': (110, 12), 'column_field': (60, 8)}
ABOVE = {'plateau': 10, 'basin': 18, 'lift': 30, 'mountain': 55, 'keep': 30}


def main():
    log, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)
    sites = {}
    for line in open(log, encoding='utf-8', errors='replace'):
        m = SITE_RE.search(line)
        if m:
            b, x, z, core, ramp = m.group(1), int(m.group(2)), int(m.group(3)), int(m.group(4)), int(m.group(5))
            with open(os.path.join(REPO, 'specs', 'biomes', b + '.json'), encoding='utf-8') as f:
                spec = json.load(f)['placement']['site']
            sites[b] = {'x': x, 'z': z, 'core': core, 'ramp': ramp, 'terrain': spec.get('terrain', 'keep'), 'landmarks': []}
            continue
        m = LF_RE.search(line)
        if m and m.group(1) in sites:
            s = sites[m.group(1)]
            for pair in m.group(4).split(';'):
                pair = pair.strip()
                if not pair:
                    continue
                lx, lz = (int(v) for v in pair.split(','))
                if math.hypot(lx - s['x'], lz - s['z']) < s['core'] * 0.9:
                    s['landmarks'].append({'shape': m.group(2), 'x': lx, 'z': lz})
    over_path = os.path.join(REPO, 'tools', 'gallery', 'shots_override.json')
    over = json.load(open(over_path, encoding='utf-8')) if os.path.exists(over_path) else {}
    shots, maps = [], []
    for b, s in sorted(sites.items()):
        o = over.get(b, {})
        # Prefer the landmark nearest the centre (the iconic feature of the place).
        lms = sorted(s['landmarks'], key=lambda l: math.hypot(l['x'] - s['x'], l['z'] - s['z']))
        if lms and not o.get('no_landmark') and 'x' not in o:
            lm = lms[0]
            dist, above = LANDMARK.get(lm['shape'], (180, 30))
            dist = o.get('lm_dist', dist)
            ang = math.radians(o.get('bearing', 75))  # camera east-north-east of the landmark, looking west
            cam = {'x': lm['x'] + math.cos(ang) * dist, 'z': lm['z'] - math.sin(ang) * dist, 'above': o.get('above', above),
                   'tx': lm['x'], 'tz': lm['z'], 't_above': o.get('t_above', 10)}
        else:
            d = o.get('dist', 0.8) * s['core']
            ang = math.radians(o.get('bearing', 20))
            cam = {'x': s['x'] + math.cos(ang) * d, 'z': s['z'] - math.sin(ang) * d, 'above': o.get('above', ABOVE[s['terrain']]),
                   'tx': s['x'] - math.cos(ang) * d * 0.3, 'tz': s['z'] + math.sin(ang) * d * 0.3, 't_above': o.get('t_above', 0)}
        cam.update({k: v for k, v in o.items() if k in ('x', 'z', 'y', 'tx', 'tz', 'yaw', 'pitch')})
        shot = dict(name=b, time=o.get('time', 1500), **{k: round(v, 1) if isinstance(v, float) else v for k, v in cam.items()})
        if o.get('commands'):
            shot['commands'] = o['commands']
        shots.append(shot)
        maps.append({'name': b, 'x': s['x'], 'z': s['z'], 'size': 1024, 'step': 2})
    json.dump(sites, open(os.path.join(out, 'sites.json'), 'w'), indent=1)
    json.dump(maps, open(os.path.join(out, 'maps.json'), 'w'), indent=1)
    json.dump({'settle': 60, 'warmup': 200, 'client_commands': ['voxy import current'], 'shots': shots},
              open(os.path.join(out, 'shots.json'), 'w'), indent=1)
    print(len(sites), 'sites;', sum(1 for s in sites.values() if s['landmarks']), 'with a landmark')


if __name__ == '__main__':
    main()
