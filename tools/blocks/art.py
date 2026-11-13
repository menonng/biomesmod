"""Block art for biomesmod: every 16x16 texture, drawn the way pixel art is drawn by hand.

Rules the whole set follows (so the blocks sit next to vanilla's):
  * a small hand-picked colour ramp per material, hue-shifted (shadows cooler and more saturated, lights warmer);
    every pixel is one of the ramp colours, never a random tint
  * form first: each surface has a height field (crystals, strata, bark furrows, vesicles, cells) that is lit from the
    top left; the lit value picks the ramp colour, with a little ordered dither where two tones meet
  * details are placed in clusters with a highlight and a shadow, never as per-pixel noise
  * plants and other cut-out shapes are sprites drawn pixel by pixel

Everything is seeded and tileable; functions return 16 rows of 16 RGBA tuples (and the height field where the normal
map needs it).
"""
import math
import random

N = 16


# ---------------------------------------------------------------- helpers

def hexc(s):
    s = s.lstrip('#')
    return tuple(int(s[i:i + 2], 16) for i in (0, 2, 4))


def R(*cols):
    return [hexc(c) if isinstance(c, str) else tuple(c) for c in cols]


def mix(a, b, t):
    t = max(0.0, min(1.0, t))
    return tuple(a[i] * (1 - t) + b[i] * t for i in range(3))


def rgba(c, a=255):
    return (c[0], c[1], c[2], a)


def canvas():
    return [[(0, 0, 0, 0) for _ in range(N)] for _ in range(N)]


def spread(dark, base, light, n=6):
    """A ramp of n colours through three anchors, with the shadows pushed towards blue and the lights towards yellow."""
    out = []
    for i in range(n):
        t = i / (n - 1)
        c = mix(dark, base, t * 2) if t < 0.5 else mix(base, light, t * 2 - 1)
        s = (0.5 - t) * 14
        out.append((c[0] - s, c[1] - s * 0.3, c[2] + s))
    return out


class Noise:
    """Tileable value noise with cx by cy lattice cells across the tile."""

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


def fbm(seed, cells=((2, 2), (4, 4), (8, 8)), weights=(0.55, 0.3, 0.15)):
    ns = [Noise(seed + i * 101, c[0], c[1]) for i, c in enumerate(cells)]
    return lambda x, y: sum(w * n(x, y) for w, n in zip(weights, ns))


class Cells:
    """Tileable Worley cells: n points on a jittered grid. at(x, y) -> (f1, f2, id of the nearest point)."""

    def __init__(self, seed, per_side, jitter=0.8):
        rnd = random.Random(seed)
        step = N / per_side
        self.pts = [((i + 0.5 + (rnd.random() - 0.5) * jitter) * step, (j + 0.5 + (rnd.random() - 0.5) * jitter) * step)
                    for j in range(per_side) for i in range(per_side)]

    def at(self, x, y):
        best = (99.0, 99.0, 0)
        for k, (px, py) in enumerate(self.pts):
            dx = abs(x + 0.5 - px)
            dy = abs(y + 0.5 - py)
            d = math.hypot(min(dx, N - dx), min(dy, N - dy))
            if d < best[0]:
                best = (d, best[0], k)
            elif d < best[1]:
                best = (best[0], d, best[2])
        return best


def field(fn):
    return [[fn(x, y) for x in range(N)] for y in range(N)]


def norm(h):
    lo = min(min(r) for r in h)
    hi = max(max(r) for r in h)
    return [[(v - lo) / (hi - lo + 1e-9) for v in r] for r in h]


def lit(h, k=1.0, dx=1, dy=1):
    """Height lit from the top left: slopes facing the light get brighter, the far sides fall into shadow."""
    return norm([[h[y][x] + k * (h[(y + dy) % N][(x + dx) % N] - h[(y - dy) % N][(x - dx) % N]) for x in range(N)] for y in range(N)])


BAYER = [[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]]


def pick(ramp, v, x, y, dither=0.5):
    i = v * (len(ramp) - 1) + ((BAYER[y % 4][x % 4] + 0.5) / 16 - 0.5) * dither
    return ramp[int(round(max(0, min(len(ramp) - 1, i))))]


def paint(v, ramp, dither=0.5, lo=0.0, hi=1.0):
    return [[rgba(pick(ramp, lo + (hi - lo) * v[y][x], x, y, dither)) for x in range(N)] for y in range(N)]


def put(img, x, y, c):
    img[y % N][x % N] = rgba(c)


def sprite(rows, pal):
    """rows: 16 strings; '.' is transparent, any other character a key of pal."""
    img = canvas()
    for y, row in enumerate(rows[:N]):
        for x, ch in enumerate(row[:N]):
            if ch != '.' and ch != ' ':
                img[y][x] = rgba(hexc(pal[ch]))
    return img


