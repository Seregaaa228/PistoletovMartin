"""Procedural materials, camouflage and decals for the aircraft atlases.

Painters receive world positions (pixels) and the face normal of every texel
and return RGB or RGBA, so patterns run continuously over adjacent cubes.
"""
import numpy as np

from kit import fbm, hexrgb, value_noise

FONT = {  # 5x7 bitmap digits/letters, rows top to bottom
    '0': ['01110', '10001', '10011', '10101', '11001', '10001', '01110'],
    '1': ['00100', '01100', '00100', '00100', '00100', '00100', '01110'],
    '2': ['01110', '10001', '00001', '00010', '00100', '01000', '11111'],
    '3': ['11110', '00001', '00001', '01110', '00001', '00001', '11110'],
    '4': ['00010', '00110', '01010', '10010', '11111', '00010', '00010'],
    '5': ['11111', '10000', '11110', '00001', '00001', '10001', '01110'],
    '6': ['00110', '01000', '10000', '11110', '10001', '10001', '01110'],
    '7': ['11111', '00001', '00010', '00100', '01000', '01000', '01000'],
    '8': ['01110', '10001', '10001', '01110', '10001', '10001', '01110'],
    '9': ['01110', '10001', '10001', '01111', '00001', '00010', '01100'],
    'A': ['01110', '10001', '10001', '11111', '10001', '10001', '10001'],
    'F': ['11111', '10000', '10000', '11110', '10000', '10000', '10000'],
    'H': ['10001', '10001', '10001', '11111', '10001', '10001', '10001'],
    'L': ['10000', '10000', '10000', '10000', '10000', '10000', '11111'],
    'S': ['01111', '10000', '10000', '01110', '00001', '00001', '11110'],
    'U': ['10001', '10001', '10001', '10001', '10001', '10001', '01110'],
    'W': ['10001', '10001', '10001', '10101', '10101', '11011', '10001'],
    'R': ['11110', '10001', '10001', '11110', '10100', '10010', '10001'],
    'O': ['01110', '10001', '10001', '10001', '10001', '10001', '01110'],
    'N': ['10001', '11001', '10101', '10011', '10001', '10001', '10001'],
    'E': ['11111', '10000', '10000', '11110', '10000', '10000', '11111'],
    'T': ['11111', '00100', '00100', '00100', '00100', '00100', '00100'],
    'C': ['01110', '10001', '10000', '10000', '10000', '10001', '01110'],
    'P': ['11110', '10001', '10001', '11110', '10000', '10000', '10000'],
    'B': ['11110', '10001', '10001', '11110', '10001', '10001', '11110'],
    'D': ['11110', '10001', '10001', '10001', '10001', '10001', '11110'],
    'G': ['01110', '10001', '10000', '10111', '10001', '10001', '01111'],
    'I': ['01110', '00100', '00100', '00100', '00100', '00100', '01110'],
    'K': ['10001', '10010', '10100', '11000', '10100', '10010', '10001'],
    'M': ['10001', '11011', '10101', '10101', '10001', '10001', '10001'],
    'V': ['10001', '10001', '10001', '10001', '10001', '01010', '00100'],
    'X': ['10001', '10001', '01010', '00100', '01010', '10001', '10001'],
    'Y': ['10001', '10001', '01010', '00100', '00100', '00100', '00100'],
    ' ': ['00000'] * 7,
    '-': ['00000', '00000', '00000', '11111', '00000', '00000', '00000'],
}


def text_mask(text):
    rows = []
    for r in range(7):
        rows.append([int(ch) for c in text for ch in (FONT[c][r] + '0')][:-1])
    return np.array(rows, bool)


