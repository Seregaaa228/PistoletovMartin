"""Su-25 'Frogfoot' at 1 block = 1 m.

Proportions from the measured three-view in aircraft-references (Kaboldy,
CC BY-SA 3.0, used for proportions only) and published data: length 15.53 m
with the nose probes, span 14.36 m, LE sweep 19.6 deg, anhedral -2.5 deg,
track 2.5 m, wheelbase 3.55 m, nozzles at x 11.65 m with a slim tail boom.
x below is metres aft of the probe tips; real heights get LIFT added because
the in-game seat/camera (vehicle y 2.54 / 3.535) sit higher than a real
Su-25 cockpit. Anchors kept: seat z 3.99, functional pylons x +-2.1 with the
store attachment at y 2.2, z -0.2.

Moving parts are separate bones (parent body) modelled closed/neutral with the
bone pivot on the hinge line (see the animated-parts contract): flap_*,
aileron_*, elevator_*, rudder, the split wingtip airbrakes airbrake_*_upper/
lower, canopy (right-sill hinge), gear legs and gear doors. The fixed
structure ends at each hinge line, so a deflected surface never opens a hole:
every surface carries a 45-degree 'hinge nose' that fills the cove when it
moves. Gear hinges sit inside the bays so that a leg turned 90 deg forward
lies inside the nacelle (mains) or the fuselage (nose).
"""
import math

import numpy as np

from kit import Model, place_panel
from paint import Decal, Painter, splotch_camo, star_mask, stations_mask, text_mask

NOSE = -130.24         # geo Z (px) of the probe tips: pilot eye at x 4.15 m
M = 16.0
LIFT = 0.75            # metres added to every real height
NOZZLE_X = 11.65

