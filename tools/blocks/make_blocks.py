"""Writes biomesmod's block textures (16x16, plus LabPBR _n/_s maps for shader packs), blockstates, block/item models,
loot tables, block tags and block names (specs/lang, merged into the lang files by tools/biomegen/generate.py).

usage: python tools/blocks/make_blocks.py            (from the repo root)

Every texture is drawn procedurally from a fixed seed (tools/blocks/art.py), so re-running gives identical files.
"""
import json
import math
import os
import random
import struct
import sys
import zlib

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
RES = os.path.join(REPO, "common", "src", "main", "resources")
TEX = os.path.join(RES, "assets", "biomesmod", "textures", "block")
N = 16


# ---------------------------------------------------------------- image helpers

def png(path, px):
    """px: 16 rows of 16 RGBA tuples."""
    raw = b"".join(b"\x00" + bytes(max(0, min(255, int(round(c)))) for p in row for c in p) for row in px)

    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    data = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", N, N, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(data)


def canvas(c=(0, 0, 0, 0)):
    return [[tuple(c) for _ in range(N)] for _ in range(N)]


def rgba(c, a=255):
    return (c[0], c[1], c[2], a) if len(c) == 3 else tuple(c)


def mix(a, b, t):
    t = max(0.0, min(1.0, t))
    return tuple(a[i] * (1 - t) + b[i] * t for i in range(len(a)))


def ramp(cols, t):
    """Piecewise-linear colour ramp, t in [0, 1]."""
    t = max(0.0, min(0.9999, t)) * (len(cols) - 1)
    i = int(t)
    return mix(cols[i], cols[i + 1], t - i)


def jitter(rnd, c, amt):
    d = rnd.uniform(-amt, amt)
    return tuple(c[i] + d for i in range(3)) + tuple(c[3:])


class Noise:
    """Tileable 16x16 value noise with `cells` lattice cells across the tile (per axis)."""

    def __init__(self, seed, cx, cy=None):
        cy = cy or cx
        rnd = random.Random(seed)
        self.cx, self.cy = cx, cy
        self.g = [[rnd.random() for _ in range(cx)] for _ in range(cy)]

    def __call__(self, x, y):
        fx, fy = x / N * self.cx, y / N * self.cy
        ix, iy = int(math.floor(fx)), int(math.floor(fy))
        tx, ty = fx - ix, fy - iy
        tx, ty = tx * tx * (3 - 2 * tx), ty * ty * (3 - 2 * ty)
        x0, x1 = ix % self.cx, (ix + 1) % self.cx
        y0, y1 = iy % self.cy, (iy + 1) % self.cy
        a = self.g[y0][x0] * (1 - tx) + self.g[y0][x1] * tx
        b = self.g[y1][x0] * (1 - tx) + self.g[y1][x1] * tx
        return a * (1 - ty) + b * ty


def fbm(seed, cells=(2, 4, 8), weights=(0.55, 0.3, 0.15), stretch=(1, 1)):
    ns = [Noise(seed + i * 101, max(1, int(c * stretch[0])), max(1, int(c * stretch[1]))) for i, c in enumerate(cells)]

    def f(x, y):
        return sum(w * n(x, y) for w, n in zip(weights, ns))
    return f


def line(img, x0, y0, x1, y1, col, wrap=True, only_empty=False):
    """Bresenham line (wrapping across tile edges)."""
    dx, dy = abs(x1 - x0), -abs(y1 - y0)
    sx, sy = (1 if x0 < x1 else -1), (1 if y0 < y1 else -1)
    err = dx + dy
    pts = []
    while True:
        pts.append((x0, y0))
        if x0 == x1 and y0 == y1:
            break
        e2 = 2 * err
        if e2 >= dy:
            err += dy
            x0 += sx
        if e2 <= dx:
            err += dx
            y0 += sy
    for i, (x, y) in enumerate(pts):
        if not wrap and not (0 <= x < N and 0 <= y < N):
            continue
        c = col(i, len(pts)) if callable(col) else col
        xx, yy = x % N, y % N
        if only_empty and img[yy][xx][3] > 0:
            continue
        img[yy][xx] = rgba(c)
    return pts


# ---------------------------------------------------------------- PBR helpers (LabPBR 1.3)