def scatter(seed, n, min_dist, ok=None):
    """n tile-wrapped points at least min_dist apart."""
    rnd = random.Random(seed)
    pts = []
    for _ in range(400):
        if len(pts) >= n:
            break
        x, y = rnd.randrange(N), rnd.randrange(N)
        if ok and not ok(x, y):
            continue
        if all(math.hypot(min(abs(x - a), N - abs(x - a)), min(abs(y - b), N - abs(y - b))) >= min_dist for a, b in pts):
            pts.append((x, y))
    return pts


# ---------------------------------------------------------------- salt

_SALT_CELLS = Cells(11, 3, 0.9)


def salt_height():
    """Polygonal salt crust: flat plates, a raised rim of crystals along the seams."""
    f = fbm(12)

    def h(x, y):
        f1, f2, _ = _SALT_CELLS.at(x, y)
        rim = max(0.0, 1 - (f2 - f1) / 1.5)
        return 0.5 + 0.12 * f(x, y) + 0.38 * rim * rim
    return field(h)


def salt():
    hm = salt_height()
    v = lit(hm, 1.5)
    img = paint(v, R('#b9c6d4', '#cfd9e3', '#e2e8ee', '#f0f3f5', '#fafbfb', '#ffffff'), 0.4, 0.1, 1.0)
    # A few crystal glints on the plates: a white pixel with a cool shadow to its lower right.
    for x, y in scatter(13, 5, 4, lambda x, y: hm[y][x] < 0.6):
        put(img, x, y, hexc('#ffffff'))
        put(img, x + 1, y + 1, hexc('#cfd9e3'))
    return img


def salt_mirror():
    """Flooded salt under a few centimetres of still water: the pale blue of the sky, the seams showing faintly through."""
    hm = salt_height()
    f = fbm(212, ((1, 1), (2, 2)), (0.6, 0.4))
    v = norm(field(lambda x, y: f(x, y) + 0.9 * max(0.0, hm[y][x] - 0.66)))
    return paint(v, R('#a4c2e1', '#abc8e5', '#b3cee9', '#bcd5ed', '#c8ddf1'), 0.9)


def salt_mirror_height():
    hm = salt_height()
    return [[0.5 + 0.03 * max(0.0, v - 0.6) for v in row] for row in hm]


# ---------------------------------------------------------------- travertine, sulfur, dolerite

def travertine():
    """Terrace travertine: thin wavy layers, each with a bright rounded lip and a shadow under it, and a few pores."""
    n = Noise(101, 4)

    def h(x, y):
        yy = y + 1.5 * math.sin(2 * math.pi * x / N + 0.8) + 1.6 * (n(x, y) - 0.5)
        band = (yy / (N / 3.0)) % 1.0
        return band ** 0.7
    hm = field(h)
    v = lit(hm, 0.9, 0, 1)
    img = paint(v, R('#9c8a70', '#b8a78b', '#cfc1a6', '#e1d6be', '#efe8d6', '#faf6ea'), 0.5)
    for x, y in scatter(102, 5, 4):
        put(img, x, y, hexc('#8a775e'))
        put(img, x, y + 1, hexc('#faf6ea'))
    return img


def sulfur():
    """Sulfur crust: a tight crowd of rounded yellow crystals, orange where iron stains it."""
    cells = Cells(21, 3, 0.9)
    stain = fbm(22, ((2, 2), (4, 4)), (0.6, 0.4))

    def h(x, y):
        f1, f2, _ = cells.at(x, y)
        return max(0.0, 1 - f1 / 3.8) ** 0.8 - (0.35 if f2 - f1 < 0.6 else 0.0)
    v = lit(field(h), 0.7)
    yellow = R('#7d5c0c', '#a9830c', '#d0a912', '#ebcd2a', '#f8e65c', '#fff5a6')
    orange = R('#6b3d0c', '#96570e', '#bd7613', '#dc9a24', '#f0bd48', '#fadc7c')
    return [[rgba(pick(orange if stain(x, y) > 0.6 else yellow, v[y][x], x, y, 0.3)) for x in range(N)] for y in range(N)]


_DOL = R('#15161a', '#212328', '#2f3238', '#3e4249', '#50555d', '#646b74')


def dolerite_side():
    """Columnar jointing seen from the side: three pillars with rounded faces, tight vertical joints, a few cross joints."""
    rnd = random.Random(31)
    edges = [0, 5, 11, 16]
    streak = Noise(32, 16, 2)
    joints = {c: sorted(rnd.sample(range(N), 2)) for c in range(3)}
    img = canvas()
    for y in range(N):
        for x in range(N):
            c = max(i for i in range(3) if edges[i] <= x)
            w = edges[c + 1] - edges[c]
            dx = x - edges[c]
            v = 0.42 + 0.34 * math.sin(math.pi * (dx + 0.5) / w) + 0.14 * (0.5 - dx / w) + 0.12 * (streak(x, y) - 0.5)
            if dx == 0:
                v = 0.04
            elif dx == 1:
                v += 0.12
            if y in joints[c] and dx > 0:
                v -= 0.3
            elif (y - 1) % N in joints[c] and dx > 0:
                v += 0.12
            img[y][x] = rgba(pick(_DOL, v, x, y, 0.4))
    return img


