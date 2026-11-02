"""Su-25 'Frogfoot' at 1 block = 1 m.

Proportions from the measured three-view in aircraft-references (Kaboldy,
CC BY-SA 3.0, used for proportions only) and published data: length 15.53 m
with the nose probes, span 14.36 m, LE sweep 19.6 deg, anhedral -2.5 deg,
track 2.5 m, wheelbase 3.55 m, nozzles at x 11.65 m with a slim tail boom.
x below is metres aft of the probe tips; real heights get LIFT added because
the in-game seat/camera (vehicle y 2.54 / 3.535) sit higher than a real
Su-25 cockpit. Anchors kept: seat z 3.99, functional pylons x +-2.1 with the
store attachment at y 2.2, z -0.2.
"""
import math

import numpy as np

from kit import Model, place_panel
from paint import Decal, Painter, splotch_camo, star_mask, stations_mask, text_mask

NOSE = -130.24         # geo Z (px) of the probe tips: pilot eye at x 4.15 m
M = 16.0
LIFT = 0.75            # metres added to every real height
NOZZLE_X = 11.65


def Z(x):
    """Metres aft of the probe tips -> geo Z (px)."""
    return NOSE + x * M


def H(z):
    """Real height above ground (m) -> geo Y (px)."""
    return (z + LIFT) * M