def normal_map(height, strength=1.6, ao=None):
    """height: 16x16 floats in [0,1]. Returns RGBA rows: RG normal (DirectX, Y-), B ambient occlusion, A height."""
    out = []
    for y in range(N):
        row = []
        for x in range(N):
            h = lambda xx, yy: height[yy % N][xx % N]
            gx = (h(x + 1, y) - h(x - 1, y)) * strength
            gy = (h(x, y + 1) - h(x, y - 1)) * strength
            nx, ny, nz = -gx, gy, 1.0
            l = math.sqrt(nx * nx + ny * ny + nz * nz)
            nx, ny = nx / l, ny / l
            a = 1.0 if ao is None else ao[y][x]
            row.append((128 + nx * 127, 128 + ny * 127, 255 * a, 255 * max(0.04, min(1.0, height[y][x]))))
        out.append(row)
    return out


def spec_map(fn):
    """fn(x, y) -> (smoothness 0-1, f0 0-1 (dielectric, < 0.9), porosity/sss byte 0-255, emission 0-1 or None)."""
    out = []
    for y in range(N):
        row = []
        for x in range(N):
            s, f0, b, e = fn(x, y)
            row.append((255 * s, min(229, 255 * f0), b, 255 if not e else min(254, 254 * e)))
        out.append(row)
    return out


def luminance_height(img):
    return [[(0.3 * p[0] + 0.59 * p[1] + 0.11 * p[2]) / 255 for p in row] for row in img]


# ---------------------------------------------------------------- textures

# Every texture is drawn in art.py (colour ramps, lit height fields, hand-drawn sprites).
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from art import (abyss_stone, astral_roots, blob_leaves, desert_rose, dolerite_side, dolerite_top, dragon_blood_bark,  # noqa: E402
                 dragon_blood_leaves, dragon_blood_top, geyserite, ghost_bark, ghost_hollow, ghost_sapling, glacier_ice, grains,
                 moon_bracket, moonpetal, pale_fern, pale_moss, redwood_bark, rings_top, salt, salt_height, salt_mirror,
                 salt_mirror_height, scoria, silver_beard, star_bloom, star_cluster, star_crystal, star_spring_top, stardust,
                 starlit, sulfur, sword_leaves, thermal_mat, travertine, tussock_grass, vent_top, volcanic_rock, yareta,
                 WOODS, door, door_item, dragon_blood_sapling, planks, redwood_sapling, stripped_side, stripped_top, trapdoor)
import wood  # noqa: E402


# ---------------------------------------------------------------- assets

BLOCKS = {}


def block(name, kind, names, tags=(), tex=None):
    BLOCKS[name] = dict(kind=kind, names=names, tags=tags, tex=tex or {})


def write(rel, obj):
    p = os.path.join(RES, rel)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, "w", encoding="utf-8", newline="\n") as f:
        json.dump(obj, f, indent=2, ensure_ascii=False)
        f.write("\n")


