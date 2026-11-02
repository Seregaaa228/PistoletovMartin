"""Chengdu J-10C at 1 block = 1 m.

Proportions from the measured J-10A three-view in aircraft-references
(Kaboldy, CC BY-SA 3.0, used for proportions only; span scaled to 9.75 m) and
published data: length 16.43 m with the nose probe, height 5.43 m, wing area
33.1 m2, delta wing LE ~52 deg, close-coupled canards (span ~4.4 m), single
tall fin, two ventral fins canted 30 deg, track 2.61 m, wheelbase 4.5 m. The
J-10C differs by its DSI chin intake. x is metres aft of the probe tip; real
heights get LIFT for the in-game seat. The wing sits ~0.25 m higher than on
the real aircraft so the shared store point (y 2.2) stays below it.
Anchors kept: seat (vehicle z 3.99, y 2.54), pylons x +-2.1 (y 2.2, z -0.2).
"""
import math

import numpy as np

from kit import Model, hexrgb, place_panel
from paint import Decal, Painter, star_mask, stations_mask, text_mask

M = 16.0
NOSE = -135.04         # probe tip, geo Z (px): pilot eye at x 4.45 m
LIFT = 0.50
EXIT = 15.84
SPAN = 1.044           # drawing span correction (9.34 -> 9.75 m)


def Z(x):
    return NOSE + x * M


def H(z):
    return (z + LIFT) * M


def wing_le(y):
    return 7.3 + (y - 0.67) * (12.4 - 7.3) / (4.875 - 0.67)


def wing_te(y):
    return 13.25 + (y - 0.67) * (13.08 - 13.25) / (4.875 - 0.67)


