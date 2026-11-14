#!/usr/bin/env python3
"""biomegen: the spec-driven biome pipeline of biomesmod (stdlib only).

Inputs (version-agnostic, see docs/biome-specs.md):
    specs/biomes/<id>.json      one biome each
    specs/features/<id>.json    placed feature biomesmod:<id> (+ its configured feature)
    specs/regions.json          TerraBlender region weights
    specs/places.json           place catalog (optional, only used to check "places")
    specs/lang/<locale>.json    extra lang keys merged into the generated lang files (optional)

Vanilla reference data (real files, nothing hard-coded that can be derived):
    <vanilla root>/<mc>/mojmap/data/minecraft/worldgen/...   biomes, placed/configured features, carvers, noises
    <vanilla root>/<mc>/mojmap/net/minecraft/...             overworld slots, nether/end biomes, particles, sounds
    <vanilla root>/<mc>/mojmap/assets/minecraft/...          block ids, entity ids

Outputs (committed, never hand-edited):
    versions/<mc>/src/main/resources/data/biomesmod/worldgen/biome/<id>.json
    {common,versions/<mc>}/src/main/resources/data/biomesmod/worldgen/{configured,placed}_feature/<id>.json
    common/src/main/resources/biomesmod/placements.json
    common/src/main/resources/assets/biomesmod/lang/<locale>.json

Usage, from anywhere:
    python -I tools/biomegen/generate.py            regenerate (writes changed files, deletes stale ones)
    python -I tools/biomegen/generate.py --check    exit 1 if any generated file is stale; writes nothing
Options: --vanilla <dir> (default %APPDATA%/minecraft-dev-mcp/decompiled or $BIOMEGEN_VANILLA), --quiet.
"""

import argparse
import copy
import difflib
import heapq
import json
import os
import re
import sys
from collections import defaultdict

MOD = 'biomesmod'
REPO = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))
SPECS = os.path.join(REPO, 'specs')
COMMON_RES = os.path.join(REPO, 'common', 'src', 'main', 'resources')

STEPS = [
    'raw_generation', 'lakes', 'local_modifications', 'underground_structures', 'surface_structures',
    'strongholds', 'underground_ores', 'underground_decoration', 'fluid_springs', 'vegetal_decoration',
    'top_layer_modification',
]
STEP_INDEX = {name: i for i, name in enumerate(STEPS)}
SPAWN_CATEGORIES = [
    'ambient', 'axolotls', 'creature', 'misc', 'monster', 'underground_water_creature', 'water_ambient', 'water_creature',
]
# Level ids (Level.OVERWORLD / NETHER / END), build height and the "surface line" used to decide whether a
# carver can breach the surface (overworld: sea level; nether: lava sea level).
DIMENSIONS = {
    'overworld': {'level': 'minecraft:overworld', 'min_y': -64, 'height': 384, 'surface_y': 63},
    'nether': {'level': 'minecraft:the_nether', 'min_y': 0, 'height': 256, 'surface_y': 32},
    'end': {'level': 'minecraft:the_end', 'min_y': 0, 'height': 256, 'surface_y': 0},
}
DIMENSION_ORDER = ['overworld', 'nether', 'end']
END_TYPES = ['highlands', 'midlands', 'edge', 'islands']
COLOR_KEYS = ['sky', 'fog', 'water', 'water_fog', 'grass', 'foliage', 'dry_foliage']
GRASS_MODIFIERS = ['none', 'dark_forest', 'swamp']
NETHER_AXES = ['temperature', 'humidity', 'continentalness', 'erosion', 'depth', 'weirdness', 'offset']
REQUIRED_LOCALES = ['en_us', 'ko_kr']
ID_RE = re.compile(r'^[a-z0-9_]+$')
RL_RE = re.compile(r'^[a-z0-9_.\-]+:[a-z0-9_./\-]+$')
COLOR_RE = re.compile(r'^#[0-9a-fA-F]{6}$')

BIOME_KEYS = {
    'id', 'names', 'places', 'dimension', 'placement', 'base', 'climate', 'colors', 'particles', 'music', 'features',
    'spawners', 'spawn_costs', 'creature_spawn_probability', 'surface', 'carvers', 'structures',
}
FEATURE_KEYS = {'configured', 'configured_by_version', 'feature', 'placement', 'placement_by_version'}


# --------------------------------------------------------------------------------------------- utilities

def rel(path):
    return os.path.relpath(path, REPO).replace(os.sep, '/')


def strip_comments(text):
    """Removes // and /* */ comments outside JSON strings (specs may be written as JSONC)."""
    out = []
    i, n = 0, len(text)
    in_str = False
    while i < n:
        c = text[i]
        if in_str:
            out.append(c)
            if c == '\\' and i + 1 < n:
                out.append(text[i + 1])
                i += 2
                continue
            if c == '"':
                in_str = False
            i += 1
        elif c == '"':
            in_str = True
            out.append(c)
            i += 1
        elif text.startswith('//', i):
            while i < n and text[i] != '\n':
                i += 1
        elif text.startswith('/*', i):
            end = text.find('*/', i + 2)
            i = n if end < 0 else end + 2
        else:
            out.append(c)
            i += 1
    return ''.join(out)


def read_json(path, problems, comments=True):
    try:
        with open(path, encoding='utf-8-sig') as f:
            text = f.read()
        return json.loads(strip_comments(text) if comments else text)
    except (OSError, ValueError) as e:
        problems.error(rel(path), 'cannot read JSON: %s' % e)
        return None


def dump_json(obj, indent=2, sort_keys=True):
    return json.dumps(obj, indent=indent, ensure_ascii=False, sort_keys=sort_keys) + '\n'


def ns(rid):
    """Normalises an id to namespace:path (bare ids get minecraft:)."""
    return rid if ':' in rid else 'minecraft:' + rid


def suggest(word, options):
    close = difflib.get_close_matches(word, sorted(options), n=3, cutoff=0.6)
    return (' (did you mean %s?)' % ', '.join(close)) if close else ''


def is_number(v):
    return isinstance(v, (int, float)) and not isinstance(v, bool)


def is_int(v):
    return isinstance(v, int) and not isinstance(v, bool)


def version_key(mc):
    return tuple(int(p) if p.isdigit() else p for p in re.split(r'[.\-]', mc))


class Problems:
    def __init__(self):
        self.errors = []
        self.warnings = []

    def error(self, where, msg):
        self.errors.append('%s: %s' % (where, msg))

    def warn(self, where, msg):
        self.warnings.append('%s: %s' % (where, msg))


# --------------------------------------------------------------------------------------------- vanilla data

class Vanilla:
    """Real vanilla data of one Minecraft version, read from the decompiled jar."""

    def __init__(self, root, mc):
        self.mc = mc
        base = os.path.join(root, mc, 'mojmap')
        if not os.path.isdir(base):
            raise SystemExit('biomegen: vanilla data for %s not found at %s (pass --vanilla <dir>)' % (mc, base))
        self.base = base
        wg = os.path.join(base, 'data', 'minecraft', 'worldgen')
        self.biomes = self._load_dir(os.path.join(wg, 'biome'))
        self.placed = self._load_dir(os.path.join(wg, 'placed_feature'))
        self.configured = set(self._ids(os.path.join(wg, 'configured_feature')))
        self.carvers = self._load_dir(os.path.join(wg, 'configured_carver'))
        self.noises = set(self._ids(os.path.join(wg, 'noise')))
        self.modern = any('attributes' in b for b in self.biomes.values())
        src = os.path.join(base, 'net', 'minecraft')
        self.overworld_slots = self._biome_refs(self._src(src, 'world/level/biome/OverworldBiomeBuilder.java'))
        params = self._src(src, 'world/level/biome/MultiNoiseBiomeSourceParameterList.java')
        nether_block = params[params.find('Preset NETHER'):params.find('Preset OVERWORLD')]
        self.nether_biomes = self._biome_refs(nether_block)
        self.nether_points = {}
        for args, name in re.findall(r'Climate\.parameters\(([^)]*)\)\s*,\s*\S*?\(?\s*Biomes\.([A-Z0-9_]+)', nether_block):
            self.nether_points[tuple(round(float(a.strip().rstrip('Ff')), 6) for a in args.split(','))] = 'minecraft:' + name.lower()
        self.end_biomes = self._biome_refs(self._src(src, 'world/level/biome/TheEndBiomeSource.java'))
        particles = self._src(src, 'core/particles/ParticleTypes.java')
        self.simple_particles = {'minecraft:' + p for p in re.findall(r'SimpleParticleType\s+[A-Z0-9_]+\s*=\s*register\(\s*"([a-z0-9_]+)"', particles)}
        sounds = self._src(src, 'sounds/SoundEvents.java')
        self.sounds = {'minecraft:' + s for s in re.findall(r'register\w*\(\s*"([a-z0-9_.\-/]+)"', sounds)}
        self.blocks = set(self._ids(os.path.join(base, 'assets', 'minecraft', 'blockstates')))
        lang_path = os.path.join(base, 'assets', 'minecraft', 'lang', 'en_us.json')
        with open(lang_path, encoding='utf-8') as f:
            lang = json.load(f)
        self.entities = {'minecraft:' + k.split('.')[2] for k in lang if k.startswith('entity.minecraft.') and k.count('.') == 2}
        for slot in self.overworld_slots | self.nether_biomes | self.end_biomes:
            if slot not in self.biomes:
                raise SystemExit('biomegen: %s: parsed vanilla biome %s has no biome JSON' % (mc, slot))
        self.order = canonical_order(self.biomes, mc)

    @staticmethod
    def _src(src, path):
        with open(os.path.join(src, *path.split('/')), encoding='utf-8') as f:
            return f.read()

    @staticmethod
    def _biome_refs(text):
        return {'minecraft:' + n.lower() for n in re.findall(r'Biomes\.([A-Z0-9_]+)', text)}

    @staticmethod
    def _ids(directory):
        out = []
        for dirpath, _, files in os.walk(directory):
            for fn in files:
                if fn.endswith('.json'):
                    path = os.path.relpath(os.path.join(dirpath, fn[:-5]), directory).replace(os.sep, '/')
                    out.append('minecraft:' + path)
        return sorted(out)

    def _load_dir(self, directory):
        out = {}
        for rid in self._ids(directory):
            with open(os.path.join(directory, *rid[len('minecraft:'):].split('/')) + '.json', encoding='utf-8') as f:
                out[rid] = json.load(f)
        return out

    def dimension_of(self, biome):
        if biome in self.overworld_slots:
            return 'overworld'
        if biome in self.nether_biomes:
            return 'nether'
        if biome in self.end_biomes:
            return 'end'
        return None