def star_mask(n=64, points=5, inner=0.38, rot=-90):
    """Filled n-pointed star, outer radius 1, in an n x n grid."""
    y, x = np.mgrid[0:n, 0:n]
    x = (x + 0.5) / n * 2 - 1
    y = (y + 0.5) / n * 2 - 1
    ang = (np.degrees(np.arctan2(y, x)) - rot) % (360 / points)
    half = 180 / points
    a = np.radians(np.abs(ang - half))
    # radius of the star outline at this angle: interpolate between tip and notch lines
    r_tip, r_in = 1.0, inner
    th = np.radians(half)
    # line from tip (angle 0, r_tip) to notch (angle half, r_in) in polar form
    px1, py1 = r_tip, 0.0
    px2, py2 = r_in * np.cos(th), r_in * np.sin(th)
    t = np.radians(half) - a
    dx, dy = np.cos(t), np.sin(t)
    # intersection of ray with segment
    ex, ey = px2 - px1, py2 - py1
    denom = dx * ey - dy * ex
    s = (px1 * ey - py1 * ex) / np.where(np.abs(denom) < 1e-9, 1e-9, denom)
    return np.hypot(x, y) <= s


class Decal:
    """A bitmap projected onto faces whose normal matches `normal`.

    centre: world point (px); u_dir/v_dir: world unit vectors of the bitmap's
    columns and rows; size: (width, height) in px; colors: list per layer."""

    def __init__(self, centre, normal, u_dir, v_dir, size, layers, max_depth=40.0):
        self.c = np.array(centre, float)
        self.n = np.array(normal, float)
        self.u = np.array(u_dir, float)
        self.v = np.array(v_dir, float)
        self.size = size
        self.layers = layers  # list of (mask array, rgb)
        self.max_depth = max_depth

    def apply(self, p, n, rgb):
        if np.dot(n, self.n) < 0.6:
            return rgb
        d = p - self.c
        if np.all(np.abs(d @ self.n) > self.max_depth):
            return rgb
        u = d @ self.u / self.size[0] + 0.5
        v = d @ self.v / self.size[1] + 0.5
        ok = (u >= 0) & (u < 1) & (v >= 0) & (v < 1) & (np.abs(d @ self.n) <= self.max_depth)
        if not ok.any():
            return rgb
        for mask, color in self.layers:
            h, w = mask.shape
            iu = np.clip((u * w).astype(int), 0, w - 1)
            iv = np.clip((v * h).astype(int), 0, h - 1)
            hit = ok & mask[iv, iu]
            rgb[hit, :3] = color
        return rgb


def solid(color, n):
    return np.tile(hexrgb(color), (n, 1))


def grain(p, amount=0.05, scale=6.0, seed=3):
    return 1 + (value_noise(p, scale, seed) - 0.5) * 2 * amount


def stations_mask(coord, stations, width=0.55):
    out = np.zeros(len(coord), bool)
    for s in stations:
        out |= np.abs(coord - s) < width
    return out


def dilate(mask, r=1):
    """Grow a bitmap by r cells (outline layers under markings)."""
    out = mask.copy()
    for dy in range(-r, r + 1):
        for dx in range(-r, r + 1):
            out |= np.roll(np.roll(mask, dy, 0), dx, 1)
    return out


def pad(mask, r):
    return np.pad(mask, r)


def outlined(mask, inner, outer, r=1):
    """Layers for a marking drawn in `inner` with an `outer` border of r cells; the bitmap is
    doubled first so the border stays thinner than the strokes and letters keep their gaps."""
    m = pad(np.kron(mask, np.ones((2, 2), bool)), r)
    return [(dilate(m, r), outer), (m, inner)]


def arrow_mask(n=24):
    """Rescue arrow: a triangle head on a short shaft, pointing towards +u (columns)."""
    y, x = np.mgrid[0:n, 0:2 * n]
    x = (x + 0.5) / (2 * n)
    y = (y + 0.5) / n - 0.5
    head = (x > 0.55) & (np.abs(y) < (1.0 - x) * 1.1)
    shaft = (x <= 0.58) & (np.abs(y) < 0.14)
    return head | shaft


def triangle_mask(n=24):
    """Upward warning triangle with a hollow centre."""
    y, x = np.mgrid[0:n, 0:n]
    x = (x + 0.5) / n - 0.5
    y = (y + 0.5) / n
    outer = np.abs(x) < y * 0.58
    inner = (np.abs(x) < (y - 0.3) * 0.58) & (y < 0.88)
    return outer & ~inner