def build(lod=False):
    full = not lod
    m = Model('j10_lod' if lod else 'j10')
    m.bone('body', None)
    m.bone('pilot_anchor', 'body', (0, 40.64, -63.84))
    for name in ('wing_l', 'wing_r', 'tail_fin', 'stabilizer_l', 'stabilizer_r', 'canopy', 'chin_intake', 'engine'):
        m.bone(name)
    nose = (0.0, H(1.20), Z(5.45))
    main = (1.305 * M, H(1.20), Z(9.95))
    m.bone('gear_main_l', 'body', main)
    m.bone('gear_main_r', 'body', (-main[0], main[1], main[2]))
    m.bone('gear_nose', 'body', nose)
    m.bone('pylon_l', 'body', (33.6, 35.2, -3.2))
    m.bone('pylon_r', 'body', (-33.6, 35.2, -3.2))

    # ---- fuselage (x, half-width, z bottom, z top, chamfer top, chamfer bottom; real m)
    radome = [
        (0.90, 0.08, 1.96, 2.11, 0.03, 0.03),
        (1.20, 0.20, 1.88, 2.26, 0.09, 0.08),
        (1.60, 0.32, 1.79, 2.41, 0.14, 0.12),
        (2.30, 0.47, 1.68, 2.60, 0.20, 0.17),
    ]
    body = [
        (2.30, 0.47, 1.68, 2.60, 0.20, 0.17),
        (2.80, 0.53, 1.72, 2.70, 0.22, 0.17),
        (3.50, 0.58, 1.74, 2.76, 0.22, 0.18),
        (5.80, 0.66, 1.70, 3.02, 0.24, 0.18),
        (7.00, 0.65, 1.62, 3.05, 0.24, 0.18),
        (10.0, 0.68, 1.40, 2.82, 0.24, 0.18),
        (12.4, 0.70, 1.36, 2.76, 0.24, 0.20),
        (14.2, 0.68, 1.53, 2.70, 0.22, 0.20),
    ]

    def conv(rows):
        return [(Z(x), 0, (H(zb) + H(zt)) / 2, 2 * hw * M, (zt - zb) * M, ct * M, cb * M) for x, hw, zb, zt, ct, cb in rows]

    rs, bs = conv(radome), conv(body)
    if lod:
        rs, bs = rs[::2] + [rs[-1]], [bs[i] for i in (0, 2, 3, 5, 7)]
    m.loft('body', rs, mat='radome')
    m.loft('body', bs)
    m.box('body', -0.45, 0.45, H(2.03) - 0.45, H(2.03) + 0.45, Z(0.0), Z(0.95), 'metal')      # nose probe
    if full:
        m.box('body', -0.9, 0.9, H(2.03) - 0.9, H(2.03) + 0.9, Z(0.7), Z(1.0), 'metal')
        # IRST ball ahead of the windscreen, starboard
        m.box('body', -4.6, -1.4, H(2.62), H(2.80), Z(2.40), Z(2.72), 'irst')
        # dorsal spine to the fin and drag-chute fairing at the fin base
        m.wedge('body', Z(5.8), H(3.04), Z(12.2), H(2.80), H(2.6), 10.0, 'skin')
        m.box('body', -2.6, 2.6, H(2.90), H(3.25), Z(14.7), Z(16.15), 'skin')
        m.box('body', -2.0, 2.0, H(2.95), H(3.20), Z(16.15), Z(16.25), 'dark')

    # ---- cockpit and gold-tinted canopy (framed windscreen + one-piece canopy)
    sill = H(2.72)
    m.box('body', -7.4, 7.4, sill - 0.8, sill, Z(2.85), Z(5.75), 'cockpit')
    if full:
        m.box('body', -6.0, 6.0, sill, sill + 5.0, Z(3.48), Z(3.62), 'panel')
        m.box('body', -6.6, 6.6, sill + 4.6, sill + 5.6, Z(3.45), Z(3.70), 'antiglare')
        m.box('body', -2.2, 2.2, sill + 5.6, sill + 7.4, Z(3.70), Z(3.74), 'hud')
        m.box('body', -4.4, 4.4, sill, sill + 1.8, Z(4.25), Z(4.85), 'seat')
        m.box('body', -4.0, 4.0, sill + 1.8, sill + 7.6, Z(4.85), Z(5.1), 'seat')
        m.box('body', -3.2, 3.2, sill + 6.4, sill + 9.2, Z(4.82), Z(5.12), 'seat_head')
        m.box('body', -1.0, 1.0, sill + 1.8, sill + 5.4, Z(4.65), Z(4.85), 'handle')
        for sx in (-1, 1):
            m.box('body', sx * 5.6, sx * 7.4, sill, sill + 2.4, Z(3.4), Z(4.8), 'panel')
    m.wedge('canopy', Z(2.78), H(2.70), Z(3.57), H(3.13), H(2.55), 13.2, 'glass', thick=0.8)
    bubble = [(3.55, 3.12, 14.2), (3.95, 3.27, 14.4), (4.45, 3.35, 14.4), (5.00, 3.30, 14.2), (5.40, 3.17, 13.6),
              (5.75, 3.03, 12.6)]
    if lod:
        bubble = bubble[::2]
    m.loft('canopy', [(Z(x), 0, (sill + H(top)) / 2, w, H(top) - sill, min(0.42 * w, (H(top) - sill) * 0.62), 0.1)
                      for x, top, w in bubble], mat='glass', skip_bottom=True)
    if full:
        m.box('canopy', -6.4, 6.4, H(3.08), H(3.15), Z(3.50), Z(3.60), 'frame')       # windscreen arch
        for sx in (-1, 1):
            m.slab('canopy', (sx * 6.8, H(2.71), Z(2.80)), (sx * 6.0, H(3.12), Z(3.55)), 1.0, 1.0, 'frame')
            m.box('canopy', sx * 6.6, sx * 7.6, sill - 0.8, sill + 0.2, Z(2.80), Z(5.8), 'frame')
        m.box('canopy', -6.2, 6.2, sill, H(3.06), Z(5.66), Z(5.8), 'frame')

    # ---- DSI chin intake trunk
    trunk = [
        (Z(3.95), 0, (H(1.19) + H(1.86)) / 2, 1.42 * M, 0.67 * M, 0.12 * M, 0.26 * M),
        (Z(4.7), 0, (H(1.16) + H(1.82)) / 2, 1.44 * M, 0.66 * M, 0.12 * M, 0.26 * M),
        (Z(7.4), 0, (H(1.16) + H(1.80)) / 2, 1.40 * M, 0.64 * M, 0.12 * M, 0.24 * M),
        (Z(9.4), 0, (H(1.24) + H(1.66)) / 2, 1.20 * M, 0.42 * M, 0.10 * M, 0.14 * M),
        (Z(10.6), 0, (H(1.36) + H(1.50)) / 2, 0.90 * M, 0.14 * M, 0.04 * M, 0.04 * M),
    ]
    m.loft('chin_intake', trunk if full else trunk[::2])
    # fill the groove where the wider trunk meets the forward fuselage
    m.box('chin_intake', -11.0, 11.0, H(1.62), H(1.86), Z(3.95), Z(9.2), 'skin')
    m.box('chin_intake', -9.6, 9.6, H(1.24), H(1.80), Z(3.90), Z(3.98), 'intake')
    if full:
        # 'smile' lower lip swept forward, DSI bump on the forward fuselage underside
        m.box('chin_intake', -9.6, 9.6, H(1.12), H(1.32), Z(3.80), Z(4.5), 'skin')
        m.box('chin_intake', -7.0, 7.0, H(1.15), H(1.22), Z(3.70), Z(3.80), 'skin')
        m.section('chin_intake', Z(3.55), Z(4.55), 0, H(1.72), 11.0, 3.2, 1.1, 1.1)
        for sx in (-1, 1):   # intake-corner pylons
            m.box('chin_intake', sx * 8.4 - 0.9, sx * 8.4 + 0.9, H(1.00), H(1.40), Z(6.6), Z(8.4), 'pylon')

    # ---- canards (all-moving) on the trunk sides, 8 deg dihedral
    for side in (1, -1):
        bone = 'stabilizer_l' if side > 0 else 'stabilizer_r'
        c = m.panel(bone, (0.62 * M, Z(4.88), 2.14 * M), (2.21 * M, Z(7.02), 0.44 * M), 2.4, 'skin', side=side,
                    strips=2 if full else 1, root_in=0.22 * M)
        place_panel(c, H(2.33), 8.0, side, 0.62 * M)

    # ---- delta wing with elevons
    wing_y = H(1.85)
    for side in (1, -1):
        bone = 'wing_l' if side > 0 else 'wing_r'
        y0, y1 = 0.62, 4.875
        cubes = m.panel(bone, (y0 * M, Z(wing_le(y0)), (wing_te(y0) - wing_le(y0)) * M),
                        (y1 * M, Z(wing_le(y1)), (wing_te(y1) - wing_le(y1)) * M), 3.2, 'skin', side=side,
                        strips=4 if full else 2, root_in=0.3 * M)
        place_panel(cubes, wing_y, 0.0, side, y0 * M)
        if full:
            m.box(bone, side * (y1 * M - 0.6), side * (y1 * M + 0.8), wing_y - 0.8, wing_y + 0.8, Z(12.45),
                  Z(12.9), 'light_green' if side > 0 else 'light_red')
            m.box(bone, side * 0.9 * M, side * 4.6 * M, wing_y + 1.4, wing_y + 1.8, Z(12.55), Z(12.62), 'hinge')
            for yy in (2.61 if False else 2.75, 3.76):      # outer pylons (decorative)
                zc = Z((wing_le(yy) + wing_te(yy)) / 2)
                m.box(bone, side * yy * M - 0.9, side * yy * M + 0.9, wing_y - 6.0, wing_y - 1.0,
                      zc - 12.0, zc + 12.0, 'pylon')
    # functional pylons: stores hang from y 2.2 at z -0.2, just under the wing
    for name, x in (('pylon_l', 33.6), ('pylon_r', -33.6)):
        m.box(name, x - 1.0, x + 1.0, 35.2, wing_y - 0.6, Z(8.0), Z(11.4), 'pylon')
        if full:
            m.box(name, x - 1.4, x + 1.4, 34.8, 35.6, Z(8.1), Z(9.6), 'rail')

    # ---- aft fuselage beams and nozzle
    aft = [(Z(14.2), 0, (H(1.53) + H(2.70)) / 2, 1.36 * M, 1.17 * M, 0.30 * M, 0.30 * M),
           (Z(14.7), 0, (H(1.47) + H(2.63)) / 2, 1.30 * M, 1.16 * M, 0.32 * M, 0.32 * M)]
    m.loft('engine', aft)
    ny = H(2.05)
    m.section('engine', Z(14.7), Z(15.3), 0, ny, 18.4, 18.4, 5.4, mat='nozzle', ends=(False, False))
    m.section('engine', Z(15.3), Z(EXIT), 0, ny, 15.4, 15.4, 4.5, mat='nozzle', ends=(False, True))
    m.box('engine', -5.4, 5.4, ny - 5.4, ny + 5.4, Z(EXIT) - 0.3, Z(EXIT) + 0.02, 'nozzle_in')
    for side in (1, -1):  # aft 'beam' fairings beside the engine
        m.box('engine', side * 7.0, side * 1.09 * M, H(1.72), H(2.06), Z(12.4), Z(15.4), 'skin')

    # ---- tall fin with rudder, canted ventral fins
    base = H(3.2)
    fin = m.panel('tail_fin', (0, Z(12.06), 3.6 * M), (2.28 * M, Z(15.32), 1.11 * M), 3.0, 'skin', side=1,
                  strips=3 if full else 1, skip_root=False, root_in=0.45 * M)
    for c in fin:
        _upright(c, base)
    if full:
        m.box('tail_fin', -1.9, 1.9, H(2.66), base + 0.4, Z(11.95), Z(14.7), 'skin')      # fin root fairing
        m.box('tail_fin', -1.0, 1.0, H(5.36), H(5.46), Z(15.5), Z(15.8), 'light_white')
    for side in (1, -1):
        m.centered('body', (side * 1.0 * M, H(1.52), Z(14.1)), (0.9, 0.78 * M, 1.6 * M), 'skin',
                   rotation=(0, 0, -side * 30), lod=full)

    _gear(m, nose, main, full)
    return m


