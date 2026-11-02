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

Animated bones (parent body, pivot on the hinge, modelled neutral/closed):
elevon_left/right (wing TE aft of x 12.70), canard_left/right (pivot at 40 %
root chord), rudder (swept hinge, pivot at mid height), airbrake_left/right
(aft fuselage petals, vertical hinge at the front edge), canopy (rear hinge)
and the fixed windscreen, gear_nose (folds aft), gear_main_l/r (fold forward)
and gear_door_nose_l/r, gear_door_left_l, gear_door_right_r.
Deviation: the mains fold forward about X only, so they stand at +-0.89 m
(track 1.78 m instead of 2.61 m) under fixed bay fairings beneath the wing
root, where the stowed wheels fit; the legs rake 8 deg so they lie flat at
the renderer's 82 deg fold.
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

HINGE = 12.70          # elevon hinge line (x, m); fixed wing TE
EL_IN, EL_SPLIT, EL_OUT = 1.09, 2.55, 4.0      # elevon span stations (m)
TIP_ROOT = 4.15        # fixed wing tip panel (full chord) outboard of the elevons
RUD_H1, RUD_H2, FIN_TIP = 0.30, 1.95, 2.28     # rudder bottom/top, fin height above its base (m)
RUD_CHORD = 0.55


def Z(x):
    return NOSE + x * M


def H(z):
    return (z + LIFT) * M


def wing_le(y):
    return 7.3 + (y - 0.67) * (12.4 - 7.3) / (4.875 - 0.67)


def wing_te(y):
    return 13.25 + (y - 0.67) * (13.08 - 13.25) / (4.875 - 0.67)


def fin_te(h):
    return 15.66 + (16.43 - 15.66) * h / FIN_TIP


def fin_hinge(h):
    # rudder hinge parallel to the swept fin TE (constant 0.55 m rudder chord)
    return fin_te(h) - RUD_CHORD


# pivots (geo px)
NOSE_PIVOT = (0.0, 33.0, Z(5.45))
MAIN_X, MAIN_Y, MAIN_AXLE_Z = 14.2, 32.8, Z(9.95)
MAIN_RAKE = 8.0                                    # deg, wheel ahead of the pivot
WHEEL_R = 5.0                                      # main tyre radius (0.62 m tyre)
MAIN_PIVOT_Z = MAIN_AXLE_Z + (MAIN_Y - WHEEL_R) * math.tan(math.radians(MAIN_RAKE))
FAIR_Y0 = 26.6                                     # main bay fairing bottom
NOSE_DOOR = (3.4, Z(5.45) - 3.16, Z(7.39))         # half width, z front, z rear
MAIN_DOOR = (11.5, 17.3, Z(8.0), Z(10.36))         # inner x, hinge x, z front, z rear