def build(lod=False):
    m = Model('su25_lod' if lod else 'su25')
    full = not lod
    m.bone('body', None)
    m.bone('pilot_anchor', 'body', (0, 40.64, -63.84))
    for name in ('wing_l', 'wing_r', 'tail_fin', 'stabilizer_l', 'stabilizer_r', 'canopy', 'engine_l', 'engine_r'):
        m.bone(name)
    nose_gear = (0.0, H(0.95), Z(4.90))
    main_gear = (20.0, H(1.05), Z(8.20))
    m.bone('gear_main_l', 'body', main_gear)
    m.bone('gear_main_r', 'body', (-main_gear[0], main_gear[1], main_gear[2]))
    m.bone('gear_nose', 'body', nose_gear)
    m.bone('pylon_l', 'body', (33.6, 35.2, -3.2))
    m.bone('pylon_r', 'body', (-33.6, 35.2, -3.2))

    # ---- fuselage: chisel nose, deep cockpit section, spine, slim tail boom
    st = [  # x, half-width, z bottom, z top (real metres); chamfer top/bottom (m)
        (1.13, 0.17, 1.22, 1.48, 0.07, 0.07),
        (1.40, 0.26, 1.10, 1.54, 0.12, 0.11),
        (1.80, 0.35, 0.98, 1.64, 0.17, 0.15),
        (2.30, 0.43, 0.90, 1.78, 0.22, 0.18),
        (2.90, 0.49, 0.87, 1.93, 0.25, 0.20),
        (3.50, 0.50, 0.85, 2.31, 0.24, 0.20),
        (4.50, 0.50, 0.85, 2.62, 0.22, 0.20),
        (5.80, 0.50, 0.86, 2.65, 0.22, 0.20),
        (9.50, 0.50, 0.90, 2.65, 0.22, 0.20),
        (11.0, 0.42, 1.10, 2.65, 0.20, 0.18),
        (12.5, 0.33, 1.63, 2.65, 0.15, 0.13),
        (13.5, 0.28, 1.91, 2.70, 0.13, 0.11),
        (14.5, 0.23, 2.16, 2.69, 0.10, 0.09),
        (15.53, 0.12, 2.30, 2.60, 0.05, 0.05),
    ]
    stations = [(Z(x), 0, (H(zb) + H(zt)) / 2, 2 * hw * M, (zt - zb) * M, ct * M, cb * M)
                for x, hw, zb, zt, ct, cb in st]
    if lod:
        stations = [stations[i] for i in (0, 2, 4, 6, 8, 10, 12, 13)]
    m.loft('body', stations)
    if full:
        # laser rangefinder window ('Klyon') in the chisel nose and the twin air-data probes
        m.box('body', -2.6, 2.6, H(1.24), H(1.46), Z(1.10), Z(1.16), 'laser')
        for sx in (-1, 1):
            m.box('body', sx * 4.2 - 0.35, sx * 4.2 + 0.35, H(1.47) - 0.35, H(1.47) + 0.35, Z(0.0), Z(1.6), 'metal')
        # GSh-30-2 gun fairing under the nose, port side (+X)
        m.box('body', 2.0, 7.6, H(0.80), H(1.05), Z(2.3), Z(4.4), 'skin')
        m.box('body', 3.2, 6.4, H(0.84), H(1.02), Z(2.05), Z(2.3), 'metal')
        m.box('body', 3.8, 5.8, H(0.88), H(0.98), Z(1.95), Z(2.05), 'intake')
        # radio-transparent nose panels
        m.box('body', -5.8, 5.8, H(1.60), H(1.70), Z(1.95), Z(2.5), 'dielectric')

    # ---- cockpit: floor colour under the glass, seat, panel, HUD
    sill = H(2.40)
    m.box('body', -7.4, 7.4, sill - 1.2, sill, Z(3.3), Z(5.15), 'cockpit')
    if full:
        m.box('body', -6.2, 6.2, sill, sill + 5.0, Z(3.66), Z(3.86), 'panel')
        m.box('body', -6.8, 6.8, sill + 4.6, sill + 5.6, Z(3.64), Z(3.92), 'antiglare')
        m.box('body', -2.4, 2.4, sill + 5.6, sill + 8.0, Z(3.80), Z(3.84), 'hud')
        m.box('body', -4.4, 4.4, sill, sill + 2.0, Z(4.05), Z(4.6), 'seat')
        m.box('body', -4.0, 4.0, sill + 2.0, sill + 7.0, Z(4.42), Z(4.66), 'seat')
        m.box('body', -3.3, 3.3, sill + 6.0, sill + 8.4, Z(4.38), Z(4.68), 'seat_head')
        m.box('body', -1.2, 1.2, sill, sill + 3.0, Z(3.65), Z(3.85), 'panel')
        for sx in (-1, 1):
            m.box('body', sx * 5.6, sx * 7.4, sill, sill + 2.6, Z(3.6), Z(4.6), 'panel')
    # canopy top stands slightly proud of the spine so a seated player fits
    top = H(2.95)
    m.wedge('body', Z(5.05), top - 0.4, Z(6.7), H(2.66), H(2.45), 11.0, 'skin', lod=full)

    # ---- canopy (hinges to the right in reality); glass is translucent
    m.wedge('canopy', Z(2.98), H(1.84), Z(3.72), top - 0.2, H(1.80), 14.0, 'glass', thick=0.8)
    m.section('canopy', Z(3.68), Z(5.10), 0, (sill - 2.5 + top) / 2, 15.4, top - sill + 2.5, 4.4, 0.2, mat='glass',
              ends=(False, True), skip_bottom=True)
    if full:
        for x in (3.68, 4.40, 5.08):
            m.box('canopy', -3.4, 3.4, top - 0.6, top + 0.4, Z(x) - 0.5, Z(x) + 0.5, 'frame')
            for sx in (-1, 1):
                m.box('canopy', sx * 7.0, sx * 7.9, sill - 2.6, top - 4.2, Z(x) - 0.5, Z(x) + 0.5, 'frame')
                m.centered('canopy', (sx * 5.5, top - 2.2, Z(x)), (4.8, 1.0, 1.0), 'frame', rotation=(0, 0, sx * 45))
        for sx in (-1, 1):
            m.box('canopy', sx * 6.9, sx * 7.9, sill - 2.8, sill + 0.2, Z(3.1), Z(5.1), 'frame')
            m.slab('canopy', (sx * 6.8, H(1.93), Z(3.08)), (sx * 6.0, top - 0.6, Z(3.70)), 1.0, 1.0, 'frame')
        m.box('canopy', -0.7, 0.7, top - 0.6, top + 1.8, Z(4.3), Z(4.6), 'frame')        # periscope
        m.box('canopy', -1.1, 1.1, top + 1.6, top + 2.6, Z(4.25), Z(4.65), 'mirror')
        # armoured front panel
        m.slab('canopy', (0, H(1.93) + 0.2, Z(3.06)), (0, top - 0.2, Z(3.68)), 4.0, 0.9, 'frame')

    # ---- engine nacelles R-95Sh: tall oval intakes at x 5.8, nozzles at x 11.65
    for side, bone in ((1, 'engine_l'), (-1, 'engine_r')):
        nac = [  # x, centre y (m), z bottom, z top, width
            (5.80, 0.92, 1.18, 2.08, 0.70),
            (6.30, 0.92, 1.08, 2.20, 0.80),
            (8.50, 0.90, 1.05, 2.24, 0.80),
            (10.5, 0.82, 1.10, 2.10, 0.74),
            (11.3, 0.74, 1.20, 1.92, 0.66),
        ]
        ns = [(Z(x), side * cy * M, (H(zb) + H(zt)) / 2, w * M, (zt - zb) * M, 0.22 * M, 0.26 * M)
              for x, cy, zb, zt, w in nac]
        if lod:
            ns = [ns[i] for i in (0, 2, 4)]
        m.loft(bone, ns)
        # fill the V between nacelle and fuselage sides (top and bottom)
        m.box(bone, side * 0.40 * M, side * 1.0 * M, H(1.04), H(2.20), Z(5.74), Z(11.1), 'skin')
        # fairing between the boom and the nozzle can
        m.box(bone, side * 0.30 * M, side * 0.62 * M, H(1.30), H(1.84), Z(10.9), Z(11.55), 'skin')
        ix = side * 0.92 * M
        m.box(bone, ix - 4.5, ix + 4.5, H(1.25), H(2.0), Z(5.74), Z(5.82), 'intake')
        if full:
            m.box(bone, ix - 5.6, ix + 5.6, H(2.0), H(2.12), Z(5.70), Z(6.2), 'skin')
            m.box(bone, ix - 5.6, ix + 5.6, H(1.13), H(1.25), Z(5.70), Z(6.2), 'skin')
            m.box(bone, ix + side * 4.4, ix + side * 5.6, H(1.2), H(2.06), Z(5.70), Z(6.2), 'skin')
            m.box(bone, ix - side * 5.6, ix - side * 4.4, H(1.2), H(2.06), Z(5.70), Z(6.2), 'skin')
        # nozzle: metal can, sooty interior, exit at the code's exhaust point
        nx, ny = side * 0.71 * M, H(1.54)
        m.section(bone, Z(NOZZLE_X) - 10, Z(NOZZLE_X) - 3, nx, ny, 10.4, 10.4, 3.0, mat='nozzle', ends=(False, True),
                  lod=full)
        m.section(bone, Z(NOZZLE_X) - 3, Z(NOZZLE_X), nx, ny, 9.6, 9.6, 2.8, mat='nozzle', ends=(True, True))
        m.box(bone, nx - 3.6, nx + 3.6, ny - 3.6, ny + 3.6, Z(NOZZLE_X) - 0.2, Z(NOZZLE_X) + 0.05, 'nozzle_in')

    # ---- wings: high-mounted, LE 19.6 deg, unswept TE at x 10.15, anhedral 2.5 deg
    root_y, tip_y = 1.30, 6.95
    wing_z = H(2.25)

    def le(y):
        return 7.15 + (y - 1.36) * math.tan(math.radians(19.6))

    def chord(y):
        return 10.15 - le(y)

    def wy(y):
        return wing_z - (y - root_y) * M * math.tan(math.radians(2.5))

    for side, bone in ((1, 'wing_l'), (-1, 'wing_r')):
        cubes = m.panel(bone, (root_y * M, Z(le(root_y)), chord(root_y) * M), (tip_y * M, Z(le(tip_y)), chord(tip_y) * M),
                        4.2, 'skin', side=side, strips=3 if full else 1, root_in=0.85 * M)
        place_panel(cubes, wing_z, -2.5, side, root_y * M)
        # wing-root glove over the nacelle: closes the slot under the swept root
        m.box(bone, side * 0.95 * M, side * (root_y + 0.35) * M, H(1.95), wing_z - 1.6, Z(le(root_y) + 0.15),
              Z(10.05), 'skin')
        # wingtip pod with split 'crocodile' airbrakes, landing light at the front
        py, px = wy(6.93), side * 6.93 * M
        m.box(bone, px - 4.0, px + 4.0, py - 3.0, py + 2.6, Z(8.75), Z(10.45), 'skin')
        m.box(bone, px - 3.0, px + 3.0, py - 2.2, py + 1.8, Z(8.55), Z(8.75), 'skin', lod=full)
        m.box(bone, px - 1.8, px + 1.8, py - 1.4, py + 1.0, Z(8.50), Z(8.55), 'light_white', lod=full)
        m.box(bone, px - 3.0, px + 3.0, py - 0.25, py + 0.25, Z(10.45), Z(10.65), 'skin', lod=full)
        m.box(bone, px + side * 4.0, px + side * 4.3, py - 0.6, py + 0.6, Z(9.0), Z(9.3),
              'light_green' if side > 0 else 'light_red', lod=full)
        if full:
            # decorative outer pylons (the functional pair is pylon_l/pylon_r)
            for yy in (2.82, 3.61, 4.37, 5.10):
                yw = wy(yy) - 2.0
                zc = Z(le(yy) + chord(yy) * 0.40)
                m.box(bone, side * yy * M - 0.9, side * yy * M + 0.9, yw - 5.6, yw, zc - 12.0, zc + 10.0, 'pylon')

    # functional pylons hang to y 2.2 m (35.2 px) where the stores attach
    for bone, x in (('pylon_l', 33.6), ('pylon_r', -33.6)):
        yw = wy(2.1) - 1.6
        m.box(bone, x - 1.0, x + 1.0, 35.2, yw + 0.4, -3.2 - 15.0, -3.2 + 14.0, 'pylon')
        if full:
            m.box(bone, x - 1.4, x + 1.4, 35.2, 36.6, -3.2 - 11.0, -3.2 + 9.0, 'metal')

    # ---- tail: fin with split rudder on the boom, tailplane with 5 deg dihedral
    spine = H(2.65)
    fin_h = 2.45
    fin = m.panel('tail_fin', (0, Z(11.2), 3.0 * M), (fin_h * M, Z(13.0), 0.8 * M), 3.0, 'skin', side=1,
                  strips=3 if full else 1, skip_root=False, root_in=0.3 * M)
    for c in fin:
        _upright(c, spine)
    if full:
        m.box('tail_fin', -1.0, 1.0, spine + 2.1 * M, spine + 2.3 * M, Z(13.55), Z(13.75), 'light_white')
        m.wedge('tail_fin', Z(9.9), spine + 0.3, Z(11.6), spine + 4.4, spine - 1.6, 3.0, 'skin')      # fillet
        m.box('tail_fin', -1.6, 1.6, spine - 2.0, spine + 1.0, Z(14.6), Z(15.6), 'skin')           # chute can
    for side, bone in ((1, 'stabilizer_l'), (-1, 'stabilizer_r')):
        tip_le = 12.52 + 2.03 * math.tan(math.radians(24.0))
        cubes = m.panel(bone, (0.30 * M, Z(12.52), 1.85 * M), (2.33 * M, Z(tip_le), 0.85 * M), 2.8, 'skin',
                        side=side, strips=2 if full else 1, thin_tip=1.8, root_in=0.25 * M)
        place_panel(cubes, H(2.60), 5.0, side, 0.30 * M)

    _gear(m, nose_gear, main_gear, full)
    return m