# wing trailing-edge surfaces (metres outboard / aft of the probe tips)
FLAP = (1.40, 4.50)        # inner flaps
AILERON = (4.60, 6.686)    # outer ailerons, end 0.1 px inside the wingtip pod's inner face
HINGE_FLAP = 9.45          # flap hinge = fixed wing TE (flap chord 0.70 m)
HINGE_AIL = 9.62           # aileron hinge (chord 0.53 m) and airbrake split
WING_TE = 10.15
# tail surfaces
RUDDER_X = 13.45           # vertical rudder hinge
RUDDER_H = (0.25, 2.05)    # rudder span above the spine
ELEV_X = 13.90             # elevator hinge (unswept)
ELEV_ROOT = 0.34           # elevator inner end (outboard of the tail boom)


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
    sill = H(2.40)
    for name in ('wing_l', 'wing_r', 'tail_fin', 'stabilizer_l', 'stabilizer_r'):
        m.bone(name)
    # canopy opens up and over to the right: hinge on the right (-X) sill rail
    m.bone('canopy', 'body', (-7.9, sill + 0.2, Z(4.39)))
    m.bone('windscreen', 'body', (0, H(1.93), Z(3.08)))
    for name in ('engine_l', 'engine_r'):
        m.bone(name)
    # retraction hinges (pivots) inside the bays; both legs fold forward.
    # Nose leg offset to starboard, clear of the port gun fairing.
    nose_gear = (-1.5, 32.0, Z(4.90))
    main_gear = (16.8, 37.0, Z(8.61))
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

    # ---- fixed windscreen (sloped front glass, its posts and arch, armoured
    # front panel) and the opening canopy, hinged on the right sill rail
    m.wedge('windscreen', Z(2.98), H(1.84), Z(3.72), top - 0.2, H(1.80), 14.0, 'glass', thick=0.8)
    m.section('canopy', Z(3.72) - 0.15, Z(5.10), 0, (sill - 2.5 + top) / 2, 15.4, top - sill + 2.5, 4.4, 0.2, mat='glass',
              ends=(True, True), skip_bottom=True)
    if full:
        for x, bone in ((3.68, 'windscreen'), (4.40, 'canopy'), (5.08, 'canopy')):
            m.box(bone, -3.4, 3.4, top - 0.6, top + 0.4, Z(x) - 0.5, Z(x) + 0.5, 'frame')
            for sx in (-1, 1):
                # right-side posts of the opening canopy start at the hinge (the fixed rail
                # covers below), so nothing swings inward through the console when it opens
                y0 = sill + 0.2 if (bone == 'canopy' and sx < 0) else sill - 2.6
                m.box(bone, sx * 7.0, sx * 7.9, y0, top - 4.2, Z(x) - 0.5, Z(x) + 0.5, 'frame')
                m.centered(bone, (sx * 5.5, top - 2.2, Z(x)), (4.8, 1.0, 1.0), 'frame', rotation=(0, 0, sx * 45))
        # sill rails: the right one carries the hinge and stays fixed
        m.box('windscreen', -7.9, -6.9, sill - 2.8, sill + 0.2, Z(3.1), Z(5.1), 'frame')
        m.box('windscreen', 6.9, 7.9, sill - 2.8, sill + 0.2, Z(3.1), Z(3.68), 'frame')
        m.box('canopy', 6.9, 7.9, sill - 2.8, sill + 0.2, Z(3.68), Z(5.1), 'frame')
        for sx in (-1, 1):
            m.slab('windscreen', (sx * 6.8, H(1.93), Z(3.08)), (sx * 6.0, top - 0.6, Z(3.70)), 1.0, 1.0, 'frame')
        m.box('canopy', -0.7, 0.7, top - 0.6, top + 1.8, Z(4.3), Z(4.6), 'frame')        # periscope
        m.box('canopy', -1.1, 1.1, top + 1.6, top + 2.6, Z(4.25), Z(4.65), 'mirror')
        # armoured front panel
        m.slab('windscreen', (0, H(1.93) + 0.2, Z(3.06)), (0, top - 0.2, Z(3.68)), 4.0, 0.9, 'frame')

    # ---- engine nacelles R-95Sh: tall oval intakes at x 5.8, nozzles at x 11.65
    nacelles = {}
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
            ns = [ns[i] for i in (0, 1, 2, 4)]     # keep the bay segment exact for the doors
        m.loft(bone, ns)
        nacelles[side] = ns
        # fill the V between nacelle and fuselage sides (top and bottom); stops
        # at the start of the flat nacelle bottom, where the main bay doors begin
        m.box(bone, side * 0.40 * M, side * 0.78 * M, H(1.04), H(2.20), Z(5.74), Z(8.5), 'skin')
        m.box(bone, side * 0.40 * M, side * 1.0 * M, H(1.04), H(2.20), Z(8.5) - 0.3, Z(11.1), 'skin')
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
    anhedral = math.tan(math.radians(2.5))

    def le(y):
        return 7.15 + (y - 1.36) * math.tan(math.radians(19.6))

    def chord(y):
        return WING_TE - le(y)

    def wy(y):
        return wing_z - (y - root_y) * M * anhedral

    def wbox(bone, side, y0, y1, x0, x1, thick, nose=False):
        """Box in the wing plane between y0..y1 m outboard and x0..x1 m aft (anhedral
        applied). nose=True makes the 45-degree hinge nose on the hinge line x0."""
        xc, span = side * (y0 + y1) / 2 * M, (y1 - y0) * M
        if nose:
            s = (thick / 2 - 0.06) * math.sqrt(2)
            c = m.cube(bone, (xc - span / 2, -s / 2, Z(x0) - s / 2), (span, s, s), 'skin',
                       pivot=(xc, 0, Z(x0)), rotation=(45, 0, 0))
        else:
            c = m.box(bone, xc - span / 2, xc + span / 2, -thick / 2, thick / 2, Z(x0), Z(x1), 'skin',
                      pivot=(xc, 0, (Z(x0) + Z(x1)) / 2))
        place_panel([c], wing_z, -2.5, side, root_y * M)

    for side, bone, lr in ((1, 'wing_l', 'left'), (-1, 'wing_r', 'right')):
        # fixed wing ends at the flap hinge; full-chord pieces only where no surface is
        cubes = m.panel(bone, (root_y * M, Z(le(root_y)), (HINGE_FLAP - le(root_y)) * M),
                        (tip_y * M, Z(le(tip_y)), (HINGE_FLAP - le(tip_y)) * M),
                        4.2, 'skin', side=side, strips=3 if full else 1, root_in=0.85 * M)
        place_panel(cubes, wing_z, -2.5, side, root_y * M)
        te = 4.2 - 0.08 + (0.2 if side < 0 else 0.0)     # = the panel's trailing boxes (kit.panel)
        wbox(bone, side, root_y - 0.85, FLAP[0], HINGE_FLAP, WING_TE, te)
        wbox(bone, side, FLAP[1], AILERON[0], HINGE_FLAP, WING_TE, te)
        wbox(bone, side, AILERON[0], AILERON[1], HINGE_FLAP, HINGE_AIL, te)
        for name, (y0, y1), xh in (('flap_' + lr, FLAP, HINGE_FLAP), ('aileron_' + lr, AILERON, HINGE_AIL)):
            ym = (y0 + y1) / 2
            m.bone(name, 'body', (side * ym * M, wy(ym), Z(xh)))
            wbox(name, side, y0, y1, xh, WING_TE, te)
            wbox(name, side, y0, y1, xh, WING_TE, te, nose=True)
        # wing-root glove over the nacelle: closes the slot under the swept root
        # (ends ahead of the flap hinge so the lowered flap clears it)
        m.box(bone, side * 0.95 * M, side * (root_y + 0.35) * M, H(1.95), wing_z - 1.6, Z(le(root_y) + 0.15),
              Z(HINGE_FLAP - 0.05), 'skin')
        m.box(bone, side * 0.95 * M, side * (FLAP[0] * M - 0.05), H(1.95), wing_z - 1.6, Z(HINGE_FLAP - 0.05),
              Z(10.05), 'skin')
        # wingtip pod; its aft part splits into the 'crocodile' airbrake petals,
        # hinged on the pod centre line where the split starts
        py, px = wy(6.93), side * 6.93 * M
        split = Z(HINGE_AIL)
        m.box(bone, px - 4.0, px + 4.0, py - 3.0, py + 2.6, Z(8.75), split, 'skin')
        m.box(bone, px - 3.0, px + 3.0, py - 2.2, py + 1.8, Z(8.55), Z(8.75), 'skin', lod=full)
        m.box(bone, px - 1.8, px + 1.8, py - 1.4, py + 1.0, Z(8.50), Z(8.55), 'light_white', lod=full)
        m.box(bone, px + side * 4.0, px + side * 4.3, py - 0.6, py + 0.6, Z(9.0), Z(9.3),
              'light_green' if side > 0 else 'light_red', lod=full)
        for part, y0, y1, r0, r1 in (('upper', py, py + 2.6, py, py + 0.25), ('lower', py - 3.0, py, py - 0.25, py)):
            name = 'airbrake_%s_%s' % (lr, part)
            m.bone(name, 'body', (px, py, split))
            m.box(name, px - 4.0, px + 4.0, y0, y1, split, Z(10.45), 'skin')
            m.box(name, px - 3.0, px + 3.0, r0, r1, Z(10.45), Z(10.65), 'skin', lod=full)
        if full:
            # decorative outer pylons (the functional pair is pylon_l/pylon_r); they
            # end 2 px ahead of the hinge line so the lowered flaps/ailerons clear them
            for yy in (2.82, 3.61, 4.37, 5.10):
                yw = wy(yy) - 2.0
                zc = Z(le(yy) + chord(yy) * 0.40)
                aft = min(zc + 10.0, Z(HINGE_FLAP if yy < AILERON[0] else HINGE_AIL) - 2.0)
                m.box(bone, side * yy * M - 0.9, side * yy * M + 0.9, yw - 5.6, yw, aft - 22.0, aft, 'pylon')

    # functional pylons hang to y 2.2 m (35.2 px) where the stores attach
    for bone, x in (('pylon_l', 33.6), ('pylon_r', -33.6)):
        yw = wy(2.1) - 1.6
        # (full model: the metal pad alone forms the 35.2 bottom, never coplanar with the pylon)
        m.box(bone, x - 1.0, x + 1.0, 35.2 + (0.06 if full else 0.0), yw + 0.4, -3.2 - 15.0, -3.2 + 14.0, 'pylon')
        if full:
            m.box(bone, x - 1.4, x + 1.4, 35.2, 36.6, -3.2 - 11.0, -3.2 + 9.0, 'metal')

    # ---- tail: fin with the rudder on a vertical hinge, tailplane with 5 deg dihedral
    spine = H(2.65)
    fin_h = 2.45

    def fin_te(h):
        return 14.2 - 0.4 * h / fin_h

    def fin_piece(bone, h0, h1, x0, x1, thick, strips, root_in=0.0):
        """Fin planform between heights h0..h1 (m above the spine), LE at x0(h0)..x0(h1)
        and TE x1(h0)..x1(h1) (callables or numbers, metres aft of the probes)."""
        f0 = x0 if callable(x0) else (lambda h: x0)
        cubes = m.panel(bone, (h0 * M, Z(f0(h0)), (x1(h0) - f0(h0)) * M), (h1 * M, Z(f0(h1)), (x1(h1) - f0(h1)) * M),
                        thick, 'skin', side=1, strips=strips, skip_root=False, root_in=root_in)
        for c in cubes:
            _upright(c, spine)

    r0, r1 = RUDDER_H
    fin_piece('tail_fin', 0, fin_h, lambda h: 11.2 + 1.8 * h / fin_h, lambda h: RUDDER_X, 3.0,
              3 if full else 1, root_in=0.3 * M)
    fin_piece('tail_fin', 0, r0, RUDDER_X, fin_te, 2.92, 1, root_in=0.3 * M)
    fin_piece('tail_fin', r1, fin_h, RUDDER_X, fin_te, 2.92, 1)
    m.bone('rudder', 'body', (0, spine + (r0 + r1) / 2 * M, Z(RUDDER_X)))
    fin_piece('rudder', r0, r1, RUDDER_X, fin_te, 2.92, 2 if full else 1)
    s = (2.92 / 2 - 0.06) * math.sqrt(2)
    m.cube('rudder', (-s / 2, spine + r0 * M, Z(RUDDER_X) - s / 2), (s, (r1 - r0) * M, s), 'skin',
           pivot=(0, spine, Z(RUDDER_X)), rotation=(0, 45, 0))
    if full:
        m.box('tail_fin', -1.0, 1.0, spine + 2.1 * M, spine + 2.3 * M, Z(13.55), Z(13.75), 'light_white')
        m.wedge('tail_fin', Z(9.9), spine + 0.3, Z(11.6), spine + 4.4, spine - 1.6, 3.0, 'skin')      # fillet
        m.box('tail_fin', -1.6, 1.6, spine - 2.0, spine + 1.0, Z(14.6), Z(15.6), 'skin')           # chute can

    def stab_te(y):
        return 14.37 - 0.10 * (y - 0.30) / 2.03

    dihedral = math.tan(math.radians(5.0))
    for side, bone, lr in ((1, 'stabilizer_l', 'left'), (-1, 'stabilizer_r', 'right')):
        tip_le = 12.52 + 2.03 * math.tan(math.radians(24.0))
        cubes = m.panel(bone, (0.30 * M, Z(12.52), (ELEV_X - 12.52) * M), (2.33 * M, Z(tip_le), (ELEV_X - tip_le) * M),
                        2.8, 'skin', side=side, strips=2 if full else 1, root_in=0.25 * M)
        te = 2.72 + (0.2 if side < 0 else 0.0)
        # fixed root piece behind the hinge, between the boom and the elevator
        xa, xb = 0.05 * M, ELEV_ROOT * M
        c = m.box(bone, side * xa, side * xb, -te / 2, te / 2, Z(ELEV_X), Z(stab_te(0.30)), 'skin',
                  pivot=(side * (xa + xb) / 2, 0, Z(ELEV_X)))
        cubes.append(c)
        place_panel(cubes, H(2.60), 5.0, side, 0.30 * M)
        name = 'elevator_' + lr
        ym = (ELEV_ROOT + 2.33) / 2 * M
        m.bone(name, 'body', (side * ym, H(2.60) + (ym - 0.30 * M) * dihedral, Z(ELEV_X)))
        cubes = m.panel(name, (ELEV_ROOT * M, Z(ELEV_X), (stab_te(ELEV_ROOT) - ELEV_X) * M),
                        (2.33 * M, Z(ELEV_X), (stab_te(2.33) - ELEV_X) * M), 2.72, 'skin', side=side,
                        strips=2 if full else 1, skip_root=False)
        s = (te / 2 - 0.06) * math.sqrt(2)
        span = (2.33 - ELEV_ROOT) * M
        cubes.append(m.cube(name, (side * ym - span / 2, -s / 2, Z(ELEV_X) - s / 2), (span, s, s), 'skin',
                            pivot=(side * ym, 0, Z(ELEV_X)), rotation=(45, 0, 0)))
        place_panel(cubes, H(2.60), 5.0, side, 0.30 * M)

    _gear(m, nose_gear, main_gear, full)
    _doors(m, nose_gear, stations, nacelles)
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
    """Legs hang from hinges inside the bays. Both legs fold forward (about X)
    into the fuselage/nacelle; wheels are sized and placed to fit there."""
    # nose leg: 0.63 m wheel straight under the hinge
    nx, ny, nz = nose
    m.box('gear_nose', nx - 1.0, nx + 1.0, 6.0, ny, nz - 1.0, nz + 1.0, 'strut')
    m.box('gear_nose', nx - 1.8, nx + 1.8, 0.0, 10.1, nz - 5.05, nz + 5.05, 'tire')
    if full:
        m.box('gear_nose', nx - 2.0, nx + 2.0, 3.6, 6.4, nz - 1.8, nz + 1.8, 'hub')
        m.box('gear_nose', nx - 2.4, nx + 2.4, 10.1, 11.0, nz - 5.6, nz + 3.6, 'skin')   # mudguard
        m.box('gear_nose', nx - 1.5, nx + 1.5, 11.0, ny - 3.0, nz - 1.4, nz + 1.4, 'strut')  # oleo
    for side, bone in ((1, 'gear_main_l'), (-1, 'gear_main_r')):
        x, y, z = side * main[0], main[1], main[2]
        # vertical leg, short lever forward to the 0.75 m wheel (contact at x 8.45 m)
        wz = Z(8.45)
        m.box(bone, x - 1.3, x + 1.3, 8.0, y, z - 1.3, z + 1.3, 'strut')
        m.box(bone, x - 1.0, x + 1.0, 4.8, 8.6, wz - 1.0, z + 1.3, 'strut')
        m.box(bone, x - 2.5, x + 2.5, 0.0, 12.0, wz - 6.0, wz + 6.0, 'tire')
        if full:
            m.box(bone, x - 2.7, x + 2.7, 4.0, 8.0, wz - 2.0, wz + 2.0, 'hub')
            m.box(bone, x - 1.8, x + 1.8, 12.0, y - 3.0, z - 1.8, z + 1.8, 'strut')
            m.box(bone, x - 0.6, x + 0.6, 12.0, y - 3.0, z - 3.2, z - 1.8, 'strut')     # drag strut


