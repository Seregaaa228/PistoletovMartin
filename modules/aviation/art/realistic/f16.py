"""F-16C Block 50/52 at 1 block = 1 m.

Proportions from the measured three-views in aircraft-references (Marek Cel,
CC0; USAF drawing, public domain; used for proportions only): length 15.03 m
to the fin cap with a 0.48 m pitot, rail span 9.45 m, LE sweep 39.5 deg,
unswept TE at x 10.77, stabilators span 5.58 m with 10 deg anhedral, track
2.41 m, wheelbase 3.98 m. x is metres aft of the pitot tip; real heights get
LIFT so that the nozzle axis meets the exhaust point in AircraftCatalog.
Anchors kept: seat (vehicle z 3.99, y 2.54), ADS view point above the canopy,
stations x +-2.1 / +-3.4 (y 2.2, z -0.2), tip rails at x +-4.95, aft airbrake
hinges (+-11.15, 39, 70 px), wheel radii 0.36/0.29 m of the wheel-spin code.
"""
import math

import numpy as np

from kit import Model, place_panel
from paint import Decal, Painter, star_mask, stations_mask, text_mask

M = 16.0
NOSE = -133.44         # pitot tip, geo Z (px): pilot eye at x 4.35 m
LIFT = 0.59
TIP_X = 4.95           # wingtip missile station (m), as in AircraftRenderer
EXIT = 14.45           # nozzle exit (m)


def Z(x):
    return NOSE + x * M


def H(z):
    return (z + LIFT) * M


def wing_le(y):
    return 7.0 + (y - 1.37) * math.tan(math.radians(39.5))


def wing_chord(y):
    return 10.77 - wing_le(y)


LERX = [(2.8, 0.59), (3.5, 0.72), (4.3, 0.78), (5.0, 0.89), (5.5, 1.00), (6.0, 1.18), (6.5, 1.29), (7.0, 1.37)]