def _upright(c, base_y):
    """Rotate a planform built in the XZ plane into the YZ plane (fin)."""
    ox, oy, oz = c.origin
    sx, sy, sz = c.size
    px, py, pz = c.pivot
    # local box is centred on its pivot; swap X/Y extents and move the pivot.
    c.origin = [-sy / 2, base_y + px - sx / 2, oz]
    c.size = [sy, sx, sz]
    c.pivot = [0.0, base_y + px, pz]
    ry = c.rotation[1] if c.rotation else 0.0
    # a yaw sweep in the planform becomes a rotation about X for the fin
    c.rotation = [ry, 0.0, 0.0]


def _gear(m, nose, main, full):
    # nose leg retracts forward into the bay under the cockpit; 0.63 m wheel
    nx, ny, nz = nose
    m.box('gear_nose', nx - 1.0, nx + 1.0, 6.0, ny, nz - 1.0, nz + 1.0, 'strut')
    m.box('gear_nose', nx - 1.8, nx + 1.8, 0.0, 10.1, nz - 5.05, nz + 5.05, 'tire')
    if full:
        m.box('gear_nose', nx - 2.0, nx + 2.0, 3.6, 6.4, nz - 1.8, nz + 1.8, 'hub')
        m.box('gear_nose', nx - 3.0, nx + 3.0, 10.1, 11.0, nz - 5.6, nz + 3.6, 'skin')   # mudguard
        m.box('gear_nose', nx - 1.5, nx + 1.5, 14.0, ny - 3.0, nz - 1.4, nz + 1.4, 'strut')  # oleo
        m.box('body', nx - 3.4, nx + 3.4, H(0.83), H(0.95), Z(4.25), Z(5.55), 'well')
    for side, bone in ((1, 'gear_main_l'), (-1, 'gear_main_r')):
        x, y, z = side * main[0], main[1], main[2]
        # levered leg on the nacelle, the 0.8 m wheel trailing behind it
        m.box(bone, x - 1.3, x + 1.3, 7.0, y, z - 1.3, z + 1.3, 'strut')
        m.box(bone, x - 1.0, x + 1.0, 5.8, 8.2, z - 0.5, z + 4.6, 'strut')
        wz = z + 4.0
        m.box(bone, x - 2.9, x + 2.9, 0.0, 12.8, wz - 6.4, wz + 6.4, 'tire')
        if full:
            m.box(bone, x - 3.1, x + 3.1, 4.4, 8.4, wz - 2.1, wz + 2.1, 'hub')
            m.box(bone, x - 1.8, x + 1.8, 14.0, y - 3.0, z - 1.8, z + 1.8, 'strut')
            m.box(bone, x - 0.6, x + 0.6, 10.0, y - 2.0, z - 4.5, z - 3.3, 'strut')     # drag strut