def models(name, b):
    k = b["kind"]
    m = f"biomesmod:block/{name}"
    t = b["tex"]
    bs = {"variants": {"": {"model": m}}}
    item_model = m
    if k == "all":
        model = {"parent": "minecraft:block/cube_all", "textures": {"all": t.get("all", m)}}
    elif k == "column":
        model = {"parent": "minecraft:block/cube_column", "textures": {"end": t.get("end", m + "_top"), "side": t.get("side", m)}}
    elif k == "bottom_top":
        model = {"parent": "minecraft:block/cube_bottom_top", "textures": {"top": t["top"], "side": t["side"], "bottom": t["bottom"]}}
    elif k in ("log", "wood"):
        side, end = t["side"], (t["end"] if k == "log" else t["side"])
        model = {"parent": "minecraft:block/cube_column", "textures": {"end": end, "side": side}}
        if k == "log":
            write(f"assets/biomesmod/models/block/{name}_horizontal.json",
                  {"parent": "minecraft:block/cube_column_horizontal", "textures": {"end": end, "side": side}})
            h = m + "_horizontal"
            bs = {"variants": {"axis=y": {"model": m}, "axis=z": {"model": h, "x": 90}, "axis=x": {"model": h, "x": 90, "y": 90}}}
        else:
            bs = {"variants": {"axis=y": {"model": m}, "axis=z": {"model": m, "x": 90}, "axis=x": {"model": m, "x": 90, "y": 90}}}
    elif k == "leaves":
        model = {"parent": "minecraft:block/cube_all", "textures": {"all": m}}
    elif k in ("cross", "hanging"):
        model = {"parent": "minecraft:block/cross", "textures": {"cross": m}}
        item_model = None
    elif k == "carpet":
        model = {"parent": "minecraft:block/carpet", "textures": {"wool": t["wool"]}}
    elif k == "cluster":
        model = {"parent": "minecraft:block/cross", "textures": {"cross": m}}
        bs = {"variants": {
            "facing=up": {"model": m}, "facing=down": {"model": m, "x": 180},
            "facing=north": {"model": m, "x": 90}, "facing=south": {"model": m, "x": 90, "y": 180},
            "facing=east": {"model": m, "x": 90, "y": 90}, "facing=west": {"model": m, "x": 90, "y": 270}}}
        item_model = None
    else:
        raise ValueError(k)
    write(f"assets/biomesmod/blockstates/{name}.json", bs)
    write(f"assets/biomesmod/models/block/{name}.json", model)
    if item_model:
        write(f"assets/biomesmod/models/item/{name}.json", {"parent": item_model})  # 1.21.1
        write(f"assets/biomesmod/items/{name}.json", {"model": {"type": "minecraft:model", "model": item_model}})  # 26.2
    else:
        gen = {"parent": "minecraft:item/generated", "textures": {"layer0": m}}
        write(f"assets/biomesmod/models/item/{name}.json", gen)
        write(f"assets/biomesmod/items/{name}.json", {"model": {"type": "minecraft:model", "model": f"biomesmod:item/{name}"}})
    write(f"data/biomesmod/loot_table/blocks/{name}.json", loot(name, k))


def loot(name, kind):
    entry = {"type": "minecraft:item", "name": f"biomesmod:{name}"}
    if kind in ("leaves", "cluster", "hanging"):
        # Only with shears or silk touch, like vanilla leaves and amethyst clusters.
        tool = {"condition": "minecraft:any_of", "terms": [
            {"condition": "minecraft:match_tool", "predicate": {"items": "minecraft:shears"}},
            {"condition": "minecraft:match_tool", "predicate": {"predicates": {"minecraft:enchantments": [
                {"enchantments": "minecraft:silk_touch", "levels": {"min": 1}}]}}}]}
        return {"type": "minecraft:block", "pools": [{"rolls": 1, "bonus_rolls": 0, "entries": [dict(entry, conditions=[tool])]}],
                "random_sequence": f"biomesmod:blocks/{name}"}
    return {"type": "minecraft:block",
            "pools": [{"rolls": 1, "bonus_rolls": 0, "entries": [entry], "conditions": [{"condition": "minecraft:survives_explosion"}]}],
            "random_sequence": f"biomesmod:blocks/{name}"}


