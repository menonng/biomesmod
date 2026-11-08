"""Feature order compatibility with other biome mods.

Minecraft needs one consistent feature order per decoration step across every biome of a dimension. Two mods whose
biomes list features in conflicting orders (directly, or through a chain of biomes) crash the game with
"Feature order cycle found" on the first chunk.

  python -I tools/biomegen/ordercheck.py check <mc> <mod.jar | pack.zip>...
      builds the order graph of the vanilla, this mod's and the given mods' biomes and reports every cycle
  python -I tools/biomegen/ordercheck.py hints <mc> <mod.jar | pack.zip>...
      records the given mods' orders as hints for <mc> in tools/biomegen/order_hints.json; regenerate afterwards
      (generate.py sorts every step after the vanilla lists plus these hints, so this mod follows the other mods)
"""
import json
import os
import sys
import zipfile
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
STEPS = ['raw_generation', 'lakes', 'local_modifications', 'underground_structures', 'surface_structures', 'strongholds',
         'underground_ores', 'underground_decoration', 'fluid_springs', 'vegetal_decoration', 'top_layer_modification']
VANILLA = os.environ.get('BIOMEGEN_VANILLA') or os.path.join(os.environ.get('APPDATA', os.path.expanduser('~')), 'minecraft-dev-mcp', 'decompiled')


def from_dir(d, ns):
    out = {}
    for f in sorted(os.listdir(d)):
        if f.endswith('.json'):
            with open(os.path.join(d, f), encoding='utf-8') as fh:
                out[ns + ':' + f[:-5]] = json.load(fh)
    return out


def from_archive(path):
    out = {}
    with zipfile.ZipFile(path) as z:
        for n in z.namelist():
            parts = n.split('/')
            # data/<ns>/worldgen/biome/<path>.json, also inside data pack overlays and bundled resource packs
            for i in range(len(parts) - 4):
                if parts[i] == 'data' and parts[i + 2:i + 4] == ['worldgen', 'biome'] and n.endswith('.json'):
                    try:
                        out[parts[i + 1] + ':' + '/'.join(parts[i + 4:])[:-5]] = json.loads(z.read(n))
                    except ValueError:
                        pass
                    break
    return out


def steps(biome):
    feats = biome.get('features', [])
    return [[f for f in (feats[i] if i < len(feats) else []) if isinstance(f, str)] for i in range(len(STEPS))]


def cycles(lists):
    """Strongly connected components with more than one feature in the graph of consecutive pairs."""
    succ = defaultdict(set)
    nodes = set()
    for lst in lists:
        nodes.update(lst)
        for a, b in zip(lst, lst[1:]):
            succ[a].add(b)
    index, low, on, stack, out, counter = {}, {}, set(), [], [], [0]

    def strong(v):
        work = [(v, iter(sorted(succ[v])))]
        index[v] = low[v] = counter[0]
        counter[0] += 1
        stack.append(v)
        on.add(v)
        while work:
            node, it = work[-1]
            advanced = False
            for w in it:
                if w not in index:
                    index[w] = low[w] = counter[0]
                    counter[0] += 1
                    stack.append(w)
                    on.add(w)
                    work.append((w, iter(sorted(succ[w]))))
                    advanced = True
                    break
                if w in on:
                    low[node] = min(low[node], index[w])
            if advanced:
                continue
            work.pop()
            if work:
                low[work[-1][0]] = min(low[work[-1][0]], low[node])
            if low[node] == index[node]:
                comp = []
                while True:
                    w = stack.pop()
                    on.discard(w)
                    comp.append(w)
                    if w == node:
                        break
                if len(comp) > 1:
                    out.append(sorted(comp))

    for v in sorted(nodes):
        if v not in index:
            strong(v)
    return out


def main():
    mode, mc, archives = sys.argv[1], sys.argv[2], sys.argv[3:]
    ours = from_dir(os.path.join(REPO, 'versions', mc, 'src', 'main', 'resources', 'data', 'biomesmod', 'worldgen', 'biome'), 'biomesmod')
    vanilla = from_dir(os.path.join(VANILLA, mc, 'mojmap', 'data', 'minecraft', 'worldgen', 'biome'), 'minecraft')
    foreign = {}
    for a in archives:
        foreign.update(from_archive(a))
    print('%s: %d vanilla, %d biomesmod, %d other biome(s)' % (mc, len(vanilla), len(ours), len(foreign)))
    if mode == 'hints':
        path = os.path.join(HERE, 'order_hints.json')
        with open(path, encoding='utf-8') as f:
            data = json.load(f)
        section = data.setdefault(mc, {})
        added = 0
        for step, name in enumerate(STEPS):
            have = {tuple(p) for p in section.get(name, [])}
            for b in foreign.values():
                lst = steps(b)[step]
                for p in zip(lst, lst[1:]):
                    if p not in have:
                        have.add(p)
                        added += 1
            if have:
                section[name] = sorted([list(p) for p in have])
        with open(path, 'w', encoding='utf-8', newline='\n') as f:
            json.dump(data, f, indent=1)
            f.write('\n')
        print('%d hint pair(s) added for %s; run generate.py' % (added, mc))
        return
    bad = 0
    for step, name in enumerate(STEPS):
        lists = [steps(b)[step] for b in list(vanilla.values()) + list(ours.values()) + list(foreign.values())]
        for comp in cycles(lists):
            bad += 1
            members = set(comp)
            who = sorted(n for n, b in list(ours.items()) + list(foreign.items()) + list(vanilla.items())
                         if len([f for f in steps(b)[step] if f in members]) > 1)
            print('CYCLE in %s among %s; biomes involved: %s%s' % (name, comp, ', '.join(who[:8]), ' ...' if len(who) > 8 else ''))
    print('no feature order cycles' if bad == 0 else '%d cycle(s)' % bad)
    sys.exit(1 if bad else 0)


if __name__ == '__main__':
    main()
