"""1.20.1 copies of the block data in common/ whose format changed in 1.20.5: loot tables (item and enchantment
predicates, the any_of condition) and the recipe-book advancements (inventory_changed item predicates).

The copies keep the 1.21 folder names (loot_table/, advancement/) and the same paths as the common files, so the 1.20.1
build takes them instead of the common ones; the build renames the folders to the 1.20.1 plurals when it packs the jar.

usage: python tools/blocks/legacy120.py   (make_blocks.py runs it at the end)
"""
import json
import os

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = os.path.join(REPO, 'common', 'src', 'main', 'resources', 'data', 'biomesmod')
DST = os.path.join(REPO, 'versions', '1.20.1', 'src', 'main', 'resources', 'data', 'biomesmod')


def item_predicate(p):
    """{"items": "a"} or {"items": "#t"} -> {"items": ["a"]} / {"tag": "t"}; enchantment predicates to the old list."""
    p = dict(p)
    items = p.pop('items', None)
    if isinstance(items, str):
        if items.startswith('#'):
            p['tag'] = items[1:]
        else:
            p['items'] = [items]
    elif items is not None:
        p['items'] = items
    preds = p.pop('predicates', None)
    if preds:
        for e in preds.pop('minecraft:enchantments', []):
            p.setdefault('enchantments', []).append({'enchantment': e['enchantments'], **{k: v for k, v in e.items() if k != 'enchantments'}})
        if preds:
            raise ValueError('unsupported item sub-predicates %s' % list(preds))
    return p


def loot(o):
    if isinstance(o, list):
        return [loot(x) for x in o]
    if not isinstance(o, dict):
        return o
    o = {k: loot(v) for k, v in o.items()}
    if o.get('condition') == 'minecraft:any_of':
        o['condition'] = 'minecraft:alternative'
    if o.get('condition') == 'minecraft:all_of':
        raise ValueError('all_of has no 1.20.1 equivalent')
    if o.get('condition') == 'minecraft:match_tool' and 'predicate' in o:
        o['predicate'] = item_predicate(o['predicate'])
    return o


def advancement(a):
    a = json.loads(json.dumps(a))
    for c in a.get('criteria', {}).values():
        if c.get('trigger') == 'minecraft:inventory_changed':
            c['conditions']['items'] = [item_predicate(p) for p in c['conditions'].get('items', [])]
    return a


NESTED = {'minecraft:uniform', 'minecraft:clamped', 'minecraft:clamped_normal', 'minecraft:trapezoid', 'minecraft:biased_to_bottom'}


def providers(o):
    """Int and float providers nested their parameters under "value" before 1.20.5:
    {"type": "uniform", "min_inclusive": 1, "max_inclusive": 3} -> {"type": "uniform", "value": {...}}.
    Height providers ({"absolute": y} bounds) and anything already nested stay as they are."""
    if isinstance(o, list):
        return [providers(x) for x in o]
    if not isinstance(o, dict):
        return o
    o = {k: providers(v) for k, v in o.items()}
    t = o.get('type')
    if t in NESTED and 'value' not in o:
        params = {k: v for k, v in o.items() if k != 'type'}
        numeric = any(isinstance(params.get(k), (int, float)) for k in ('min_inclusive', 'max_inclusive', 'max_exclusive', 'min', 'max', 'mean'))
        if numeric or 'source' in params:
            return {'type': t, 'value': params}
    return o


def write(rel, data):
    path = os.path.join(DST, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        json.dump(data, f, indent=2)
        f.write('\n')


def worldgen():
    """1.20.1 copies of the hand-written vanilla overrides (canyon carver, overworld density functions)."""
    src = os.path.join(REPO, 'common', 'src', 'main', 'resources', 'data', 'minecraft', 'worldgen')
    dst = os.path.join(REPO, 'versions', '1.20.1', 'src', 'main', 'resources', 'data', 'minecraft', 'worldgen')
    n = 0
    for root, _, files in os.walk(src):
        for fn in files:
            if fn.endswith('.json'):
                with open(os.path.join(root, fn), encoding='utf-8') as f:
                    data = json.load(f)
                conv = providers(data)
                if conv != data:
                    out = os.path.join(dst, os.path.relpath(os.path.join(root, fn), src))
                    os.makedirs(os.path.dirname(out), exist_ok=True)
                    with open(out, 'w', encoding='utf-8', newline=chr(10)) as f:
                        json.dump(conv, f, indent=2)
                        f.write(chr(10))
                    n += 1
    return n


def main():
    n = worldgen()
    for folder, convert in (('loot_table', loot), ('advancement', advancement)):
        out_root = os.path.join(DST, folder)
        keep = set()
        for root, _, files in os.walk(os.path.join(SRC, folder)):
            for fn in files:
                if fn.endswith('.json'):
                    rel = os.path.relpath(os.path.join(root, fn), SRC)
                    with open(os.path.join(root, fn), encoding='utf-8') as f:
                        write(rel, convert(json.load(f)))
                    keep.add(os.path.normcase(os.path.join(DST, rel)))
                    n += 1
        for root, _, files in os.walk(out_root):
            for fn in files:
                if os.path.normcase(os.path.join(root, fn)) not in keep:
                    os.remove(os.path.join(root, fn))
    print('%d 1.20.1 data file(s) written' % n)


if __name__ == '__main__':
    main()