def build(lod=False):
    full = not lod
    m = Model('j10_lod' if lod else 'j10')
    m.bone('body', None)
    m.bone('pilot_anchor', 'body', (0, 40.64, -63.84))
    for name in ('wing_l', 'wing_r', 'tail_fin', 'chin_intake', 'engine'):
        m.bone(name)
    wing_y = H(1.85)
    sill = H(2.72)
    canard_pivot = (0.62 * M, H(2.33), Z(4.88 + 0.4 * 2.14))
    m.bone('canard_left', 'body', canard_pivot)
    m.bone('canard_right', 'body', (-canard_pivot[0], canard_pivot[1], canard_pivot[2]))
    el_mid = (EL_IN * M + EL_OUT * M) / 2
    m.bone('elevon_left', 'body', (el_mid, wing_y, Z(HINGE)))
    m.bone('elevon_right', 'body', (-el_mid, wing_y, Z(HINGE)))
    hm = (RUD_H1 + RUD_H2) / 2
    base = H(3.2)
    m.bone('rudder', 'body', (0.0, base + hm * M, Z(fin_hinge(hm))))
    brake = (0.69 * M, (40.98 + 47.8) / 2, Z(13.25))
    m.bone('airbrake_left', 'body', brake)
    m.bone('airbrake_right', 'body', (-brake[0], brake[1], brake[2]))
    m.bone('windscreen', 'body', (0.0, sill, Z(3.2)))
    m.bone('canopy', 'body', (0.0, sill, Z(5.75)))
    main = (MAIN_X, MAIN_Y, MAIN_PIVOT_Z)
    m.bone('gear_main_l', 'body', main)
    m.bone('gear_main_r', 'body', (-main[0], main[1], main[2]))
    m.bone('gear_nose', 'body', NOSE_PIVOT)
    nose_door_y = (H(1.16) if full else H(1.175)) - 0.15         # 0.15 px proud of the trunk belly
    nhw, nz0, nz1 = NOSE_DOOR
    m.bone('gear_door_nose_l', 'body', (nhw, nose_door_y + 0.225, (nz0 + nz1) / 2))
    m.bone('gear_door_nose_r', 'body', (-nhw, nose_door_y + 0.225, (nz0 + nz1) / 2))
    mx0, mx1, mz0, mz1 = MAIN_DOOR
    m.bone('gear_door_left_l', 'body', (mx1, FAIR_Y0 - 0.15 + 0.2, (mz0 + mz1) / 2))
    m.bone('gear_door_right_r', 'body', (-mx1, FAIR_Y0 - 0.15 + 0.2, (mz0 + mz1) / 2))
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

    # ---- cockpit (body), fixed windscreen and the rear-hinged gold-tinted canopy
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
    m.wedge('windscreen', Z(2.78), H(2.70), Z(3.57), H(3.13), H(2.55), 13.2, 'glass', thick=0.8)
    if full:
        m.box('windscreen', -6.4, 6.4, H(3.08), H(3.15), Z(3.50), Z(3.60), 'frame')      # windscreen arch
        for sx in (-1, 1):
            m.slab('windscreen', (sx * 6.8, H(2.71), Z(2.80)), (sx * 6.0, H(3.12), Z(3.55)), 1.0, 1.0, 'frame')
            m.box('windscreen', sx * 6.6, sx * 7.6, sill - 0.8, sill + 0.2, Z(2.80), Z(3.58), 'frame')
    bubble = [(3.55, 3.12, 14.2), (3.95, 3.27, 14.4), (4.45, 3.35, 14.4), (5.00, 3.30, 14.2), (5.40, 3.17, 13.6),
              (5.75, 3.03, 12.6)]
    if lod:
        bubble = bubble[::2]
    m.loft('canopy', [(Z(x), 0, (sill + H(top)) / 2, w, H(top) - sill, min(0.42 * w, (H(top) - sill) * 0.62), 0.1)
                      for x, top, w in bubble], mat='glass', skip_bottom=True)
    if full:
        for sx in (-1, 1):   # canopy sill rails
            m.box('canopy', sx * 6.6, sx * 7.6, sill - 0.8, sill + 0.2, Z(3.62), Z(5.8), 'frame')
        m.box('canopy', -6.2, 6.2, sill, H(3.06), Z(5.66), Z(5.8), 'frame')

    # ---- DSI chin intake trunk
    trunk = [
        (Z(3.95), 0, (H(1.19) + H(1.86)) / 2, 1.42 * M, 0.67 * M, 0.12 * M, 0.26 * M),
        (Z(4.7), 0, (H(1.16) + H(1.82)) / 2, 1.44 * M, 0.66 * M, 0.12 * M, 0.26 * M),
        (Z(7.4), 0, (H(1.16) + H(1.80)) / 2, 1.40 * M, 0.64 * M, 0.12 * M, 0.24 * M),
        (Z(9.4), 0, (H(1.24) + H(1.66)) / 2, 1.20 * M, 0.42 * M, 0.10 * M, 0.14 * M),
        (Z(10.6), 0, (H(1.36) + H(1.50)) / 2, 0.90 * M, 0.14 * M, 0.04 * M, 0.04 * M),
    ]
    m.loft('chin_intake', trunk if full else [trunk[i] for i in (0, 2, 3, 4)])
    # fill the groove where the wider trunk meets the forward fuselage
    m.box('chin_intake', -11.0, 11.0, H(1.62), H(1.86), Z(3.95), Z(9.2), 'skin')
    m.box('chin_intake', -9.6, 9.6, H(1.24), H(1.80), Z(3.90), Z(3.98), 'intake')
    if full:
        # 'smile' lower lip swept forward, DSI bump on the forward fuselage underside
        m.box('chin_intake', -9.6, 9.6, H(1.12), H(1.32), Z(3.80), Z(4.5), 'skin')
        m.box('chin_intake', -7.0, 7.0, H(1.15), H(1.22), Z(3.70), Z(3.80), 'skin')
        m.section('chin_intake', Z(3.55), Z(4.55), 0, H(1.72), 11.0, 3.2, 1.1, 1.1)
        for sx in (-1, 1):   # intake-corner pylons
            m.box('chin_intake', sx * 8.4 - 0.9, sx * 8.4 + 0.9, H(1.00), H(1.40), Z(6.4), Z(7.55), 'pylon')

    # ---- main gear bay fairings under the wing root (the stowed wheels lie here)
    for side in (1, -1):
        if full:
            m.section('body', Z(7.62), Z(7.95), side * 12.6, 33.6, 9.0, 6.0, 0.0, 1.0)
            m.section('body', Z(10.45), Z(10.85), side * 12.6, 33.6, 9.0, 6.0, 0.0, 1.0)
        m.section('body', Z(7.95), Z(10.45), side * 13.1, (FAIR_Y0 + 36.6) / 2, 11.0, 36.6 - FAIR_Y0, 0.0, 1.0)

    # ---- all-moving canards on the trunk sides, 8 deg dihedral
    for side in (1, -1):
        bone = 'canard_left' if side > 0 else 'canard_right'
        c = m.panel(bone, (0.62 * M, Z(4.88), 2.14 * M), (2.21 * M, Z(7.02), 0.44 * M), 2.4, 'skin', side=side,
                    strips=2 if full else 1, root_in=0.22 * M)
        place_panel(c, H(2.33), 8.0, side, 0.62 * M)

    # ---- delta wing: fixed part to the elevon hinge, full-chord tip, elevons
    for side in (1, -1):
        bone = 'wing_l' if side > 0 else 'wing_r'
        y0, y1 = 0.62, 4.875
        cubes = m.panel(bone, (y0 * M, Z(wing_le(y0)), (HINGE - wing_le(y0)) * M),
                        (TIP_ROOT * M, Z(wing_le(TIP_ROOT)), (HINGE - wing_le(TIP_ROOT)) * M), 3.2, 'skin',
                        side=side, strips=4 if full else 2, root_in=0.3 * M)
        place_panel(cubes, wing_y, 0.0, side, y0 * M)
        tip = m.panel(bone, (TIP_ROOT * M, Z(wing_le(TIP_ROOT)), (wing_te(TIP_ROOT) - wing_le(TIP_ROOT)) * M),
                      (y1 * M, Z(wing_le(y1)), (wing_te(y1) - wing_le(y1)) * M), 3.12, 'skin', side=side,
                      strips=1, skip_root=False, root_in=(TIP_ROOT - EL_OUT) * M - 0.1)
        place_panel(tip, wing_y, 0.0, side, TIP_ROOT * M)
        if full:
            m.box(bone, side * (y1 * M - 0.6), side * (y1 * M + 0.8), wing_y - 0.8, wing_y + 0.8, Z(12.45),
                  Z(12.9), 'light_green' if side > 0 else 'light_red')
            for yy in (2.75, 3.76):      # outer pylons (decorative), ahead of the elevon hinge
                z0 = Z(wing_le(yy) - 0.1)
                m.box(bone, side * yy * M - 0.9, side * yy * M + 0.9, wing_y - 6.0, wing_y - 1.0,
                      z0, min(z0 + 24.0, Z(HINGE - 0.25)), 'pylon')
        # elevon: inner and outer surfaces on one hinge, tucked 0.8 px into the wing
        el = 'elevon_left' if side > 0 else 'elevon_right'
        spans = ((EL_IN, EL_SPLIT), (EL_SPLIT, EL_OUT)) if full else ((EL_IN, EL_OUT),)
        for a, b in spans:
            xa, xb = a * M + (0.02 if a == EL_IN else 0.0), b * M - (0.1 if b == EL_OUT else 0.0)
            m.box(el, side * xa, side * xb, wing_y - 1.3, wing_y + 1.3, Z(HINGE) - 0.8, Z(wing_te((a + b) / 2)),
                  'skin')

    # functional pylons: stores hang from y 2.2 at z -0.2. The attach point is ahead of the wing
    # LE here, so the pylon's nose fairing rises into the leading edge and the body runs under the wing.
    for name, x in (('pylon_l', 33.6), ('pylon_r', -33.6)):
        m.wedge(name, -9.0, 35.85, Z(wing_le(2.1)) + 1.6, wing_y - 0.1, 35.3, 2.0, 'pylon', cx=x, thick=0.5)
        m.box(name, x - 1.0, x + 1.0, 35.3, wing_y - 0.9, Z(wing_le(2.1)) - 1.0, Z(11.2), 'pylon')
        m.box(name, x - 0.7, x + 0.7, 35.6, wing_y - 0.9, Z(11.2), Z(11.45), 'pylon')
        m.box(name, x - 1.25, x + 1.25, 35.2, 35.7, -8.0, 4.4, 'rail')

    # ---- aft fuselage beams, airbrake petals and nozzle
    aft = [(Z(14.2), 0, (H(1.53) + H(2.70)) / 2, 1.36 * M, 1.17 * M, 0.30 * M, 0.30 * M),
           (Z(14.7), 0, (H(1.47) + H(2.63)) / 2, 1.30 * M, 1.16 * M, 0.32 * M, 0.32 * M)]
    m.loft('engine', aft)
    ny = H(2.05)
    m.section('engine', Z(14.7), Z(15.3), 0, ny, 18.4, 18.4, 5.4, mat='nozzle', ends=(False, False))
    m.section('engine', Z(15.3), Z(EXIT), 0, ny, 15.4, 15.4, 4.5, mat='nozzle', ends=(False, True))
    m.box('engine', -5.4, 5.4, ny - 5.4, ny + 5.4, Z(EXIT) - 0.3, Z(EXIT) + 0.02, 'nozzle_in')
    for side in (1, -1):  # aft 'beam' fairings beside the engine
        m.box('engine', side * 7.0, side * 1.09 * M, H(1.72), H(2.06), Z(12.4), Z(15.4), 'skin')
        # petal on the fuselage side above the beam; hinge at its front edge
        m.box('airbrake_left' if side > 0 else 'airbrake_right', side * brake[0], side * (brake[0] + 0.8),
              40.98, 47.8, Z(13.25), Z(14.15), 'skin')

    # ---- tall fin with rudder, canted ventral fins
    def fin_le(h):
        return 12.06 + (15.32 - 12.06) * h / FIN_TIP

    fin = m.panel('tail_fin', (0, Z(fin_le(0)), (fin_hinge(0) - fin_le(0)) * M),
                  (FIN_TIP * M, Z(fin_le(FIN_TIP)), (fin_hinge(FIN_TIP) - fin_le(FIN_TIP)) * M), 3.0, 'skin', side=1,
                  strips=3 if full else 1, skip_root=False, root_in=0.45 * M)
    for c in fin:
        _upright(c, base)
    # constant-chord band behind the hinge: fixed below and above the rudder, the rudder between.
    # One box per piece along the swept TE, so neighbouring end faces share one plane.
    sweep = math.atan((16.43 - 15.66) / FIN_TIP)
    corner = RUD_CHORD / 2 * math.sin(sweep) * math.cos(sweep)      # end-face corner offset (m)

    def band(bone, u0, u1, thick, tuck=0.0):
        um = (u0 + u1) / 2
        length = (u1 - u0) * M / math.cos(sweep)
        d = (RUD_CHORD * M + tuck) * math.cos(sweep)
        # a tuck widens the box forward, normal to its axis, so its end planes stay where the fills' are
        off = tuck * math.cos(sweep) / 2
        uc = um * M + off * math.sin(sweep)
        zc = Z(fin_te(um)) - RUD_CHORD * M / 2 - off * math.cos(sweep)
        c = m.cube(bone, (uc - length / 2, -thick / 2, zc - d / 2), (length, thick, d), 'skin',
                   pivot=(uc, 0, zc), rotation=(0, -math.degrees(sweep), 0))
        _upright(c, base)

    band('tail_fin', -0.6, RUD_H1 - 0.0005, 2.8)
    m.box('tail_fin', -1.4, 1.4, H(2.55), H(2.95), Z(14.4), Z(15.3), 'skin')    # closes the fin foot over the nozzle
    band('rudder', RUD_H1 + 0.0005, RUD_H2 - 0.0005, 2.4, tuck=0.8)
    band('tail_fin', RUD_H2 + 0.0005, FIN_TIP - corner, 2.8)
    # fin-tip cap closes the aft corner the slanted band end leaves under the tip
    m.box('tail_fin', -1.25, 1.25, base + (FIN_TIP - 2 * corner - 0.02) * M, base + (FIN_TIP + 0.02) * M,
          Z(fin_hinge(FIN_TIP) + 0.1), Z(16.47), 'skin')
    if full:
        m.box('tail_fin', -1.9, 1.9, H(2.66), base + 0.4, Z(11.95), Z(14.7), 'skin')      # fin root fairing
        m.box('tail_fin', -0.5, 0.5, base + (FIN_TIP - 0.12) * M, base + (FIN_TIP - 0.04) * M, Z(16.47),
              Z(16.52), 'light_white')
    for side in (1, -1):
        m.centered('body', (side * 1.0 * M, H(1.52), Z(14.1)), (0.9, 0.78 * M, 1.6 * M), 'skin',
                   rotation=(0, 0, -side * 30), lod=full)

    _gear(m, full, nose_door_y)
    return m