def make_weather(seed=0, grime=0.10, streaks=0.08, soot=(), wear=None):
    """Weathering for painted skin: blotchy grime, flow streaks running aft (+Z), soot plumes
    behind exhausts/guns and lighter wear where crews walk. soot: (point, radius, length)
    per source, darkening a cone that grows aft of the point. wear(p, n) -> 0..1 mask."""
    def weather(p, n, cube, rgb):
        g = fbm(p, 22.0, seed + 300, 3)
        k = 1 - grime * np.clip((g - 0.45) * 2.4, 0, 1)
        s = value_noise(p * np.array([1.0, 1.0, 0.07]), 1.6, seed + 310)
        k *= 1 - streaks * np.clip((s - 0.55) * 3.0, 0, 1)
        for (sx, sy, sz), radius, length in soot:
            d = p[:, 2] - sz
            r = np.hypot(p[:, 0] - sx, p[:, 1] - sy)
            reach = radius + np.clip(d, 0, None) * 0.18
            amt = np.clip(1 - r / reach, 0, 1) * np.clip(1 - d / length, 0, 1) * (d > -radius)
            k *= 1 - 0.55 * amt * (0.7 + 0.3 * g)
        out = rgb * k[:, None]
        if wear is not None:
            w = wear(p, n) * (value_noise(p, 1.3, seed + 320) > 0.55)
            out = np.where(w[:, None] > 0, out * 0.9 + 16, out)
        return out
    return weather


def splinter_camo(p, n, colors, belly, cell=30.0, seed=5, belly_line=None):
    """Angular 'splinter' camouflage: Voronoi cells of a jittered grid (in X/Z plan, with the
    height folded in so the sides follow), one colour per cell, plain belly."""
    q = np.stack([p[:, 0] + p[:, 1] * 0.55, p[:, 2] - p[:, 1] * 0.35], 1) / cell
    base = np.floor(q).astype(np.int64)
    best = np.full(len(p), 1e9)
    pick = np.zeros(len(p), np.int64)
    for dx in (-1, 0, 1):
        for dz in (-1, 0, 1):
            ix, iz = base[:, 0] + dx, base[:, 1] + dz
            jx = _cell_hash(ix, iz, seed)
            jz = _cell_hash(ix, iz, seed + 7)
            d = (q[:, 0] - (ix + jx)) ** 2 * 1.0 + (q[:, 1] - (iz + jz)) ** 2 * 0.45
            closer = d < best
            best = np.where(closer, d, best)
            pick = np.where(closer, (_cell_hash(ix, iz, seed + 13) * 997).astype(np.int64), pick)
    rgb = np.stack([hexrgb(c) for c in colors])[pick % len(colors)]
    if belly is not None:
        under = n[1] < -0.55
        if belly_line is not None:
            under = under | (p[:, 1] < belly_line(p))
        rgb = np.where(np.broadcast_to(np.atleast_1d(under), (len(p),))[:, None], hexrgb(belly), rgb)
    return rgb


def digital_camo(p, n, colors, belly, pixel=2.0, scale=14.0, seed=9, belly_line=None):
    """Pixelated ('digital') camouflage: smooth noise sampled on a world grid of `pixel` px
    squares, so the pattern breaks into small blocks on every surface."""
    q = (np.floor(p / pixel) + 0.5) * pixel
    v = fbm(q * np.array([1.0, 0.7, 1.0]), scale, seed, 3)
    k = len(colors)
    idx = np.clip(np.floor((v - 0.28) / 0.44 * k), 0, k - 1).astype(int)
    rgb = np.stack([hexrgb(c) for c in colors])[idx]
    if belly is not None:
        under = n[1] < -0.55
        if belly_line is not None:
            under = under | (p[:, 1] < belly_line(p))
        rgb = np.where(np.broadcast_to(np.atleast_1d(under), (len(p),))[:, None], hexrgb(belly), rgb)
    return rgb


def _cell_hash(ix, iz, seed):
    h = (ix * 374761393 + iz * 668265263 + seed * 2246822519) & 0xFFFFFFFF
    h = ((h ^ (h >> 13)) * 1274126177) & 0xFFFFFFFF
    return ((h ^ (h >> 16)) & 0xFFFF) / 65535.0