PALETTE = {
    'laser': '#1f2a2f', 'metal': '#8d8f8c', 'antiglare': '#2f3330', 'cockpit': '#3f8f8a',
    'panel': '#1d2322', 'hud': '#6e9286', 'seat': '#3a3b38', 'seat_head': '#8a2d23',
    'mirror': '#c8d2d6', 'dielectric': '#6f7a6a', 'hinge': '#4a4f40', 'light_white': '#f2f0dc', 'light_red': '#d23a2a', 'light_green': '#38b24a',
    'glass': '#5f7d86', 'canopy_frame': '#5a6248', 'hub': '#5c7a4f', 'strut': '#a9aea4', 'well': '#8f9a86',
}
CAMO = ['#c2a979', '#8a6a46', '#7b8752', '#4f5b37']
BELLY = '#a9c2cf'


def painter():
    def camo(p, n):
        return splotch_camo(p, n, CAMO, BELLY, scale=27.0, seed=25,
                            belly_line=lambda q: H(1.30) + 2.0 * np.sin(q[:, 2] / 9.0))

    frames = [Z(x) for x in (2.3, 3.1, 5.15, 5.9, 7.4, 9.0, 10.3, 11.7, 12.9, 14.1)]

    def panel_lines(p, n, cube):
        side = np.abs(n[1]) < 0.6
        m = stations_mask(p[:, 2], frames, 0.5) & (np.abs(p[:, 0]) < 22)
        wing = np.abs(p[:, 0]) > 21
        m |= wing & ~side & (np.mod(p[:, 0] + 7, 21.0) < 0.6)
        m |= wing & ~side & stations_mask(p[:, 2], [Z(8.3)], 0.45) & (n[1] > 0.5)
        return m

    star = star_mask(48)
    decals = []
    for sx in (1, -1):
        # red stars on the fin both sides and under the wings
        decals.append(Decal((sx * 2.0, H(2.65) + 1.15 * M, Z(12.75)), (sx, 0, 0), (0, 0, sx), (0, -1, 0), (15, 15),
                            [(star, (196, 40, 32))], max_depth=3))
        decals.append(Decal((sx * 4.6 * M, H(2.0), Z(8.95)), (0, -1, 0), (-1, 0, 0), (0, 0, 1), (19, 19),
                            [(star, (196, 40, 32))], max_depth=10))
        # bort number on the nose sides
        decals.append(Decal((sx * 7.0, H(1.42), Z(2.55)), (sx, 0, 0), (0, 0, sx), (0, -1, 0),
                            (12, 6.5), [(text_mask('07'), (225, 225, 220))], max_depth=4))
        # red intake warning stripe on the nacelle sides
        decals.append(Decal((sx * 20.0, H(1.62), Z(6.15)), (sx, 0, 0), (0, 0, sx), (0, -1, 0), (5, 14),
                            [(np.ones((1, 1), bool), (180, 40, 30))], max_depth=2.0))
    return Painter(PALETTE, camo, panel_lines, decals)