def holder_list(value):
    """A biome feature step / carver set is a HolderSet: a list of ids, one id, or a tag."""
    if value is None:
        return []
    if isinstance(value, str):
        return [value]
    return list(value)


def feature_steps(biome_json):
    steps = [holder_list(s) for s in biome_json.get('features', [])]
    return steps + [[] for _ in range(len(STEPS) - len(steps))]


def topo_merge(lists, what):
    """Deterministic topological merge of ordered lists (Kahn; ties broken by first appearance).
    Returns (order, None) or (None, cycle_nodes)."""
    first = {}
    succ = defaultdict(list)
    indeg = defaultdict(int)
    seen_edges = set()
    for lst in lists:
        for f in lst:
            first.setdefault(f, len(first))
        for a, b in zip(lst, lst[1:]):
            if (a, b) not in seen_edges:
                seen_edges.add((a, b))
                succ[a].append(b)
                indeg[b] += 1
    heap = [(first[f], f) for f in first if indeg[f] == 0]
    heapq.heapify(heap)
    out = []
    while heap:
        _, f = heapq.heappop(heap)
        out.append(f)
        for s in succ[f]:
            indeg[s] -= 1
            if indeg[s] == 0:
                heapq.heappush(heap, (first[s], s))
    if len(out) != len(first):
        return None, sorted(f for f in first if f not in set(out))
    return out, None


def order_hints():
    """Extra "a before b" pairs per step (tools/biomegen/order_hints.json) for features that no vanilla biome orders.
    They follow other worldgen mods' orders: a biome mod listing two features the other way round than ours makes
    the game fail with "Feature order cycle found" as soon as both mods are installed."""
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'order_hints.json')
    if not os.path.exists(path):
        return {}
    with open(path, encoding='utf-8') as f:
        data = json.load(f)
    return data


def hints_for(mc):
    """Hint pairs per step index for one version: the "all" section plus the version's own (both optional)."""
    data = order_hints()
    out = defaultdict(list)
    for section in ('all', mc):
        for step, pairs in data.get(section, {}).items():
            out[STEPS.index(step)].extend(pairs)
    return out


def canonical_order(biomes, mc):
    """Per step: one global order that is a topological sort of every vanilla biome's list (plus the order hints)."""
    orders = []
    hints = hints_for(mc)
    for step in range(len(STEPS)):
        lists = [feature_steps(biomes[name])[step] for name in sorted(biomes)] + [list(p) for p in hints.get(step, [])]
        order, cycle = topo_merge(lists, 'vanilla')
        if order is None:
            raise SystemExit('biomegen: %s: vanilla feature order has a cycle in %s: %s' % (mc, STEPS[step], cycle))
        orders.append({f: i for i, f in enumerate(order)})
    return orders


# --------------------------------------------------------------------------------------------- carvers

def anchor_y(anchor, dim):
    lo = DIMENSIONS[dim]['min_y']
    hi = lo + DIMENSIONS[dim]['height']
    if 'absolute' in anchor:
        return anchor['absolute']
    if 'above_bottom' in anchor:
        return lo + anchor['above_bottom']
    if 'below_top' in anchor:
        return hi - 1 - anchor['below_top']
    raise ValueError('unknown vertical anchor %r' % anchor)


def height_max(provider, dim):
    if isinstance(provider, dict) and 'type' not in provider:
        return anchor_y(provider, dim)
    t = provider.get('type', '')
    if t in ('minecraft:constant',):
        return anchor_y(provider['value'], dim)
    if t in ('minecraft:uniform', 'minecraft:biased_to_bottom', 'minecraft:very_biased_to_bottom', 'minecraft:trapezoid'):
        return anchor_y(provider['max_inclusive'], dim)
    if t == 'minecraft:weighted_list':
        return max(height_max(e['data'], dim) for e in provider['distribution'])
    raise ValueError('unknown height provider %r' % t)


def float_max(provider, default=1.0):
    if provider is None:
        return default
    if is_number(provider):
        return float(provider)
    t = provider.get('type', '')
    if t == 'minecraft:constant':
        return float(provider['value'])
    if t == 'minecraft:uniform':
        return float(provider['max_exclusive'])
    if t in ('minecraft:trapezoid', 'minecraft:clamped_normal'):
        return float(provider['max'])
    raise ValueError('unknown float provider %r' % t)


def carver_top(carver, dim):
    """Highest y a configured carver can cut: top of its y range plus its largest vertical radius.
    Canyon: (1.5 + max thickness) * yScale * vertical radius factor. Cave-like: max(room (1.5 + 7) * yScale,
    tunnel (1.5 + 3) * vertical_radius_multiplier) - typical maxima from CaveWorldCarver/CanyonWorldCarver."""
    cfg = carver.get('config', {})
    top = height_max(cfg['y'], dim)
    y_scale = float_max(cfg.get('yScale'), 1.0)
    if carver.get('type') == 'minecraft:canyon':
        shape = cfg.get('shape', {})
        factor = shape.get('vertical_radius_default_factor', 1.0) + max(0.0, shape.get('vertical_radius_center_factor', 0.0))
        reach = (1.5 + float_max(shape.get('thickness'), 6.0)) * y_scale * factor
    else:
        reach = max((1.5 + 7.0) * y_scale, (1.5 + 3.0) * float_max(cfg.get('vertical_radius_multiplier'), 1.0))
    return top + reach


def find_carver(rid, mc, vanilla):
    """Override in this repo (version first, then common) wins over vanilla."""
    namespace, path = rid.split(':', 1)
    for res in (os.path.join(REPO, 'versions', mc, 'src', 'main', 'resources'), COMMON_RES):
        p = os.path.join(res, 'data', namespace, 'worldgen', 'configured_carver', *path.split('/')) + '.json'
        if os.path.isfile(p):
            with open(p, encoding='utf-8') as f:
                return json.load(f), rel(p)
    if rid in vanilla.carvers:
        return vanilla.carvers[rid], 'vanilla %s' % mc
    return None, None


# --------------------------------------------------------------------------------------------- spec loading

def load_specs(directory, problems):
    out = {}
    if not os.path.isdir(directory):
        return out
    for fn in sorted(os.listdir(directory)):
        if not fn.endswith('.json'):
            continue
        sid = fn[:-5]
        path = os.path.join(directory, fn)
        if not ID_RE.match(sid):
            problems.error(rel(path), 'file name must be [a-z0-9_]+.json (it becomes the id)')
            continue
        data = read_json(path, problems)
        if data is None:
            continue
        if not isinstance(data, dict):
            problems.error(rel(path), 'top level must be an object')
            continue
        out[sid] = (data, rel(path))
    return out


def unknown_keys(obj, allowed, where, problems):
    for k in obj:
        if k not in allowed and not k.startswith('_') and k != '$schema':
            problems.error(where, 'unknown key "%s"%s' % (k, suggest(k, allowed)))


# --------------------------------------------------------------------------------------------- feature specs

class FeatureOut:
    def __init__(self, fid):
        self.fid = fid
        self.configured = {}  # mc -> json or None (references an existing configured feature)
        self.placed = {}      # mc -> json