def _upright(c, base_y):
    sx, sy, sz = c.size
    px, py, pz = c.pivot
    c.origin = [-sy / 2, base_y + px - sx / 2, pz - sz / 2]
    c.size = [sy, sx, sz]
    c.pivot = [0.0, base_y + px, pz]
    c.rotation = [c.rotation[1] if c.rotation else 0.0, 0.0, 0.0]


def _gear(m, full, nose_door_y):
    # nose leg: vertical, folds 88 deg aft into the trunk; two doors on the trunk belly
    nx, ny, nz = NOSE_PIVOT
    m.box('gear_nose', nx - 0.9, nx + 0.9, 4.4, ny, nz - 0.9, nz + 0.9, 'strut')
    m.box('gear_nose', nx - 1.7, nx + 1.7, 0.0, 8.8, nz - 4.4, nz + 4.4, 'tire')
    if full:
        m.box('gear_nose', nx - 1.9, nx + 1.9, 3.0, 5.8, nz - 1.4, nz + 1.4, 'hub')
        m.box('gear_nose', nx - 1.5, nx + 1.5, 10.0, ny - 2.0, nz - 1.5, nz + 1.5, 'strut')
        m.box('gear_nose', nx - 2.4, nx + 2.4, 8.8, 9.6, nz - 4.0, nz + 3.0, 'strut')    # fork / mudguard
        m.box('gear_nose', nx - 0.5, nx + 0.5, 12.0, 20.0, nz - 2.6, nz - 1.5, 'strut')   # torque link
    hw, z0, z1 = NOSE_DOOR
    for name, sx in (('gear_door_nose_l', 1), ('gear_door_nose_r', -1)):
        m.box(name, 0.0, sx * hw, nose_door_y, nose_door_y + 0.45, z0, z1, 'skin')
    if full:   # bay lining, hidden by the closed doors
        m.box('body', -hw + 0.2, hw - 0.2, nose_door_y + 0.07, nose_door_y + 0.3, z0 + 0.2, z1 - 0.2, 'well')

    # main legs: raked 8 deg (wheel ahead), fold 82 deg forward into the bay fairings
    mx0, mx1, mz0, mz1 = MAIN_DOOR
    for side in (1, -1):
        name = 'gear_main_l' if side > 0 else 'gear_main_r'
        x, y, zp, za = side * MAIN_X, MAIN_Y, MAIN_PIVOT_Z, MAIN_AXLE_Z
        top = (x, y + 0.6, zp + 0.6 * math.tan(math.radians(MAIN_RAKE)))
        axle = (x, WHEEL_R, za)
        mid = tuple(top[i] + 0.55 * (axle[i] - top[i]) for i in range(3))
        m.slab(name, top, axle, 2.2, 2.2, 'strut')
        m.box(name, x - 2.3, x + 2.3, 0.0, 2 * WHEEL_R, za - WHEEL_R, za + WHEEL_R, 'tire')
        if full:
            m.slab(name, top, mid, 3.2, 3.2, 'strut')
            m.box(name, x - 2.5, x + 2.5, WHEEL_R - 2.0, WHEEL_R + 2.0, za - 1.7, za + 1.7, 'hub')
            m.slab(name, (x, y - 4.0, zp - 2.0), (x, y - 15.0, za - 1.0), 0.8, 0.8, 'strut')   # drag brace
        door = 'gear_door_left_l' if side > 0 else 'gear_door_right_r'
        m.box(door, side * mx0, side * mx1, FAIR_Y0 - 0.15, FAIR_Y0 + 0.25, mz0, mz1, 'skin')
        if full:
            m.box('body', side * (mx0 + 0.2), side * (mx1 - 0.2), FAIR_Y0 - 0.08, FAIR_Y0 + 0.15, mz0 + 0.2,
                  mz1 - 0.2, 'well')


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
        # fin insignia ahead of the rudder hinge
        decals.append(Decal((sx * 2.0, H(4.2), Z(14.6)), (sx, 0, 0), (0, 0, sx), (0, -1, 0), (26, 9.5), insignia,
                            max_depth=3))
        decals.append(Decal((sx * 3.3 * M, H(1.6), Z(11.8)), (0, -1, 0), (-1, 0, 0), (0, 0, 1), (22, 8),
                            insignia, max_depth=8))
        decals.append(Decal((sx * 9.6, H(2.32), Z(2.25)), (sx, 0, 0), (0, 0, sx), (0, -1, 0), (17, 4.6),
                            [(text_mask('61523'), red)], max_depth=4))
    return Painter(PALETTE, camo, panel_lines, decals, glass_alpha=92)