def dolerite_top():
    """The top of a basalt column: one six-sided polygon, slightly domed, in its dark joint."""
    f = fbm(41)

    def hexd(x, y):
        dx, dy = abs(x - 7.5), abs(y - 7.5)
        return max(dx, dx * 0.5 + dy * 0.866)
    hm = field(lambda x, y: 0.5 + 0.3 * (1 - hexd(x, y) / 6.4) + 0.3 * (f(x, y) - 0.5))
    v = lit(hm, 0.8)
    img = canvas()
    for y in range(N):
        for x in range(N):
            d = hexd(x, y)
            if d > 7.3:
                t = 0.30 + 0.2 * f(x, y)
            elif d > 6.4:
                t = 0.03
            elif d > 5.5:
                t = 0.62 if (x - 7.5) + (y - 7.5) < 0 else 0.3
            else:
                t = 0.3 + 0.45 * v[y][x]
            img[y][x] = rgba(pick(_DOL, t, x, y, 0.4))
    return img


# ---------------------------------------------------------------- wood

def bark(seed, ramp, furrows, depth, lenticels=0, wobble=1.2):
    """Bark with vertical furrows: rounded ridges between dark wavy grooves, lit from the left; optional lenticels."""
    rnd = random.Random(seed)
    fibre = Noise(seed + 1, 16, 3)
    grain = Noise(seed + 2, 8, 2)
    xs = [(i + 0.5) * N / furrows + rnd.uniform(-0.8, 0.8) for i in range(furrows)]
    ph = [rnd.uniform(0, 6.28) for _ in range(furrows)]

    def h(x, y):
        d = 99.0
        for fx, p in zip(xs, ph):
            cx = fx + wobble * math.sin(2 * math.pi * y / N + p)
            dd = abs(x - cx) % N
            d = min(d, dd, N - dd)
        ridge = min(1.0, d / (N / furrows / 2.0))
        return (1 - depth) + depth * ridge ** 0.6 + 0.16 * (fibre(x, y) - 0.5) + 0.12 * (grain(x, y) - 0.5)
    hm = field(h)
    v = lit(hm, 0.9, 1, 0)
    img = paint(v, ramp, 0.45)
    for x, y in scatter(seed + 3, lenticels, 4):
        w = 2 + rnd.randrange(2)
        for i in range(w):
            put(img, x + i, y, ramp[0])
            put(img, x + i, y + 1, ramp[-1])
    return img


def dragon_blood_bark():
    """Smooth grey bark of the dragon's blood tree with two beads of dark red resin."""
    img = bark(301, R('#574e45', '#6f655a', '#887d70', '#a09586', '#b7ad9d', '#cbc2b3'), 3, 0.5, lenticels=3, wobble=0.8)
    for x, y, n in ((4, 3, 4), (11, 9, 3)):
        for i in range(n):
            put(img, x, y + i, hexc('#c8342a' if i == 0 else '#971b1b' if i < n - 1 else '#5e0e10'))
        put(img, x, y - 1, hexc('#574e45'))
    return img