def build_feature_specs(raw, versions, vanilla, problems):
    out = {}
    defined = {fid for fid, (d, _) in raw.items() if 'feature' not in d}
    for fid, (spec, where) in raw.items():
        unknown_keys(spec, FEATURE_KEYS, where, problems)
        fo = FeatureOut(fid)
        cbv = spec.get('configured_by_version', {})
        pbv = spec.get('placement_by_version', {})
        for key, val in (('configured_by_version', cbv), ('placement_by_version', pbv)):
            if not isinstance(val, dict):
                problems.error(where, '%s must be an object keyed by version' % key)
            else:
                for mc in val:
                    if mc not in versions:
                        problems.error(where, '%s: unknown version "%s" (known: %s)' % (key, mc, ', '.join(versions)))
        if not isinstance(cbv, dict) or not isinstance(pbv, dict):
            continue
        if 'feature' in spec and ('configured' in spec or cbv):
            problems.error(where, 'use either "feature" (reference) or "configured"/"configured_by_version", not both')
            continue
        for mc in versions:
            if 'feature' in spec:
                ref = spec['feature']
                if not isinstance(ref, str) or not RL_RE.match(ns(ref)):
                    problems.error(where, '"feature" must be a configured feature id')
                    continue
                ref = ns(ref)
                if ref.startswith('minecraft:') and ref not in vanilla[mc].configured:
                    problems.error(where, 'unknown vanilla configured feature %s in %s%s' % (ref, mc, suggest(ref, vanilla[mc].configured)))
                elif ref.startswith(MOD + ':') and ref.split(':', 1)[1] not in defined:
                    problems.error(where, 'configured feature %s is not defined by any specs/features/*.json' % ref)
                fo.configured[mc] = None
            else:
                conf = cbv.get(mc, spec.get('configured'))
                if conf is None:
                    problems.error(where, 'no "configured" feature for %s (set "configured" or configured_by_version["%s"])' % (mc, mc))
                    continue
                if not isinstance(conf, dict) or not isinstance(conf.get('type'), str):
                    problems.error(where, 'configured feature for %s must be an object with a "type"' % mc)
                    continue
                fo.configured[mc] = conf
                ref = MOD + ':' + fid
            placement = pbv.get(mc, spec.get('placement', []))
            if not isinstance(placement, list) or not all(isinstance(p, dict) and isinstance(p.get('type'), str) for p in placement):
                problems.error(where, 'placement for %s must be a list of placement modifier objects with a "type"' % mc)
                continue
            fo.placed[mc] = {'feature': ref, 'placement': placement}
        out[fid] = fo
    return out


# --------------------------------------------------------------------------------------------- biome specs

class BiomeSpec:
    def __init__(self, sid, data, where):
        self.id = sid
        self.data = data
        self.where = where
        self.dimension = data.get('dimension')
        self.slots = []
        self.site = None
        self.site_also = None
        self.zone_of = None
        self.nether_point = None
        self.end = None


def check_block(block, where, what, versions, vanilla, problems):
    if not isinstance(block, str) or not RL_RE.match(ns(block)):
        problems.error(where, '%s must be a block id' % what)
        return None
    block = ns(block)
    if block.startswith('minecraft:'):
        for mc in versions:
            if block not in vanilla[mc].blocks:
                problems.error(where, '%s: unknown block %s in %s%s' % (what, block, mc, suggest(block, vanilla[mc].blocks)))
    elif block.startswith(MOD + ':'):
        state = os.path.join(COMMON_RES, 'assets', MOD, 'blockstates', block.split(':', 1)[1] + '.json')
        if not os.path.isfile(state):
            problems.warn(where, '%s: mod block %s has no blockstate in this checkout (it must exist at runtime)' % (what, block))
    else:
        problems.warn(where, '%s: block %s is from another mod' % (what, block))
    return block


def validate_biome(spec, versions, vanilla, features, places, problems):
    d, where = spec.data, spec.where
    unknown_keys(d, BIOME_KEYS, where, problems)
    if d.get('id') != spec.id:
        problems.error(where, '"id" must be "%s" (the file name)' % spec.id)
    names = d.get('names')
    if not isinstance(names, dict):
        problems.error(where, '"names" must be an object of locale -> name')
    else:
        for loc in REQUIRED_LOCALES:
            if not isinstance(names.get(loc), str) or not names.get(loc).strip():
                problems.error(where, 'names.%s is required' % loc)
        for loc, name in names.items():
            if not re.match(r'^[a-z]{2,3}_[a-z]{2,3}$', loc) or not isinstance(name, str):
                problems.error(where, 'names: bad entry "%s"' % loc)
    pl = d.get('places', [])
    if not isinstance(pl, list) or not all(is_int(p) for p in pl):
        problems.error(where, '"places" must be a list of integers')
    elif places is not None:
        for p in pl:
            if p not in places:
                problems.warn(where, 'places: %d is not an "n" in specs/places.json' % p)
    if spec.dimension not in DIMENSIONS:
        problems.error(where, '"dimension" must be one of %s' % ', '.join(DIMENSION_ORDER))
        return
    # base
    base = d.get('base')
    if not isinstance(base, str):
        problems.error(where, '"base" (vanilla biome to inherit from) is required')
    else:
        base = ns(base)
        d['base'] = base
        for mc in versions:
            if base not in vanilla[mc].biomes:
                problems.error(where, 'base: unknown vanilla biome %s in %s%s' % (base, mc, suggest(base, vanilla[mc].biomes)))
            elif vanilla[mc].dimension_of(base) not in (None, spec.dimension):
                problems.warn(where, 'base %s is a %s biome but this biome is %s' % (base, vanilla[mc].dimension_of(base), spec.dimension))
    validate_placement(spec, versions, vanilla, problems)
    validate_climate(d, where, problems)
    validate_colors(d, where, problems)
    validate_particles(d, where, versions, vanilla, problems)
    validate_music(d, where, versions, vanilla, problems)
    validate_features(spec, versions, vanilla, features, problems)
    validate_spawns(d, where, versions, vanilla, problems)
    validate_surface(spec, versions, vanilla, problems)
    validate_carvers(spec, versions, vanilla, problems)


# Vanilla climate parameter bins (Climate / OverworldBiomeBuilder), used for site climate filters.
SITE_TEMPERATURE = {'polar': (-2.0, -0.65), 'frozen': (-0.65, -0.45), 'cold': (-0.45, -0.15), 'temperate': (-0.15, 0.2), 'warm': (0.2, 0.55), 'hot': (0.55, 2.0)}
SITE_HUMIDITY = {'arid': (-2.0, -0.35), 'dry': (-0.35, -0.1), 'neutral': (-0.1, 0.1), 'wet': (0.1, 0.3), 'humid': (0.3, 2.0)}
SITE_ZONE = {'deep_ocean': (-2.0, -0.455), 'ocean': (-0.455, -0.19), 'coast': (-0.19, -0.11), 'near_inland': (-0.11, 0.03),
             'mid_inland': (0.03, 0.3), 'far_inland': (0.3, 2.0)}
SITE_TERRAIN = ('keep', 'plateau', 'lift', 'mountain', 'basin')
SITE_KEYS = {'temperature', 'humidity', 'zone', 'continentalness', 'erosion', 'size', 'ramp', 'terrain', 'elevation_m',
             'target_y', 'relief', 'weight', 'biome_threshold', 'ridges', 'zones', 'channels'}


def site_target_y(elevation_m):
    """Real elevation to block height with the default config (BiomesConfig.targetY): below 2,000 m sea level y=63 and
    1 block = 25.6 m; from 2,000 m up high ground starts at y=190 and rises 1 block per 40 m, capped at 300. The mod
    recomputes it from config/biomesmod.json at runtime; this value is the fallback and what the docs show."""
    if elevation_m < 2000:
        y = 63 + elevation_m / 25.6
    else:
        y = max(190, 63 + 2000 / 25.6) + (elevation_m - 2000) / 40
    return int(round(min(300, max(0, y))))


