"""Writes biomesmod's block textures (16x16, plus LabPBR _n/_s maps for shader packs), blockstates, block/item models,
loot tables, block tags and block names (specs/lang, merged into the lang files by tools/biomegen/generate.py).

usage: python tools/blocks/make_blocks.py            (from the repo root)

Every texture is drawn procedurally from a fixed seed, so re-running gives identical files. Keep the style close to
vanilla: a small palette per block, clustered (not per-pixel random) shading, light from the top left.
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


# ---------------------------------------------------------------- textures: existing blocks (unchanged look)

def salt():
    rnd = random.Random(11)
    n = Noise(12, 4)
    img = canvas()
    for y in range(N):
        for x in range(N):
            c = mix((238, 240, 241), (250, 250, 248), n(x, y))
            r = rnd.random()
            if r < 0.10:
                c = (255, 255, 255)
            elif r < 0.17:
                c = (224, 228, 232)
            img[y][x] = rgba(jitter(rnd, c, 3))
    return img


def salt_height():
    """Polygonal salt crust: raised ridges along the cell borders (the hexagons of Uyuni), flat plates between."""
    rnd = random.Random(13)
    pts = [(rnd.uniform(0, N), rnd.uniform(0, N)) for _ in range(5)]
    hmap = []
    for y in range(N):
        row = []
        for x in range(N):
            ds = sorted(min(math.hypot(x + 0.5 - px - ox, y + 0.5 - py - oy) for ox in (-N, 0, N) for oy in (-N, 0, N)) for px, py in pts)
            edge = ds[1] - ds[0]
            row.append(0.55 + 0.4 * max(0.0, 1 - edge / 1.6) + 0.05 * rnd.random())
        hmap.append(row)
    return hmap


def salt_mirror():
    """Flooded salt seen through a few centimetres of still water: the pale blue of the sky it mirrors, with the
    polygon seams of the crust showing faintly through. This is what the block looks like without a shader pack."""
    rnd = random.Random(211)
    n = Noise(212, 3)
    hm = salt_height()
    img = canvas()
    for y in range(N):
        for x in range(N):
            c = mix((168, 198, 228), (184, 210, 236), n(x, y))
            c = mix(c, (214, 228, 242), max(0.0, hm[y][x] - 0.6) * 0.9)
            img[y][x] = rgba(jitter(rnd, c, 1))
    return img


def salt_mirror_height():
    """A calm film: flat, with only a trace of the seams below so the reflection carries fine salt detail."""
    hm = salt_height()
    return [[0.5 + 0.06 * max(0.0, v - 0.6) for v in row] for row in hm]


def travertine():
    rnd = random.Random(21)
    n = Noise(22, 4)
    bands = [(240, 235, 222), (229, 220, 200), (246, 243, 234), (222, 211, 188), (236, 229, 213)]
    img = canvas()
    for y in range(N):
        for x in range(N):
            wave = 1.2 * math.sin(2 * math.pi * x / 16 + 0.7) + 0.6 * math.sin(2 * math.pi * 2 * x / 16)
            band = bands[int((y + wave) % 16 / 3.2) % len(bands)]
            c = mix(band, (250, 248, 240), 0.25 * n(x, y))
            if rnd.random() < 0.06:
                c = mix(c, (205, 192, 165), 0.6)
            img[y][x] = rgba(jitter(rnd, c, 2.5))
    return img


def sulfur():
    rnd = random.Random(31)
    n = Noise(32, 4)
    m = Noise(33, 8)
    img = canvas()
    for y in range(N):
        for x in range(N):
            c = mix((214, 214, 52), (246, 226, 70), n(x, y))
            if m(x, y) > 0.72:
                c = mix(c, (178, 206, 64), 0.7)
            r = rnd.random()
            if r < 0.07:
                c = (246, 244, 222)
            elif r < 0.10:
                c = (222, 160, 42)
            img[y][x] = rgba(jitter(rnd, c, 4))
    return img


def dolerite_side():
    rnd = random.Random(41)
    n = Noise(42, 2)
    img = canvas()
    for y in range(N):
        for x in range(N):
            base = {0: (46, 44, 44), 1: (60, 57, 56), 2: (72, 68, 66), 3: (58, 55, 54)}[x % 4]
            c = mix(base, (82, 72, 62), 0.35 * n(x, y))
            if rnd.random() < 0.05:
                c = (96, 74, 56)
            if (y + 7 * (x // 4)) % 16 == 0:
                c = mix(c, (34, 33, 33), 0.7)
            img[y][x] = rgba(jitter(rnd, c, 3))
    return img


def dolerite_top():
    rnd = random.Random(51)
    img = canvas()
    for y in range(N):
        for x in range(N):
            dx, dy = abs(x - 7.5), abs(y - 7.5)
            hexd = max(dx * 0.866 + dy * 0.5, dy)
            c = (88, 84, 80) if 5.6 < hexd < 7.0 else (40, 38, 38) if hexd >= 7.0 else (58, 55, 54)
            img[y][x] = rgba(jitter(rnd, c, 4))
    return img


# ---------------------------------------------------------------- Socotra

def dragon_blood_bark():
    """Smooth pale bark with wavy leaf-scar rings every few pixels and a little red resin weeping from two scars."""
    rnd = random.Random(101)
    f = fbm(102, (2, 4, 8), stretch=(0.5, 2))
    light, base, dark, crease = (180, 172, 157), (154, 146, 131), (126, 118, 105), (98, 90, 80)
    img = canvas()
    rings = {}
    for x in range(N):
        wave = round(0.7 * math.sin(2 * math.pi * x / 16 + 1.3) + 0.4 * math.sin(2 * math.pi * 3 * x / 16))
        for k, r0 in enumerate((1, 6, 11)):
            rings[(x, (r0 + wave + (k == 1 and x > 9)) % N)] = True
    for y in range(N):
        for x in range(N):
            t = f(x, y)
            c = ramp([dark, base, base, light], t)
            if rings.get((x, y)):
                c = mix(c, crease, 0.8)
            elif rings.get((x, (y - 1) % N)):
                c = mix(c, light, 0.55)
            img[y][x] = rgba(jitter(rnd, c, 2.5))
    for sx, sy, ln in ((4, 7, 3), (12, 12, 2)):
        for i in range(ln):
            img[(sy + i) % N][sx] = rgba(mix((150, 30, 28), (110, 18, 22), i / max(1, ln - 1)))
        img[sy % N][(sx + 1) % N] = rgba((128, 26, 26))
    return img


def dragon_blood_top():
    """Monocot cross-section: fibrous pale core speckled with red resin, grey bark rim."""
    rnd = random.Random(111)
    f = fbm(112, (4, 8), (0.6, 0.4))
    img = canvas()
    for y in range(N):
        for x in range(N):
            d = max(abs(x - 7.5), abs(y - 7.5))
            r = math.hypot(x - 7.5, y - 7.5)
            if d > 7:
                c = (110, 102, 90)
            elif d > 6:
                c = (150, 142, 128)
            else:
                c = ramp([(170, 140, 100), (198, 172, 128), (214, 192, 150)], f(x, y))
                if r < 3.2 and rnd.random() < 0.55:
                    c = mix(c, (156, 40, 34), 0.75)
                elif rnd.random() < 0.08:
                    c = (150, 46, 36)
            img[y][x] = rgba(jitter(rnd, c, 3))
    return img


def sword_leaves(seed, dark, mid, tip, centers, blades, length, fill=0.0):
    """Clusters of stiff narrow blades radiating mostly upward from rosette centres (wrapping), over a dark mass that
    covers `fill` of the tile so the crown reads dense, not as scattered strokes."""
    rnd = random.Random(seed)
    img = canvas()
    if fill > 0:
        f = fbm(seed + 7, (2, 4, 8))
        vals = sorted(f(x, y) for y in range(N) for x in range(N))
        cut = vals[int(len(vals) * (1 - fill))]
        deep = tuple(c * 0.8 for c in dark)
        for y in range(N):
            for x in range(N):
                if f(x, y) >= cut:
                    img[y][x] = rgba(jitter(rnd, mix(deep, dark, (f(x, y) - cut) * 6), 3))
    for (cx, cy) in centers:
        for b in range(blades):
            a = -math.pi / 2 + rnd.uniform(-1.35, 1.35)
            ln = rnd.uniform(*length)
            x1, y1 = round(cx + math.cos(a) * ln), round(cy + math.sin(a) * ln)
            line(img, cx, cy, x1, y1, lambda i, n: jitter(rnd, ramp([dark, mid, tip], i / max(1, n - 1)), 4))
    # A few stray lower blades so the tile does not read as dots.
    for _ in range(6):
        cx, cy = rnd.randrange(N), rnd.randrange(N)
        a = rnd.uniform(0, math.pi * 2)
        line(img, cx, cy, round(cx + math.cos(a) * 3), round(cy + math.sin(a) * 3), lambda i, n: ramp([dark, mid], i / 3), only_empty=True)
    return img


def dragon_blood_leaves():
    return sword_leaves(121, (34, 66, 38), (60, 104, 52), (104, 142, 70), [(3, 6), (11, 3), (8, 12), (14, 10), (6, 1)], 11, (3.0, 6.0), 0.5)


def desert_rose():
    """Adenium: fat grey bottle trunk, a few stubby branches, pink flowers at the tips."""
    rnd = random.Random(131)
    img = canvas()
    trunk, trunk_d, trunk_l = (156, 146, 132), (122, 112, 100), (182, 174, 160)
    for y in range(9, 16):
        w = {9: 1, 10: 2, 11: 3, 12: 3, 13: 4, 14: 4, 15: 3}[y]
        for x in range(8 - w, 8 + w):
            c = trunk_d if x == 8 + w - 1 else trunk_l if x == 8 - w else trunk
            img[y][x] = rgba(jitter(rnd, c, 4))
    branches = [((7, 9), (4, 5)), ((8, 9), (11, 4)), ((8, 9), (8, 3))]
    for (x0, y0), (x1, y1) in branches:
        line(img, x0, y0, x1, y1, lambda i, n: jitter(rnd, trunk_d if i % 2 else trunk, 3), wrap=False)
    petal, petal_d, eye = (236, 98, 152), (196, 52, 104), (252, 214, 228)
    for (fx, fy) in ((4, 4), (11, 3), (8, 2), (3, 6), (12, 5)):
        for dx, dy in ((0, -1), (-1, 0), (1, 0), (0, 1)):
            x, y = fx + dx, fy + dy
            if 0 <= x < N and 0 <= y < N:
                img[y][x] = rgba(petal if dy <= 0 else petal_d)
        img[fy][fx] = rgba(eye)
    for (lx, ly) in ((5, 6), (10, 5), (9, 4), (6, 4)):
        if img[ly][lx][3] == 0:
            img[ly][lx] = rgba((86, 128, 60))
    return img


# ---------------------------------------------------------------- geothermal

def geyserite():
    """Pearly sinter: pale grey-white with knobbly 'popcorn' nodules lit from the top left."""
    rnd = random.Random(201)
    f = fbm(202)
    img = canvas()
    hmap = [[0.5] * N for _ in range(N)]
    for y in range(N):
        for x in range(N):
            c = ramp([(190, 184, 172), (210, 206, 195), (226, 222, 212)], f(x, y))
            img[y][x] = rgba(c)
    for _ in range(11):
        cx, cy, r = rnd.uniform(0, N), rnd.uniform(0, N), rnd.uniform(0.9, 1.7)
        for y in range(N):
            for x in range(N):
                d = min(math.hypot(x + 0.5 - cx - ox, y + 0.5 - cy - oy) for ox in (-N, 0, N) for oy in (-N, 0, N))
                if d < r:
                    k = 1 - d / r
                    hmap[y][x] = max(hmap[y][x], 0.5 + 0.5 * k)
    for y in range(N):
        for x in range(N):
            dh = hmap[y][x] - hmap[(y + 1) % N][(x + 1) % N]
            c = img[y][x]
            if dh > 0.15:
                c = mix(c, (240, 238, 230, 255), 0.7)
            elif dh < -0.15:
                c = mix(c, (168, 162, 150, 255), 0.7)
            if rnd.random() < 0.05:
                c = mix(c, (214, 186, 150, 255), 0.6)
            img[y][x] = rgba(jitter(rnd, c, 2.5))
    return img, hmap


def vent_top(seed, rim_tex, hole, hole_rim, ring, glint, stain=None):
    rnd = random.Random(seed)
    img = [row[:] for row in rim_tex]
    for y in range(N):
        for x in range(N):
            a = math.atan2(y - 7.5, x - 7.5)
            r = math.hypot(x - 7.5, y - 7.5) * (1 + 0.12 * math.sin(3 * a + 1))
            if r < 2.6:
                c = mix(hole, hole_rim, r / 2.6)
                if rnd.random() < 0.12:
                    c = glint
                img[y][x] = rgba(c)
            elif r < 3.6:
                img[y][x] = rgba(jitter(rnd, ring, 4))
            elif stain and r < 6.5 and rnd.random() < 0.55 * (1 - (r - 3.6) / 2.9):
                img[y][x] = rgba(jitter(rnd, stain, 6))
    return img


def volcanic_rock(seed, stain=None):
    rnd = random.Random(seed)
    f = fbm(seed + 1)
    img = canvas()
    for y in range(N):
        for x in range(N):
            c = ramp([(92, 88, 80), (118, 112, 100), (138, 130, 114)], f(x, y))
            if stain and y < 6 and rnd.random() < 0.5 * (1 - y / 6):
                c = mix(c, stain, 0.6)
            img[y][x] = rgba(jitter(rnd, c, 4))
    return img


def thermal_mat(seed, light, base, dark, speck=None):
    """Microbial mat: wavy runoff filaments, glossy and bubbly."""
    rnd = random.Random(seed)
    f = fbm(seed + 1, (2, 4))
    img = canvas()
    hmap = []
    for y in range(N):
        hr = []
        for x in range(N):
            wav = math.sin(2 * math.pi * (x + 2.2 * math.sin(2 * math.pi * y / 16 * 2 + seed)) / 16 * 3 + 6 * f(x, y))
            t = 0.5 + 0.35 * wav + 0.25 * (f(x, y) - 0.5)
            c = ramp([dark, base, base, light], t)
            hr.append(t)
            if speck and rnd.random() < 0.05:
                c = speck
            img[y][x] = rgba(jitter(rnd, c, 3))
        hmap.append(hr)
    for _ in range(7):
        x, y = rnd.randrange(N), rnd.randrange(N)
        img[y][x] = rgba(mix(img[y][x], (255, 250, 230, 255), 0.55))
        img[(y + 1) % N][x] = rgba(mix(img[(y + 1) % N][x], (0, 0, 0, 255), 0.25))
    return img, hmap


# ---------------------------------------------------------------- sand, ash, scoria

def grains(seed, base, dark, light, extra=None, extra_p=0.0, amt=4):
    rnd = random.Random(seed)
    f = fbm(seed + 1, (2, 4), (0.6, 0.4))
    img = canvas()
    for y in range(N):
        for x in range(N):
            c = mix(base, light, 0.35 * (f(x, y) - 0.5) + 0.0)
            r = rnd.random()
            if r < 0.16:
                c = dark
            elif r < 0.27:
                c = light
            elif extra and r < 0.27 + extra_p:
                c = extra
            img[y][x] = rgba(jitter(rnd, c, amt))
    return img


def scoria():
    rnd = random.Random(301)
    f = fbm(302)
    img = canvas()
    hmap = [[0.6] * N for _ in range(N)]
    for y in range(N):
        for x in range(N):
            c = ramp([(80, 38, 32), (110, 52, 40), (136, 66, 46)], f(x, y))
            if f((x * 3) % N, (y * 3) % N) > 0.72:
                c = mix(c, (52, 42, 42), 0.7)
            img[y][x] = rgba(jitter(rnd, c, 4))
    for _ in range(9):
        cx, cy = rnd.randrange(N), rnd.randrange(N)
        r = rnd.choice((1, 1, 2))
        for dy in range(-1, r):
            for dx in range(-1, r):
                if dx * dx + dy * dy <= r * r:
                    img[(cy + dy) % N][(cx + dx) % N] = rgba((34, 22, 20))
                    hmap[(cy + dy) % N][(cx + dx) % N] = 0.15
        img[(cy + r) % N][cx] = rgba(mix(img[(cy + r) % N][cx], (170, 96, 70, 255), 0.6))
    return img, hmap


# ---------------------------------------------------------------- white old-growth forest

def ghost_bark():
    """Near-white bark with long vertical furrows, silver lichen and a few dark lenticels."""
    rnd = random.Random(401)
    f = fbm(402, (2, 4, 8), stretch=(2, 0.5))
    img = canvas()
    for y in range(N):
        for x in range(N):
            c = ramp([(196, 196, 190), (218, 218, 212), (234, 234, 229)], f(x, y))
            img[y][x] = rgba(jitter(rnd, c, 2))
    for fx in (1, 6, 10, 14):
        x = fx
        for y in range(N):
            if rnd.random() < 0.3:
                x += rnd.choice((-1, 1))
            if rnd.random() < 0.88:
                img[y][x % N] = rgba(jitter(rnd, (150, 150, 146), 5))
                img[y][(x + 1) % N] = rgba(mix(img[y][(x + 1) % N], (244, 244, 240, 255), 0.5))
    for _ in range(3):
        cx, cy = rnd.randrange(N), rnd.randrange(N)
        for dy in range(-1, 2):
            for dx in range(-1, 2):
                if rnd.random() < 0.6:
                    img[(cy + dy) % N][(cx + dx) % N] = rgba(jitter(rnd, (200, 212, 198), 4))
    for _ in range(4):
        img[rnd.randrange(N)][rnd.randrange(N)] = rgba((118, 118, 116))
    return img


def rings_top(seed, bark, bark_d, wood_cols, ring_step=2.2):
    rnd = random.Random(seed)
    img = canvas()
    for y in range(N):
        for x in range(N):
            d = max(abs(x - 7.5), abs(y - 7.5))
            r = math.hypot(x - 7.5, y - 7.5)
            if d > 7:
                c = bark_d
            elif d > 6:
                c = bark
            else:
                k = int(r / ring_step) % len(wood_cols)
                c = wood_cols[k]
            img[y][x] = rgba(jitter(rnd, c, 3))
    return img


def blob_leaves(seed, cols, coverage, size=(1, 2)):
    """Leaf clumps: small blobs of 2-4 pixels, shaded light top-left, dark bottom-right, gaps transparent."""
    rnd = random.Random(seed)
    img = canvas()
    f = fbm(seed + 1, (2, 4))
    target = int(N * N * coverage)
    filled = 0
    tries = 0
    while filled < target and tries < 4000:
        tries += 1
        cx, cy = rnd.randrange(N), rnd.randrange(N)
        w, h = rnd.randint(*size), rnd.randint(*size)
        t = f(cx, cy)
        for dy in range(h):
            for dx in range(w):
                x, y = (cx + dx) % N, (cy + dy) % N
                if img[y][x][3] == 0:
                    filled += 1
                shade = t + (0.18 if dx == 0 and dy == 0 else -0.18 if dx == w - 1 and dy == h - 1 else 0)
                img[y][x] = rgba(jitter(rnd, ramp(cols, shade), 4))
    return img


def pale_moss():
    rnd = random.Random(421)
    f = fbm(422, (4, 8), (0.6, 0.4))
    img = canvas()
    for y in range(N):
        for x in range(N):
            c = ramp([(132, 142, 126), (166, 174, 158), (196, 204, 186)], f(x, y))
            img[y][x] = rgba(jitter(rnd, c, 5))
    for _ in range(12):
        x, y = rnd.randrange(N), rnd.randrange(N)
        img[y][x] = rgba((214, 220, 206))
        img[(y + 1) % N][x] = rgba((120, 130, 116))
    return img


def pale_fern():
    rnd = random.Random(431)
    img = canvas()
    stem = [(8, 15), (8, 12), (7, 9), (7, 6), (8, 3), (9, 1)]
    for (x0, y0), (x1, y1) in zip(stem, stem[1:]):
        line(img, x0, y0, x1, y1, (112, 132, 116), wrap=False)
    for (x, y) in ((8, 13), (8, 11), (7, 9), (7, 7), (7, 5), (8, 3)):
        span = max(2, 6 - abs(y - 8) // 2)
        for s in (-1, 1):
            line(img, x, y, x + s * span, y - 1 - (span > 3), lambda i, n: jitter(rnd, ramp([(122, 142, 124), (172, 192, 174), (218, 230, 216)], i / max(1, n - 1)), 4), wrap=False)
            line(img, x, y + 1, x + s * (span - 1), y, lambda i, n: jitter(rnd, ramp([(112, 132, 116), (150, 170, 152)], i / max(1, n - 1)), 3), wrap=False, only_empty=True)
    return img


# ---------------------------------------------------------------- giant tree forest

def redwood_bark():
    """Thick, fibrous red-brown bark: deep vertical furrows between rounded, stringy ridges."""
    rnd = random.Random(501)
    f = fbm(502, (2, 4, 8), stretch=(2, 0.4))
    img = canvas()
    for y in range(N):
        for x in range(N):
            ridge = math.cos(2 * math.pi * (x + 1.6 * math.sin(2 * math.pi * y / 16 + x)) / 16 * 4)
            t = 0.5 + 0.38 * ridge + 0.3 * (f(x, y) - 0.5)
            c = ramp([(70, 32, 22), (106, 50, 33), (132, 64, 42), (156, 82, 54)], t)
            if rnd.random() < 0.04:
                c = mix(c, (140, 116, 104), 0.6)
            img[y][x] = rgba(jitter(rnd, c, 3))
    return img


# ---------------------------------------------------------------- glacier

def glacier_ice():
    rnd = random.Random(601)
    f = fbm(602, (2, 4), (0.7, 0.3))
    img = canvas()
    hmap = []
    for y in range(N):
        hr = []
        for x in range(N):
            band = math.sin(2 * math.pi * (y + 1.2 * math.sin(2 * math.pi * x / 16)) / 16 * 2)
            c = ramp([(84, 132, 196), (104, 152, 210), (132, 180, 228)], f(x, y))
            if band > 0.55:
                c = mix(c, (176, 206, 236), 0.6)
                if rnd.random() < 0.22:
                    c = (230, 240, 250)
            hr.append(0.6 + 0.1 * band)
            img[y][x] = rgba(jitter(rnd, c, 2))
        hmap.append(hr)
    for (x0, y0, x1, y1) in ((2, 1, 7, 9), (11, 6, 14, 15)):
        for (x, y) in line(canvas(), x0, y0, x1, y1, (0, 0, 0)):
            img[y % N][x % N] = rgba((62, 102, 168))
            hmap[y % N][x % N] = 0.35
    return img, hmap


# ---------------------------------------------------------------- star abyss

def abyss_stone():
    rnd = random.Random(701)
    f = fbm(702)
    g = fbm(703, (4, 8), (0.5, 0.5))
    img = canvas()
    stars = []
    for y in range(N):
        for x in range(N):
            c = ramp([(16, 14, 28), (26, 23, 44), (36, 31, 60)], f(x, y))
            if abs(g(x, y) - 0.5) < 0.035:
                c = mix(c, (64, 42, 96), 0.7)
            img[y][x] = rgba(jitter(rnd, c, 2))
    for (x, y, col) in ((3, 2, (250, 250, 255)), (12, 5, (200, 230, 255)), (7, 11, (255, 240, 205)), (14, 13, (230, 220, 255)), (1, 9, (190, 220, 255))):
        img[y][x] = rgba(col)
        stars.append((x, y))
    for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        img[(2 + dy) % N][(3 + dx) % N] = rgba(mix(img[(2 + dy) % N][(3 + dx) % N], (110, 110, 170, 255), 0.6))
    return img, stars


def star_crystal():
    rnd = random.Random(711)
    img = canvas()
    for y in range(N):
        for x in range(N):
            facet = ((x + y) // 5 + (x - y + 16) // 6) % 3
            c = [(110, 160, 220), (156, 204, 242), (206, 234, 255)][facet]
            if (x + y) % 5 == 0 or (x - y) % 6 == 0:
                c = mix(c, (236, 248, 255), 0.5)
            img[y][x] = rgba(jitter(rnd, c, 4))
    for (x, y) in ((4, 3), (11, 9), (6, 13), (13, 2)):
        img[y][x] = rgba((255, 255, 255))
    return img


def star_cluster():
    rnd = random.Random(721)
    img = canvas()
    shards = [(4, 7, 2), (8, 2, 3), (12, 6, 2), (6, 9, 1), (10, 10, 1)]
    for (x, top, w) in shards:
        for y in range(top, 16):
            half = w if y > top + 2 else max(0, w - (top + 2 - y))
            for dx in range(-half, half + 1):
                xx = x + dx
                if 0 <= xx < N:
                    t = (xx - x + half) / max(1, 2 * half)
                    c = ramp([(206, 236, 255), (150, 200, 240), (96, 144, 210)], t)
                    if y == top:
                        c = (250, 252, 255)
                    img[y][xx] = rgba(jitter(rnd, c, 3))
    return img


# ---------------------------------------------------------------- white old growth: dressing

def silver_beard():
    """Beard lichen: pale grey-green strands hanging from the top edge, tangled and thinning downwards."""
    rnd = random.Random(441)
    img = canvas()
    for x0 in (1, 3, 4, 6, 8, 9, 11, 12, 14):
        x = x0
        ln = rnd.randint(8, 16)
        for y in range(ln):
            if rnd.random() < 0.25:
                x += rnd.choice((-1, 1))
            if 0 <= x < N:
                t = y / ln
                img[y][x] = rgba(jitter(rnd, ramp([(214, 222, 206), (182, 194, 176), (150, 164, 146)], t), 5))
                if rnd.random() < 0.15 and 0 <= x + 1 < N:
                    img[y][x + 1] = rgba((196, 206, 190))
    return img


def moon_bracket():
    """Shelf fungus: three stacked half-discs, cream with a glowing pale rim and dark gills underneath."""
    rnd = random.Random(451)
    img = canvas()
    for (cx, cy, r) in ((8, 13, 6.5), (6, 8, 4.5), (10, 4, 3.5)):
        for y in range(N):
            for x in range(N):
                dx, dy = x + 0.5 - cx, (y + 0.5 - cy) * 1.9
                d = math.hypot(dx, dy)
                if d < r and y + 0.5 <= cy:
                    rim = d > r - 1.2
                    c = (246, 248, 236) if rim else ramp([(214, 206, 180), (232, 226, 204)], 1 - d / r)
                    img[y][x] = rgba(jitter(rnd, c, 3))
                elif d < r and y + 0.5 <= cy + 1:
                    img[y][x] = rgba((132, 124, 110))
    return img


def moonpetal():
    img = canvas()
    line(img, 8, 15, 8, 8, (86, 132, 92), wrap=False)
    line(img, 8, 12, 5, 10, (96, 146, 98), wrap=False)
    line(img, 8, 13, 11, 11, (96, 146, 98), wrap=False)
    for k in range(5):
        a = -math.pi / 2 + k * 2 * math.pi / 5
        for s2 in (1, 2, 3):
            x, y = round(8 + math.cos(a) * s2), round(5 + math.sin(a) * s2 * 0.9)
            if 0 <= x < N and 0 <= y < N:
                img[y][x] = rgba((250, 252, 248) if s2 < 3 else (226, 232, 240))
    img[5][8] = rgba((200, 230, 255))
    img[4][8] = rgba((236, 246, 255))
    return img


def ghost_hollow(bark):
    img = [row[:] for row in bark]
    for y in range(N):
        for x in range(N):
            d = math.hypot((x + 0.5 - 8) / 3.6, (y + 0.5 - 8.5) / 5.2)
            if d < 1:
                img[y][x] = rgba(mix((18, 20, 26), (48, 52, 60), d))
            elif d < 1.3:
                img[y][x] = rgba((170, 168, 160))
    img[7][7] = rgba((230, 240, 255))
    img[10][9] = rgba((200, 220, 255))
    return img


# ---------------------------------------------------------------- star abyss: dressing

def stardust():
    rnd = random.Random(731)
    f = fbm(732, (2, 4), (0.6, 0.4))
    img = canvas()
    sparkle = []
    for y in range(N):
        for x in range(N):
            c = ramp([(22, 26, 66), (36, 42, 98), (56, 64, 136)], f(x, y))
            r = rnd.random()
            if r < 0.06:
                c = rnd.choice(((240, 246, 255), (170, 230, 255), (255, 236, 200)))
                sparkle.append((x, y))
            elif r < 0.2:
                c = mix(c, (14, 16, 44), 0.6)
            img[y][x] = rgba(jitter(rnd, c, 3))
    return img, set(sparkle)


def starlit(abyss):
    rnd = random.Random(741)
    img = [row[:] for row in abyss]
    veins = []
    for (x0, y0, x1, y1) in ((0, 3, 9, 7), (9, 7, 15, 4), (6, 16, 10, 7), (12, 15, 15, 11)):
        for (x, y) in line(canvas(), x0, y0, x1, y1, (0, 0, 0)):
            img[y % N][x % N] = rgba(jitter(rnd, (110, 220, 255), 10))
            veins.append((x % N, y % N))
    for (x, y) in list(veins)[::3]:
        img[(y + 1) % N][x] = rgba(mix(img[(y + 1) % N][x], (60, 120, 200, 255), 0.6))
    return img, set(veins)


def star_bloom():
    img = canvas()
    line(img, 8, 15, 8, 9, (60, 90, 140), wrap=False)
    line(img, 8, 13, 5, 11, (70, 110, 160), wrap=False)
    line(img, 8, 14, 11, 12, (70, 110, 160), wrap=False)
    for k in range(6):
        a = k * math.pi / 3
        for s2 in (1, 2, 3, 4):
            x, y = round(8 + math.cos(a) * s2), round(6 + math.sin(a) * s2)
            if 0 <= x < N and 0 <= y < N:
                img[y][x] = rgba(ramp([(240, 250, 255), (150, 220, 255), (150, 120, 240)], s2 / 4))
    for (x, y) in ((7, 6), (9, 6), (8, 5), (8, 7)):
        img[y][x] = rgba((255, 255, 255))
    return img


def astral_roots():
    rnd = random.Random(761)
    img = canvas()
    for x0 in (2, 5, 7, 10, 13):
        x = x0
        ln = rnd.randint(9, 16)
        for y in range(ln):
            if rnd.random() < 0.2:
                x += rnd.choice((-1, 1))
            if 0 <= x < N:
                img[y][x] = rgba(ramp([(70, 80, 130), (100, 130, 190), (170, 220, 255)], y / ln))
        if 0 <= x < N:
            img[min(N - 1, ln - 1)][x] = rgba((236, 250, 255))
    return img


def star_spring_top(abyss):
    rnd = random.Random(771)
    img = [row[:] for row in abyss]
    for y in range(N):
        for x in range(N):
            d = math.hypot(x + 0.5 - 8, y + 0.5 - 8)
            if d < 4.6:
                c = ramp([(250, 254, 255), (160, 220, 255), (70, 110, 220)], d / 4.6)
                if rnd.random() < 0.08:
                    c = (255, 255, 255)
                img[y][x] = rgba(c)
            elif d < 5.8:
                img[y][x] = rgba(jitter(rnd, (120, 170, 230), 8))
    return img


def ghost_sapling():
    """A pale little tree: white forked stem, silver-white leaf tufts."""
    rnd = random.Random(471)
    img = canvas()
    line(img, 8, 15, 8, 8, (220, 220, 214), wrap=False)
    line(img, 8, 11, 5, 7, (200, 200, 194), wrap=False)
    line(img, 8, 10, 11, 6, (200, 200, 194), wrap=False)
    for (cx, cy, r) in ((5, 5, 2.3), (11, 4, 2.3), (8, 6, 2.6)):
        for y in range(N):
            for x in range(N):
                d = math.hypot(x + 0.5 - cx, y + 0.5 - cy)
                if d < r and rnd.random() < 0.85:
                    img[y][x] = rgba(jitter(rnd, ramp([(178, 196, 190), (220, 230, 226), (246, 250, 248)], 1 - d / r), 4))
    return img


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
    if name == "ghostwood_leaves":
        tool = loot(name, k)["pools"][0]["entries"][0]["conditions"][0]
        write(f"data/biomesmod/loot_table/blocks/{name}.json", {"type": "minecraft:block", "pools": [{"rolls": 1, "bonus_rolls": 0, "entries": [
            {"type": "minecraft:alternatives", "children": [
                {"type": "minecraft:item", "name": "biomesmod:ghostwood_leaves", "conditions": [tool]},
                {"type": "minecraft:item", "name": "biomesmod:ghostwood_sapling",
                 "conditions": [{"condition": "minecraft:survives_explosion"}, {"condition": "minecraft:random_chance", "chance": 0.05}]}]}]}],
            "random_sequence": f"biomesmod:blocks/{name}"})


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
    block("pale_moss_carpet", "carpet", ("Pale Moss Carpet", "은빛 이끼 카펫"), ("mineable/hoe",), dict(wool=t("pale_moss")))
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

    # ---- textures
    P = lambda name: os.path.join(TEX, name + ".png")
    png(P("salt_crust"), salt())
    png(P("salt_crust_n"), normal_map(salt_height(), 1.2))
    png(P("salt_crust_s"), spec_map(lambda x, y: (0.82, 0.05, 30, None)))
    # LabPBR: smoothness 0.97 (roughness (1-s)^2 ~ 0.001), dielectric F0 0.16: looking down the salt shows through,
    # towards the horizon Fresnel takes it to a full mirror. Porosity 0 (a wet, closed surface).
    png(P("salt_mirror"), salt_mirror())
    png(P("salt_mirror_n"), normal_map(salt_mirror_height(), 0.6))
    png(P("salt_mirror_s"), spec_map(lambda x, y: (0.97, 0.16, 0, None)))
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

    # ---- models, loot, tags
    tags = {}
    for name, b in BLOCKS.items():
        models(name, b)
        for tg in b["tags"]:
            tags.setdefault(tg, []).append("biomesmod:" + name)
    tag_dir = os.path.join(RES, "data", "minecraft", "tags", "block")
    for tg, vals in sorted(tags.items()):
        write(f"data/minecraft/tags/block/{tg}.json", {"replace": False, "values": vals})
        if tg in ("logs", "logs_that_burn", "leaves", "sand", "small_flowers", "flowers", "saplings"):
            write(f"data/minecraft/tags/item/{tg}.json", {"replace": False, "values": vals})

    # ---- names, merged into specs/lang (the biome generator turns them into the lang files)
    for loc, i in (("en_us", 0), ("ko_kr", 1)):
        p = os.path.join(REPO, "specs", "lang", loc + ".json")
        with open(p, encoding="utf-8") as f:
            lang = json.load(f)
        for name, b in BLOCKS.items():
            lang[f"block.biomesmod.{name}"] = b["names"][i]
        lang = dict(sorted(lang.items()))
        with open(p, "w", encoding="utf-8", newline="\n") as f:
            json.dump(lang, f, indent=2, ensure_ascii=False)
            f.write("\n")
    print("%d blocks written" % len(BLOCKS))


if __name__ == "__main__":
    main()
