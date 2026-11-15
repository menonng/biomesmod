"""Models, blockstates, loot tables, tags, names and recipes of the worked-wood blocks (stairs, slabs, fences, doors and so
on), the potted plants, and the recipes of the other blocks. Used by make_blocks.py."""
import os

MOD = 'biomesmod'
FACING = {'east': 0, 'south': 90, 'west': 180, 'north': 270}

# id suffix -> (English, Korean) name pattern, block tags, item tags
PARTS = {
    'stairs': ('%s Stairs', '%s 계단', ('wooden_stairs',)),
    'slab': ('%s Slab', '%s 반 블록', ('wooden_slabs',)),
    'fence': ('%s Fence', '%s 울타리', ('wooden_fences',)),
    'fence_gate': ('%s Fence Gate', '%s 울타리 문', ('fence_gates',)),
    'door': ('%s Door', '%s 문', ('wooden_doors',)),
    'trapdoor': ('%s Trapdoor', '%s 다락문', ('wooden_trapdoors',)),
    'pressure_plate': ('%s Pressure Plate', '%s 압력판', ('wooden_pressure_plates',)),
    'button': ('%s Button', '%s 버튼', ('wooden_buttons',)),
}
ITEM_TAGS = ('planks', 'wooden_stairs', 'wooden_slabs', 'wooden_fences', 'fence_gates', 'wooden_doors', 'wooden_trapdoors',
             'wooden_pressure_plates', 'wooden_buttons')


def rot(model, y=0, x=0, uvlock=False):
    v = {'model': model}
    if x:
        v['x'] = x
    if y % 360:
        v['y'] = y % 360
    if uvlock and (x or y % 360):
        v['uvlock'] = True
    return v


def self_drop(name, extra=None):
    entry = {'type': 'minecraft:item', 'name': f'{MOD}:{name}'}
    entry.update(extra or {})
    return {'type': 'minecraft:block',
            'pools': [{'rolls': 1, 'bonus_rolls': 0, 'entries': [entry], 'conditions': [{'condition': 'minecraft:survives_explosion'}]}],
            'random_sequence': f'{MOD}:blocks/{name}'}


class Out:
    """Collects what the caller merges into its own tables."""

    def __init__(self, write, repo):
        self.write, self.repo = write, repo
        self.block_tags, self.item_tags, self.own_tags, self.names = {}, {}, {}, {}

    def asset(self, kind, name, obj):
        self.write(f'assets/{MOD}/{kind}/{name}.json', obj)

    def item(self, name, model):
        self.asset('models/item', name, {'parent': model})  # 1.21.1
        self.asset('items', name, {'model': {'type': 'minecraft:model', 'model': model}})  # 26.2

    def flat_item(self, name, texture):
        self.asset('models/item', name, {'parent': 'minecraft:item/generated', 'textures': {'layer0': texture}})
        self.asset('items', name, {'model': {'type': 'minecraft:model', 'model': f'{MOD}:item/{name}'}})

    def loot(self, name, table):
        self.write(f'data/{MOD}/loot_table/blocks/{name}.json', table)

    def tag(self, name, tags):
        for t in tags:
            self.block_tags.setdefault(t, []).append(f'{MOD}:{name}')
            if t in ITEM_TAGS:
                self.item_tags.setdefault(t, []).append(f'{MOD}:{name}')