def _upright(c, base_y):
    sx, sy, sz = c.size
    px, py, pz = c.pivot
    c.origin = [-sy / 2, base_y + px - sx / 2, pz - sz / 2]
    c.size = [sy, sx, sz]
    c.pivot = [0.0, base_y + px, pz]
    c.rotation = [c.rotation[1] if c.rotation else 0.0, 0.0, 0.0]


def _gear(m, nose, main, full):
    nx, ny, nz = nose
    m.box('gear_nose', nx - 0.9, nx + 0.9, 4.4, ny, nz - 0.9, nz + 0.9, 'strut')
    m.box('gear_nose', nx - 1.7, nx + 1.7, 0.0, 8.8, nz - 4.4, nz + 4.4, 'tire')
    if full:
        m.box('gear_nose', nx - 1.9, nx + 1.9, 3.0, 5.8, nz - 1.4, nz + 1.4, 'hub')
        m.box('gear_nose', nx - 1.5, nx + 1.5, 10.0, ny - 2.0, nz - 1.5, nz + 1.5, 'strut')
        m.box('gear_nose', nx - 2.4, nx + 2.4, 8.8, 9.6, nz - 4.0, nz + 3.0, 'strut')    # fork / mudguard
        m.box('body', -3.0, 3.0, H(1.15), H(1.17), Z(5.6), Z(7.0), 'well')
    for side in (1, -1):
        name = 'gear_main_l' if side > 0 else 'gear_main_r'
        x, y, z = side * main[0], main[1], main[2]
        m.box(name, x - 1.2, x + 1.2, 7.0, y, z - 1.2, z + 1.2, 'strut')
        m.box(name, x - 2.6, x + 2.6, 0.0, 12.8, z - 6.4, z + 6.4, 'tire')
        if full:
            m.box(name, x - 2.8, x + 2.8, 4.4, 8.4, z - 2.1, z + 2.1, 'hub')
            m.box(name, x - 1.8, x + 1.8, 13.0, y - 3.0, z - 1.8, z + 1.8, 'strut')
            m.box(name, min(x, side * 10.0) - 0.6, max(x, side * 10.0) + 0.6, y - 5.0, y + 0.4, z - 0.7, z + 0.7,
                  'strut')
            m.box('body', x - 3.6, x + 3.6, y - 0.3, y + 0.3, z - 6.0, z + 14.0, 'well')