def main():
    t = lambda n: f"biomesmod:block/{n}"
    # name, model kind, names, tags
    block("salt_crust", "all", ("Salt Crust", "소금 지각"), ("mineable/shovel",))
    block("travertine", "all", ("Travertine", "석회화"), ("mineable/pickaxe",))
    block("sulfur_crust", "all", ("Sulfur Crust", "황 지각"), ("mineable/pickaxe",))
    block("dolerite", "column", ("Dolerite", "조립현무암"), ("mineable/pickaxe",))
    block("dragon_blood_log", "log", ("Dragon's Blood Log", "용혈수 원목"), ("logs", "logs_that_burn", "mineable/axe", "overworld_natural_logs"),
          dict(side=t("dragon_blood_log"), end=t("dragon_blood_log_top")))
    block("dragon_blood_wood", "wood", ("Dragon's Blood Wood", "용혈수"), ("logs", "logs_that_burn", "mineable/axe"), dict(side=t("dragon_blood_log")))
    block("dragon_blood_leaves", "leaves", ("Dragon's Blood Leaves", "용혈수 잎"), ("leaves", "mineable/hoe"))
    block("desert_rose", "cross", ("Desert Rose", "사막장미"), ("small_flowers", "flowers"))
    block("geyserite", "all", ("Geyserite", "규화"), ("mineable/pickaxe",))
    block("geyser_vent", "bottom_top", ("Geyser Vent", "간헐천 분출구"), ("mineable/pickaxe",),
          dict(top=t("geyser_vent_top"), side=t("geyserite"), bottom=t("geyserite")))
    block("steam_vent", "bottom_top", ("Steam Vent", "분기공"), ("mineable/pickaxe",),
          dict(top=t("steam_vent_top"), side=t("steam_vent"), bottom=t("steam_vent")))
    block("thermal_mat_orange", "all", ("Orange Thermal Mat", "주황 미생물 매트"), ("mineable/shovel",))
    block("thermal_mat_yellow", "all", ("Yellow Thermal Mat", "노랑 미생물 매트"), ("mineable/shovel",))
    block("thermal_mat_green", "all", ("Green Thermal Mat", "초록 미생물 매트"), ("mineable/shovel",))
    block("white_sand", "all", ("White Sand", "흰 모래"), ("mineable/shovel", "sand"))
    block("volcanic_ash", "all", ("Volcanic Ash", "화산재"), ("mineable/shovel",))
    block("scoria", "all", ("Scoria", "스코리아"), ("mineable/pickaxe",))
    block("ghostwood_log", "log", ("Ghostwood Log", "백목 원목"), ("logs", "logs_that_burn", "mineable/axe", "overworld_natural_logs"),
          dict(side=t("ghostwood_log"), end=t("ghostwood_log_top")))
    block("ghostwood_wood", "wood", ("Ghostwood", "백목"), ("logs", "logs_that_burn", "mineable/axe"), dict(side=t("ghostwood_log")))
    block("ghostwood_leaves", "leaves", ("Ghostwood Leaves", "백목 잎"), ("leaves", "mineable/hoe"))
    block("pale_moss", "all", ("Pale Moss", "은빛 이끼"), ("mineable/hoe", "dirt"))
    block("ghostwood_sapling", "cross", ("Ghostwood Sapling", "백목 묘목"), ("saplings",))
    block("pale_fern", "cross", ("Pale Fern", "은빛 고사리"), ("small_flowers",))
    block("redwood_log", "log", ("Redwood Log", "레드우드 원목"), ("logs", "logs_that_burn", "mineable/axe", "overworld_natural_logs"),
          dict(side=t("redwood_log"), end=t("redwood_log_top")))
    block("redwood_wood", "wood", ("Redwood", "레드우드"), ("logs", "logs_that_burn", "mineable/axe"), dict(side=t("redwood_log")))
    block("redwood_leaves", "leaves", ("Redwood Leaves", "레드우드 잎"), ("leaves", "mineable/hoe"))
    block("glacier_ice", "all", ("Glacier Ice", "빙하 얼음"), ("mineable/pickaxe", "ice"))
    block("abyss_stone", "all", ("Abyss Stone", "심연석"), ("mineable/pickaxe",))
    block("star_crystal", "all", ("Star Crystal", "별 수정"), ("mineable/pickaxe",))
    block("star_crystal_cluster", "cluster", ("Star Crystal Cluster", "별 수정 군집"), ("mineable/pickaxe",))
    block("pale_moss_carpet", "carpet", ("Pale Moss Carpet", "은빛 이끼 카펫"), ("mineable/hoe", "combination_step_sound_blocks"),
          dict(wool=t("pale_moss")))
    block("silver_beard", "hanging", ("Silver Beard", "은빛 수염이끼"), ())
    block("moon_bracket", "cluster", ("Moon Bracket", "달빛 선반버섯"), ("mineable/axe",))
    block("moonpetal", "cross", ("Moonpetal", "달꽃잎"), ("small_flowers", "flowers"))
    block("ghostwood_hollow", "log", ("Ghostwood Hollow", "백목 나무구멍"), ("logs", "logs_that_burn", "mineable/axe"),
          dict(side=t("ghostwood_hollow"), end=t("ghostwood_log_top")))
    block("stardust", "all", ("Stardust", "별가루"), ("mineable/shovel",))
    block("starlit_abyss_stone", "all", ("Starlit Abyss Stone", "별빛 심연석"), ("mineable/pickaxe",))
    block("star_bloom", "cluster", ("Star Bloom", "별꽃"), ())
    block("astral_roots", "hanging", ("Astral Roots", "성광 뿌리"), ())
    block("star_spring", "bottom_top", ("Star Spring", "별샘"), ("mineable/pickaxe",),
          dict(top=t("star_spring_top"), side=t("starlit_abyss_stone"), bottom=t("abyss_stone")))

    block("salt_mirror", "bottom_top", ("Salt Mirror", "소금 거울"), ("mineable/shovel",),
          dict(top=t("salt_mirror"), side=t("salt_crust"), bottom=t("salt_crust")))

    block("tussock_grass", "cross", ("Tussock Grass", "다발풀"), ("replaceable_by_trees",))
    block("yareta", "all", ("Yareta", "야레타"), ("mineable/hoe",))

    # ---- saplings and worked wood
    block("dragon_blood_sapling", "cross", ("Dragon's Blood Sapling", "용혈수 묘목"), ("saplings",))
    block("redwood_sapling", "cross", ("Redwood Sapling", "레드우드 묘목"), ("saplings",))
    wood_names = {"dragon_blood": ("Dragon's Blood", "용혈수"), "ghostwood": ("Ghostwood", "백목"), "redwood": ("Redwood", "레드우드")}
    for w, (en, ko) in wood_names.items():
        block(f"stripped_{w}_log", "log", (f"Stripped {en} Log", f"껍질 벗긴 {ko} 원목"), ("logs", "logs_that_burn"),
              dict(side=t(f"stripped_{w}_log"), end=t(f"stripped_{w}_log_top")))
        block(f"stripped_{w}_wood", "wood", (f"Stripped {en} Wood", f"껍질 벗긴 {ko}"), ("logs", "logs_that_burn"), dict(side=t(f"stripped_{w}_log")))
        block(f"{w}_planks", "all", (f"{en} Planks", f"{ko} 판자"), ("planks",))

    # ---- textures
    P = lambda name: os.path.join(TEX, name + ".png")
    png(P("salt_crust"), salt())
    png(P("salt_crust_n"), normal_map(salt_height(), 1.2))
    png(P("salt_crust_s"), spec_map(lambda x, y: (0.82, 0.05, 30, None)))
    # LabPBR: smoothness 0.98 (roughness (1-s)^2 ~ 0.0004), dielectric F0 0.35: looking down the salt shows through,
    # towards the horizon Fresnel takes it to a full mirror. Porosity 0 (a wet, closed surface).
    png(P("salt_mirror"), salt_mirror())
    png(P("salt_mirror_n"), normal_map(salt_mirror_height(), 0.6))
    png(P("salt_mirror_s"), spec_map(lambda x, y: (0.98, 0.35, 0, None)))
    png(P("tussock_grass"), tussock_grass())
    yar = yareta()
    png(P("yareta"), yar)
    png(P("yareta_n"), normal_map(luminance_height(yar), 2.2))
    png(P("yareta_s"), spec_map(lambda x, y: (0.25, 0.04, 150, None)))
    trav = travertine()
    png(P("travertine"), trav)
    png(P("travertine_n"), normal_map(luminance_height(trav), 2.0))
    png(P("travertine_s"), spec_map(lambda x, y: (0.55, 0.04, 60, None)))
    png(P("sulfur_crust"), sulfur())
    png(P("dolerite"), dolerite_side())
    png(P("dolerite_top"), dolerite_top())

    png(P("dragon_blood_log"), dragon_blood_bark())
    png(P("dragon_blood_log_top"), dragon_blood_top())
    leaves = dragon_blood_leaves()
    png(P("dragon_blood_leaves"), leaves)
    png(P("dragon_blood_leaves_s"), spec_map(lambda x, y: (0.45, 0.04, 190, None)))
    png(P("desert_rose"), desert_rose())

    gey, gh = geyserite()
    png(P("geyserite"), gey)
    png(P("geyserite_n"), normal_map(gh, 2.4))
    png(P("geyserite_s"), spec_map(lambda x, y: (0.62, 0.04, 40, None)))
    png(P("geyser_vent_top"), vent_top(211, gey, (34, 46, 54), (86, 100, 106), (232, 230, 222), (112, 156, 176)))
    rock = volcanic_rock(221, stain=(206, 186, 70))
    png(P("steam_vent"), rock)
    png(P("steam_vent_top"), vent_top(222, volcanic_rock(223), (30, 26, 24), (70, 60, 50), (150, 132, 70), (190, 120, 50), stain=(214, 194, 72)))
    for name, cols, speck in (("orange", ((238, 148, 66), (214, 112, 42), (168, 72, 30)), None),
                              ("yellow", ((244, 212, 112), (222, 184, 74), (184, 144, 50)), None),
                              ("green", ((146, 168, 82), (112, 138, 62), (80, 102, 46)), (124, 92, 52))):
        img, hm = thermal_mat(231 + len(name), *cols, speck=speck)
        png(P("thermal_mat_" + name), img)
        png(P("thermal_mat_%s_n" % name), normal_map(hm, 1.4))
        png(P("thermal_mat_%s_s" % name), spec_map(lambda x, y: (0.78, 0.04, 20, None)))

    ws = grains(241, (243, 242, 236), (226, 224, 214), (253, 253, 250), extra=(232, 222, 200), extra_p=0.04, amt=3)
    png(P("white_sand"), ws)
    sp = random.Random(242)
    sparkle = [[sp.random() < 0.08 for _ in range(N)] for _ in range(N)]
    png(P("white_sand_s"), spec_map(lambda x, y: ((0.9 if sparkle[y][x] else 0.35), (0.12 if sparkle[y][x] else 0.04), 90, None)))
    png(P("white_sand_n"), normal_map(luminance_height(ws), 1.0))
    png(P("volcanic_ash"), grains(251, (80, 78, 76), (62, 60, 59), (98, 95, 92), extra=(92, 72, 64), extra_p=0.05))
    sc, sh = scoria()
    png(P("scoria"), sc)
    png(P("scoria_n"), normal_map(sh, 2.6))

    png(P("ghostwood_log"), ghost_bark())
    png(P("ghostwood_log_top"), rings_top(411, (206, 206, 200), (150, 150, 146), [(232, 224, 206), (214, 204, 184), (226, 216, 196)]))
    png(P("ghostwood_leaves"), blob_leaves(412, [(150, 170, 164), (190, 206, 200), (222, 232, 228), (244, 250, 246)], 0.62))
    png(P("ghostwood_leaves_s"), spec_map(lambda x, y: (0.4, 0.04, 200, None)))
    png(P("pale_moss"), pale_moss())
    png(P("pale_fern"), pale_fern())
    png(P("ghostwood_sapling"), ghost_sapling())

    png(P("redwood_log"), redwood_bark())
    png(P("redwood_log_top"), rings_top(511, (106, 50, 33), (74, 34, 24), [(170, 84, 54), (150, 70, 46), (190, 120, 82), (200, 140, 96)], 1.6))
    png(P("redwood_leaves"), sword_leaves(512, (30, 58, 34), (48, 88, 48), (78, 120, 64), [(2, 4), (7, 8), (12, 3), (10, 13), (4, 13), (15, 9)], 8, (2.0, 4.5), 0.55))
    png(P("redwood_leaves_s"), spec_map(lambda x, y: (0.4, 0.04, 180, None)))

    gi, gih = glacier_ice()
    png(P("glacier_ice"), gi)
    png(P("glacier_ice_n"), normal_map(gih, 1.2))
    png(P("glacier_ice_s"), spec_map(lambda x, y: (0.9, 0.07, 230, None)))

    ab, stars = abyss_stone()
    png(P("abyss_stone"), ab)
    starset = set(stars)
    png(P("abyss_stone_s"), spec_map(lambda x, y: ((0.7, 0.1, 0, 1.0) if (x, y) in starset else (0.3, 0.04, 20, None))))
    sc2 = star_crystal()
    png(P("star_crystal"), sc2)
    png(P("star_crystal_s"), spec_map(lambda x, y: (0.95, 0.12, 220, 0.6)))
    png(P("star_crystal_cluster"), star_cluster())

    bark = ghost_bark()
    png(P("silver_beard"), silver_beard())
    png(P("moon_bracket"), moon_bracket())
    png(P("moonpetal"), moonpetal())
    png(P("ghostwood_hollow"), ghost_hollow(bark))
    sd, sparks = stardust()
    png(P("stardust"), sd)
    png(P("stardust_s"), spec_map(lambda x, y: ((0.85, 0.15, 60, 0.8) if (x, y) in sparks else (0.35, 0.04, 60, None))))
    sl, veins = starlit(ab)
    png(P("starlit_abyss_stone"), sl)
    png(P("starlit_abyss_stone_s"), spec_map(lambda x, y: ((0.6, 0.08, 0, 1.0) if (x, y) in veins or (x, y) in starset else (0.3, 0.04, 20, None))))
    png(P("star_bloom"), star_bloom())
    png(P("astral_roots"), astral_roots())
    png(P("star_spring_top"), star_spring_top(ab))

    png(P("dragon_blood_sapling"), dragon_blood_sapling())
    png(P("redwood_sapling"), redwood_sapling())
    for k, (w, cols) in enumerate(WOODS.items()):
        png(P(f"{w}_planks"), planks(700 + k * 10, cols))
        png(P(f"stripped_{w}_log"), stripped_side(701 + k * 10, cols))
        png(P(f"stripped_{w}_log_top"), stripped_top(702 + k * 10, cols))
        top, bottom = door(703 + k * 10, cols)
        png(P(f"{w}_door_top"), top)
        png(P(f"{w}_door_bottom"), bottom)
        png(os.path.join(RES, "assets", "biomesmod", "textures", "item", f"{w}_door.png"), door_item(top, bottom))
        png(P(f"{w}_trapdoor"), trapdoor(704 + k * 10, cols))

    # ---- models, loot, tags
    out = wood.Out(write, REPO)
    for w, (en, ko) in wood_names.items():
        wood.family(out, w, en, ko)
    out.own_tags["ghostwood_logs"].append("biomesmod:ghostwood_hollow")
    for plant in ("dragon_blood_sapling", "redwood_sapling", "ghostwood_sapling", "desert_rose", "moonpetal", "pale_fern"):
        wood.potted(out, plant, *BLOCKS[plant]["names"])
    tags = {}
    for name, b in BLOCKS.items():
        models(name, b)
        for tg in b["tags"]:
            tags.setdefault(tg, []).append("biomesmod:" + name)
    for w in wood_names:
        wood.leaves_loot(out, f"{w}_leaves", f"{w}_sapling")
    for tg, vals in out.block_tags.items():
        tags.setdefault(tg, []).extend(vals)
    for tg, vals in sorted(tags.items()):
        write(f"data/minecraft/tags/block/{tg}.json", {"replace": False, "values": vals})
        if tg in ("logs", "logs_that_burn", "leaves", "sand", "small_flowers", "flowers", "saplings") + wood.ITEM_TAGS:
            write(f"data/minecraft/tags/item/{tg}.json", {"replace": False, "values": vals})
    for tg, vals in out.own_tags.items():
        write(f"data/biomesmod/tags/block/{tg}.json", {"replace": False, "values": vals})
        write(f"data/biomesmod/tags/item/{tg}.json", {"replace": False, "values": vals})
    n_recipes = wood.recipes(out, list(wood_names))

    # ---- names, merged into specs/lang (the biome generator turns them into the lang files)
    for loc, i in (("en_us", 0), ("ko_kr", 1)):
        p = os.path.join(REPO, "specs", "lang", loc + ".json")
        with open(p, encoding="utf-8") as f:
            lang = json.load(f)
        for name, b in BLOCKS.items():
            lang[f"block.biomesmod.{name}"] = b["names"][i]
        for name, names in out.names.items():
            lang[f"block.biomesmod.{name}"] = names[i]
        for w, wn in wood_names.items():
            lang[f"tag.item.biomesmod.{w}_logs"] = (wn[0] + " Logs", wn[1] + " 원목")[i]
        lang = dict(sorted(lang.items()))
        with open(p, "w", encoding="utf-8", newline="\n") as f:
            json.dump(lang, f, indent=2, ensure_ascii=False)
            f.write("\n")
    print("%d blocks, %d recipes written" % (len(BLOCKS) + len(out.names), n_recipes))


if __name__ == "__main__":
    main()
