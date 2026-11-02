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


class Painter:
    """Shared material library. Aircraft provide camo(), panel lines and decals."""

    def __init__(self, palette, camo, panel_lines=None, decals=(), glass_alpha=120, overrides=None):
        self.pal = {k: hexrgb(v) for k, v in palette.items()}
        self.camo = camo
        self.panel_lines = panel_lines
        self.decals = list(decals)
        self.glass_alpha = glass_alpha
        self.overrides = overrides or {}

    def __call__(self, p, n, cube, face, s, t, wh):
        N = len(p)
        mat = cube.mat
        if mat in self.overrides:
            out = self.overrides[mat](p, n, cube, face, s, t, wh)
            return out
        alpha = None
        if mat in ('skin', 'pylon', 'frame'):
            rgb = self.camo(p, n)
            if self.panel_lines is not None:
                m = self.panel_lines(p, n, cube)
                rgb = rgb * np.where(m, 0.78, 1.0)[:, None]
            rgb = rgb * grain(p)[:, None]
        elif mat == 'glass':
            base = self.pal.get('glass', hexrgb('#5b7f93'))
            rgb = np.tile(base, (N, 1)) * (0.9 + 0.25 * value_noise(p, 9.0, 41))[:, None]
            # diagonal glints read as glass even in strong light
            g = np.mod(p[:, 2] * 0.7 + p[:, 1] * 1.3 + p[:, 0] * 0.4, 23.0)
            glint = (g < 1.6) | ((g > 4.0) & (g < 4.8))
            rgb[glint] = rgb[glint] * 0.5 + 140
            alpha = np.where(glint, 200, self.glass_alpha)
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
            rgb = solid('#1d1d1d', N)
            tread = np.mod(p[:, 1] + p[:, 2], 2.0) < 0.5
            rgb[tread] = hexrgb('#2a2a2a')
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