class Painter:
    """Shared material library. Aircraft provide camo(), panel lines and decals.

    panel_lines(p, n, cube) returns a mask of recessed lines, or (lines, rivets).
    weather(p, n, cube, rgb) darkens/lightens painted skin (see make_weather)."""

    def __init__(self, palette, camo, panel_lines=None, decals=(), glass_alpha=120, overrides=None, weather=None):
        self.pal = {k: hexrgb(v) for k, v in palette.items()}
        self.camo = camo
        self.panel_lines = panel_lines
        self.decals = list(decals)
        self.glass_alpha = glass_alpha
        self.overrides = overrides or {}
        self.weather = weather

    def __call__(self, p, n, cube, face, s, t, wh):
        N = len(p)
        mat = cube.mat
        if mat in self.overrides:
            out = self.overrides[mat](p, n, cube, face, s, t, wh)
            return out
        alpha = None
        if mat in ('skin', 'pylon', 'frame', 'lip'):
            rgb = self.camo(p, n)
            if mat == 'lip':     # intake lips: bare-metal leading edge band
                rgb = rgb * 0.55 + hexrgb('#a3a6a6') * 0.45
            if self.panel_lines is not None:
                m = self.panel_lines(p, n, cube)
                if isinstance(m, tuple):
                    m, rv = m
                    rgb = rgb * np.where(rv & ~m, 0.88, 1.0)[:, None]
                rgb = rgb * np.where(m, 0.78, 1.0)[:, None]
            rgb = rgb * grain(p)[:, None]
            if self.weather is not None:
                rgb = self.weather(p, n, cube, rgb)
        elif mat == 'petal':
            # nozzle flaps: heat-tinted metal outside, sooty inside (cube.tag = nozzle axis point)
            ax = np.array(cube.tag if cube.tag is not None else cube_centre(cube), float)
            outward = (p[:, 0] - ax[0]) * n[0] + (p[:, 1] - ax[1]) * n[1] > 0
            heat = np.clip((p[:, 2] - ax[2]) / 12.0, 0, 1)
            rgb = solid('#77736d', N) * (0.85 + 0.25 * value_noise(p, 2.0, 11))[:, None]
            rgb = rgb * (1 - 0.25 * heat)[:, None] + np.array([12.0, 4.0, 22.0]) * heat[:, None]
            seam = np.mod(np.degrees(np.arctan2(p[:, 1] - ax[1], p[:, 0] - ax[0])), 360.0 / 15) < 1.4
            rgb[seam] *= 0.7
            rgb[~outward] = hexrgb('#2b2724') * (0.8 + 0.3 * value_noise(p[~outward], 1.5, 12))[:, None]
        elif mat == 'glass':
            base = self.pal.get('glass', hexrgb('#5b7f93'))
            rgb = np.tile(base, (N, 1)) * (0.9 + 0.25 * value_noise(p, 9.0, 41))[:, None]
            # diagonal glints read as glass even in strong light
            g = np.mod(p[:, 2] * 0.7 + p[:, 1] * 1.3 + p[:, 0] * 0.4, 23.0)
            glint = (g < 1.6) | ((g > 4.0) & (g < 4.8))
            rgb[glint] = rgb[glint] * 0.5 + 140
            alpha = np.where(glint, 200, self.glass_alpha)
        elif mat == 'mfd':
            # multifunction display: dark glass, bezel with buttons, green symbology
            w, h = wh
            rgb = solid('#141d1a', N)
            edge = np.minimum(np.minimum(s, 1 - s) * w, np.minimum(t, 1 - t) * h)
            rgb[edge < 1.0] = hexrgb('#3b403d')
            btn = (edge < 1.0) & (np.mod(s * w, 2.0) < 0.9)
            rgb[btn] = hexrgb('#8a8f86')
            sym = (edge > 1.5) & ((np.abs(t - 0.35) < 0.5 / h) | (np.abs(s - 0.5) < 0.5 / w) & (t > 0.5)
                                  | (np.abs(t - 0.75) < 0.5 / h) & (s > 0.3) & (s < 0.7))
            rgb[sym] = hexrgb('#59d886')
        elif mat == 'dials':
            # analogue instrument panel: black dials with white rims and needles
            rgb = solid('#1f2321', N)
            q = np.mod(p[:, 0] + p[:, 1] * 0.0, 2.2) - 1.1
            r = np.hypot(q, np.mod(p[:, 1], 2.2) - 1.1)
            rgb[np.abs(r - 0.75) < 0.18] = hexrgb('#c9ccc4')
            rgb[(r < 0.6) & (np.abs(q - (np.mod(p[:, 1], 2.2) - 1.1) * 0.5) < 0.12)] = hexrgb('#e8e2c8')
        elif mat == 'dispenser':
            # flare/chaff magazine: dark box with a grid of cartridge ends
            rgb = np.tile(self.pal.get('dispenser', hexrgb('#3a3d3a')), (N, 1))
            q = np.mod(p, 0.9)
            cells = ((q[:, 0] - 0.45) ** 2 + (q[:, 2] - 0.45) ** 2 < 0.09) | ((q[:, 1] - 0.45) ** 2 + (q[:, 2] - 0.45) ** 2 < 0.09)
            rgb[cells & (np.abs(n[1]) > 0.5)] = hexrgb('#6b6f66')
        elif mat == 'nozzle':
            rgb = solid('#6d6a66', N)
            ring = np.mod(p[:, 2], 3.0) < 0.6
            rgb = rgb * np.where(ring, 0.75, 1.0)[:, None]
            heat = np.clip((p[:, 2] - p[:, 2].min()) / 40.0, 0, 1)
            rgb = rgb * (0.9 + 0.2 * value_noise(p, 3.0, 9))[:, None]
            rgb[:, 2] += 18 * heat
        elif mat == 'nozzle_in':
            rgb = solid('#26221f', N) * (0.8 + 0.4 * value_noise(p, 2.0, 5))[:, None]
            petals = np.mod(np.degrees(np.arctan2(p[:, 1] - cube_centre(cube)[1], p[:, 0] - cube_centre(cube)[0])), 22.5) < 3
            rgb[petals] *= 0.6
        elif mat == 'intake':
            rgb = solid('#141414', N)
        elif mat == 'tire':
            rgb = solid('#1d1d1d', N) * (0.9 + 0.2 * value_noise(p, 1.2, 15))[:, None]
            side = np.abs(n[0]) > 0.7
            # circumferential grooves on the tread, a moulded ring on the sidewalls
            groove = ~side & (np.mod(p[:, 0] + 0.35, 1.3) < 0.35)
            rgb[groove] = hexrgb('#0f0f0f')
            if side.any():
                c = cube.tag if isinstance(cube.tag, tuple) else tuple(cube_centre(cube))
                r = np.hypot(p[:, 1] - c[1], p[:, 2] - c[2])
                rmax = max(cube.size) / 2
                ring = side & (np.abs(r - rmax * 0.78) < 0.35)
                rgb[ring] = hexrgb('#2c2c2c')
        elif mat == 'hub':
            rgb = np.tile(self.pal.get('hub', hexrgb('#5f7a55')), (N, 1))
        elif mat == 'strut':
            rgb = np.tile(self.pal.get('strut', hexrgb('#b9bdb8')), (N, 1)) * grain(p, 0.04)[:, None]
            chrome = (np.minimum(s, 1 - s) > 0.35) & (cube.size[1] > 8)
            rgb[chrome] = rgb[chrome] * 0.6 + 100
        elif mat == 'well':
            rgb = np.tile(self.pal.get('well', hexrgb('#9aa59a')), (N, 1)) * grain(p, 0.08)[:, None]
        elif mat in self.pal:
            rgb = np.tile(self.pal[mat], (N, 1)) * grain(p, 0.035)[:, None]
        else:
            rgb = solid('#ff00ff', N)
        rgba = np.concatenate([rgb, np.full((N, 1), 255.0) if alpha is None else alpha[:, None]], 1)
        if mat in ('skin', 'pylon') or mat in self.pal and mat not in ('tire', 'intake'):
            for d in self.decals:
                rgba = d.apply(p, n, rgba)
        return rgba