def family(out, wood, en, ko):
    """Everything made of the planks of one wood."""
    planks = f'{MOD}:block/{wood}_planks'
    tex3 = {'bottom': planks, 'top': planks, 'side': planks}
    one = {'texture': planks}

    def model(name, parent, textures):
        out.asset('models/block', name, {'parent': f'minecraft:block/{parent}', 'textures': textures})
        return f'{MOD}:block/{name}'

    for part, (pe, pk, tags) in PARTS.items():
        name = f'{wood}_{part}'
        out.names[name] = (pe % en, pk % ko)
        out.tag(name, tags)
        if part not in ('slab', 'door'):
            out.loot(name, self_drop(name))

    # ---- stairs
    n = f'{wood}_stairs'
    st, inner, outer = model(n, 'stairs', tex3), model(n + '_inner', 'inner_stairs', tex3), model(n + '_outer', 'outer_stairs', tex3)
    v = {}
    for f, y in FACING.items():
        for shape, (m, bottom, top) in {'straight': (st, 0, 0), 'outer_right': (outer, 0, 90), 'outer_left': (outer, -90, 0),
                                        'inner_right': (inner, 0, 90), 'inner_left': (inner, -90, 0)}.items():
            v[f'facing={f},half=bottom,shape={shape}'] = rot(m, y + bottom, 0, True)
            v[f'facing={f},half=top,shape={shape}'] = rot(m, y + top, 180, True)
    out.asset('blockstates', n, {'variants': v})
    out.item(n, st)

    # ---- slab
    n = f'{wood}_slab'
    lo, hi = model(n, 'slab', tex3), model(n + '_top', 'slab_top', tex3)
    out.asset('blockstates', n, {'variants': {'type=bottom': {'model': lo}, 'type=top': {'model': hi}, 'type=double': {'model': planks}}})
    out.item(n, lo)
    out.loot(n, {'type': 'minecraft:block', 'pools': [{'rolls': 1, 'bonus_rolls': 0, 'entries': [{
        'type': 'minecraft:item', 'name': f'{MOD}:{n}', 'functions': [
            {'function': 'minecraft:set_count', 'add': False, 'count': 2, 'conditions': [
                {'condition': 'minecraft:block_state_property', 'block': f'{MOD}:{n}', 'properties': {'type': 'double'}}]},
            {'function': 'minecraft:explosion_decay'}]}]}], 'random_sequence': f'{MOD}:blocks/{n}'})

    # ---- fence
    n = f'{wood}_fence'
    post, side = model(n + '_post', 'fence_post', one), model(n + '_side', 'fence_side', one)
    out.asset('blockstates', n, {'multipart': [{'apply': {'model': post}}] + [
        {'when': {d: 'true'}, 'apply': rot(side, y, 0, True)} for d, y in (('north', 0), ('east', 90), ('south', 180), ('west', 270))]})
    out.item(n, model(n + '_inventory', 'fence_inventory', one))

    # ---- fence gate
    n = f'{wood}_fence_gate'
    g = {(False, False): model(n, 'template_fence_gate', one), (False, True): model(n + '_open', 'template_fence_gate_open', one),
         (True, False): model(n + '_wall', 'template_fence_gate_wall', one), (True, True): model(n + '_wall_open', 'template_fence_gate_wall_open', one)}
    v = {}
    for f, y in (('south', 0), ('west', 90), ('north', 180), ('east', 270)):
        for (wall, opened), m in g.items():
            v[f'facing={f},in_wall={str(wall).lower()},open={str(opened).lower()}'] = dict({'model': m, 'uvlock': True}, **({'y': y} if y else {}))
    out.asset('blockstates', n, {'variants': v})
    out.item(n, g[(False, False)])

    # ---- door
    n = f'{wood}_door'
    tex = {'bottom': f'{MOD}:block/{n}_bottom', 'top': f'{MOD}:block/{n}_top'}
    v = {}
    for half, hm in (('lower', 'bottom'), ('upper', 'top')):
        for hinge in ('left', 'right'):
            closed = model(f'{n}_{hm}_{hinge}', f'door_{hm}_{hinge}', tex)
            opened = model(f'{n}_{hm}_{hinge}_open', f'door_{hm}_{hinge}_open', tex)
            for f, y in FACING.items():
                v[f'facing={f},half={half},hinge={hinge},open=false'] = rot(closed, y)
                v[f'facing={f},half={half},hinge={hinge},open=true'] = rot(opened, y + (90 if hinge == 'left' else 270))
    out.asset('blockstates', n, {'variants': v})
    out.flat_item(n, f'{MOD}:item/{n}')
    out.loot(n, {'type': 'minecraft:block', 'pools': [{'rolls': 1, 'bonus_rolls': 0, 'entries': [{
        'type': 'minecraft:item', 'name': f'{MOD}:{n}', 'conditions': [
            {'condition': 'minecraft:block_state_property', 'block': f'{MOD}:{n}', 'properties': {'half': 'lower'}}]}],
        'conditions': [{'condition': 'minecraft:survives_explosion'}]}], 'random_sequence': f'{MOD}:blocks/{n}'})

    # ---- trapdoor
    n = f'{wood}_trapdoor'
    tt = {'texture': f'{MOD}:block/{n}'}
    lo, hi, op = (model(n + '_bottom', 'template_orientable_trapdoor_bottom', tt), model(n + '_top', 'template_orientable_trapdoor_top', tt),
                  model(n + '_open', 'template_orientable_trapdoor_open', tt))
    v = {}
    for f, y in (('north', 0), ('east', 90), ('south', 180), ('west', 270)):
        v[f'facing={f},half=bottom,open=false'] = rot(lo, y)
        v[f'facing={f},half=top,open=false'] = rot(hi, y)
        v[f'facing={f},half=bottom,open=true'] = rot(op, y)
        v[f'facing={f},half=top,open=true'] = rot(op, y + 180, 180)
    out.asset('blockstates', n, {'variants': v})
    out.item(n, lo)

    # ---- pressure plate
    n = f'{wood}_pressure_plate'
    up, down = model(n, 'pressure_plate_up', one), model(n + '_down', 'pressure_plate_down', one)
    out.asset('blockstates', n, {'variants': {'powered=false': {'model': up}, 'powered=true': {'model': down}}})
    out.item(n, up)

    # ---- button
    n = f'{wood}_button'
    b, pressed = model(n, 'button', one), model(n + '_pressed', 'button_pressed', one)
    v = {}
    for f, y in (('north', 0), ('east', 90), ('south', 180), ('west', 270)):
        for powered, m in (('false', b), ('true', pressed)):
            v[f'face=floor,facing={f},powered={powered}'] = rot(m, y)
            v[f'face=wall,facing={f},powered={powered}'] = rot(m, y, 90, True)
            v[f'face=ceiling,facing={f},powered={powered}'] = rot(m, y + 180, 180)
    out.asset('blockstates', n, {'variants': v})
    out.item(n, model(n + '_inventory', 'button_inventory', one))

    out.own_tags[f'{wood}_logs'] = [f'{MOD}:{wood}_log', f'{MOD}:{wood}_wood', f'{MOD}:stripped_{wood}_log', f'{MOD}:stripped_{wood}_wood']