def validate_site(site, where, problems):
    if not isinstance(site, dict):
        problems.error(where, 'must be an object')
        return None
    unknown_keys(site, SITE_KEYS, where, problems)
    out = {}

    def bands(key, table):
        v = site.get(key)
        if v is None:
            return [-10.0, 10.0]
        if isinstance(v, str):
            v = [v]
        if not isinstance(v, list) or not v or any(b not in table for b in v):
            problems.error(where, '%s must be a list of %s' % (key, ', '.join(table)))
            return [-10.0, 10.0]
        names = list(table)
        idx = sorted(names.index(b) for b in v)
        if idx != list(range(idx[0], idx[-1] + 1)):
            problems.error(where, '%s bands must be adjacent (%s)' % (key, ', '.join(v)))
        return [table[names[idx[0]]][0], table[names[idx[-1]]][1]]

    out['temperature'] = bands('temperature', SITE_TEMPERATURE)
    out['humidity'] = bands('humidity', SITE_HUMIDITY)
    if 'continentalness' in site:
        c = site['continentalness']
        if not (isinstance(c, list) and len(c) == 2 and all(is_number(x) for x in c) and c[0] < c[1]):
            problems.error(where, 'continentalness must be [min, max]')
        out['continentalness'] = [float(c[0]), float(c[1])]
    else:
        out['continentalness'] = bands('zone', SITE_ZONE)
    if 'erosion' in site:
        e = site['erosion']
        if not (isinstance(e, list) and len(e) == 2 and all(is_number(x) for x in e) and e[0] < e[1]):
            problems.error(where, 'erosion must be [min, max]')
        out['erosion'] = [float(e[0]), float(e[1])]
    size = site.get('size')
    if not (isinstance(size, list) and len(size) == 2 and all(is_number(x) for x in size) and 64 <= size[0] <= size[1] <= 1600):
        problems.error(where, 'size must be [min, max] core radius in blocks (64..1600)')
        size = [256, 256]
    out['core'] = [float(size[0]), float(size[1])]
    ramp = site.get('ramp', 200)
    if not is_number(ramp) or not 32 <= ramp <= 800:
        problems.error(where, 'ramp must be 32..800 blocks')
    out['ramp'] = float(ramp)
    terrain = site.get('terrain', 'keep')
    if terrain not in SITE_TERRAIN:
        problems.error(where, 'terrain must be one of ' + ', '.join(SITE_TERRAIN))
    out['terrain'] = terrain
    if 'target_y' in site:
        ty = site['target_y']
        if not is_int(ty) or not 0 <= ty <= 300:
            problems.error(where, 'target_y must be an integer 0..300')
        out['target_y'] = int(ty)
    elif 'elevation_m' in site:
        if not is_number(site['elevation_m']):
            problems.error(where, 'elevation_m must be a number')
        out['target_y'] = site_target_y(site['elevation_m'])
        out['elevation_m'] = float(site['elevation_m'])
    else:
        if terrain != 'keep':
            problems.error(where, 'terrain %s needs elevation_m or target_y' % terrain)
        out['target_y'] = 63
    relief = site.get('relief', 0.5)
    if not is_number(relief) or not 0 <= relief <= 1.5:
        problems.error(where, 'relief must be 0..1.5')
    out['relief'] = float(relief)
    weight = site.get('weight', 1.0)
    if not is_number(weight) or weight <= 0:
        problems.error(where, 'weight must be > 0')
    out['weight'] = float(weight)
    thr = site.get('biome_threshold', 0.5)
    if not is_number(thr) or not 0 < thr <= 1:
        problems.error(where, 'biome_threshold must be in (0, 1]')
    out['biome_threshold'] = float(thr)
    if 'ridges' in site:
        if not is_number(site['ridges']) or not 0 <= site['ridges'] <= 160:
            problems.error(where, 'ridges must be 0..160 blocks')
        out['ridges'] = float(site['ridges'])
    if 'channels' in site:
        c = site['channels']
        if not (isinstance(c, dict) and is_int(c.get('count')) and 1 <= c['count'] <= 3 and is_number(c.get('width')) and 12 <= c['width'] <= 200
                and is_number(c.get('depth')) and 2 <= c['depth'] <= 60):
            problems.error(where, 'channels must be {"count": 1..3, "width": 12..200, "depth": 2..60}')
        else:
            out['channels'] = {'count': c['count'], 'width': float(c['width']), 'depth': float(c['depth'])}
    if 'zones' in site:
        zs = site['zones']
        if not isinstance(zs, list) or not zs:
            problems.error(where, 'zones must be a non-empty list of {"biome", "from", "to"}')
        else:
            out['zones'] = []
            for z in zs:
                if not (isinstance(z, dict) and isinstance(z.get('biome'), str) and is_number(z.get('from')) and is_number(z.get('to'))
                        and 0 <= z['from'] < z['to'] <= 1.01):
                    problems.error(where, 'each zone needs "biome", "from" and "to" with 0 <= from < to <= 1')
                    continue
                out['zones'].append({'biome': (z['biome'] if ':' in z['biome'] else MOD + ':' + z['biome']), 'from': float(z['from']), 'to': float(z['to'])})
    return out


def validate_placement(spec, versions, vanilla, problems):
    d, where = spec.data, spec.where
    p = d.get('placement')
    if not isinstance(p, dict):
        problems.error(where, '"placement" is required')
        return
    if spec.dimension == 'overworld':
        unknown_keys(p, {'replace', 'site', 'site_also', 'zone_of'}, where + ' placement', problems)
        if sum(k in p for k in ('replace', 'site', 'zone_of')) != 1:
            problems.error(where, 'overworld placement needs exactly one of "replace" (climate slots), "site" (large site) or '
                                  '"zone_of" (a sub-biome of another site biome)')
            return
        if 'zone_of' in p:
            spec.zone_of = p['zone_of'] if ':' in p['zone_of'] else MOD + ':' + p['zone_of']
            return
        if 'site' in p:
            spec.site = validate_site(p['site'], where + ' placement.site', problems)
            if 'site_also' in p:
                spec.site_also = validate_site(p['site_also'], where + ' placement.site_also', problems)
            return
        slots = p.get('replace')
        if isinstance(slots, str):
            slots = [slots]
        if not isinstance(slots, list) or not slots or not all(isinstance(s, str) for s in slots):
            problems.error(where, 'placement.replace must be a non-empty list of vanilla overworld biome ids (climate slots)')
            return
        slots = [ns(s) for s in slots]
        if len(set(slots)) != len(slots):
            problems.error(where, 'placement.replace lists a slot twice')
        for s in slots:
            for mc in versions:
                if s not in vanilla[mc].overworld_slots:
                    problems.error(where, 'placement.replace: %s is not an overworld climate slot in %s%s' % (s, mc, suggest(s, vanilla[mc].overworld_slots)))
        spec.slots = sorted(set(slots))
    elif spec.dimension == 'nether':
        unknown_keys(p, {'nether_point'}, where + ' placement', problems)
        pt = p.get('nether_point')
        if not isinstance(pt, dict):
            problems.error(where, 'placement.nether_point is required for nether biomes')
            return
        unknown_keys(pt, set(NETHER_AXES), where + ' placement.nether_point', problems)
        point = {}
        for axis in NETHER_AXES:
            v = pt.get(axis, 0.0)
            if not is_number(v) or not -2.0 <= v <= 2.0:
                problems.error(where, 'placement.nether_point.%s must be a number in [-2, 2]' % axis)
                return
            point[axis] = float(v)
        if 'temperature' not in pt or 'humidity' not in pt:
            problems.error(where, 'placement.nether_point needs temperature and humidity')
        key = tuple(round(point[a], 6) for a in NETHER_AXES)
        for mc in versions:
            if key in vanilla[mc].nether_points:
                problems.error(where, 'placement.nether_point equals the vanilla point of %s' % vanilla[mc].nether_points[key])
        spec.nether_point = point
    else:
        unknown_keys(p, {'end', 'weight'}, where + ' placement', problems)
        if p.get('end') not in END_TYPES:
            problems.error(where, 'placement.end must be one of %s' % ', '.join(END_TYPES))
            return
        w = p.get('weight', 5)
        if not is_int(w) or w <= 0:
            problems.error(where, 'placement.weight must be a positive integer')
            return
        spec.end = {'type': p['end'], 'weight': w}


def validate_climate(d, where, problems):
    c = d.get('climate', {})
    if not isinstance(c, dict):
        problems.error(where, '"climate" must be an object')
        return
    unknown_keys(c, {'temperature', 'downfall', 'has_precipitation', 'temperature_modifier'}, where + ' climate', problems)
    for k in ('temperature', 'downfall'):
        if k in c and not is_number(c[k]):
            problems.error(where, 'climate.%s must be a number' % k)
    if 'has_precipitation' in c and not isinstance(c['has_precipitation'], bool):
        problems.error(where, 'climate.has_precipitation must be true/false')
    if 'temperature_modifier' in c and c['temperature_modifier'] not in ('none', 'frozen'):
        problems.error(where, 'climate.temperature_modifier must be "none" or "frozen"')
    if 'creature_spawn_probability' in d and (not is_number(d['creature_spawn_probability']) or not 0 <= d['creature_spawn_probability'] < 1):
        problems.error(where, 'creature_spawn_probability must be a number in [0, 1)')


def validate_colors(d, where, problems):
    c = d.get('colors', {})
    if not isinstance(c, dict):
        problems.error(where, '"colors" must be an object')
        return
    unknown_keys(c, set(COLOR_KEYS) | {'grass_modifier'}, where + ' colors', problems)
    for k in COLOR_KEYS:
        if k in c and (not isinstance(c[k], str) or not COLOR_RE.match(c[k])):
            problems.error(where, 'colors.%s must be "#rrggbb" (got %r)' % (k, c[k]))
    if 'grass_modifier' in c and c['grass_modifier'] not in GRASS_MODIFIERS:
        problems.error(where, 'colors.grass_modifier must be one of %s' % ', '.join(GRASS_MODIFIERS))


def validate_particles(d, where, versions, vanilla, problems):
    if 'particles' not in d:
        return
    ps = d['particles']
    if not isinstance(ps, list):
        problems.error(where, '"particles" must be a list')
        return
    for p in ps:
        if not isinstance(p, dict) or not isinstance(p.get('type'), str) or not is_number(p.get('probability')):
            problems.error(where, 'particles: each entry needs "type" and "probability"')
            continue
        unknown_keys(p, {'type', 'probability'}, where + ' particles', problems)
        p['type'] = ns(p['type'])
        if not 0 < p['probability'] <= 1:
            problems.error(where, 'particles: probability must be in (0, 1]')
        for mc in versions:
            if p['type'] not in vanilla[mc].simple_particles:
                problems.error(where, 'particles: %s is not a simple particle type in %s%s' % (p['type'], mc, suggest(p['type'], vanilla[mc].simple_particles)))
    if len(ps) > 1:
        problems.warn(where, 'particles: 1.21.1-style biomes keep only the first particle entry')


def music_obj(m):
    if isinstance(m, str):
        return {'sound': ns(m), 'min_delay': 12000, 'max_delay': 24000, 'replace_current_music': False}
    return {'sound': ns(m['sound']), 'min_delay': m.get('min_delay', 12000), 'max_delay': m.get('max_delay', 24000),
            'replace_current_music': m.get('replace_current_music', False)}