PALETTE = {
    'metal': '#9a9c9a', 'radome': '#4f555a', 'irst': '#2b3a40', 'cockpit': '#3d4246', 'panel': '#1c1f22',
    'antiglare': '#2c2f31', 'hud': '#7c9a84', 'seat': '#2f3133', 'seat_head': '#3b3d3f', 'handle': '#d8b23a',
    'rail': '#7d8487', 'glass': '#8c7d46', 'dark': '#2a2c2e', 'hinge': '#5c6366', 'canopy_frame': '#4d5357', 'light_white': '#f2f0dc',
    'light_red': '#d23a2a', 'light_green': '#38b24a', 'strut': '#cfd2cd', 'hub': '#bfc3be', 'well': '#c9ccc4',
}
UPPER, LOWER, SPLINTER = '#7e878c', '#b8bec2', '#6f787d'


def painter():
    from kit import value_noise

    def camo(p, n):
        # J-10C: blue-grey upper surfaces with soft darker patches over a light belly.
        rgb = np.tile(hexrgb(UPPER), (len(p), 1))
        patch = value_noise(p * np.array([1.0, 0.5, 1.0]), 30.0, 7) > 0.62
        rgb[patch] = hexrgb(SPLINTER)
        wave = H(1.62) + 2.0 * np.sin(p[:, 2] / 13.0)
        under = (n[1] < -0.45) | (p[:, 1] < wave)
        rgb[under] = hexrgb(LOWER)
        return rgb

    frames = [Z(x) for x in (2.3, 2.8, 5.8, 7.2, 9.0, 10.8, 12.4, 14.2)]

    def panel_lines(p, n, cube):
        r = np.abs(p[:, 0])
        m = stations_mask(p[:, 2], frames, 0.45) & (r < 18)
        wing = r > 18
        m |= wing & (np.abs(n[1]) > 0.5) & (np.mod(r - 18, 17.0) < 0.5)
        return m

    bars = np.zeros((16, 44), bool)
    bars[6:10, :] = True
    star = np.pad(star_mask(16), ((0, 0), (14, 14)))
    red = (196, 30, 28)
    insignia = [(bars & ~star, red), (star, red)]
    decals = []
    for sx in (1, -1):
        decals.append(Decal((sx * 2.0, H(4.2), Z(14.95)), (sx, 0, 0), (0, 0, sx), (0, -1, 0), (26, 9.5), insignia,
                            max_depth=3))
        decals.append(Decal((sx * 3.3 * M, H(1.6), Z(11.8)), (0, -1, 0), (-1, 0, 0), (0, 0, 1), (22, 8),
                            insignia, max_depth=8))
        decals.append(Decal((sx * 9.6, H(2.32), Z(2.25)), (sx, 0, 0), (0, 0, sx), (0, -1, 0), (17, 4.6),
                            [(text_mask('61523'), red)], max_depth=4))
    return Painter(PALETTE, camo, panel_lines, decals, glass_alpha=92)