def potted(out, plant, en, ko):
    n = f'potted_{plant}'
    out.asset('models/block', n, {'parent': 'minecraft:block/flower_pot_cross', 'textures': {'plant': f'{MOD}:block/{plant}'}})
    out.asset('blockstates', n, {'variants': {'': {'model': f'{MOD}:block/{n}'}}})
    out.names[n] = ('Potted ' + en, ko + ' 화분')
    out.block_tags.setdefault('flower_pots', []).append(f'{MOD}:{n}')
    pool = lambda item: {'rolls': 1, 'bonus_rolls': 0, 'entries': [{'type': 'minecraft:item', 'name': item}],
                         'conditions': [{'condition': 'minecraft:survives_explosion'}]}
    out.loot(n, {'type': 'minecraft:block', 'pools': [pool('minecraft:flower_pot'), pool(f'{MOD}:{plant}')], 'random_sequence': f'{MOD}:blocks/{n}'})


def leaves_loot(out, name, sapling):
    """Leaves: themselves with shears or silk touch, otherwise now and then a sapling or a stick."""
    tool = {'condition': 'minecraft:any_of', 'terms': [
        {'condition': 'minecraft:match_tool', 'predicate': {'items': 'minecraft:shears'}},
        {'condition': 'minecraft:match_tool', 'predicate': {'predicates': {'minecraft:enchantments': [
            {'enchantments': 'minecraft:silk_touch', 'levels': {'min': 1}}]}}}]}
    out.loot(name, {'type': 'minecraft:block', 'pools': [
        {'rolls': 1, 'bonus_rolls': 0, 'entries': [{'type': 'minecraft:alternatives', 'children': [
            {'type': 'minecraft:item', 'name': f'{MOD}:{name}', 'conditions': [tool]},
            {'type': 'minecraft:item', 'name': f'{MOD}:{sapling}',
             'conditions': [{'condition': 'minecraft:survives_explosion'}, {'condition': 'minecraft:random_chance', 'chance': 0.05}]}]}]},
        {'rolls': 1, 'bonus_rolls': 0, 'conditions': [{'condition': 'minecraft:inverted', 'term': tool}], 'entries': [
            {'type': 'minecraft:item', 'name': 'minecraft:stick', 'conditions': [{'condition': 'minecraft:random_chance', 'chance': 0.02}],
             'functions': [{'function': 'minecraft:set_count', 'add': False, 'count': {'type': 'minecraft:uniform', 'min': 1, 'max': 2}},
                           {'function': 'minecraft:explosion_decay'}]}]}],
        'random_sequence': f'{MOD}:blocks/{name}'})


# ---------------------------------------------------------------- recipes

