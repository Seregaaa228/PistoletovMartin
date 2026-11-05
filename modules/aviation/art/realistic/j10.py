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

from kit import Model, hexrgb, place_panel, smooth
from paint import (Decal, Painter, digital_camo, dilate, hatch, make_weather, outlined, rescue_arrow, rivet_rows,
                   side_decal, star_mask, stations_mask, text_mask, triangle_mask)

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
    else:
        rs, bs = smooth(rs, 3), smooth(bs[:5], 3) + bs[5:]
    m.loft('body', rs, mat='radome', round_=full)
    m.loft('body', bs, round_='top' if full else False)       # the trunk meets the lower corners
    m.box('body', -0.45, 0.45, H(2.03) - 0.45, H(2.03) + 0.45, Z(0.0), Z(0.95), 'metal')      # nose probe
    if full:
        m.box('body', -0.9, 0.9, H(2.03) - 0.9, H(2.03) + 0.9, Z(0.7), Z(1.0), 'metal')
        # IRST: a faceted ball on a short fairing ahead of the windscreen, starboard
        m.box('body', -4.4, -1.6, H(2.58), H(2.70), Z(2.32), Z(2.78), 'skin')
        m.prism('body', 'y', (-3.0, H(2.74), Z(2.55)), 1.55, 1.5, 'irst', n=4)
        m.prism('body', 'z', (-3.0, H(2.80), Z(2.55)), 0.75, 2.6, 'irst', n=4)
        # pitot vanes, AoA probes, blade antennas above and below, MAWS sensor bumps
        for sx in (-1, 1):
            m.box('body', sx * 0.4, sx * 1.5, H(2.03) - 0.08, H(2.03) + 0.08, Z(0.52), Z(0.66), 'metal')
            m.slab('body', (sx * 6.6, H(2.25), Z(2.6)), (sx * 8.3, H(2.25) - 0.3, Z(2.9)), 0.35, 0.35, 'metal')
            m.prism('body', 'x', (sx * 10.9, H(2.40), Z(6.3)), 0.9, 1.6, 'sensor', n=4)
            m.prism('body', 'x', (sx * 11.6, H(2.30), Z(12.9)), 0.8, 1.6, 'sensor', n=4)
        m.slab('body', (0, H(3.04) + 0.2, Z(7.4)), (0, H(3.04) + 2.9, Z(7.8)), 0.45, 2.2, 'antenna')
        m.slab('body', (0, H(1.40) - 0.1, Z(10.4)), (0, H(1.40) - 2.6, Z(10.7)), 0.4, 1.8, 'antenna')
        # chaff/flare dispensers under the tail and formation lights on the fin and the spine sides
        for sx in (-1, 1):
            m.box('body', sx * 2.5, sx * 6.5, H(1.445) - 0.8, H(1.445) + 0.5, Z(13.4), Z(14.1), 'dispenser')
            m.box('body', sx * 10.5, sx * 10.85, H(2.35), H(2.45), Z(9.0), Z(9.8), 'formation')
        m.slab('tail_fin', (0, H(3.2) + 8.0, Z(13.2)), (0, H(3.2) + 26.0, Z(14.6)), 3.1, 0.5, 'formation')
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
        # three MFDs, centre stick and throttle
        for x0, x1 in ((-5.2, -1.9), (-1.5, 1.5), (1.9, 5.2)):
            m.box('body', x0, x1, sill + 1.0, sill + 4.1, Z(3.62), Z(3.62) + 0.3, 'mfd')
        m.box('body', -0.45, 0.45, sill, sill + 4.2, Z(4.2), Z(4.32), 'panel')
        m.box('body', 5.4, 6.2, sill + 2.4, sill + 4.0, Z(4.0), Z(4.3), 'panel')
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
    bst = [(Z(x), 0, (sill + H(top)) / 2, w, H(top) - sill, min(0.42 * w, (H(top) - sill) * 0.62), 0.1)
           for x, top, w in bubble]
    m.loft('canopy', bst[::2] if lod else smooth(bst, 2), mat='glass', skip_bottom=True, round_=full)
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
    # (the belly under the nose-gear doors stays exactly as measured; only the tapering tail is smoothed)
    m.loft('chin_intake', trunk[:3] + smooth(trunk[2:], 2)[1:] if full else [trunk[i] for i in (0, 2, 3, 4)],
           round_='bottom' if full else False)
    # fill the groove where the wider trunk meets the forward fuselage
    m.box('chin_intake', -11.0, 11.0, H(1.62), H(1.86), Z(3.95), Z(9.2), 'skin')
    m.box('chin_intake', -9.6, 9.6, H(1.24), H(1.80), Z(3.90), Z(3.98), 'intake')
    if full:
        # 'smile' lower lip swept forward, DSI bump on the forward fuselage underside
        m.box('chin_intake', -9.6, 9.6, H(1.12), H(1.32), Z(3.80), Z(4.5), 'skin')
        m.box('chin_intake', -7.0, 7.0, H(1.15), H(1.22), Z(3.70), Z(3.80), 'lip')
        m.section('chin_intake', Z(3.55), Z(4.55), 0, H(1.72), 11.0, 3.2, 1.1, 1.1, round_=True)
        # bare-metal lip round the mouth (top and side lips; the 'smile' lower lip leads)
        m.box('chin_intake', -10.0, 10.0, H(1.80), H(1.85), Z(3.84), Z(4.05), 'lip')
        for sx in (-1, 1):
            m.box('chin_intake', sx * 9.6, sx * 10.7, H(1.30), H(1.80), Z(3.84), Z(4.05), 'lip')
            m.centered('chin_intake', (sx * 9.4, H(1.27), Z(3.95)), (2.4, 0.9, 0.21 * M), 'lip',
                       rotation=(0, 0, sx * 40))
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
                    strips=2 if full else 1, root_in=0.22 * M, le_round=full)
        place_panel(c, H(2.33), 8.0, side, 0.62 * M)

    # ---- delta wing: fixed part to the elevon hinge, full-chord tip, elevons
    for side in (1, -1):
        bone = 'wing_l' if side > 0 else 'wing_r'
        y0, y1 = 0.62, 4.875
        cubes = m.panel(bone, (y0 * M, Z(wing_le(y0)), (HINGE - wing_le(y0)) * M),
                        (TIP_ROOT * M, Z(wing_le(TIP_ROOT)), (HINGE - wing_le(TIP_ROOT)) * M), 3.2, 'skin',
                        side=side, strips=4 if full else 2, root_in=0.3 * M, le_round=full)
        place_panel(cubes, wing_y, 0.0, side, y0 * M)
        tip = m.panel(bone, (TIP_ROOT * M, Z(wing_le(TIP_ROOT)), (wing_te(TIP_ROOT) - wing_le(TIP_ROOT)) * M),
                      (y1 * M, Z(wing_le(y1)), (wing_te(y1) - wing_le(y1)) * M), 3.12, 'skin', side=side,
                      strips=1, skip_root=False, root_in=(TIP_ROOT - EL_OUT) * M - 0.1, le_round=full)
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
    m.loft('engine', aft, round_=full)
    ny = H(2.05)
    if lod:
        m.section('engine', Z(14.7), Z(15.3), 0, ny, 18.4, 18.4, 5.4, mat='nozzle', ends=(False, False))
        m.section('engine', Z(15.3), Z(EXIT), 0, ny, 15.4, 15.4, 4.5, mat='nozzle', ends=(False, True))
        m.box('engine', -5.4, 5.4, ny - 5.4, ny + 5.4, Z(EXIT) - 0.3, Z(EXIT) + 0.02, 'nozzle_in')
    else:
        # AL-31FN: round shroud with its actuator ring, 16 convergent flaps, sooty liner
        m.prism('engine', 'z', (0, ny, (Z(14.7) + Z(15.35)) / 2), 9.0, Z(15.35) - Z(14.7), 'nozzle', n=8,
                caps=(False, True))
        m.prism('engine', 'z', (0, ny, Z(15.0)), 9.3, 1.4, 'nozzle', n=8)
        m.petals('engine', 0, ny, Z(15.3), Z(EXIT), 8.5, 7.3, 16, 2.1, 'petal', tag=(0, ny, Z(15.3)))
        m.prism('engine', 'z', (0, ny, (Z(15.2) + Z(EXIT) - 1.2) / 2), 6.7, Z(EXIT) - 1.2 - Z(15.2), 'nozzle_in',
                n=6)
        m.box('engine', -1.4, 1.4, ny - 1.4, ny + 1.4, Z(EXIT) - 1.3, Z(EXIT) - 0.6, 'nozzle_in')
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
    if full:
        m.prism('gear_nose', 'x', (nx, 4.4, nz), 4.4, 3.4, 'tire')
        m.box('gear_nose', nx - 1.9, nx + 1.9, 3.0, 5.8, nz - 1.4, nz + 1.4, 'hub')
        m.box('gear_nose', nx - 1.5, nx + 1.5, 13.0, ny - 2.0, nz - 1.5, nz + 1.5, 'strut')
        m.box('gear_nose', nx - 1.1, nx + 1.1, 9.4, 13.2, nz - 1.1, nz + 1.1, 'chrome')
        m.box('gear_nose', nx - 1.4, nx + 1.4, 17.0, 18.4, nz + 1.4, nz + 2.0, 'light_white')     # taxi lights
        for sx in (-1, 1):
            m.box('gear_nose', nx + sx * 1.8, nx + sx * 2.3, 3.4, 9.0, nz - 1.0, nz + 1.0, 'strut')
    else:
        m.box('gear_nose', nx - 1.7, nx + 1.7, 0.0, 8.8, nz - 4.4, nz + 4.4, 'tire')
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
        if full:
            m.prism(name, 'x', (x, WHEEL_R, za), WHEEL_R, 4.6, 'tire')
            low = tuple(top[i] + 0.82 * (axle[i] - top[i]) for i in range(3))
            m.slab(name, mid, low, 2.6, 2.6, 'chrome')                      # oleo piston
            m.slab(name, top, mid, 3.2, 3.2, 'strut')
            m.box(name, x - 2.5, x + 2.5, WHEEL_R - 2.0, WHEEL_R + 2.0, za - 1.7, za + 1.7, 'hub')
            m.slab(name, (x, y - 4.0, zp - 2.0), (x, y - 15.0, za - 1.0), 0.8, 0.8, 'strut')   # drag brace
            m.prism(name, 'x', (x, WHEEL_R, za), 1.0, 5.4, 'strut', n=4)                      # axle
        else:
            m.box(name, x - 2.3, x + 2.3, 0.0, 2 * WHEEL_R, za - WHEEL_R, za + WHEEL_R, 'tire')
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
    'formation': '#c6e3a1', 'chrome': '#e3e6e7', 'antenna': '#4f565b', 'sensor': '#1f2c33', 'dispenser': '#3a3e42',
    'dielectric': '#6c7378',
}
UPPER, LOWER, SPLINTER = '#7e878c', '#b8bec2', '#6f787d'