def validate_music(d, where, versions, vanilla, problems):
    if 'music' not in d:
        return
    m = d['music']
    if isinstance(m, dict):
        unknown_keys(m, {'sound', 'min_delay', 'max_delay', 'replace_current_music'}, where + ' music', problems)
        if not isinstance(m.get('sound'), str):
            problems.error(where, 'music.sound is required')
            return
        if not is_int(m.get('min_delay', 0)) or not is_int(m.get('max_delay', 0)) or m.get('min_delay', 12000) > m.get('max_delay', 24000):
            problems.error(where, 'music.min_delay/max_delay must be integers with min <= max')
    elif not isinstance(m, str):
        problems.error(where, '"music" must be a sound event id or an object')
        return
    sound = music_obj(m)['sound']
    for mc in versions:
        if sound.startswith('minecraft:') and sound not in vanilla[mc].sounds:
            problems.error(where, 'music: unknown sound event %s in %s%s' % (sound, mc, suggest(sound, vanilla[mc].sounds)))


def feature_sections(spec, mc):
    """Merged remove/clear/add/mod for one version (top level + features.by_version[mc])."""
    f = spec.data.get('features', {})
    parts = [f]
    bv = f.get('by_version', {}) if isinstance(f, dict) else {}
    if isinstance(bv, dict) and isinstance(bv.get(mc), dict):
        parts.append(bv[mc])
    remove, clear, add, mod = [], [], defaultdict(list), defaultdict(list)
    for p in parts:
        remove += [ns(x) for x in p.get('remove', [])]
        clear += p.get('clear', [])
        for step, ids in p.get('add', {}).items():
            add[step] += [ns(x) for x in ids]
        for step, ids in p.get('mod', {}).items():
            mod[step] += [x if ':' in x else MOD + ':' + x for x in ids]
    return remove, clear, add, mod


def validate_features(spec, versions, vanilla, features, problems):
    d, where = spec.data, spec.where
    f = d.get('features', {})
    if not isinstance(f, dict):
        problems.error(where, '"features" must be an object')
        return
    blocks = [('features', f, versions)]
    unknown_keys(f, {'remove', 'add', 'mod', 'clear', 'by_version'}, where + ' features', problems)
    bv = f.get('by_version', {})
    if not isinstance(bv, dict):
        problems.error(where, 'features.by_version must be an object keyed by version')
        return
    for mc, sub in bv.items():
        if mc not in versions:
            problems.error(where, 'features.by_version: unknown version "%s" (known: %s)' % (mc, ', '.join(versions)))
            continue
        if not isinstance(sub, dict):
            problems.error(where, 'features.by_version.%s must be an object' % mc)
            continue
        unknown_keys(sub, {'remove', 'add', 'mod', 'clear'}, where + ' features.by_version.' + mc, problems)
        blocks.append(('features.by_version.' + mc, sub, [mc]))
    base = d.get('base') if isinstance(d.get('base'), str) else ''
    for label, blk, vers in blocks:
        rem = blk.get('remove', [])
        if not isinstance(rem, list) or not all(isinstance(x, str) for x in rem):
            problems.error(where, '%s.remove must be a list of placed feature ids' % label)
        else:
            for x in rem:
                x = ns(x)
                if not any(x in vanilla[mc].placed for mc in versions):
                    problems.error(where, '%s.remove: unknown vanilla placed feature %s%s' % (label, x, suggest(x, vanilla[vers[0]].placed)))
                    continue
                in_base = False
                for mc in vers:
                    if base in vanilla[mc].biomes:
                        in_base |= any(x in lst for lst in feature_steps(vanilla[mc].biomes[base]))
                if not in_base:
                    problems.warn(where, '%s.remove: %s is not in base %s (no effect)' % (label, x, base))
        clear = blk.get('clear', [])
        if not isinstance(clear, list) or any(s not in STEP_INDEX for s in clear):
            problems.error(where, '%s.clear must be a list of step names (%s)' % (label, ', '.join(STEPS)))
        for key in ('add', 'mod'):
            sect = blk.get(key, {})
            if not isinstance(sect, dict):
                problems.error(where, '%s.%s must be an object of step -> [ids]' % (label, key))
                continue
            for step, ids in sect.items():
                if step not in STEP_INDEX:
                    problems.error(where, '%s.%s: unknown step "%s"%s' % (label, key, step, suggest(step, STEPS)))
                    continue
                if not isinstance(ids, list) or not all(isinstance(x, str) for x in ids):
                    problems.error(where, '%s.%s.%s must be a list of ids' % (label, key, step))
                    continue
                if len(set(ids)) != len(ids):
                    problems.error(where, '%s.%s.%s lists a feature twice' % (label, key, step))
                for x in ids:
                    if key == 'add':
                        x = ns(x)
                        if not x.startswith('minecraft:'):
                            problems.error(where, '%s.add.%s: %s is not vanilla; mod features go in "mod"' % (label, step, x))
                            continue
                        for mc in vers:
                            if x not in vanilla[mc].placed:
                                hint = ' (exists only in another version: put it in features.by_version)' if any(x in vanilla[o].placed for o in versions) else suggest(x, vanilla[mc].placed)
                                problems.error(where, '%s.add.%s: unknown vanilla placed feature %s in %s%s' % (label, step, x, mc, hint))
                    else:
                        x = x if ':' in x else MOD + ':' + x
                        nsp, fid = x.split(':', 1)
                        if nsp != MOD:
                            problems.error(where, '%s.mod.%s: %s is not a biomesmod feature; vanilla features go in "add"' % (label, step, x))
                        elif fid not in features:
                            problems.error(where, '%s.mod.%s: missing mod feature %s (no specs/features/%s.json)%s' % (label, step, x, fid, suggest(fid, features)))


def validate_spawns(d, where, versions, vanilla, problems):
    sp = d.get('spawners', 'base')
    if sp != 'base':
        if not isinstance(sp, dict):
            problems.error(where, '"spawners" must be "base" or a vanilla spawners object')
            return
        unknown_keys(sp, set(SPAWN_CATEGORIES), where + ' spawners', problems)
        for cat, entries in sp.items():
            if cat not in SPAWN_CATEGORIES:
                continue
            if not isinstance(entries, list):
                problems.error(where, 'spawners.%s must be a list' % cat)
                continue
            for e in entries:
                if not isinstance(e, dict) or not isinstance(e.get('type'), str):
                    problems.error(where, 'spawners.%s: each entry needs "type", "weight", "minCount", "maxCount"' % cat)
                    continue
                unknown_keys(e, {'type', 'weight', 'minCount', 'maxCount'}, where + ' spawners.' + cat, problems)
                e['type'] = ns(e['type'])
                if not all(is_int(e.get(k)) for k in ('weight', 'minCount', 'maxCount')) or e['weight'] <= 0 \
                        or e['minCount'] < 1 or e['maxCount'] < e['minCount']:
                    problems.error(where, 'spawners.%s: %s needs integer weight > 0 and 1 <= minCount <= maxCount' % (cat, e['type']))
                for mc in versions:
                    if e['type'].startswith('minecraft:') and e['type'] not in vanilla[mc].entities:
                        problems.error(where, 'spawners.%s: unknown entity %s in %s%s' % (cat, e['type'], mc, suggest(e['type'], vanilla[mc].entities)))
    sc = d.get('spawn_costs', 'base')
    if sc != 'base' and not isinstance(sc, dict):
        problems.error(where, '"spawn_costs" must be "base" or an object')


def validate_surface(spec, versions, vanilla, problems):
    d, where = spec.data, spec.where
    if 'surface' not in d:
        return
    s = d['surface']
    if not isinstance(s, dict):
        problems.error(where, '"surface" must be an object')
        return
    unknown_keys(s, {'top', 'under', 'under_depth', 'underwater', 'top_patches'}, where + ' surface', problems)
    if spec.dimension == 'end':
        problems.error(where, 'surface rules are supported for overworld and nether biomes only')
        return
    if spec.dimension == 'nether' and 'underwater' in s:
        problems.error(where, 'surface.underwater has no meaning in the nether')
    for k in ('top', 'under', 'underwater'):
        if k in s:
            s[k] = check_block(s[k], where, 'surface.' + k, versions, vanilla, problems)
    if 'under_depth' in s:
        if not is_int(s['under_depth']) or not 1 <= s['under_depth'] <= 64:
            problems.error(where, 'surface.under_depth must be an integer 1..64')
        if 'under' not in s:
            problems.error(where, 'surface.under_depth needs surface.under')
    patches = s.get('top_patches', [])
    if not isinstance(patches, list):
        problems.error(where, 'surface.top_patches must be a list')
        return
    for p in patches:
        if not isinstance(p, dict):
            problems.error(where, 'surface.top_patches entries must be objects')
            continue
        unknown_keys(p, {'block', 'noise', 'min', 'max'}, where + ' surface.top_patches', problems)
        p['block'] = check_block(p.get('block'), where, 'surface.top_patches.block', versions, vanilla, problems)
        p['noise'] = ns(p.get('noise', 'minecraft:surface'))
        for mc in versions:
            if p['noise'].startswith('minecraft:') and p['noise'] not in vanilla[mc].noises:
                problems.error(where, 'surface.top_patches: unknown noise %s in %s%s' % (p['noise'], mc, suggest(p['noise'], vanilla[mc].noises)))
        if not is_number(p.get('min')) or ('max' in p and (not is_number(p['max']) or p['max'] <= p['min'])):
            problems.error(where, 'surface.top_patches: "min" (number) is required; "max" must be greater than "min"')