def build(lod=False):
    full = not lod
    m = Model('f16_lod' if lod else 'f16')
    m.bone('body', None)
    m.bone('pilot_anchor', 'body', (0, 40.64, -63.84))
    for name in ('wing_l', 'wing_r', 'tail_fin', 'stabilizer_l', 'stabilizer_r', 'canopy', 'chin_intake', 'engine'):
        m.bone(name)
    main = (19.3, H(1.02), Z(9.2))
    nose = (0.0, H(0.92), Z(5.2))
    for side, name in ((1, 'gear_main_l'), (-1, 'gear_main_r')):
        x = side * main[0]
        m.bone(name, 'body', (x, main[1], main[2]))
        m.bone(name + '_suspension', name, (x, 11.52, main[2]))
        m.bone(name + '_wheel', name + '_suspension', (x, 5.76, main[2]))
    m.bone('gear_nose', 'body', nose)
    m.bone('gear_nose_suspension', 'gear_nose', (0, 9.28, nose[2]))
    m.bone('gear_nose_wheel', 'gear_nose_suspension', (0, 4.64, nose[2]))
    m.bone('pylon_l', 'body', (33.6, 35.2, -3.2))
    m.bone('pylon_r', 'body', (-33.6, 35.2, -3.2))
    wing_y = H(1.9)
    m.bone('f16_aileron_left', 'body', (2.2 * M, wing_y, Z(10.27)))
    m.bone('f16_aileron_right', 'body', (-2.2 * M, wing_y, Z(10.27)))
    m.bone('f16_elevator_left', 'body', (1.3 * M, H(1.85), Z(13.0)))
    m.bone('f16_elevator_right', 'body', (-1.3 * M, H(1.85), Z(13.0)))
    m.bone('f16_rudder', 'body', (0, H(4.0), Z(13.86)))
    m.bone('dummy_0_1_1', 'body', (54.4, 35.2, -3.2))
    m.bone('dummy_0_1_2', 'body', (-54.4, 35.2, -3.2))
    m.bone('pylon_outer_1', 'body', (54.4, 35.2, -3.2))
    m.bone('pylon_outer_2', 'body', (-54.4, 35.2, -3.2))
    m.bone('f16_airbrake_left', 'body', (11.15, 39.0, 70.0))
    m.bone('f16_airbrake_right', 'body', (-11.15, 39.0, 70.0))
    m.bone('f16_tip_pylon_left', 'body', (TIP_X * M, 43.8, 20.0))
    m.bone('f16_tip_pylon_right', 'body', (-TIP_X * M, 43.8, 20.0))

    # ---- fuselage (x, half-width, z bottom, z top, chamfer top, chamfer bottom; real m)
    radome = [
        (0.48, 0.07, 1.55, 1.68, 0.03, 0.03),
        (0.75, 0.18, 1.52, 1.78, 0.08, 0.08),
        (1.05, 0.28, 1.49, 1.88, 0.12, 0.12),
        (1.50, 0.41, 1.45, 2.05, 0.17, 0.16),
        (2.20, 0.51, 1.46, 2.25, 0.21, 0.18),
    ]
    body = [
        (2.20, 0.51, 1.46, 2.25, 0.21, 0.18),
        (2.80, 0.55, 1.49, 2.36, 0.20, 0.16),
        (4.30, 0.58, 1.58, 2.40, 0.18, 0.14),
        (5.20, 0.62, 1.42, 2.45, 0.20, 0.16),
        (6.30, 0.82, 1.08, 2.55, 0.26, 0.20),
        (7.00, 1.02, 0.96, 2.62, 0.30, 0.22),
        (10.0, 1.02, 1.00, 2.66, 0.30, 0.24),
        (11.2, 0.92, 1.18, 2.72, 0.30, 0.26),
    ]

    def conv(rows):
        return [(Z(x), 0, (H(zb) + H(zt)) / 2, 2 * hw * M, (zt - zb) * M, ct * M, cb * M) for x, hw, zb, zt, ct, cb in rows]

    rs, bs = conv(radome), conv(body)
    if lod:
        rs, bs = rs[::2], [bs[i] for i in (0, 2, 4, 5, 7)]
    m.loft('body', rs, mat='radome')
    m.loft('body', bs)
    m.box('body', -0.6, 0.6, H(1.585), H(1.645), Z(0.0), Z(0.5), 'metal')        # pitot
    if full:
        m.box('body', -1.0, 1.0, H(1.56), H(1.67), Z(0.4), Z(0.55), 'metal')
        # IFF 'bird-slicer' blades ahead of the windscreen (Block 50/52)
        for sx in (-1, 1):
            m.box('body', sx * 1.6 - 0.15, sx * 1.6 + 0.15, H(2.25), H(2.25) + 2.0, Z(2.35), Z(2.55), 'antenna')
            m.box('body', sx * 4.6 - 0.15, sx * 4.6 + 0.15, H(2.15), H(2.15) + 1.6, Z(2.35), Z(2.55), 'antenna')
        # dorsal fairing rising from the spine to the fin root
        m.wedge('body', Z(9.7), H(2.66), Z(11.5), H(3.0), H(2.55), 9.0, 'skin')
    # canopy fairing aft of the transparency, blending into the spine
    m.wedge('body', Z(5.1), H(2.98) - 0.6, Z(6.5), H(2.64), H(2.35), 12.4, 'skin', lod=full)

    # ---- LERX strakes, outline measured at the wing plane
    for side in (1, -1):
        bone = 'wing_l' if side > 0 else 'wing_r'
        pts = LERX if full else LERX[::3] + [LERX[-1]]
        for k, ((xa, ya), (xb, yb)) in enumerate(zip(pts, pts[1:])):
            length = math.hypot(xb - xa, yb - ya) * M
            ang = math.atan2(yb - ya, xb - xa)
            width = (ya + yb) / 2 * M
            mx, my = (xa + xb) / 2, (ya + yb) / 2
            # centre: edge midpoint moved inboard by half the width, perpendicular to the edge
            cx = (my * M - math.cos(ang) * width / 2)
            cz = Z(mx) + math.sin(ang) * width / 2
            m.centered(bone, (side * cx, wing_y + (0.1 if k % 2 else 0.0), cz), (width, 2.4, length + 3.0), 'skin',
                       rotation=(0, side * math.degrees(ang), 0))

    # ---- cockpit interior and gold-tinted bubble canopy
    sill = H(2.40)
    m.box('body', -7.4, 7.4, sill - 0.8, sill, Z(2.9), Z(5.3), 'cockpit')
    if full:
        m.box('body', -5.8, 5.8, sill, sill + 5.0, Z(3.45), Z(3.62), 'panel')
        m.box('body', -6.4, 6.4, sill + 4.6, sill + 5.6, Z(3.40), Z(3.70), 'antiglare')
        m.box('body', -2.2, 2.2, sill + 5.6, sill + 7.6, Z(3.62), Z(3.66), 'hud')
        m.box('body', -4.2, 4.2, sill, sill + 1.8, Z(4.15), Z(4.75), 'seat')           # ACES II
        m.slab('body', (0, sill + 1.8, Z(4.75)), (0, sill + 8.4, Z(5.05)), 7.6, 1.2, 'seat')
        m.box('body', -3.0, 3.0, sill + 7.6, sill + 10.0, Z(4.98), Z(5.25), 'seat_head')
        m.box('body', -0.9, 0.9, sill + 1.8, sill + 5.4, Z(4.85), Z(5.0), 'handle')
        for sx in (-1, 1):
            m.box('body', sx * 5.2, sx * 7.2, sill, sill + 2.4, Z(3.4), Z(4.8), 'panel')
    # bubble profile (x, real top z incl. head room, width px); sill at 2.40 m
    bubble = [(2.80, 2.42, 10.4), (3.15, 2.70, 12.0), (3.65, 2.94, 12.8), (4.30, 3.18, 13.2), (4.85, 3.16, 13.0),
              (5.30, 3.00, 12.6), (5.70, 2.80, 12.0)]
    if lod:
        bubble = bubble[::2]
    m.loft('canopy', [(Z(x), 0, (sill + H(top)) / 2, w, H(top) - sill, min(0.42 * w, (H(top) - sill) * 0.62), 0.1)
                      for x, top, w in bubble], mat='glass', skip_bottom=True)
    if full:
        for sx in (-1, 1):
            m.box('canopy', sx * 6.3, sx * 7.3, sill - 1.0, sill, Z(2.8), Z(5.75), 'frame')   # canopy sill rail
        m.box('canopy', -6.0, 6.0, sill, H(2.98), Z(5.62), Z(5.78), 'frame')                 # aft bow

    # ---- chin intake (Block 50 'big mouth'), splitter/diverter, dark duct
    m.loft('chin_intake', [
        (Z(4.40), 0, (H(0.92) + H(1.56)) / 2, 1.58 * M, 0.64 * M, 0.10 * M, 0.22 * M),
        (Z(5.4), 0, (H(0.92) + H(1.60)) / 2, 1.50 * M, 0.68 * M, 0.10 * M, 0.24 * M),
        (Z(6.6), 0, (H(0.94) + H(1.40)) / 2, 1.30 * M, 0.46 * M, 0.08 * M, 0.18 * M),
        (Z(7.6), 0, (H(0.96) + H(1.16)) / 2, 1.00 * M, 0.20 * M, 0.04 * M, 0.06 * M),
    ])
    m.box('chin_intake', -10.4, 10.4, H(0.97), H(1.53), Z(4.36), Z(4.44), 'intake')
    if full:
        m.box('chin_intake', -10.0, 10.0, H(0.90), H(1.05), Z(4.48), Z(5.1), 'skin')       # lower lip
        m.box('chin_intake', -2.0, 2.0, H(1.53), H(1.62), Z(4.30), Z(6.4), 'skin')         # diverter
        for sx in (-1, 1):                                                                 # sensor-pod stations
            m.box('chin_intake', sx * 9.0 - 0.8, sx * 9.0 + 0.8, H(0.98), H(1.12), Z(5.3), Z(6.3), 'pylon')

    # ---- wings: unswept TE, flaperons inboard, tip launch rails
    for side in (1, -1):
        bone = 'wing_l' if side > 0 else 'wing_r'
        y0, y1 = 1.00, 4.58
        cubes = m.panel(bone, (y0 * M, Z(wing_le(y0)), wing_chord(y0) * M), (y1 * M, Z(wing_le(y1)), wing_chord(y1) * M),
                        3.0, 'skin', side=side, strips=3 if full else 1, root_in=0.45 * M)
        place_panel(cubes, wing_y, 0.0, side, y0 * M)
        tip = 'f16_tip_pylon_left' if side > 0 else 'f16_tip_pylon_right'
        m.box(tip, side * 72.6, side * 76.6, wing_y - 1.4, wing_y + 1.2, Z(8.3), Z(10.98), 'rail')
        m.box(tip, side * 73.8, side * 75.6, wing_y - 2.6, wing_y - 1.2, Z(8.6), Z(10.8), 'rail', lod=full)
        if full:
            m.box(bone, side * 71.0, side * 72.8, wing_y - 0.8, wing_y + 0.8, Z(9.65), Z(9.85),
                  'light_green' if side > 0 else 'light_red')
        ail = 'f16_aileron_left' if side > 0 else 'f16_aileron_right'
        m.box(ail, side * 1.15 * M, side * 3.30 * M, wing_y - 1.3, wing_y + 1.3, Z(10.27), Z(10.80), 'skin')
        if full:
            m.box(ail, side * 1.15 * M, side * 3.30 * M, wing_y - 0.9, wing_y + 0.9, Z(10.22), Z(10.29), 'hinge')

    # underwing pylons (2 cubes each for pylon_outer_*); their tops stay inside the wing
    for name, x in (('pylon_l', 33.6), ('pylon_r', -33.6), ('pylon_outer_1', 54.4), ('pylon_outer_2', -54.4)):
        zc = -3.2
        m.box(name, x - 0.9, x + 0.9, 36.4, wing_y + 0.6, zc - 13.0, zc + 30.0, 'pylon')
        m.box(name, x - 1.3, x + 1.3, 35.2, 36.8, zc - 9.0, zc + 12.0, 'rail')

    # ---- engine section, tail booms, F110 nozzle (engine bone holds the airbrake mounts)
    aft = [
        (Z(11.2), 0, (H(1.18) + H(2.72)) / 2, 1.84 * M, 1.54 * M, 0.30 * M, 0.26 * M),
        (Z(12.2), 0, (H(1.18) + H(2.62)) / 2, 1.42 * M, 1.44 * M, 0.34 * M, 0.32 * M),
        (Z(12.95), 0, (H(1.27) + H(2.47)) / 2, 1.24 * M, 1.20 * M, 0.34 * M, 0.34 * M),
    ]
    m.loft('engine', aft)
    for side in (1, -1):
        m.box('engine', side * 7.0, side * 1.05 * M, H(1.76), H(1.98), Z(10.4), Z(14.3), 'skin')
    ny = H(1.87)
    m.section('engine', Z(12.95), Z(13.7), 0, ny, 18.4, 18.4, 5.4, mat='nozzle', ends=(False, False))
    m.section('engine', Z(13.7), Z(EXIT), 0, ny, 15.6, 15.6, 4.6, mat='nozzle', ends=(False, True))
    m.box('engine', -5.6, 5.6, ny - 5.6, ny + 5.6, Z(EXIT) - 0.3, Z(EXIT) + 0.02, 'nozzle_in')

    # aft speed brakes beside the nozzle, hinged at the code's pivot
    for side in (1, -1):
        name = 'f16_airbrake_left' if side > 0 else 'f16_airbrake_right'
        x = side * 11.15
        m.box(name, x - 0.6, x + 0.6, H(1.45), H(2.30), 69.4, 94.0, 'skin')
        # boom end structure between the nozzle and the closed brake
        m.box('engine', side * 7.4, side * 10.6, H(1.47), H(2.28), 69.4, 93.0, 'skin')

    # ---- vertical fin with rudder, ventral fins
    base = H(3.0)
    fin = m.panel('tail_fin', (0, Z(11.4), 2.6 * M), (2.16 * M, Z(13.75), 1.2 * M), 3.0, 'skin', side=1,
                  strips=3 if full else 1, skip_root=False, root_in=0.38 * M)
    for c in fin:
        _upright(c, base)
    # rudder: the aft strip of the fin, aligned with its swept trailing edge
    te = math.atan2(14.95 - 14.0, 2.16)
    hc, depth = 1.0, 0.55 * M
    zte = Z(14.0 + (14.95 - 14.0) * hc / 2.16)
    m.centered('f16_rudder', (0, base + hc * M + math.sin(te) * depth / 2, zte - math.cos(te) * depth / 2),
               (3.2, 1.5 * M, depth), 'skin', rotation=(-math.degrees(te), 0, 0))
    if full:
        m.box('tail_fin', -1.9, 1.9, H(2.40), base + 0.4, Z(11.3), Z(14.6), 'skin')        # fin base fairing
        m.box('tail_fin', -1.0, 1.0, H(4.98), H(5.10), Z(13.95), Z(14.25), 'light_white')
    for side in (1, -1):
        m.centered('body', (side * 0.52 * M, H(0.80), Z(11.1)), (0.8, 0.55 * M, 1.3 * M), 'skin',
                   rotation=(0, 0, -side * 14), lod=full)

    # ---- all-moving stabilators (anhedral 10 deg)
    for side in (1, -1):
        name = 'f16_elevator_left' if side > 0 else 'f16_elevator_right'
        cubes = m.panel(name, (1.05 * M, Z(12.0), 2.42 * M), (2.79 * M, Z(13.55), 0.65 * M), 2.4, 'skin', side=side,
                        strips=2 if full else 1, root_in=0.5 * M)
        place_panel(cubes, H(1.85), -10.0, side, 1.05 * M)

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
    # nose: leg (fixed part) + oleo; wheel radius 0.29 m centred on the wheel pivot
    m.box('gear_nose', -0.9, 0.9, 8.0, ny + 1.0, nz - 0.9, nz + 0.9, 'strut')
    if full:
        m.box('gear_nose', -1.5, 1.5, 9.4, 14.0, nz - 1.5, nz + 1.5, 'strut')
    m.box('gear_nose_wheel', -1.5, 1.5, 0.0, 9.28, nz - 4.64, nz + 4.64, 'tire')
    if full:
        m.box('gear_nose_wheel', -1.7, 1.7, 3.2, 6.1, nz - 1.5, nz + 1.5, 'hub')
    for side in (1, -1):
        name = 'gear_main_l' if side > 0 else 'gear_main_r'
        x, y, z = side * main[0], main[1], main[2]
        # strut first, brace last: the brace must touch the strut and the fuselage
        m.box(name, x - 1.1, x + 1.1, 10.0, y, z - 1.1, z + 1.1, 'strut')
        if full:
            m.box(name, x - 1.6, x + 1.6, 12.4, 18.0, z - 1.6, z + 1.6, 'strut')
        m.box(name, min(x, side * 12.0) - 0.6, max(x, side * 12.0) + 0.6, y - 6.0, y + 0.6, z - 0.8, z + 0.8, 'strut')
        wheel = name + '_wheel'
        m.box(wheel, x - 2.6, x + 2.6, 0.0, 11.52, z - 5.76, z + 5.76, 'tire')
        if full:
            m.box(wheel, x - 2.8, x + 2.8, 3.8, 7.7, z - 1.9, z + 1.9, 'hub')
        m.box('body', x - 3.4, x + 3.4, y - 0.4, y + 0.4, z - 5.0, z + 14.0, 'well', lod=True)
    if full:
        m.box('body', -3.0, 3.0, H(0.91), H(1.0), Z(5.3), Z(6.6), 'well')