# SBW vehicle skins (spray can): the first is the default texture
SCHEMES = [
    ('standard', 'PLAAF Grey', 'Серо-голубая окраска J-10C'),
    ('digital', 'Digital Camo', 'Пиксельный серо-голубой камуфляж'),
    ('display', 'Display Team', 'Бело-красная окраска пилотажной группы'),
]


def painter(scheme=None):
    from kit import value_noise
    scheme = scheme or 'standard'

    def under(p, n):
        wave = H(1.62) + 2.0 * np.sin(p[:, 2] / 13.0)
        return (n[1] < -0.45) | (p[:, 1] < wave)

    if scheme == 'digital':
        def camo(p, n):
            return digital_camo(p, n, ['#566e7e', '#7b92a1', '#a2b4bf'], '#c3cdd3', pixel=2.0, scale=15.0, seed=19,
                                belly_line=lambda q: H(1.62) + 2.0 * np.sin(q[:, 2] / 13.0))
    elif scheme == 'display':
        def camo(p, n):
            # white aircraft, red belly, a red spine flash and red chevrons along the wing and
            # canard leading edges, each edged in gold
            white, red, gold = hexrgb('#eceeec'), hexrgb('#c21f27'), hexrgb('#d9ad3c')
            rgb = np.tile(white, (len(p), 1))
            r = np.abs(p[:, 0])
            up = n[1] > 0.3
            dist = np.full(len(p), 99.0)
            wing = (r > 10) & (np.abs(p[:, 1] - H(1.85)) < 3.0)
            le = np.array([Z(wing_le(min(max(x / M, 0.62), 4.875))) for x in r])
            dist = np.where(wing, np.abs(p[:, 2] - le - 14.0) - 6.0, dist)
            can = (r > 9) & (r < 36) & (np.abs(p[:, 1] - H(2.33)) < 4.0) & (p[:, 2] < Z(7.2))
            dist = np.where(can, np.abs(p[:, 2] - (Z(4.88) + (r - 9.9) * 1.2) - 7.0) - 3.5, dist)
            spine = up & (r < 12) & (p[:, 1] > H(2.5)) & (p[:, 2] > Z(3.8))
            dist = np.where(spine, r - (2.0 + (p[:, 2] - Z(3.8)) * 0.045), dist)
            fin = (r < 2.5) & (p[:, 1] > H(3.2))
            dist = np.where(fin, np.abs((p[:, 2] - Z(13.0)) - (p[:, 1] - H(3.2)) * 0.9) - 7.0, dist)
            rgb[(dist < 0.9) & (dist > -0.0)] = gold
            rgb[dist <= 0.0] = red
            rgb[under(p, n)] = red
            return rgb
    else:
        def camo(p, n):
            # J-10C: blue-grey upper surfaces with soft darker patches over a light belly.
            rgb = np.tile(hexrgb(UPPER), (len(p), 1))
            patch = value_noise(p * np.array([1.0, 0.5, 1.0]), 30.0, 7) > 0.62
            rgb[patch] = hexrgb(SPLINTER)
            rgb[under(p, n)] = hexrgb(LOWER)
            return rgb

    frames = [Z(x) for x in (2.3, 2.8, 3.6, 5.8, 7.2, 8.1, 9.0, 9.9, 10.8, 11.6, 12.4, 13.3, 14.2)]
    hatches = [('x', Z(2.45), Z(3.3), H(1.95), H(2.4)), ('x', Z(6.0), Z(6.9), H(2.05), H(2.6)),
               ('x', Z(8.2), Z(9.0), H(2.0), H(2.6)), ('x', Z(11.7), Z(12.3), H(1.75), H(2.45)),
               ('x', Z(5.0), Z(6.2), H(1.25), H(1.65)), ('y', Z(6.3), Z(7.1), 0.0, 4.5),
               ('y', Z(10.0), Z(10.9), 1.0, 6.0), ('y', Z(12.6), Z(13.3), 0.0, 4.0)]

    def panel_lines(p, n, cube):
        r = np.abs(p[:, 0])
        body = r < 18
        m = stations_mask(p[:, 2], frames, 0.4) & body
        m |= body & (np.abs(n[0]) > 0.5) & stations_mask(p[:, 1], [H(2.1), H(2.55)], 0.3) & (p[:, 2] > Z(2.3))
        m |= body & (n[1] > 0.5) & (r < 0.35) & (p[:, 2] > Z(5.8))
        for axis, z0, z1, a0, a1 in hatches:
            m |= body & hatch(p, n, axis, z0, z1, a0, a1, 0.3)
        wing = r > 18
        flat = np.abs(n[1]) > 0.5
        m |= wing & flat & (np.mod(r - 18, 17.0) < 0.45)
        m |= wing & flat & stations_mask(p[:, 2], [Z(11.4)], 0.35)
        rv = body & rivet_rows(p[:, 2], frames, p[:, 1] + p[:, 0])
        rv |= wing & flat & rivet_rows(r, [18 + 17 * k for k in range(4)], p[:, 2])
        return m, rv

    def walkway(p, n):
        r = np.abs(p[:, 0])
        return ((n[1] > 0.5) & (r > 12) & (r < 22) & (p[:, 2] > Z(9.0)) & (p[:, 2] < Z(11.0))).astype(float)

    weather = make_weather(seed=10, grime=0.05 if scheme == 'display' else 0.09, streaks=0.07,
                           soot=[((9.6, H(2.35), Z(4.4)), 1.2, 30.0)], wear=walkway)

    red, yellow = (196, 30, 28), (232, 190, 40)
    bars = np.zeros((16, 44), bool)
    bars[6:10, :] = True
    star = np.pad(star_mask(16), ((0, 0), (14, 14)))
    shape = np.pad(bars | star, 1)
    insignia = [(dilate(shape, 1), yellow), (shape, red)]      # red star and bars edged in yellow
    num = (32, 52, 120) if scheme == 'display' else red
    arrow, aspect = rescue_arrow()
    decals = []
    for sx in (1, -1):
        # fin insignia ahead of the rudder hinge
        decals.append(Decal((sx * 2.0, H(4.2), Z(14.6)), (sx, 0, 0), (0, 0, sx), (0, -1, 0), (27, 10), insignia,
                            max_depth=3))
        decals.append(Decal((sx * 3.3 * M, H(1.6), Z(11.8)), (0, -1, 0), (-1, 0, 0), (0, 0, 1), (23, 8.5),
                            insignia, max_depth=8))
    decals += side_decal(9.6, H(2.32), Z(2.25), (18, 5.6), outlined(text_mask('61523'), num, (236, 236, 232))
                         if scheme == 'display' else [(text_mask('61523'), num)], depth=4)
    decals += side_decal(2.0, H(3.2) + 1.95 * M, Z(15.4), (6, 1.4), [(text_mask('61523'), (40, 44, 48))], depth=3)
    decals += side_decal(10.6, H(2.62), Z(3.2), (2.0 * aspect, 2.0), arrow, depth=2.5)
    decals += side_decal(10.4, H(2.62), Z(5.6), (2.2, 2.2), [(triangle_mask(16), (196, 30, 28))], depth=2.5)
    decals += side_decal(11.2, H(1.45), Z(4.3), (6.0, 1.0), [(text_mask('DANGER'), (196, 30, 28))], depth=2.5)
    return Painter(PALETTE, camo, panel_lines, decals, glass_alpha=92, weather=weather)