def validate_carvers(spec, versions, vanilla, problems):
    d, where = spec.data, spec.where
    c = d.get('carvers', 'base')
    if c in ('base', 'underground'):
        return
    if not isinstance(c, list) or not all(isinstance(x, str) for x in c):
        problems.error(where, '"carvers" must be "base", "underground" or a list of configured carver ids')
        return
    for x in c:
        x = ns(x)
        for mc in versions:
            carver, _ = find_carver(x, mc, vanilla[mc])
            if carver is None:
                problems.error(where, 'carvers: unknown configured carver %s in %s' % (x, mc))
                continue
            base = vanilla[mc].biomes.get(ns(d['base'])) if isinstance(d.get('base'), str) else None
            raw = (base or {}).get('carvers', {})
            base_ids = holder_list(raw.get('air')) + holder_list(raw.get('liquid')) if isinstance(raw, dict) else holder_list(raw)
            if x not in base_ids and spec.dimension != 'end' and carver_top(carver, spec.dimension) >= DIMENSIONS[spec.dimension]['surface_y']:
                problems.warn(where, 'carvers: %s is not a base carver and can breach the surface in %s (naturalness rules: do not reintroduce surface-breaching carvers)' % (x, mc))


# --------------------------------------------------------------------------------------------- building

def color_int(c):
    return int(c[1:], 16)


def build_carvers(spec, mc, v, base_json, problems):
    c = spec.data.get('carvers', 'base')
    if c in ('base', 'underground'):
        raw = base_json.get('carvers', {})
        ids = holder_list(raw.get('air')) + holder_list(raw.get('liquid')) if isinstance(raw, dict) else holder_list(raw)
    else:
        ids = [ns(x) for x in c]
    if c == 'underground':
        kept = []
        for x in ids:
            carver, source = find_carver(x, mc, v)
            if carver is None:
                problems.error(spec.where, 'carvers: cannot resolve %s in %s' % (x, mc))
                continue
            top = carver_top(carver, spec.dimension)
            if top < DIMENSIONS[spec.dimension]['surface_y']:
                kept.append(x)
        ids = kept
    if v.modern:
        return ids
    return {'air': ids} if ids else {}


def build_biome(spec, mc, v, mod_order, extra_order, problems):
    d = spec.data
    base = v.biomes[d['base']]
    out = {}
    climate = d.get('climate', {})
    out['has_precipitation'] = climate.get('has_precipitation', base['has_precipitation'])
    out['temperature'] = climate.get('temperature', base['temperature'])
    out['downfall'] = climate.get('downfall', base['downfall'])
    if 'temperature_modifier' in climate:
        if climate['temperature_modifier'] != 'none':
            out['temperature_modifier'] = climate['temperature_modifier']
    elif 'temperature_modifier' in base:
        out['temperature_modifier'] = base['temperature_modifier']
    if 'creature_spawn_probability' in d:
        out['creature_spawn_probability'] = d['creature_spawn_probability']
    elif 'creature_spawn_probability' in base:
        out['creature_spawn_probability'] = base['creature_spawn_probability']

    effects = copy.deepcopy(base.get('effects', {}))
    colors = d.get('colors', {})
    if v.modern:
        attributes = copy.deepcopy(base.get('attributes', {}))
        for key, attr in (('sky', 'sky_color'), ('fog', 'fog_color'), ('water_fog', 'water_fog_color')):
            if key in colors:
                attributes['minecraft:visual/' + attr] = colors[key].lower()
        for key, eff in (('water', 'water_color'), ('grass', 'grass_color'), ('foliage', 'foliage_color'), ('dry_foliage', 'dry_foliage_color')):
            if key in colors:
                effects[eff] = colors[key].lower()
        if 'particles' in d:
            attributes.pop('minecraft:visual/ambient_particles', None)
            if d['particles']:
                attributes['minecraft:visual/ambient_particles'] = [
                    {'particle': {'type': p['type']}, 'probability': p['probability']} for p in d['particles']]
        if 'music' in d:
            m = music_obj(d['music'])
            music = {'sound': m['sound'], 'min_delay': m['min_delay'], 'max_delay': m['max_delay']}
            if m['replace_current_music']:
                music['replace_current_music'] = True
            bg = attributes.get('minecraft:audio/background_music', {})
            bg['default'] = music
            attributes['minecraft:audio/background_music'] = bg
        out['attributes'] = attributes
    else:
        for key, eff in (('sky', 'sky_color'), ('fog', 'fog_color'), ('water_fog', 'water_fog_color'), ('water', 'water_color'),
                         ('grass', 'grass_color'), ('foliage', 'foliage_color')):
            if key in colors:
                effects[eff] = color_int(colors[key])
        if 'particles' in d:
            effects.pop('particle', None)
            if d['particles']:
                p = d['particles'][0]
                effects['particle'] = {'options': {'type': p['type']}, 'probability': p['probability']}
        if 'music' in d:
            effects['music'] = music_obj(d['music'])
    if 'grass_modifier' in colors:
        if colors['grass_modifier'] == 'none':
            effects.pop('grass_color_modifier', None)
        else:
            effects['grass_color_modifier'] = colors['grass_modifier']
    out['effects'] = effects
    out['carvers'] = build_carvers(spec, mc, v, base, problems)
    out['features'] = build_features(spec, mc, v, base, mod_order, extra_order, problems)
    sp = d.get('spawners', 'base')
    if sp == 'base':
        out['spawners'] = copy.deepcopy(base['spawners'])
    else:
        out['spawners'] = {cat: copy.deepcopy(sp.get(cat, [])) for cat in SPAWN_CATEGORIES}
    sc = d.get('spawn_costs', 'base')
    out['spawn_costs'] = copy.deepcopy(base.get('spawn_costs', {}) if sc == 'base' else sc)
    return out


def vanilla_step_lists(spec, mc, v):
    """Vanilla part of each step, unsorted: base - clear - remove + add."""
    base = v.biomes[spec.data['base']]
    remove, clear, add, _ = feature_sections(spec, mc)
    steps = feature_steps(base)
    remove = set(remove)
    clear = {STEP_INDEX[s] for s in clear}
    out = []
    for i in range(len(STEPS)):
        lst = [] if i in clear else [f for f in steps[i] if f not in remove]
        for f in add.get(STEPS[i], []):
            if f not in lst and f not in remove:
                lst.append(f)
        out.append(lst)
    return out


def build_features(spec, mc, v, base, mod_order, extra_order, problems):
    _, _, _, mod = feature_sections(spec, mc)
    out = []
    for i, lst in enumerate(vanilla_step_lists(spec, mc, v)):
        canon = v.order[i]
        lst = sorted(lst, key=lambda f: (0, canon[f]) if f in canon else (1, extra_order[mc][i].index(f)))
        mods = sorted(mod.get(STEPS[i], []), key=lambda f: mod_order[i].index(f))
        out.append(lst + mods)
    while out and not out[-1]:
        out.pop()
    return out


def global_orders(specs, versions, vanilla, problems):
    """Mod feature order per step (topological merge of every spec's mod lists) and, per version and step, the
    order of vanilla features that no vanilla biome uses in that step (appended after the canonical ones)."""
    mod_order = []
    for i, step in enumerate(STEPS):
        lists = []
        owners = []
        for s in specs:
            for mc in versions:
                lst = feature_sections(s, mc)[3].get(step, [])
                if lst:
                    lists.append(lst)
                    owners.append(s.id)
        order, cycle = topo_merge(lists, 'mod')
        if order is None:
            involved = sorted({o for o, lst in zip(owners, lists) if len(set(lst) & set(cycle)) > 1})
            problems.error('features.mod', 'conflicting mod feature order in %s between biomes %s (features %s); list shared mod features in one order everywhere'
                           % (step, ', '.join(involved), ', '.join(cycle)))
            order = []
        mod_order.append(order)
    extra = {}
    for mc in versions:
        extra[mc] = []
        for i, step in enumerate(STEPS):
            names = set()
            for s in specs:
                for f in vanilla_step_lists(s, mc, vanilla[mc])[i]:
                    if f not in vanilla[mc].order[i]:
                        names.add(f)
                        problems.warn(s.where, '%s: no vanilla biome uses %s in %s; it is placed after the vanilla features of that step'
                                      % (mc, f, step))
            extra[mc].append(sorted(names))
    return mod_order, extra


# --------------------------------------------------------------------------------------------- self-test