PALETTE = {
    'metal': '#9a9c9a', 'radome': '#8f9799', 'cockpit': '#3d4246', 'panel': '#1c1f22', 'antiglare': '#2c2f31',
    'hud': '#7c9a84', 'seat': '#2f3133', 'seat_head': '#3b3d3f', 'handle': '#d8b23a', 'rail': '#7d8487',
    'glass': '#8c7d46', 'antenna': '#5c6366', 'canopy_frame': '#4d5357', 'hinge': '#5c6366', 'light_white': '#f2f0dc',
    'light_red': '#d23a2a', 'light_green': '#38b24a', 'strut': '#d9dbd6', 'hub': '#c9ccc7', 'well': '#d4d6cf',
}
DARK, MID, LIGHT = '#59616a', '#878d91', '#8e9498'   # Hill Gray II: FS 36118 over FS 36270


def painter():
    from kit import hexrgb

    def camo(p, n):
        # Hill Gray II: FS 36118 on the spine and the wing/stabilator tops, FS 36270 elsewhere.
        up = n[1] > 0.55
        dark = up & (p[:, 2] > Z(3.0)) & (p[:, 1] < H(3.1))
        under = (n[1] < -0.45) | (p[:, 1] < H(1.3))
        rgb = np.tile(hexrgb(MID), (len(p), 1))
        rgb[dark] = hexrgb(DARK)
        rgb[under] = hexrgb(LIGHT)
        return rgb

    frames = [Z(x) for x in (2.2, 2.8, 5.75, 7.0, 8.4, 9.9, 11.2, 12.2)]

    def panel_lines(p, n, cube):
        r = np.abs(p[:, 0])
        m = stations_mask(p[:, 2], frames, 0.45) & (r < 18)
        wing = r > 19
        m |= wing & (np.abs(n[1]) > 0.5) & (np.mod(r - 19, 17.0) < 0.5)
        m |= wing & (np.abs(n[1]) > 0.5) & stations_mask(p[:, 2], [Z(8.9), Z(10.27)], 0.4)
        return m

    star = star_mask(48)
    decals = []
    lowvis = (64, 70, 74)
    for sx in (1, -1):
        decals.append(Decal((sx * 12.6, H(1.24), Z(5.9)), (sx, 0, 0), (0, 0, sx), (0, -1, 0), (8, 8),
                            [(star, lowvis)], max_depth=3))
        decals.append(Decal((sx * 2.0, H(4.15), Z(13.15)), (sx, 0, 0), (0, 0, sx), (0, -1, 0), (13, 7),
                            [(text_mask('FN'), (52, 56, 58))], max_depth=3))
        decals.append(Decal((sx * 2.0, H(3.55), Z(13.25)), (sx, 0, 0), (0, 0, sx), (0, -1, 0), (15, 3.2),
                            [(text_mask('91 0333'), (52, 56, 58))], max_depth=3))
        decals.append(Decal((sx * 3.3 * M, H(2.0), Z(9.95)), (0, 1, 0), (-1, 0, 0), (0, 0, 1), (12, 12),
                            [(star, lowvis)], max_depth=4))
    return Painter(PALETTE, camo, panel_lines, decals, glass_alpha=92)