DOOR_T = 0.5      # door plate thickness (px)
DOOR_GAP = -0.06  # door inner face 0.06 px inside the skin: no slit, never coplanar


def _seg(stations, z):
    """Averaged loft section (cx, cy, w, h, ct, cb) of the interval containing z."""
    for a, b in zip(stations, stations[1:]):
        if a[0] <= z <= b[0]:
            return [(a[i] + b[i]) / 2 for i in range(1, 7)]
    raise ValueError(z)


def _doors(m, nose, stations, nacelles):
    """Closed gear doors, flush under the skin. Contract names: suffix _l is hinged
    on its +X edge, _r on its -X edge; pivots lie on the hinge lines."""
    r2 = math.sqrt(2)
    # nose bay: two doors under the flat fuselage bottom, split on the leg
    nx, _, nz = nose
    z0, z1 = Z(2.90) + 0.05, nz + 2.4
    # the skin bottom steps by ~0.2 px between loft segments: the door top follows
    # the highest one (slightly embedded in the others) so no slit opens above it
    yb = max((a[2] + b[2]) / 2 - (a[4] + b[4]) / 4 for a, b in zip(stations, stations[1:])
             if b[0] > z0 and a[0] < z1) - DOOR_GAP
    zc = (z0 + z1) / 2
    xr, xl = nx - 3.0, 1.95                  # starboard edge / just short of the gun fairing
    m.bone('gear_door_nose_r', 'body', (xr, yb, zc))
    m.box('gear_door_nose_r', xr, nx, yb - DOOR_T, yb, z0, z1, 'skin')
    m.bone('gear_door_nose_l', 'body', (xl, yb, zc))
    m.box('gear_door_nose_l', nx, xl, yb - DOOR_T, yb, z0, z1, 'skin')
    # main bays in the nacelle bottoms (one loft segment, x 6.3..8.5 m): an inner door
    # on the flat bottom and an outer one on the 45-degree chamfer, split near the leg
    za, zb = Z(6.30) + 0.1, Z(8.50) - 0.02
    zc = (za + zb) / 2
    for side, lr in ((1, 'left'), (-1, 'right')):
        cx, cy, w, h, ct, cb = _seg(nacelles[side], zc)
        ybot = cy - h / 2
        edge = abs(cx) + w / 2 - cb            # flat bottom -> chamfer (outboard)
        inner = 0.78 * M + 0.05                # meets the nacelle/fuselage fill box
        y0 = ybot - DOOR_GAP
        name_in = 'gear_door_%s_%s' % (lr, 'r' if side > 0 else 'l')     # hinged on the inboard edge
        name_out = 'gear_door_%s_%s' % (lr, 'l' if side > 0 else 'r')    # hinged on the outboard edge
        m.bone(name_in, 'body', (side * inner, y0, zc))
        m.box(name_in, side * inner, side * edge, y0 - DOOR_T, y0, za, zb, 'skin')
        run = cb - 0.1
        d = DOOR_GAP + DOOR_T / 2
        m.centered(name_out, (side * (edge + run / 2 + d / r2), ybot + run / 2 - d / r2, zc), (run * r2, DOOR_T, zb - za),
                   'skin', rotation=(0, 0, -45 * side))
        k = DOOR_GAP / r2                       # hinge on the skin-side edge
        m.bone(name_out, 'body', (side * (edge + run + k), ybot + run - k, zc))


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