def feature_order_selftest(mc, sources, problems):
    """Like Minecraft's FeatureSorter: nodes are (step, feature); every biome adds an edge between consecutive
    features of its flattened list. Any cycle crashes the game at startup. Also reports conflicting pairs."""
    succ = defaultdict(set)
    edge_owner = {}
    pair_seen = {}
    for name, steps in sources:
        flat = [(i, f) for i, lst in enumerate(steps) for f in lst]
        if len(set(flat)) != len(flat):
            problems.error('selftest %s' % mc, '%s lists a feature twice in one step' % name)
        for a, b in zip(flat, flat[1:]):
            if b not in succ[a]:
                succ[a].add(b)
                edge_owner[(a, b)] = name
        for i, lst in enumerate(steps):
            for x in range(len(lst)):
                for y in range(x + 1, len(lst)):
                    a, b = lst[x], lst[y]
                    if (i, b, a) in pair_seen and (i, a, b) not in pair_seen:
                        problems.error('selftest %s' % mc, 'feature order conflict in %s: %s puts %s before %s, %s puts it after'
                                       % (STEPS[i], pair_seen[(i, b, a)], b, a, name))
                    pair_seen.setdefault((i, a, b), name)
    # cycle detection (iterative DFS)
    WHITE, GREY, BLACK = 0, 1, 2
    color = defaultdict(int)
    for start in sorted(succ):
        if color[start] != WHITE:
            continue
        stack = [(start, iter(sorted(succ[start])))]
        path = [start]
        color[start] = GREY
        while stack:
            node, it = stack[-1]
            nxt = next(it, None)
            if nxt is None:
                color[node] = BLACK
                stack.pop()
                path.pop()
            elif color[nxt] == GREY:
                cyc = path[path.index(nxt):] + [nxt]
                owners = sorted({edge_owner.get((a, b), '?') for a, b in zip(cyc, cyc[1:])})
                problems.error('selftest %s' % mc, 'Feature order cycle: %s (biomes: %s)'
                               % (' -> '.join('%s@%s' % (f, STEPS[s]) for s, f in cyc), ', '.join(owners)))
                return False
            elif color[nxt] == WHITE:
                color[nxt] = GREY
                path.append(nxt)
                stack.append((nxt, iter(sorted(succ[nxt]))))
    return True


# --------------------------------------------------------------------------------------------- placements

def pack_regions(specs, regions_cfg, problems):
    """Greedy first-fit: biomes with more slots first, then by id; each region replaces a vanilla slot at most once."""
    cfg = regions_cfg.get('overworld', {})
    default_w = cfg.get('default_weight', 3)
    weights = cfg.get('weights', {})
    regions = []
    for s in sorted(specs, key=lambda s: (-len(s.slots), s.id)):
        for r in regions:
            if not any(slot in r for slot in s.slots):
                break
        else:
            r = {}
            regions.append(r)
        for slot in s.slots:
            r[slot] = MOD + ':' + s.id
    out = []
    for i, r in enumerate(regions):
        w = weights.get(str(i + 1), default_w)
        if not is_int(w) or w <= 0:
            problems.error('specs/regions.json', 'overworld weight for region %d must be a positive integer' % (i + 1))
        out.append({'name': '%s:overworld_%d' % (MOD, i + 1), 'weight': w, 'replace': {k: r[k] for k in sorted(r)}})
    return out


def build_sites(specs, regions_cfg, problems):
    cfg = regions_cfg.get('sites', {})
    grid = {'cell': cfg.get('cell', 2048), 'jitter': cfg.get('jitter', 0.8), 'chance': cfg.get('chance', 0.85), 'warp': cfg.get('warp', 90)}
    if not is_int(grid['cell']) or grid['cell'] < 256:
        problems.error('specs/regions.json', 'sites.cell must be an integer >= 256')
    out = []
    zone_of = {MOD + ':' + s.id: s for s in specs if s.zone_of}
    used = set()
    for s in specs:
        if s.site:
            e = {'biome': MOD + ':' + s.id}
            e.update(s.site)
            for z in s.site.get('zones', []):
                child = zone_of.get(z['biome'])
                if child is None or child.zone_of != MOD + ':' + s.id:
                    problems.error(s.where, 'zone %s must be a biome spec with placement {"zone_of": "%s"}' % (z['biome'], s.id))
                used.add(z['biome'])
            out.append(e)
            if s.site_also:
                e2 = {'biome': MOD + ':' + s.id}
                e2.update(s.site_also)
                out.append(e2)
    for bid, child in zone_of.items():
        if bid not in used:
            problems.error(child.where, 'zone_of %s, but that site does not list this biome in its zones' % child.zone_of)
    return {'grid': grid, 'list': out}


def build_placements(specs, regions_cfg, problems):
    by_dim = {dim: sorted([s for s in specs if s.dimension == dim], key=lambda s: s.id) for dim in DIMENSION_ORDER}
    placements = {
        '_generated': 'by tools/biomegen/generate.py from specs/ - do not edit',
        'format': 1,
        'biomes': [{'id': MOD + ':' + s.id, 'dimension': DIMENSIONS[dim]['level']} for dim in DIMENSION_ORDER for s in by_dim[dim]],
        'overworld_regions': pack_regions([s for s in by_dim['overworld'] if s.slots], regions_cfg, problems),
        'nether_regions': [],
        'end': [{'biome': MOD + ':' + s.id, 'type': s.end['type'], 'weight': s.end['weight']} for s in by_dim['end'] if s.end],
        'surface': {'overworld': [], 'nether': []},
        'sites': build_sites(by_dim['overworld'], regions_cfg, problems),
    }
    seen = {}
    points = []
    for s in by_dim['nether']:
        if not s.nether_point:
            continue
        key = tuple(s.nether_point[a] for a in NETHER_AXES)
        if key in seen:
            problems.error(s.where, 'placement.nether_point is identical to %s' % seen[key])
        seen[key] = s.id
        points.append(dict([('biome', MOD + ':' + s.id)] + [(a, s.nether_point[a]) for a in NETHER_AXES]))
    if points:
        w = regions_cfg.get('nether', {}).get('default_weight', 2)
        if not is_int(w) or w <= 0:
            problems.error('specs/regions.json', 'nether default_weight must be a positive integer')
        placements['nether_regions'].append({'name': MOD + ':nether_1', 'weight': w, 'vanilla_points': True, 'points': points})
    for dim in ('overworld', 'nether'):
        for s in by_dim[dim]:
            sf = s.data.get('surface')
            if not sf:
                continue
            entry = {'biome': MOD + ':' + s.id}
            for k in ('top', 'under', 'under_depth', 'underwater'):
                if k in sf:
                    entry[k] = sf[k]
            if sf.get('top_patches'):
                entry['top_patches'] = [dict([('block', p['block']), ('noise', p['noise']), ('min', p['min'])] + ([('max', p['max'])] if 'max' in p else []))
                                        for p in sf['top_patches']]
            placements['surface'][dim].append(entry)
    return placements


# --------------------------------------------------------------------------------------------- output sync

def vanilla_biome_tags(v):
    """Vanilla biome tags of one version: {tag path relative to tags/worldgen/biome, no extension: [values]}."""
    root = os.path.join(v.base, 'data', 'minecraft', 'tags', 'worldgen', 'biome')
    tags = {}
    for d, _dirs, files in os.walk(root):
        for fn in files:
            if fn.endswith('.json'):
                with open(os.path.join(d, fn), encoding='utf-8') as f:
                    values = json.load(f).get('values', [])
                tags[os.path.relpath(os.path.join(d, fn), root).replace(os.sep, '/')[:-5]] = [x if isinstance(x, str) else x.get('id') for x in values]
    return tags


def build_biome_tags(specs, versions, vanilla, features, problems):
    """Every mod biome joins the vanilla biome tags that list its `base` biome directly, so it gets what the base gets:
    strongholds, mineshafts, trial chambers, villages, mob variants, ... (tags that include other tags follow by
    themselves). `"structures"`: "base" keeps every has_structure tag of the base, "none" drops them, and the default
    "auto" drops them only where a terrain shaper rebuilds the ground after structures were planned (a village laid out
    on the original surface would end up buried or in the air). Underground structures tied to is_overworld stay."""
    out = {}
    for mc in versions:
        tags = vanilla_biome_tags(vanilla[mc])
        per = defaultdict(list)
        for s in sorted(specs, key=lambda s: s.id):
            mode = s.data.get('structures', 'auto')
            if mode not in ('auto', 'base', 'none'):
                problems.error(s.where, '"structures" must be "auto", "base" or "none"')
                continue
            base = s.data.get('base')
            shaped = any(isinstance(features[x.split(':', 1)[1]].configured.get(mc), dict)
                         and features[x.split(':', 1)[1]].configured[mc].get('type') == MOD + ':terrain_shaper'
                         for ids in feature_sections(s, mc)[3].values() for x in ids if x.split(':', 1)[1] in features)
            for tag, values in tags.items():
                if base not in values:
                    continue
                if tag.startswith('has_structure/') and (mode == 'none' or (mode == 'auto' and shaped)):
                    continue
                per[tag].append(MOD + ':' + s.id)
        out[mc] = {tag: dump_json({'replace': False, 'values': ids}) for tag, ids in per.items()}
    return out


def owned_dirs(versions):
    dirs = []
    for res in [COMMON_RES] + [os.path.join(REPO, 'versions', mc, 'src', 'main', 'resources') for mc in versions]:
        dirs.append(os.path.join(res, 'data', 'minecraft', 'tags', 'worldgen', 'biome'))
        dirs.append(os.path.join(res, 'data', 'minecraft', 'tags', 'worldgen', 'biome', 'has_structure'))
        for kind in ('configured_feature', 'placed_feature'):
            dirs.append(os.path.join(res, 'data', MOD, 'worldgen', kind))
    for mc in versions:
        dirs.append(os.path.join(REPO, 'versions', mc, 'src', 'main', 'resources', 'data', MOD, 'worldgen', 'biome'))
    dirs.append(os.path.join(COMMON_RES, 'assets', MOD, 'lang'))
    return dirs