def recipes(out, woods, versions=('1.21.1', '26.2')):
    """Crafting and smelting recipes, written per version (the ingredient format differs), each with the advancement that
    puts it into the recipe book."""
    table = {}

    def add(name, unlock, make):
        table[name] = (unlock, make)

    def shaped(result, count, pattern, key, category, group=None):
        def make(ing):
            r = {'type': 'minecraft:crafting_shaped', 'category': category, 'key': {k: ing(v) for k, v in key.items()}, 'pattern': pattern,
                 'result': {'count': count, 'id': result}}
            if group:
                r['group'] = group
            return r
        return make

    def shapeless(result, count, ingredients, category, group=None):
        def make(ing):
            r = {'type': 'minecraft:crafting_shapeless', 'category': category, 'ingredients': [ing(i) for i in ingredients],
                 'result': {'count': count, 'id': result}}
            if group:
                r['group'] = group
            return r
        return make

    for w in woods:
        p, logs = f'{MOD}:{w}_planks', f'#{MOD}:{w}_logs'
        add(f'{w}_planks', logs, shapeless(p, 4, [logs], 'building', 'planks'))
        add(f'{w}_wood', f'{MOD}:{w}_log', shaped(f'{MOD}:{w}_wood', 3, ['##', '##'], {'#': f'{MOD}:{w}_log'}, 'building', 'bark'))
        add(f'stripped_{w}_wood', f'{MOD}:stripped_{w}_log',
            shaped(f'{MOD}:stripped_{w}_wood', 3, ['##', '##'], {'#': f'{MOD}:stripped_{w}_log'}, 'building', 'bark'))
        add(f'{w}_stairs', p, shaped(f'{MOD}:{w}_stairs', 4, ['#  ', '## ', '###'], {'#': p}, 'building', 'wooden_stairs'))
        add(f'{w}_slab', p, shaped(f'{MOD}:{w}_slab', 6, ['###'], {'#': p}, 'building', 'wooden_slab'))
        add(f'{w}_fence', p, shaped(f'{MOD}:{w}_fence', 3, ['W#W', 'W#W'], {'W': p, '#': 'minecraft:stick'}, 'misc', 'wooden_fence'))
        add(f'{w}_fence_gate', p, shaped(f'{MOD}:{w}_fence_gate', 1, ['#W#', '#W#'], {'W': p, '#': 'minecraft:stick'}, 'redstone', 'wooden_fence_gate'))
        add(f'{w}_door', p, shaped(f'{MOD}:{w}_door', 3, ['##', '##', '##'], {'#': p}, 'redstone', 'wooden_door'))
        add(f'{w}_trapdoor', p, shaped(f'{MOD}:{w}_trapdoor', 2, ['###', '###'], {'#': p}, 'redstone', 'wooden_trapdoor'))
        add(f'{w}_pressure_plate', p, shaped(f'{MOD}:{w}_pressure_plate', 1, ['##'], {'#': p}, 'redstone', 'wooden_pressure_plate'))
        add(f'{w}_button', p, shapeless(f'{MOD}:{w}_button', 1, [p], 'redstone', 'wooden_button'))

    add('pale_moss_carpet', f'{MOD}:pale_moss', shaped(f'{MOD}:pale_moss_carpet', 3, ['##'], {'#': f'{MOD}:pale_moss'}, 'misc'))
    for flower, dye in (('desert_rose', 'pink_dye'), ('moonpetal', 'white_dye'), ('star_bloom', 'light_blue_dye')):
        add(f'{dye}_from_{flower}', f'{MOD}:{flower}', shapeless(f'minecraft:{dye}', 1, [f'{MOD}:{flower}'], 'misc', dye))
    add('glass_from_white_sand', f'{MOD}:white_sand', lambda ing: {
        'type': 'minecraft:smelting', 'category': 'blocks', 'cookingtime': 200, 'experience': 0.1, 'ingredient': ing(f'{MOD}:white_sand'),
        'result': {'id': 'minecraft:glass'}})

    old = lambda i: {'tag': i[1:]} if i.startswith('#') else {'item': i}
    for mc in versions:
        d = os.path.join(out.repo, 'versions', mc, 'src', 'main', 'resources', 'data', MOD, 'recipe')
        os.makedirs(d, exist_ok=True)
        keep = {name + '.json' for name in table}
        for fn in os.listdir(d):
            if fn not in keep:
                os.remove(os.path.join(d, fn))
        for name, (unlock, make) in table.items():
            out.write(os.path.join(d, name + '.json'), make(old if mc == '1.21.1' else (lambda i: i)))
    for name, (unlock, make) in table.items():
        rid = f'{MOD}:{name}'
        out.write(f'data/{MOD}/advancement/recipes/{name}.json', {
            'parent': 'minecraft:recipes/root',
            'criteria': {'has_ingredient': {'trigger': 'minecraft:inventory_changed', 'conditions': {'items': [{'items': unlock}]}},
                         'has_the_recipe': {'trigger': 'minecraft:recipe_unlocked', 'conditions': {'recipe': rid}}},
            'requirements': [['has_the_recipe', 'has_ingredient']],
            'rewards': {'recipes': [rid]}})
    return len(table)