def cube_centre(cube):
    o, s = cube.origin, cube.size
    return np.array([o[0] + s[0] / 2, o[1] + s[1] / 2, o[2] + s[2] / 2])


def splotch_camo(p, n, colors, belly, scale=26.0, seed=1, belly_line=None, warp=10.0):
    """Disruptive blotch camouflage from layered smooth noise (upper/side
    surfaces), plain belly colour below a wavy demarcation line."""
    q = p.copy()
    # Domain warp: rounded, irregular patches instead of noise speckle.
    q[:, 0] += (value_noise(p, 31.0, seed + 90) - 0.5) * warp
    q[:, 2] += (value_noise(p, 29.0, seed + 91) - 0.5) * warp
    k = len(colors)
    idx = np.floor(fbm(q * np.array([1.0, 0.6, 1.0]), scale, seed, 2) * k * 1.35 - 0.15 * k)
    idx = np.clip(idx, 0, k - 1).astype(int)
    rgb = np.stack([hexrgb(c) for c in colors])[idx]
    if belly is not None:
        under = n[1] < -0.55
        if belly_line is not None:
            under = under | (p[:, 1] < belly_line(p))
        rgb = np.where(np.atleast_1d(under)[:, None] if np.ndim(under) else np.full((len(p), 1), under),
                       hexrgb(belly), rgb)
    return rgb