def rings_top(seed, bark_c, bark_d, wood_cols, ring_step=2.2):
    """A sawn log end: bark all round, rounded-square growth rings, a darker heart and one drying crack."""
    n = Noise(seed, 4)
    img = canvas()
    for y in range(N):
        for x in range(N):
            dx, dy = x - 7.5, y - 7.5
            if max(abs(dx), abs(dy)) > 7:
                corner = abs(dx) > 7 and abs(dy) > 7
                c = bark_d if corner or (x + y + int(n(x, y) * 3)) % 3 == 0 else bark_c
            else:
                d = (abs(dx) ** 3 + abs(dy) ** 3) ** (1 / 3.0) + 0.9 * (n(x, y) - 0.5)
                r = d / ring_step
                c = wood_cols[int(r) % len(wood_cols)]
                if r % 1.0 < 0.3:
                    c = mix(c, bark_d, 0.28)
                if max(abs(dx), abs(dy)) > 6:
                    c = mix(c, bark_d, 0.35)
                if d < 1.2:
                    c = mix(c, bark_d, 0.45)
            img[y][x] = rgba(c)
    for i in range(2, 6):
        put(img, 7 + i, 8 + i // 2, mix(wood_cols[0], bark_d, 0.6))
    return img


def dragon_blood_top():
    img = rings_top(311, hexc('#887d70'), hexc('#574e45'), R('#d9a47c', '#c98a66', '#b46a52', '#c47c5c'), 1.9)
    for x, y in ((7, 7), (8, 8), (5, 9), (10, 6), (8, 5)):
        put(img, x, y, hexc('#a3201c'))
    put(img, 7, 8, hexc('#d6453a'))
    return img


def ghost_bark():
    """Ghostwood: chalk-white bark, long shallow furrows, dark birch-like lenticels."""
    return bark(401, R('#7f847c', '#9ea39a', '#bcc0b8', '#d5d8d1', '#e8eae5', '#f7f8f5'), 3, 0.42, lenticels=4, wobble=1.0)


def redwood_bark():
    """Redwood: thick fibrous bark, deep furrows between stringy red-brown ridges."""
    return bark(501, R('#30110b', '#4b1e15', '#682d20', '#84402d', '#a0573d', '#b97251'), 4, 0.7, wobble=1.6)


def ghost_hollow(bark_img):
    """A hollow in an old ghostwood trunk: a dark oval with a lit lower rim and two wisps inside."""
    img = [list(r) for r in bark_img]
    for y in range(N):
        for x in range(N):
            dx, dy = (x - 7.5) / 3.4, (y - 7.8) / 4.6
            d = math.hypot(dx, dy)
            if d < 1.0:
                img[y][x] = rgba(mix(hexc('#070a10'), hexc('#1d2835'), max(0.0, dy * 0.6 + 0.4 + 0.3 * dx)))
            elif d < 1.3:
                img[y][x] = rgba(hexc('#6c7169') if dx + dy < -0.1 else hexc('#f7f8f5') if dx + dy > 0.5 else hexc('#9ea39a'))
    put(img, 7, 6, hexc('#bfe6ff'))
    put(img, 9, 9, hexc('#7fb6e0'))
    put(img, 6, 10, hexc('#2f4a66'))
    return img


# ---------------------------------------------------------------- leaves

def leaves(seed, ramp, style, holes=0.2):
    """Foliage in vanilla's manner: leaf shapes laid in layers from the dark inside to the lit outside, with gaps.

    style: 'round' (broad leaflets), 'needle' (short sprays of needles), 'sword' (stiff blades in rosettes).
    """
    rnd = random.Random(seed)
    img = canvas()
    stamps = {
        'round': [[(0, 0), (1, 0), (0, 1), (1, 1), (2, 1), (1, 2)], [(0, 0), (1, 0), (-1, 1), (0, 1), (1, 1), (0, 2)], [(0, 0), (1, 0), (0, 1)]],
        'needle': [[(0, 0), (1, 1), (2, 2), (3, 3)], [(0, 0), (-1, 1), (-2, 2), (-3, 3)], [(0, 0), (1, 1), (2, 1), (3, 2)], [(0, 0), (-1, 1), (-2, 1), (-3, 2)]],
        'sword': [[(0, 0), (1, -1), (2, -2), (3, -3), (4, -4)], [(0, 0), (-1, -1), (-2, -2), (-3, -3)], [(0, 0), (0, -1), (1, -2), (1, -3), (1, -4)],
                  [(0, 0), (1, 0), (2, -1), (3, -1), (4, -2)], [(0, 0), (-1, 0), (-2, -1), (-3, -1), (-4, -2)], [(0, 0), (0, 1), (-1, 2), (-1, 3)]],
    }[style]
    levels = len(ramp)
    # Back to front: the first layer nearly fills the tile in the darkest tone, later layers are sparser and lighter.
    plan = [(int(52 * (1 - holes)), 0), (26, 1), (18, 2), (10, 3)] if style == 'round' else [(int(46 * (1 - holes)), 0), (22, 1), (16, 2), (8, 3)]
    for count, tone in plan:
        tone = min(tone, levels - 1)
        for _ in range(count):
            x, y = rnd.randrange(N), rnd.randrange(N)
            st = stamps[rnd.randrange(len(stamps))]
            for i, (dx, dy) in enumerate(st):
                # The tip of each leaf catches one tone more light.
                t = min(levels - 1, tone + (1 if i == 0 and tone > 0 else 0))
                put(img, x + dx, y + dy, ramp[t])
    return img


def dragon_blood_leaves():
    return leaves(331, R('#255226', '#31692d', '#417f36', '#559542', '#6caa52'), 'sword', 0.25)


def blob_leaves(seed, cols, coverage, size=None):
    return leaves(seed, cols, 'round', 1 - coverage - 0.15)


def sword_leaves(seed, dark, mid, tip, *_args):
    return leaves(seed, [mix(dark, (10, 30, 30), 0.3), dark, mid, tip, mix(tip, (220, 240, 170), 0.3)], 'needle', 0.22)


# ---------------------------------------------------------------- geothermal

def geyserite():
    """Siliceous sinter: pearly botryoidal knobs, grey in the seams."""
    cells = Cells(201, 3, 0.9)
    f = fbm(202)

    def h(x, y):
        f1, f2, _ = cells.at(x, y)
        return max(0.0, 1 - f1 / 3.8) ** 0.7 + 0.15 * f(x, y) - (0.3 if f2 - f1 < 0.6 else 0.0)
    hm = norm(field(h))
    img = paint(lit(hm, 0.7), R('#85827c', '#a29e96', '#bdb9b0', '#d5d1c8', '#e8e5dd', '#f9f8f3'), 0.25)
    return img, hm


def vent_top(seed, rim_tex, hole, hole_rim, ring, glint, stain=None):
    """A vent mouth in the middle of rim_tex: a black throat, a rim lit on the far side, mineral crust around it."""
    n = Noise(seed, 5)
    img = [list(r) for r in rim_tex]
    for y in range(N):
        for x in range(N):
            dx, dy = x - 7.5, y - 7.5
            r = math.hypot(dx, dy) + 0.7 * (n(x, y) - 0.5)
            base = img[y][x][:3]
            if r < 2.4:
                c = mix((0, 0, 0), hole, 0.35 + 0.65 * max(0.0, min(1.0, (dx + dy) / 4 + 0.5)))
            elif r < 3.4:
                c = hole_rim if dx + dy < 0 else mix(hole_rim, ring, 0.55)
            elif r < 4.8:
                c = mix(base, ring, 0.75 if dx + dy < 0 else 0.5)
            elif stain is not None and r < 6.8 and n(x + 5, y + 3) > 0.5:
                c = mix(base, stain, 0.55)
            else:
                c = base
            img[y][x] = rgba(c)
    put(img, 5, 4, glint)
    put(img, 4, 5, glint)
    put(img, 10, 10, mix(glint, hole, 0.5))
    return img


def volcanic_rock(seed, stain=None):
    """Dark vent rock with a few gas pits; with stain, a yellow mineral crust runs down from the top."""
    f = fbm(seed)
    hm = field(lambda x, y: f(x, y))
    pits = scatter(seed + 1, 7, 3)
    for x, y in pits:
        hm[y][x] -= 0.5
        hm[(y + 1) % N][(x + 1) % N] += 0.25
    img = paint(lit(hm, 1.1), R('#231f1d', '#35302d', '#47413d', '#5b544f', '#706862', '#877f77'), 0.45)
    if stain is not None:
        n = Noise(seed + 2, 6, 2)
        ramp = [mix(stain, (90, 60, 10), 0.45), mix(stain, (150, 110, 20), 0.2), stain, mix(stain, (255, 250, 200), 0.4)]
        for x in range(N):
            depth = max(0, int((n(x, 3) - 0.3) * 11))
            for y in range(depth):
                t = 1 - y / max(1, depth)
                img[y][x] = rgba(pick(ramp, 0.25 + 0.75 * t * (0.6 + 0.4 * n(x, y * 4)), x, y, 0.6))
    return img


def thermal_mat(seed, light, base, dark, speck=None):
    """A bacterial mat: silky streamers drawn out by the flowing water, with bright crests."""
    a = Noise(seed, 4, 2)
    b = Noise(seed + 1, 8, 3)
    c = Noise(seed + 2, 3, 3)

    def h(x, y):
        u = x + 0.9 * math.sin(2 * math.pi * y / N + 1.3)
        return 0.5 * a(u, y) + 0.3 * b(u, y) + 0.2 * c(x, y)
    hm = norm(field(h))
    ramp = spread(mix(dark, (40, 20, 30), 0.1), base, mix(light, (255, 250, 220), 0.15), 6)
    img = paint(lit(hm, 0.4, 1, 0), ramp, 0.55, 0.12, 0.88)
    if speck is not None:
        for x, y in scatter(seed + 2, 5, 4):
            put(img, x, y, speck)
            put(img, x, y + 1, mix(speck, dark, 0.5))
    return img, hm


# ---------------------------------------------------------------- sand, ash, scoria

def grains(seed, base, dark, light, extra=None, extra_p=0.0, amt=4):
    """Loose sand or ash: soft wind ripples, a few larger grains each with its own highlight."""
    f = fbm(seed, ((2, 2), (4, 4), (8, 8)), (0.5, 0.3, 0.2))

    def h(x, y):
        rip = math.sin(2 * math.pi * (x + 2 * y) / N + 3.0 * f(x, y))
        return 0.6 * f(x, y) + 0.2 * (rip * 0.5 + 0.5)
    ramp = [dark, mix(dark, base, 0.55), base, mix(base, light, 0.5), light]
    img = paint(norm(field(h)), ramp, 0.9, 0.1, 0.95)
    pts = scatter(seed + 1, 7, 3)
    for i, (x, y) in enumerate(pts):
        if extra is not None and i < max(1, int(extra_p * 60)):
            put(img, x, y, extra)
        else:
            put(img, x, y, dark)
        put(img, x - 1, y - 1, light)
    return img


def scoria():
    """Scoria: red-brown cinder full of gas bubbles; every bubble is a dark hole with a lit far rim."""
    f = fbm(261)
    hm = field(lambda x, y: 0.45 + 0.4 * f(x, y))
    rnd = random.Random(262)
    holes = []
    for x, y in scatter(263, 13, 2.8):
        big = rnd.random() < 0.45
        cells = [(x, y)] + ([(x + 1, y), (x, y + 1), (x + 1, y + 1)] if big else [])
        holes.append((cells, big))
        for cx, cy in cells:
            hm[cy % N][cx % N] = 0.0
    img = paint(lit(hm, 0.7), R('#1f0d0a', '#371812', '#52251b', '#6e3425', '#8a4731', '#a55f41', '#bd7a56'), 0.45, 0.12, 1.0)
    for cells, big in holes:
        x, y = cells[0]
        s = 2 if big else 1
        for cx, cy in cells:
            put(img, cx, cy, hexc('#1f0d0a') if (cx, cy) == (x, y) else hexc('#2b120e'))
        for i in range(s):
            put(img, x + s, y + i, hexc('#bd7a56') if i == s - 1 else hexc('#a55f41'))
            put(img, x + i, y + s, hexc('#a55f41'))
    return img, hm


# ---------------------------------------------------------------- moss, cushions

def cushion(seed, ramp, per_side, k=1.1):
    cells = Cells(seed, per_side, 0.9)
    f = fbm(seed + 1)

    def h(x, y):
        f1, f2, _ = cells.at(x, y)
        return max(0.0, 1 - f1 / (N / per_side * 0.8)) ** 0.75 + 0.15 * f(x, y) - (0.3 if f2 - f1 < 0.6 else 0.0)
    return paint(lit(norm(field(h)), k), ramp, 0.25)


def pale_moss():
    """Silver moss: small soft cushions, pale tips."""
    img = cushion(421, R('#65725f', '#7f8c76', '#99a68e', '#b3bea7', '#ccd4c1', '#e3e8da'), 3, 0.7)
    for x, y in scatter(423, 5, 4):
        put(img, x, y, hexc('#f1f4ea'))
    return img


def yareta():
    """Yareta: a hard, tight cushion of tiny rosettes, bright yellow-green."""
    img = cushion(241, R('#35601a', '#4d7b21', '#679629', '#84b037', '#a3c94f', '#c4df76'), 3, 0.7)
    for x, y in scatter(243, 6, 3.5):
        put(img, x, y, hexc('#dcee9a'))
        put(img, x + 1, y + 1, hexc('#4d7b21'))
    return img


# ---------------------------------------------------------------- ice

def glacier_ice():
    """Glacier ice: dense blue ice in broad soft bands, two healed cracks and a few trapped air bubbles."""
    f = fbm(601, ((1, 2), (2, 4), (4, 8)), (0.5, 0.3, 0.2))
    hm = field(lambda x, y: 0.5 + 0.5 * f(x + 0.5 * y, y))
    crack = set()
    for x0, y0, sx in ((3, 2, 1),):
        x, y = x0, y0
        rnd = random.Random(602 + x0)
        for _ in range(9):
            crack.add((x % N, y % N))
            y += 1
            if rnd.random() < 0.6:
                x += sx
    for x, y in crack:
        hm[y][x] -= 0.25
    img = paint(norm(hm), R('#4a82c4', '#6699d6', '#84b1e4', '#a3c8ef', '#c3dcf6', '#e2f0fb'), 0.6, 0.0, 0.78)
    for x, y in crack:
        put(img, x, y, hexc('#e2f0fb'))
    for x, y in scatter(603, 4, 5, lambda x, y: (x, y) not in crack):
        put(img, x, y, hexc('#f4fafe'))
        put(img, x + 1, y + 1, hexc('#6699d6'))
    return img, hm


# ---------------------------------------------------------------- star abyss

_ABYSS = R('#05040c', '#0b0a18', '#131127', '#1c1938', '#27234b', '#342f60')


def abyss_stone():
    """Abyss stone: near-black indigo rock in rounded blocks, with a handful of stars caught in it."""
    cells = Cells(701, 3, 0.9)
    f = fbm(702)

    def h(x, y):
        f1, f2, _ = cells.at(x, y)
        return 0.35 + 0.4 * max(0.0, 1 - f1 / 4.2) + 0.3 * f(x, y) - (0.4 if f2 - f1 < 0.7 else 0.0)
    hm = norm(field(h))
    img = paint(lit(hm, 0.9), _ABYSS, 0.45)
    stars = scatter(703, 6, 3.5, lambda x, y: hm[y][x] > 0.45)
    for i, (x, y) in enumerate(stars):
        put(img, x, y, hexc('#ffffff') if i == 0 else hexc('#cdd6ff') if i < 4 else hexc('#8a93dc'))
    x, y = stars[0]
    for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        put(img, x + dx, y + dy, hexc('#5b63b4'))
    return img, stars


def star_crystal():
    """Star crystal: cut facets, each a flat plane of its own brightness, with bright edges where two meet."""
    cells = Cells(711, 2, 1.0)
    rnd = random.Random(712)
    tone = [rnd.random() for _ in cells.pts]
    tilt = [rnd.uniform(0, 6.28) for _ in cells.pts]
    ramp = R('#3a70c8', '#5c92de', '#82b1ee', '#aacdf7', '#d2e6fc', '#ffffff')
    img = canvas()
    for y in range(N):
        for x in range(N):
            f1, f2, k = cells.at(x, y)
            px, py = cells.pts[k]
            wx = (x + 0.5 - px + N / 2) % N - N / 2
            wy = (y + 0.5 - py + N / 2) % N - N / 2
            g = (wx * math.cos(tilt[k]) + wy * math.sin(tilt[k])) / 7.0
            v = 0.15 + 0.45 * (k % 4) / 3.0 + 0.1 * tone[k] + 0.3 * g
            if f2 - f1 < 0.45:
                v = 0.92
            img[y][x] = rgba(pick(ramp, v, x, y, 0.3))
    for x, y in scatter(713, 2, 6):
        put(img, x, y, hexc('#ffffff'))
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            put(img, x + dx, y + dy, hexc('#d2e6fc'))
    return img


def stardust():
    """Stardust: deep blue sand with grains that shine."""
    img = grains(721, hexc('#1f2c72'), hexc('#0f1742'), hexc('#3a50a8'))
    sparks = set(scatter(722, 7, 3))
    for i, (x, y) in enumerate(sorted(sparks)):
        put(img, x, y, hexc('#fff4c2') if i % 3 == 0 else hexc('#cfe8ff'))
    return img, sparks


def starlit(abyss):
    """Abyss stone shot through with veins of starlight: a white-hot core, a cyan edge, a faint blue glow around it."""
    cells = Cells(731, 2, 1.0)
    mask = fbm(732, ((2, 2), (4, 4)), (0.6, 0.4))
    img = [list(r) for r in abyss]
    veins = set()
    core = set()
    for y in range(N):
        for x in range(N):
            f1, f2, _ = cells.at(x, y)
            if f2 - f1 < 0.5 and mask(x, y) > 0.42:
                veins.add((x, y))
                if f2 - f1 < 0.2:
                    core.add((x, y))
    for y in range(N):
        for x in range(N):
            if (x, y) in veins:
                continue
            if any(((x + dx) % N, (y + dy) % N) in veins for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                img[y][x] = rgba(mix(img[y][x][:3], hexc('#1c6fb4'), 0.42))
    for x, y in veins:
        img[y][x] = rgba(hexc('#e9fcff') if (x, y) in core else hexc('#6fdcf8'))
    return img, veins


def star_spring_top(abyss):
    """The star spring: a pool of light in an abyss-stone rim, swirling brighter towards the middle."""
    img = [list(r) for r in abyss]
    ramp = R('#ffffff', '#dff6ff', '#a9e2ff', '#6fbff5', '#4690e6', '#2f5fc4', '#22398c')
    for y in range(N):
        for x in range(N):
            dx, dy = x - 7.5, y - 7.5
            r = math.hypot(dx, dy)
            if r < 5.7:
                rr = r + 0.7 * math.sin(3 * math.atan2(dy, dx) + r * 0.9)
                img[y][x] = rgba(pick(ramp, max(0.0, rr) / 5.7, x, y, 0.6))
            elif r < 6.7:
                img[y][x] = rgba(hexc('#aab8ff') if dx + dy < -1 else hexc('#0d0c20') if dx + dy > 3 else hexc('#4a52a4'))
    for x, y in ((5, 6), (10, 8), (8, 11)):
        put(img, x, y, hexc('#ffffff'))
    return img


# ---------------------------------------------------------------- sprites

def desert_rose():
    return sprite([
        "................",
        "...pP......Pp...",
        "..PWYP....PYWP..",
        "..pPPg.pP.gPPp..",
        "...gt.PWYP.tg...",
        "....tG.pPp.t....",
        ".....t..t.Gt....",
        ".....tt.t.t.....",
        "......tTTt......",
        "......TLTt......",
        ".....tTLTTt.....",
        ".....TLLTTt.....",
        "....tTLLTTTt....",
        "....TLLLTTTt....",
        "....TLLTTTTt....",
        "....tTTTTTtt....",
    ], dict(T='#b3a48e', t='#857662', L='#d6cab6', P='#e8558a', p='#b22e68', W='#fbb0cb', Y='#f7de6e', G='#6a9a40', g='#3f6a2b'))


def pale_fern():
    return sprite([
        "................",
        ".......c........",
        "......cbc..c....",
        "..c...bab.cb....",
        ".cbc..cSc.ba.c..",
        "..bac.bSb.aScb..",
        ".c.aSc.Sc.cSb...",
        ".bc.aS.Sb.Sa.c..",
        "..bc.SaScSa.cb..",
        ".c.ba.SSSa.cb...",
        "..cb.a.Sa.ba.c..",
        "...cb.aSa.b.cb..",
        ".....b.S.ab.....",
        "......aSa.......",
        ".......S........",
        ".......S........",
    ], dict(S='#5f7a68', a='#7c9a86', b='#a8c2b0', c='#d3e4d7'))


def ghost_sapling():
    return sprite([
        "................",
        "......mhm.......",
        ".....mhhhm.l....",
        "....lmhhmmlmm...",
        "...lmmhmmlmhhm..",
        "...mhhmlllmhhml.",
        "..lmhhml.klmmml.",
        "..lmmml.kK.lll..",
        "...lll..kK......",
        "....l.k.Kk......",
        "......kkK.......",
        ".......kK.......",
        ".......kK.......",
        ".......kK.......",
        "......kkKk......",
        "................",
    ], dict(K='#c9cac2', k='#8d8e86', l='#93aba2', m='#c3d5ce', h='#f0f6f3'))


def moonpetal():
    return sprite([
        "................",
        "................",
        ".......pw.......",
        "......pwwp......",
        "...pp.qwwq.pp...",
        "..pwwpqppqpwwp..",
        "..qwwwpyypwwwq..",
        "...qppyYYyppq...",
        "..pwwwpyypwwwp..",
        "..pwwpqssqpwwp..",
        "...qq.pwsp.qq...",
        "......qwsq......",
        "....l..ds.......",
        "...lld.ds.ll....",
        "....dldssdl.....",
        ".......ds.......",
    ], dict(w='#ffffff', p='#dde8f6', q='#a9bcdc', y='#f3e08a', Y='#fff7c8', s='#5f8a5a', d='#3f6642', l='#86b478'))


def star_bloom():
    return sprite([
        "................",
        ".......c........",
        ".......W........",
        "...v..cWc..v....",
        "....b.cWc.b.....",
        ".....bcWcb......",
        "..cccWWWWWccc...",
        ".cWWWWWYWWWWWc..",
        "..cccWWWWWccc...",
        ".....bcWcb......",
        "....b.cWc.b.....",
        "...v..cdc..v....",
        ".......d........",
        "......kd........",
        ".......dk.......",
        ".......d........",
    ], dict(W='#ffffff', Y='#fff3b0', c='#b6e4ff', b='#62b2f2', v='#9a86f2', d='#3b4fb0', k='#232c66'))


def moon_bracket():
    return sprite([
        "................",
        "................",
        ".....hhhhh......",
        "....hhwhhhhm....",
        "....mdgdgdkk....",
        "................",
        "...hhhhhhhh.....",
        "..hhwwhhhhhhm...",
        "..mmdgdgdgkkk...",
        "................",
        "....hhhhhhhh....",
        "..hhhwwwhhhhhh..",
        ".hhhhhhhhhhhhhm.",
        ".mmdgdgdgdgdkkk.",
        "...kkkkkkkkkk...",
        "................",
    ], dict(w='#ffffff', h='#f6efdc', m='#dccfae', d='#b49e78', g='#cdbb96', k='#806b50'))


def star_cluster():
    return sprite([
        "................",
        ".......W........",
        "......aWb.......",
        "......aWbc......",
        "..W...abbc..W...",
        ".aWb..abcc.aWb..",
        ".abc.aabccdabc..",
        ".abcdaabccdabcd.",
        ".abcdaabccdabcd.",
        "aabccabbccdabcde",
        "aabccabbccdabcde",
        "abbccabbcddabcde",
        "abccdabccddbccde",
        "bbcddbbccddbcdde",
        "ccdddcccdddcddee",
        ".deeeddddeeddee.",
    ], dict(W='#ffffff', a='#dff0ff', b='#a6cff7', c='#6ba3ea', d='#3d6cc4', e='#274792'))


def tussock_grass():
    return sprite([
        "................",
        ".d........d.....",
        ".c..d....c...d..",
        "..c.c...dc..c...",
        "..b.c.d.cb..c.d.",
        "..b..cc.cb.cb.c.",
        "...b.cb.c.bc.cb.",
        ".d.b.bc.cbb..c..",
        ".cb.bbcbc.bbcb..",
        "..cb.bcbcbb.cb..",
        "..bcb.bcbbbcb...",
        "...bcbabcbbcb.c.",
        "...abbabcbabbcb.",
        "....abbabbabbb..",
        "....aabababaa...",
        ".....aaaaaaa....",
    ], dict(a='#7d6332', b='#ad8f54', c='#d4bb7a', d='#efe1aa'))


def silver_beard():
    return sprite([
        ".cb.c.dc.b.cd.c.",
        ".cb.c.dc.b.cc.c.",
        ".bb.c.cc.b.cc.b.",
        ".ba.b.cb.c.bc.b.",
        "..a.b.cb.c.bb.b.",
        "..b.b.bb.c.bb.a.",
        "..b.c.ba.b.ab...",
        "..a.c..a.b.a..b.",
        "....b..b.b.a..b.",
        "....b..b.a.b..a.",
        "....a..a...b....",
        "....a..b...a....",
        ".......a...a....",
        ".......a........",
        "................",
        "................",
    ], dict(a='#76867b', b='#a3b3a7', c='#cdd9d0', d='#f0f5f1'))


def astral_roots():
    return sprite([
        "..b..c...b..c.b.",
        "..b..c..bb..c.b.",
        "..bb.b..b...cb..",
        "...b.b..b..bc...",
        "...b.bb.c..b....",
        "..bb..b.c..b..b.",
        "..b...b.c.bb..b.",
        "..c...c.b.b..bb.",
        "..c..bc.b.c..b..",
        "..b..b..a.c..c..",
        "..a..b....b..c..",
        ".....c....b..b..",
        ".....c....a..a..",
        ".....d..........",
        "................",
        "................",
    ], dict(a='#2a3f9a', b='#4f7fe0', c='#8fd0ff', d='#ffffff'))