def sync(outputs, versions, check, quiet):
    existing = set()
    for d in owned_dirs(versions):
        if os.path.isdir(d):
            for fn in os.listdir(d):
                if fn.endswith('.json'):
                    existing.add(os.path.normpath(os.path.join(d, fn)))
    outputs = {os.path.normpath(p): c for p, c in outputs.items()}
    changed, stale = [], sorted(existing - set(outputs))
    for path, content in sorted(outputs.items()):
        try:
            with open(path, encoding='utf-8', newline='') as f:
                current = f.read()
        except OSError:
            current = None
        if current != content:
            changed.append(path)
    if check:
        for p in changed:
            print('stale: %s' % rel(p))
        for p in stale:
            print('not generated by any spec: %s' % rel(p))
        if changed or stale:
            print('biomegen --check: %d stale, %d extra file(s). Run: python -I tools/biomegen/generate.py' % (len(changed), len(stale)))
            return 1
        if not quiet:
            print('biomegen --check: all %d generated files are up to date' % len(outputs))
        return 0
    for p in changed:
        os.makedirs(os.path.dirname(p), exist_ok=True)
        with open(p, 'w', encoding='utf-8', newline='\n') as f:
            f.write(outputs[p])
        if not quiet:
            print('wrote   %s' % rel(p))
    for p in stale:
        os.remove(p)
        if not quiet:
            print('removed %s' % rel(p))
    print('biomegen: %d file(s) generated, %d changed, %d removed' % (len(outputs), len(changed), len(stale)))
    return 0


# --------------------------------------------------------------------------------------------- main

def discover_versions():
    versions = []
    root = os.path.join(REPO, 'versions')
    for name in os.listdir(root):
        props = os.path.join(root, name, 'gradle.properties')
        if os.path.isfile(props):
            with open(props, encoding='utf-8') as f:
                m = re.search(r'^minecraft_version\s*=\s*(\S+)', f.read(), re.M)
            if m and m.group(1) == name:
                versions.append(name)
    return sorted(versions, key=version_key)


def main(argv=None):
    ap = argparse.ArgumentParser(description='Generate biome/feature JSON, lang and placements from specs/.')
    ap.add_argument('--check', action='store_true', help='only verify that generated files are up to date')
    ap.add_argument('--vanilla', default=os.environ.get('BIOMEGEN_VANILLA') or os.path.join(
        os.environ.get('APPDATA', os.path.expanduser('~')), 'minecraft-dev-mcp', 'decompiled'), help='decompiled vanilla root')
    ap.add_argument('--quiet', action='store_true')
    args = ap.parse_args(argv)
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8')

    problems = Problems()
    versions = discover_versions()
    vanilla = {mc: Vanilla(args.vanilla, mc) for mc in versions}

    feature_raw = load_specs(os.path.join(SPECS, 'features'), problems)
    biome_raw = load_specs(os.path.join(SPECS, 'biomes'), problems)
    regions_path = os.path.join(SPECS, 'regions.json')
    regions_cfg = read_json(regions_path, problems) if os.path.isfile(regions_path) else {}
    places = None
    places_path = os.path.join(SPECS, 'places.json')
    if os.path.isfile(places_path):
        pdata = read_json(places_path, problems, comments=False)
        if isinstance(pdata, list):
            places = {p.get('n') for p in pdata if isinstance(p, dict)}
    features = build_feature_specs(feature_raw, versions, vanilla, problems)
    specs = [BiomeSpec(sid, data, where) for sid, (data, where) in biome_raw.items()]
    for s in specs:
        validate_biome(s, versions, vanilla, feature_raw, places, problems)
    if problems.errors:
        return report(problems)
    used = {x.split(':', 1)[1] for s in specs for mc in versions for ids in feature_sections(s, mc)[3].values() for x in ids}
    for fid in features:
        if fid not in used:
            problems.warn('specs/features/%s.json' % fid, 'not used by any biome (still generated)')

    mod_order, extra_order = global_orders(specs, versions, vanilla, problems)
    if problems.errors:
        return report(problems)

    outputs = {}
    generated_biomes = {mc: [] for mc in versions}
    for s in sorted(specs, key=lambda s: s.id):
        for mc in versions:
            biome = build_biome(s, mc, vanilla[mc], mod_order, extra_order, problems)
            generated_biomes[mc].append((MOD + ':' + s.id, biome))
            path = os.path.join(REPO, 'versions', mc, 'src', 'main', 'resources', 'data', MOD, 'worldgen', 'biome', s.id + '.json')
            outputs[path] = dump_json(biome)
    for fid, fo in sorted(features.items()):
        for kind, per in (('configured_feature', fo.configured), ('placed_feature', fo.placed)):
            texts = {mc: dump_json(per[mc]) for mc in versions if per.get(mc) is not None}
            if not texts:
                continue
            if len(set(texts.values())) == 1 and len(texts) == len(versions):
                outputs[os.path.join(COMMON_RES, 'data', MOD, 'worldgen', kind, fid + '.json')] = next(iter(texts.values()))
            else:
                for mc, text in texts.items():
                    outputs[os.path.join(REPO, 'versions', mc, 'src', 'main', 'resources', 'data', MOD, 'worldgen', kind, fid + '.json')] = text

    tag_out = build_biome_tags(specs, versions, vanilla, features, problems)
    for tag in sorted({t for mc in versions for t in tag_out[mc]}):
        texts = {mc: tag_out[mc][tag] for mc in versions if tag in tag_out[mc]}
        sub = os.path.join('data', 'minecraft', 'tags', 'worldgen', 'biome', *(tag + '.json').split('/'))
        if len(set(texts.values())) == 1 and len(texts) == len(versions):
            outputs[os.path.join(COMMON_RES, sub)] = next(iter(texts.values()))
        else:
            for mc, text in texts.items():
                outputs[os.path.join(REPO, 'versions', mc, 'src', 'main', 'resources', sub)] = text

    placements = build_placements(specs, regions_cfg if isinstance(regions_cfg, dict) else {}, problems)
    outputs[os.path.join(COMMON_RES, MOD, 'placements.json')] = dump_json(placements, sort_keys=False)
    build_lang(specs, outputs, problems)

    # self-tests: feature order (vanilla + generated, per version) and identical placed features
    for mc in versions:
        sources = [(name, feature_steps(b)) for name, b in sorted(vanilla[mc].biomes.items())]
        sources += [(name, feature_steps(b)) for name, b in generated_biomes[mc]]
        if feature_order_selftest(mc, sources, problems) and not args.quiet:
            print('selftest %s: feature order OK (%d vanilla + %d mod biomes, no cycles, no conflicting pairs)'
                  % (mc, len(vanilla[mc].biomes), len(generated_biomes[mc])))
        contents = {}
        for rid, placed in vanilla[mc].placed.items():
            contents.setdefault(json.dumps(placed, sort_keys=True), rid)
        for fid, fo in sorted(features.items()):
            if mc in fo.placed:
                key = json.dumps(fo.placed[mc], sort_keys=True)
                if key in contents:
                    problems.error('specs/features/%s.json' % fid, 'placed feature is identical to %s in %s; Minecraft treats identical placed features as one when ordering features' % (contents[key], mc))
                contents.setdefault(key, MOD + ':' + fid)
    if problems.errors:
        return report(problems)
    report(problems)
    if not args.quiet:
        for r in placements['overworld_regions']:
            print('region %s (weight %d): %s' % (r['name'], r['weight'], ', '.join('%s -> %s' % (k, v) for k, v in r['replace'].items())))
        for r in placements['nether_regions']:
            print('region %s (weight %d): %s' % (r['name'], r['weight'], ', '.join(p['biome'] for p in r['points'])))
        for e in placements['end']:
            print('end %s: %s weight %d' % (e['biome'], e['type'], e['weight']))
    return sync(outputs, versions, args.check, args.quiet)


def build_lang(specs, outputs, problems):
    langs = defaultdict(dict)
    for s in specs:
        for loc, name in s.data.get('names', {}).items():
            langs[loc]['biome.%s.%s' % (MOD, s.id)] = name
    extra_dir = os.path.join(SPECS, 'lang')
    if os.path.isdir(extra_dir):
        for fn in sorted(os.listdir(extra_dir)):
            if fn.endswith('.json'):
                extra = read_json(os.path.join(extra_dir, fn), problems)
                for k, v in (extra or {}).items():
                    if k in langs[fn[:-5]]:
                        problems.error('specs/lang/' + fn, 'key %s is generated from a biome spec' % k)
                    langs[fn[:-5]][k] = v
    for loc, entries in langs.items():
        outputs[os.path.join(COMMON_RES, 'assets', MOD, 'lang', loc + '.json')] = dump_json(entries, indent='\t')


def report(problems):
    for w in problems.warnings:
        print('warning: ' + w)
    for e in problems.errors:
        print('ERROR: ' + e)
    if problems.errors:
        print('biomegen: %d error(s); nothing written' % len(problems.errors))
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