# ----- shared marking helpers ------------------------------------------------

def rivet_rows(coord, positions, along, offset=0.85, pitch=1.1, width=0.3):
    """Rivet dots on both sides of each panel line at `positions` along `coord`."""
    near = np.zeros(len(coord), bool)
    for s in positions:
        near |= np.abs(np.abs(coord - s) - offset) < width
    return near & (np.mod(along, pitch) < pitch * 0.42)


def hatch(p, n, axis, z0, z1, a0, a1, width=0.35):
    """Outline of a rectangular access panel, z0..z1 lengthwise, on the faces whose normal is
    mostly along `axis`: 'x' (fuselage sides; a = height y) or 'y' (top/bottom; a = |x|)."""
    if axis == 'x':
        face, a = np.abs(n[0]) > 0.5, p[:, 1]
    else:
        face, a = np.abs(n[1]) > 0.5, np.abs(p[:, 0])
    z = p[:, 2]
    outer = (z > z0 - width) & (z < z1 + width) & (a > a0 - width) & (a < a1 + width)
    core = (z > z0 + width) & (z < z1 - width) & (a > a0 + width) & (a < a1 - width)
    return face & outer & ~core


def side_decal(x, y, z, size, layers, depth=3.0):
    """The same marking on both fuselage sides, reading front to back on each (the geo frame is
    rendered X-mirrored, so the bitmap columns run along +Z on the +X side)."""
    return [Decal((sx * x, y, z), (sx, 0, 0), (0, 0, sx), (0, -1, 0), size, layers, max_depth=depth)
            for sx in (1, -1)]


def wing_decal(x, y, z, size, layers, up=True, depth=8.0):
    """A symmetric marking on both wings, upper (up=True) or lower surface."""
    ny = 1 if up else -1
    return [Decal((sx * x, y, z), (0, ny, 0), (-1, 0, 0), (0, 0, 1), size, layers, max_depth=depth)
            for sx in (1, -1)]


def rescue_arrow(color=(222, 176, 38), text_color=(196, 40, 30)):
    """Yellow rescue arrow (pointing at the canopy release) with the word beside it. Returns
    (layers, aspect) where aspect = width / height of the bitmap."""
    t = text_mask('RESCUE')
    arrow = arrow_mask(9)
    word = np.zeros((9, t.shape[1] + 2), bool)
    word[1:8, 2:] = t
    a = np.concatenate([arrow, np.zeros_like(word)], 1)
    w = np.concatenate([np.zeros_like(arrow), word], 1)
    return [(a, color), (w, text_color)], a.shape[1] / a.shape[0]
