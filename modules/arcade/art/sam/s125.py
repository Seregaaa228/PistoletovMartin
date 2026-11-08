"""S-125 'Pechora' launcher site: 5P73 four-rail launcher, SNR-125-style guidance radar
and an UNK-style crew booth, at 1 block = 1 m (16 units), front -Z, left +X, ground y = 0.

Built on the aviation kit (modules/aviation/art/realistic: kit.py geometry, paint.py helpers)
and exported by sam_assets.py. Proportions follow the 5P73 and the V-601P/5V27 round:

  * round 5.9 m long (94 units): booster 0.55 m across with four large cruciform fins and a
    single nozzle, a conical interstage, the 0.375 m sustainer with cruciform mid-body wings,
    small canards behind the nose band, a long dark ogive nose with fuse antennas;
  * 5P73: four inclined rail beams side by side in one row (as on the owner's reference
    photo), each an open girder under its own round, carried by a transverse cradle on a
    central trunnion column; the turntable sits on a low cruciform base with four
    outriggers and screw jacks; launcher structure in sand-khaki/olive-tan;
  * guidance radar: an antenna post on a single-axle trailer, two crossed slotted-trough
    antennas and a command-link dish on a sweeping head, in Soviet protective green;
  * crew booth on skids with door, windows, ladder and roof unit, protective green.

Moving parts are move_* bones (SBW keeps them dynamic). Inside a move_* bone every cube
is axis-aligned or turned about Z only (SBW's baker flips X/Y turns in dynamic bones), so
round parts there run along Z (missiles, motors, dish) and anything round about another
axis is stepped from axis-aligned boxes. Static parts (bone base) use any rotation.

Bones (pivots in model units):
  root > base                         every fixed part
  base > move_turret      (0,15,0)    yaw turntable
  move_turret > move_launcher (0,30,0) elevation of the cradle and the four beams
  move_launcher > move_round_0..3 (0,30,0) one round each, rails RAILS (lateral, vertical)
  base > move_jack_0..3               outrigger screw jacks (front, left, rear, right)
  base > move_radar                   antenna head, sweeps about Y
  move_radar > move_antenna           antenna block, tilts about X (elevation nod)
  base > move_door                    booth door, swings about its hinge (Y)

The launcher rest elevation in game is 15 deg and it slews 10..75 deg: the rounds' rear
ends, beams and cradle were placed so they clear the turntable over that whole range.
"""
from __future__ import annotations

import math

import numpy as np

from kit import Model, axis_rot, euler_from_matrix, fbm, hexrgb, rot_matrix, value_noise
from paint import FONT, Decal, Painter, stations_mask

DENSITY = 1.9
FIXED = {"root", "base"}

TURRET_PIVOT = (0.0, 15.0, 0.0)
LAUNCHER_PIVOT = (0.0, 30.0, 0.0)
# (lateral, vertical) offsets of the four missile axes from the launcher pivot (model units),
# in move_round_0..3 order: inner pair higher, outer pair slightly lower, one row across.
RAILS = ((16.0, 9.0), (-16.0, 9.0), (40.0, 5.0), (-40.0, 5.0))
Z_REAR = 17.0            # nozzle exit of every round (geo z); the nose tip is at Z_REAR - LENGTH
LENGTH = 94.0
RB, RS = 4.4, 3.0        # booster / sustainer radius
FIN_R, WING_R, CANARD_R = 12.6, 11.4, 6.4

RADAR = (48.0, 50.0)     # x, z of the antenna mast axis
RADAR_Y = 36.0           # top of the fixed mast = move_radar pivot height
ANTENNA_Y = 52.0         # tilt axis of the antenna block
CABIN = (-63.5, -37.5, 37.5, 63.5)    # x0, x1, z0, z1 of the crew booth walls
FLOOR = 6.6
SEAT = (-50.0, FLOOR, 52.0)           # crew seat (passenger position) inside the booth
EYE = (-50.0, FLOOR + 19.0, 50.0)     # seated operator's eye
CAMERA = (RADAR[0], 77.0, RADAR[1])   # mast-top camera above the antenna head
JACKS = ((0.0, -54.0), (54.0, 0.0), (0.0, 54.0), (-54.0, 0.0))   # front, left, rear, right
DOOR_HINGE = (-37.5 - 2.6, 37.5 - 0.45)     # x, z of the booth door hinge line


def Z(s):
    """Distance s forward of a round's nozzle exit -> geo z."""
    return Z_REAR - s


# ------------------------------------------------------------------ helpers ---
def turn(cubes, r, about=(0.0, 0.0, 0.0), shift=(0.0, 0.0, 0.0)):
    """Rigidly rotate (r about `about`) and shift built cubes, keeping their own turns."""
    r = np.asarray(r, float)
    a = np.asarray(about, float)
    sh = np.asarray(shift, float)
    for c in cubes:
        size = np.array(c.size, float)
        centre = np.array(c.origin, float) + size / 2
        rc = rot_matrix(c.rotation)
        pv = np.array(c.pivot if c.pivot is not None else (0, 0, 0), float)
        w = rc @ (centre - pv) + pv
        nc = r @ (w - a) + a + sh
        rot = euler_from_matrix(r @ rc)
        c.origin = [float(v) for v in nc - size / 2]
        c.pivot = [float(v) for v in nc]
        c.rotation = [float(v) for v in rot] if any(abs(v) > 1e-7 for v in rot) else None
    return cubes


def grab(m, start):
    return m.cubes[start:]


def disc_y(m, bone, cx, cz, y0, y1, r, mat, steps=4, tag=None, lift=0.03):
    """Round plate about Y from axis-aligned boxes (for move_* bones): a stepped circle."""
    out = []
    for k in range(steps):
        a = math.radians((k + 0.5) * 90.0 / steps)
        hx, hz = r * math.cos(a), r * math.sin(a)
        out.append(m.box(bone, cx - hx, cx + hx, y0 - lift * k * 0.7, y1 + lift * k, cz - hz, cz + hz, mat, tag=tag))
    return out


def disc_x(m, bone, x0, x1, cy, cz, r, mat, steps=3, tag=None):
    """Round boss about X from axis-aligned boxes; its corners all lie on radius r."""
    out = []
    for k in range(steps):
        a = math.radians((k + 0.5) * 90.0 / steps)
        hy, hz = r * math.cos(a), r * math.sin(a)
        out.append(m.box(bone, x0 - 0.02 * k, x1 + 0.02 * k, cy - hy, cy + hy, cz - hz, cz + hz, mat, tag=tag))
    return out


def rod_z(m, bone, cx, cy, segs, n=10, tag=None, roll=0.0, zref=None):
    """Coaxial prisms along Z. segs: (s0, s1, radius, mat[, n]) in increasing s (forward).
    A segment shows an end cap only where it is the larger one; a hidden end reaches 0.3
    into its neighbour so the staggered caps of the larger one leave no slit."""
    out = []
    for i, sg in enumerate(segs):
        s0, s1, r, mat = sg[:4]
        nn = sg[4] if len(sg) > 4 else n
        prev = segs[i - 1][2] if i else None
        nxt = segs[i + 1][2] if i + 1 < len(segs) else None
        rear = prev is None or prev < r - 1e-6
        front = nxt is None or nxt < r - 1e-6
        a = s0 - (0.0 if rear else 0.3)
        b = s1 + (0.0 if front else 0.3)
        zc = Z((a + b) / 2) if zref is None else zref - (a + b) / 2
        cubes = m.prism(bone, 'z', (cx, cy, zc), r, b - a, mat, n=nn, caps=(front, rear),
                        stagger=0.03, roll=roll)
        for c in cubes:
            c.tag = tag
        out += cubes
    return out


def fins(m, bone, cx, cy, strips, thick, mat, phis=(45, 135, 225, 315), tag=None):
    """Cruciform surfaces: per fin, radial strips (r0, r1, s_te, s_le) turned about Z."""
    out = []
    for phi in phis:
        r = axis_rot('z', phi)
        for r0, r1, s_te, s_le in strips:
            centre = np.array([cx, cy, Z((s_te + s_le) / 2)]) + r @ np.array([0.0, (r0 + r1) / 2, 0.0])
            out.append(m.oriented(bone, centre, (thick, r1 - r0, s_le - s_te), r, mat, tag=tag))
    return out


def ogive(s0, s1, r0, steps):
    """Tangent-ogive nose from radius r0 at s0 to a point at s1, as coaxial steps."""
    ln = s1 - s0
    rho = (r0 * r0 + ln * ln) / (2 * r0)
    out = []
    for k in range(steps):
        a, b = s0 + ln * k / steps, s0 + ln * (k + 1) / steps
        x = ln - (a + b) / 2 + s0            # distance from the tip at the step's middle
        r = math.sqrt(max(rho * rho - (ln - x) ** 2, 0.0)) + r0 - rho
        out.append((a, b, max(r, 0.3), 'radome', 8 if r > 1.2 else 6))
    return out


def planform(r0, r1, s_te, le_root, le_tip, strips):
    """Radial strips (r0, r1, s_te, s_le) of a trapezoid whose leading edge runs from le_root at
    r0 to le_tip at r1; each strip takes the leading edge at its middle radius."""
    out = []
    for k in range(strips):
        a, b = r0 + (r1 - r0) * k / strips, r0 + (r1 - r0) * (k + 1) / strips
        le = le_root + (le_tip - le_root) * ((a + b) / 2 - r0) / (r1 - r0)
        out.append((a, b, s_te, le))
    return out


def slab_pts(m, bone, p0, p1, width, thick, mat, tag=None):
    c = m.slab(bone, p0, p1, width, thick, mat)
    c.tag = tag
    return c


def cable(m, pts, r=1.3, mat='cable'):
    """Ground cable through the points (base bone): one slab per run, a knuckle at each bend."""
    for a, b in zip(pts, pts[1:]):
        slab_pts(m, 'base', a, b, r, r, mat)
    for p in pts[1:-1]:
        m.centered('base', p, (r * 1.08, r * 1.08, r * 1.08), mat)


def beam_z(m, bone, p0, p1, w, h, mat, tag=None):
    """Bar from p0 to p1 lying in the XY plane (turned about Z only): bracing in move_* bones."""
    p0, p1 = np.array(p0, float), np.array(p1, float)
    d = p1 - p0
    ln = math.hypot(d[0], d[1])
    ang = math.degrees(math.atan2(d[1], d[0]))
    return m.oriented(bone, (p0 + p1) / 2, (ln, h, w), axis_rot('z', ang), mat, tag=tag)


# ---------------------------------------------------------------- the round ---
def missile(m, bone, rx, ry):
    cx, cy = LAUNCHER_PIVOT[0] + rx, LAUNCHER_PIVOT[1] + ry
    # booster: nozzle, aft skirt, case, forward ring; interstage cone; sustainer; ogive nose
    rod_z(m, bone, cx, cy, [
        (0.0, 1.7, 3.3, 'nozzle', 8),
        (1.7, 2.9, 4.75, 'missile'),
        (2.9, 28.7, RB, 'missile'),
        (28.7, 29.9, 4.62, 'missile'),
        (29.9, 31.9, 4.05, 'missile'),
        (31.9, 33.9, 3.5, 'missile'),
        (33.9, 76.0, RS, 'missile'),
    ] + ogive(76.0, 93.2, RS - 0.12, 9) + [(93.2, LENGTH, 0.24, 'metal', 2)], tag='round')
    rod_z(m, bone, cx, cy, [(-0.3, 0.6, 2.4, 'nozzle_in', 8)])
    # four large booster fins, cruciform wings and canards: trapezoids (straight trailing edge,
    # swept leading edge) built from radial strips, the same X for all three sets
    fins(m, bone, cx, cy, planform(4.1, FIN_R, 1.6, 15.4, 8.8, 6), 0.7, 'fin', tag='booster_fin')
    fins(m, bone, cx, cy, planform(2.7, WING_R, 37.0, 58.0, 42.0, 6), 0.6, 'fin', tag='wing')
    fins(m, bone, cx, cy, planform(2.7, CANARD_R, 62.0, 68.4, 65.6, 3), 0.5, 'fin', tag='canard')
    # radio-fuse antennas round the ogive base, between the canard planes
    for phi in (0, 90, 180, 270):
        r = axis_rot('z', phi)
        m.oriented(bone, np.array([cx, cy, Z(79.0)]) + r @ np.array([0.0, 3.25, 0.0]), (0.45, 1.3, 0.45), r, 'metal')
        m.oriented(bone, np.array([cx, cy, Z(79.0) - 0.9]) + r @ np.array([0.0, 3.75, 0.0]), (0.3, 0.3, 2.2), r,
                   'metal')
    # cable tunnel along the top of the sustainer and the booster plug fairing
    m.box(bone, cx - 0.7, cx + 0.7, cy + RS - 0.4, cy + RS + 0.9, Z(36.0), Z(61.0), 'missile', tag='round')
    m.box(bone, cx - 0.9, cx + 0.9, cy + RB - 0.4, cy + RB + 0.8, Z(22.0), Z(27.5), 'missile', tag='round')
    # launch shoes riding on the rail under the round
    rail_top = cy - RB - 0.05
    for s, r in ((6.5, RB), (44.0, RS)):
        m.box(bone, cx - 1.25, cx + 1.25, rail_top - 0.75, cy - r + 0.6, Z(s + 1.6), Z(s - 1.6), 'metal_dark')


# ------------------------------------------------------------- the launcher ---
def launcher(m):
    L = 'move_launcher'
    px, py, pz = LAUNCHER_PIVOT
    z_back, z_front = 14.6, -58.0
    # trunnion hub between the column cheeks: every corner on r 4.2, so it turns in place
    disc_x(m, L, -4.3, 4.3, py, pz, 4.2, 'metal_dark', steps=3)
    m.box(L, -4.3, 4.3, py + 3.4, py + 4.9, pz - 1.1, pz + 1.0, 'tan', tag='girder')
    # bridge over the cheeks and the blocks that carry it down onto the cradle girders
    m.box(L, -6.7, 6.7, py + 4.8, py + 8.6, pz - 4.6, pz + 1.0, 'tan', tag='girder')
    for sx in (-1, 1):
        m.box(L, sx * 6.7, sx * 9.4, py - 4.2, py + 8.6, pz - 6.0, pz + 1.0, 'tan', tag='girder')
        # transverse cradle girder out to the outer beams (top and bottom flanges, web)
        m.box(L, sx * 9.4, sx * 43.6, py - 3.6, py + 0.6, pz - 5.4, pz + 0.4, 'tan', tag='girder')
        m.box(L, sx * 9.4, sx * 43.8, py + 0.6, py + 1.3, pz - 6.0, pz + 1.0, 'tan', tag='flange')
        m.box(L, sx * 9.4, sx * 43.8, py - 4.3, py - 3.6, pz - 6.0, pz + 1.0, 'tan', tag='flange')
        for x in (12.0, 22.0, 28.0, 34.0):
            m.box(L, sx * x - 0.35, sx * x + 0.35, py - 3.6, py + 0.6, pz - 6.0, pz + 1.0, 'tan', tag='flange')
    # elevation sector bosses on the bridge and a cable trunk to the beams
    m.box(L, -2.2, 2.2, py + 8.6, py + 9.6, pz - 4.0, pz + 0.6, 'metal_dark')
    for i, (rx, ry) in enumerate(RAILS):
        cx, cy = px + rx, py + ry
        top0, top1 = cy - 6.6, cy - 5.0          # top chord carrying the rail
        bot0, bot1 = cy - 13.2, cy - 11.6        # bottom chord
        m.box(L, cx - 1.9, cx + 1.9, top0, top1, z_front, z_back, 'tan', tag='chord')
        m.box(L, cx - 0.75, cx + 0.75, top1, cy - RB - 0.05, z_front + 1.0, z_back - 0.6, 'rail')
        m.box(L, cx - 1.6, cx + 1.6, bot0, bot1, z_front + 4.0, z_back, 'tan', tag='chord')
        # open girder: vertical posts between the chords, gussets at the joints
        for k, z in enumerate(np.arange(z_back - 0.6, z_front + 3.0, -7.2)):
            m.box(L, cx - 1.2, cx + 1.2, bot1, top0, z - 1.3, z, 'tan', tag='post')
            for y in (bot1, top0):
                m.box(L, cx - 1.45, cx + 1.45, y - 0.6, y + 0.6, z - 2.0, z + 0.7, 'tan', tag='post')
        # nose end: the chord tapers into a stop; rear end plate with the shoe latch
        m.box(L, cx - 1.5, cx + 1.5, top0 - 2.5, top0, z_front, z_front + 4.0, 'tan', tag='post')
        m.box(L, cx - 2.2, cx + 2.2, bot0, top1, z_back, z_back + 0.8, 'tan', tag='post')
        m.box(L, cx - 1.0, cx + 1.0, top1, top1 + 1.2, z_back - 0.4, z_back + 0.8, 'metal_dark')
        # umbilical plug arm reaching up to the round's plug fairing (outer side of the chord)
        side = 1 if rx > 0 else -1
        m.box(L, cx + side * 1.9, cx + side * 2.8, top0, cy + 1.2, Z(26.0), Z(23.5), 'tan', tag='post')
        m.box(L, cx + side * 0.9, cx + side * 2.8, cy + RB + 0.2, cy + RB + 1.3, Z(26.0), Z(23.5), 'metal_dark')
        missile(m, 'move_round_%d' % i, rx, ry)
    # front cross frames joining the bottom chords, with X bracing (turned about Z only)
    for zf in (-57.6, -12.0):
        za, zb = zf - 1.4, zf
        inner_y = py + RAILS[0][1] - 12.4
        outer_y = py + RAILS[2][1] - 12.4
        m.box(L, -16.0, 16.0, inner_y - 0.8, inner_y + 0.8, za, zb, 'tan', tag='frame')
        for sx in (-1, 1):
            beam_z(m, L, (sx * 16.0, inner_y, (za + zb) / 2), (sx * 40.0, outer_y, (za + zb) / 2), 1.4, 1.6,
                   'tan', tag='frame')
            beam_z(m, L, (sx * 16.0, py + RAILS[0][1] - 5.8, zf - 0.7), (sx * 40.0, outer_y, zf - 0.7), 0.9, 1.0,
                   'tan', tag='frame')
    # blast shields between the inner and outer rounds, behind the booster fins
    for sx in (-1, 1):
        x = sx * 28.0
        m.box(L, x - 0.45, x + 0.45, py - 3.6, py + 14.0, Z(13.0), z_back, 'tan', tag='shield')
        m.box(L, x - 0.9, x + 0.9, py + 13.4, py + 14.6, Z(13.0), z_back, 'tan', tag='shield')
        m.box(L, x - 0.9, x + 0.9, py - 3.6, py + 14.0, Z(13.0) - 0.8, Z(13.0), 'tan', tag='shield')


# --------------------------------------------------------------- turntable ---
def turntable(m):
    T = 'move_turret'
    disc_y(m, T, 0, 0, 12.6, 14.0, 20.0, 'tan', steps=6, tag='deck')
    disc_y(m, T, 0, 0, 12.25, 12.6, 18.6, 'metal_dark', steps=5)
    # trunnion column: core, cheeks with round bearing ears, bearing caps
    m.box(T, -4.4, 4.4, 14.0, 24.8, -4.6, 4.6, 'tan', tag='col')
    for sx in (-1, 1):
        m.box(T, sx * 4.4, sx * 6.2, 14.0, 30.0, -4.6, 4.6, 'tan', tag='col')
        disc_x(m, T, sx * 4.4, sx * 6.2, 30.0, 0.0, 4.6, 'tan', tag='col')
        disc_x(m, T, sx * 6.2, sx * 6.45, 30.0, 0.0, 2.4, 'metal_dark')
        m.box(T, sx * 4.4, sx * 6.2, 14.0, 21.0, -7.4, 7.4, 'tan', tag='col')        # foot gussets
    # azimuth/elevation drive housing in front of the column, two motors on Z axes
    m.box(T, -6.2, 6.2, 14.0, 23.0, -16.0, -4.6, 'tan', tag='drive')
    m.box(T, -5.4, 5.4, 23.0, 23.7, -15.4, -5.2, 'tan', tag='drive')
    for x, y in ((-2.9, 18.0), (2.9, 18.0)):
        m.prism(T, 'z', (x, y, -18.6), 2.4, 5.2, 'tan', n=8)
        m.prism(T, 'z', (x, y, -21.6), 1.7, 0.8, 'metal_dark', n=6)
    # rear electrics box behind the column
    m.box(T, -6.2, 6.2, 14.0, 24.6, 4.6, 14.8, 'tan', tag='cabinet_r')
    m.box(T, -6.6, 6.6, 24.6, 25.0, 4.2, 15.2, 'tan', tag='cabinet_r')
    # front control cabinets left and right
    for sx in (-1, 1):
        x0, x1 = sx * 19.0, sx * 29.0
        m.box(T, x0, x1, 14.0, 20.4, -25.0, -11.0, 'tan', tag='cabinet')
        m.box(T, x0 - sx * 0.3, x1 + sx * 0.3, 20.4, 20.9, -25.4, -10.6, 'tan', tag='cabinet')
        m.box(T, x1, x1 + sx * 0.4, 16.0, 19.0, -22.5, -13.5, 'grille')
        for z in (-23.0, -13.0):
            m.box(T, x1, x1 + sx * 0.6, 17.0, 17.6, z - 0.9, z + 0.9, 'metal')
        # lifting/tie lugs on the deck rim
        m.box(T, sx * 19.2 - 0.6, sx * 19.2 + 0.6, 14.0, 15.6, 6.0, 8.0, 'metal_dark')
    # step and handrail on the right front for the crew
    m.box(T, -17.0, -11.0, 9.6, 10.2, -18.6, -16.0, 'metal')
    for x in (-16.6, -11.4):
        m.box(T, x - 0.3, x + 0.3, 10.2, 12.6, -17.6, -17.0, 'metal')


# -------------------------------------------------------------------- base ---
def leg(m):
    """One outrigger along +Z (turned into place by the caller); returns its fixed cubes."""
    B = 'base'
    s0 = len(m.cubes)
    z0, z1 = 12.0, 50.6

    def top(z):
        return 9.2 + (6.4 - 9.2) * (z - z0) / (z1 - z0)

    m.box(B, -3.9, 3.9, 2.0, 2.8, z0, z1, 'tan', tag='leg')                  # bottom flange
    m.wedge(B, z0, 9.2, z1, 6.4, 2.8, 5.4, 'tan', thick=0.9)                  # box girder sides
    for c in m.cubes[s0 + 1:]:
        c.tag = 'leg'
    slab_pts(m, B, (0, 9.55, z0), (0, 6.75, z1), 7.6, 0.7, 'tan', tag='leg_top')   # top flange
    for z in (18.5, 25.0, 31.5, 38.0, 44.5):
        for sx in (-1, 1):
            m.box(B, sx * 2.7, sx * 3.6, 2.8, top(z) - 0.1, z - 0.4, z + 0.4, 'tan', tag='leg')
    for sx in (-1, 1):                                                         # folding hinge lugs
        m.box(B, sx * 2.7, sx * 4.3, 2.0, 10.6, 13.0, 17.0, 'tan', tag='leg')
    m.prism(B, 'x', (0, 8.6, 15.0), 1.0, 9.6, 'metal_dark', n=6)
    m.box(B, -0.5, 0.5, top(41.0) + 0.6, top(41.0) + 2.3, 40.2, 41.8, 'metal_dark')   # lifting eye
    # jack housing at the leg end (the screw, wheel and pad are move_jack_N)
    jz = 54.0
    m.box(B, -3.0, 3.0, 3.6, 9.6, jz - 3.0, jz + 3.0, 'tan', tag='jack')
    m.box(B, -3.6, 3.6, 9.6, 10.4, jz - 3.6, jz + 3.6, 'tan', tag='jack')
    m.box(B, -2.4, 2.4, 2.8, 3.6, jz - 3.4, jz - 2.6, 'tan', tag='jack')
    m.box(B, -2.2, 2.2, 4.8, 8.2, jz + 3.0, jz + 3.25, 'hazard')
    return grab(m, s0)


def jack(m, k, x, z):
    """Screw jack k (bone move_jack_k, pivot on the screw axis at the housing top)."""
    J = 'move_jack_%d' % k
    disc_y(m, J, x, z, 10.4, 14.6, 1.15, 'thread', steps=2, lift=0.0)
    disc_y(m, J, x, z, 12.8, 13.5, 3.2, 'metal_dark', steps=3, lift=0.02)
    m.box(J, x - 3.0, x + 3.0, 13.0, 13.3, z - 0.25, z + 0.25, 'metal_dark')
    m.box(J, x + 2.4, x + 3.0, 13.5, 15.4, z - 0.3, z + 0.3, 'metal')
    disc_y(m, J, x, z, 1.3, 3.6, 1.15, 'thread', steps=2, lift=0.0)
    disc_y(m, J, x, z, 0.8, 1.3, 1.9, 'metal_dark', steps=3, lift=0.0)
    m.box(J, x - 4.6, x + 4.6, 0.0, 0.8, z - 4.6, z + 4.6, 'pad')
    m.box(J, x - 3.4, x + 3.4, 0.8, 1.05, z - 3.4, z + 3.4, 'pad')


def base(m):
    B = 'base'
    # pedestal with flanges, the fixed race and the slewing gear ring under the turntable
    for c in m.prism(B, 'y', (0, 5.5, 0), 12.5, 8.8, 'tan', n=10, caps=(False, False)):
        c.tag = 'ped'
    for y0, y1, r, caps in ((0.0, 1.4, 14.6, (False, True)), (9.6, 11.0, 14.6, (True, True))):
        for c in m.prism(B, 'y', (0, (y0 + y1) / 2, 0), r, y1 - y0, 'tan', n=10, caps=caps):
            c.tag = 'flange_ring'
    for c in m.prism(B, 'y', (0, 11.6, 0), 17.2, 1.2, 'metal_dark', n=10):
        c.tag = 'gear'
    # four outriggers ('+'), diagonal braces between them, screw jacks at the ends
    for k in range(4):
        turn(leg(m), axis_rot('y', 180 - 90 * k))
        s0 = len(m.cubes)
        slab_pts(m, B, (2.6, 5.4, 22.0), (22.0, 5.4, 2.6), 1.6, 3.4, 'tan', tag='brace')
        m.box(B, -1.2, 1.2, 3.0, 8.0, 20.6, 23.6, 'tan', tag='leg')
        turn(grab(m, s0), axis_rot('y', 90 * k))
    for k, (x, z) in enumerate(JACKS):
        jack(m, k, x, z)
    # pedestal access door (front-left facet) and cable junction boxes (rear corners)
    s0 = len(m.cubes)
    m.box(B, -2.2, 2.2, 2.4, 8.4, 12.25, 12.75, 'tan', tag='door_ped')
    m.box(B, 1.0, 1.6, 5.0, 6.2, 12.75, 13.1, 'metal')
    turn(grab(m, s0), axis_rot('y', 135))
    for ang in (-45, 45):
        s0 = len(m.cubes)
        m.box(B, -3.0, 3.0, 2.6, 8.6, 12.2, 15.0, 'tan', tag='jbox')
        m.box(B, -3.3, 3.3, 8.6, 9.1, 11.9, 15.3, 'tan', tag='jbox')
        for x in (-1.6, 1.6):
            m.prism(B, 'z', (x, 4.4, 15.6), 0.9, 1.4, 'metal_dark', n=6)
        turn(grab(m, s0), axis_rot('y', ang))
    # ground cables from the pedestal to the booth and to the radar trailer
    x0, x1, z0, z1 = CABIN
    rx, rz = RADAR
    cable(m, [(-11.6, 4.4, 11.6), (-13.0, 0.7, 13.0), (-28.0, 0.7, 34.0), (x1 + 2.9, 0.7, z0 + 4.2),
              (x1 + 2.9, 9.0, z0 + 4.2)])
    cable(m, [(11.6, 4.4, 11.6), (13.0, 0.7, 13.0), (30.0, 0.7, 24.0), (rx - 5.0, 0.7, rz - 16.4),
              (rx - 5.0, 16.0, rz - 16.4)])
    cable(m, [(x1 + 1.2, 6.0, z1 - 3.0), (x1 + 3.0, 0.7, z1 - 3.0), (-6.0, 0.7, 60.0), (rx - 10.5, 0.7, 60.0),
              (rx - 10.5, 9.0, 60.0)])
    # earth rod by the launcher with its strap
    m.box(B, -20.6, -19.8, 0.0, 6.0, -20.6, -19.8, 'metal')
    cable(m, [(-20.2, 4.8, -20.2), (-20.2, 0.6, -18.0), (-12.0, 0.6, -10.0), (-10.8, 2.2, -8.6)], r=0.6,
          mat='strap')


# ------------------------------------------------------------- crew booth ---
def window(m, bone, face, a0, a1, y0, y1, plane, out):
    """Opaque tinted window with a frame standing proud of it, on a wall at `plane`;
    face 'x' (wall normal along X) or 'z'; out = +1/-1 outward direction."""
    gt, ft = 0.3, 0.55
    if face == 'z':
        def b(xa, xb, ya, yb, d0, d1, mat, tag=None):
            return m.box(bone, xa, xb, ya, yb, plane + out * d0, plane + out * d1, mat, tag=tag)
    else:
        def b(za, zb, ya, yb, d0, d1, mat, tag=None):
            return m.box(bone, plane + out * d0, plane + out * d1, ya, yb, za, zb, mat, tag=tag)
    b(a0, a1, y0, y1, 0.0, gt, 'window')
    w = 0.8
    b(a0 - w, a1 + w, y1, y1 + w, 0.0, ft, 'green', 'frame')
    b(a0 - w, a1 + w, y0 - w, y0, 0.0, ft + 0.15, 'green', 'frame')
    b(a0 - w, a0, y0, y1, 0.0, ft, 'green', 'frame')
    b(a1, a1 + w, y0, y1, 0.0, ft, 'green', 'frame')
    b((a0 + a1) / 2 - 0.3, (a0 + a1) / 2 + 0.3, y0, y1, 0.0, gt + 0.12, 'green', 'frame')


def booth(m):
    B = 'base'
    x0, x1, z0, z1 = CABIN
    xm = (x0 + x1) / 2
    # sledge: two runners with turned-up ends, cross members, the floor sill
    for x in (x0 + 2.6, x1 - 2.6):
        m.box(B, x - 1.4, x + 1.4, 0.0, 2.6, z0 - 1.0, z1 + 1.0, 'green', tag='skid')
        slab_pts(m, B, (x, 1.3, z0 - 0.6), (x, 2.8, z0 - 2.2), 2.8, 2.6, 'green', tag='skid')
        slab_pts(m, B, (x, 1.3, z1 + 0.6), (x, 2.8, z1 + 2.2), 2.8, 2.6, 'green', tag='skid')
        m.box(B, x - 0.6, x + 0.6, 1.8, 3.2, z0 - 2.8, z0 - 1.8, 'metal_dark')
    for z in (z0 + 1.0, (z0 + z1) / 2, z1 - 1.0):
        m.box(B, x0 + 1.0, x1 - 1.0, 2.6, 5.0, z - 0.8, z + 0.8, 'green', tag='skid')
    m.box(B, x0, x1, 5.0, FLOOR, z0, z1, 'green', tag='sill')
    # walls, corner posts, roof with overhang and a crowned top, rain strips
    m.box(B, x0 + 0.4, x1 - 0.4, FLOOR, 33.0, z0 + 0.4, z1 - 0.4, 'green', tag='booth')
    for x in (x0, x1 - 1.4):
        for z in (z0, z1 - 1.4):
            m.box(B, x, x + 1.4, FLOOR, 33.0, z, z + 1.4, 'green', tag='post')
    m.box(B, x0 - 0.4, x1 + 0.4, 33.0, 34.0, z0 - 0.4, z1 + 0.4, 'green', tag='roof')
    m.box(B, x0 + 1.0, x1 - 1.0, 34.0, 34.6, z0 + 1.0, z1 - 1.0, 'green', tag='roof')
    for z in (z0 - 0.4, z1 + 0.4):
        m.box(B, x0 - 0.4, x1 + 0.4, 32.4, 33.0, z - 0.25, z + 0.25, 'green', tag='frame')
    # door (move_door, hinged on its right edge) with its window, handle and hinges
    hx, hz = DOOR_HINGE
    D = 'move_door'
    dx0, dx1 = x1 - 11.0, hx
    m.box(D, dx0, dx1, FLOOR + 0.6, 30.4, hz - 0.15, hz + 0.25, 'green', tag='door')
    m.box(D, dx0 + 1.2, dx1 - 1.2, 21.5, 28.2, hz - 0.35, hz - 0.15, 'window')
    m.box(D, dx0 + 0.8, dx0 + 2.6, 17.4, 18.2, hz - 0.85, hz - 0.15, 'metal')
    m.box(D, dx0 + 0.9, dx0 + 1.5, 15.6, 17.4, hz - 0.55, hz - 0.15, 'metal_dark')
    m.box(D, dx0 + 1.2, dx1 - 1.2, 10.0, 15.0, hz - 0.3, hz - 0.15, 'grille')
    for y in (10.0, 19.0, 27.0):
        m.box(B, hx - 0.2, hx + 0.9, y - 1.1, y + 1.1, z0 - 0.15, z0 + 0.4, 'metal_dark')
    m.box(B, dx0 - 0.5, hx + 0.9, 30.4, 31.2, z0 - 0.5, z0 + 0.4, 'green', tag='frame')    # door head
    # door steps (ladder) with stringers, treads and a grab rail
    for x in (dx0 - 0.4, hx + 0.4):
        slab_pts(m, B, (x, FLOOR - 0.3, z0 - 0.5), (x, 0.4, z0 - 5.4), 0.8, 1.1, 'metal_dark')
    for k, y in enumerate((2.0, 4.4)):
        zt = z0 - 0.5 - (FLOOR - 0.3 - y) * (4.9 / (FLOOR - 0.7))
        m.box(B, dx0 - 0.1, hx + 0.1, y - 0.3, y + 0.3, zt - 1.6, zt + 0.6, 'tread')
    m.box(B, dx0 - 1.6, dx0 - 0.9, 12.0, 27.0, z0 - 1.1, z0 - 0.5, 'metal')
    for y in (12.0, 26.4):
        m.box(B, dx0 - 1.6, dx0 - 0.9, y, y + 0.6, z0 - 1.1, z0, 'metal')
    # windows: front (left of the door), outer side, inner side
    window(m, B, 'z', x0 + 2.6, x0 + 11.6, 18.0, 28.6, z0, -1)
    window(m, B, 'x', z0 + 5.0, z0 + 19.0, 18.0, 28.6, x0, -1)
    window(m, B, 'x', z0 + 9.0, z0 + 15.0, 19.0, 28.6, x1, 1)
    # vertical ladder to the roof on the inner (+X) side, rear
    for z in (z1 - 8.0, z1 - 3.2):
        m.box(B, x1 + 0.9, x1 + 1.6, 1.0, 37.0, z - 0.4, z + 0.4, 'metal_dark')
        for y in (10.0, 30.0):
            m.box(B, x1, x1 + 0.9, y - 0.3, y + 0.3, z - 0.3, z + 0.3, 'metal_dark')
    for y in np.arange(4.0, 36.0, 3.6):
        m.box(B, x1 + 1.0, x1 + 1.5, y, y + 0.5, z1 - 7.6, z1 - 3.6, 'metal')
    # cable entry box (inner side, front) and the filter-ventilation unit on the rear wall
    m.box(B, x1, x1 + 1.8, 7.4, 13.4, z0 + 1.6, z0 + 6.8, 'green', tag='jbox')
    m.box(B, x0 + 3.0, x0 + 11.0, 12.0, 23.0, z1, z1 + 1.6, 'green', tag='fvu')
    m.box(B, x0 + 3.6, x0 + 10.4, 13.0, 21.0, z1 + 1.6, z1 + 1.9, 'grille')
    m.prism(B, 'y', (x0 + 16.0, 27.0, z1 + 1.1), 0.9, 12.0, 'metal_dark', n=6)        # heater flue
    m.box(B, x0 + 14.8, x0 + 17.2, 33.0, 33.6, z1 + 0.2, z1 + 2.0, 'metal_dark')
    m.prism(B, 'y', (x1 + 1.0, 14.0, z0 + 10.0), 0.9, 4.2, 'red', n=6)              # extinguisher
    # roof: air-conditioner unit, vent mushroom, floodlight and whip antenna
    m.box(B, x0 + 3.0, x0 + 12.0, 34.6, 38.6, z1 - 12.0, z1 - 3.0, 'green', tag='ac')
    m.box(B, x0 + 3.5, x0 + 11.5, 35.4, 38.0, z1 - 3.0, z1 - 2.7, 'grille')
    m.box(B, x0 + 2.7, x0 + 3.0, 35.4, 38.0, z1 - 11.4, z1 - 3.6, 'grille')
    m.prism(B, 'y', (xm + 4.0, 35.6, z0 + 8.0), 0.8, 2.0, 'metal_dark', n=6)
    m.prism(B, 'y', (xm + 4.0, 36.9, z0 + 8.0), 1.9, 0.6, 'green', n=8)
    m.box(B, x1 - 4.2, x1 - 1.8, 34.6, 35.4, z0 + 1.0, z0 + 3.0, 'metal_dark')
    m.box(B, x1 - 4.4, x1 - 1.6, 35.4, 37.4, z0 + 0.4, z0 + 2.2, 'lamp')
    m.box(B, x1 - 2.6, x1 - 1.4, 34.6, 36.0, z1 - 2.6, z1 - 1.4, 'metal_dark')
    m.box(B, x1 - 2.2, x1 - 1.8, 36.0, 58.0, z1 - 2.2, z1 - 1.8, 'antenna')


# ----------------------------------------------------- radar trailer and head ---
def trailer(m):
    B = 'base'
    rx, rz = RADAR
    # single axle on leaf springs, two wheels, mudguards
    for sx in (-1, 1):
        x = rx + sx * 11.0
        m.prism(B, 'x', (x, 5.2, rz + 4.0), 5.2, 3.4, 'tire', n=10)
        m.prism(B, 'x', (x + sx * 0.3, 5.2, rz + 4.0), 2.8, 3.6, 'hub', n=8)
        m.prism(B, 'x', (x + sx * 2.1, 5.2, rz + 4.0), 1.0, 0.75, 'metal_dark', n=6)
        m.box(B, x - 1.9, x + 1.9, 10.8, 11.4, rz - 2.4, rz + 10.4, 'green', tag='guard')
        for z in (rz - 2.4, rz + 9.9):
            m.box(B, x - 1.9, x + 1.9, 7.2, 10.8, z, z + 0.5, 'green', tag='guard')
        m.box(B, rx + sx * 6.2 - 1.0, rx + sx * 6.2 + 1.0, 6.0, 7.6, rz - 2.0, rz + 10.0, 'metal_dark')
    m.box(B, rx - 10.0, rx + 10.0, 4.4, 6.0, rz + 3.2, rz + 4.8, 'metal_dark')
    # frame rails, cross members, deck, drawbar with towing eye and jockey wheel
    z0, z1 = rz - 14.0, rz + 14.0
    for sx in (-1, 1):
        m.box(B, rx + sx * 6.0, rx + sx * 8.0, 7.6, 10.0, z0 - 1.0, z1, 'green', tag='frame')
    for z in (z0, rz - 6.0, rz + 11.0):
        m.box(B, rx - 6.0, rx + 6.0, 8.0, 9.6, z, z + 1.4, 'green', tag='frame')
    m.box(B, rx - 9.0, rx + 9.0, 10.0, 11.0, z0 - 1.0, z1 + 0.6, 'green', tag='deck_t')
    for sx in (-1, 1):
        slab_pts(m, B, (rx + sx * 6.6, 8.8, z0 - 0.5), (rx + sx * 0.7, 7.6, z0 - 11.0), 1.4, 1.6, 'green',
                 tag='frame')
    m.box(B, rx - 1.1, rx + 1.1, 6.8, 8.4, z0 - 13.0, z0 - 10.2, 'metal_dark')
    m.prism(B, 'y', (rx, 5.2, z0 - 8.0), 0.6, 6.0, 'metal_dark', n=6)
    m.prism(B, 'x', (rx, 1.6, z0 - 8.0), 1.6, 1.2, 'tire', n=8)
    # four levelling jacks at the corners
    for sx in (-1, 1):
        for z in (z0 + 0.5, z1 - 0.5):
            x = rx + sx * 9.6
            m.box(B, x - 1.3, x + 1.3, 6.4, 10.4, z - 1.3, z + 1.3, 'green', tag='jack_t')
            m.prism(B, 'y', (x, 3.7, z), 0.75, 5.4, 'thread', n=6)
            m.prism(B, 'y', (x, 0.5, z), 2.4, 1.0, 'pad', n=8)
    # antenna-post equipment body with hatches, louvres and the connector panel
    m.box(B, rx - 8.5, rx + 8.5, 11.0, 24.0, z0, z1, 'green', tag='unv')
    m.box(B, rx - 8.9, rx + 8.9, 24.0, 24.6, z0 - 0.4, z1 + 0.4, 'green', tag='roof_t')
    for sx in (-1, 1):
        m.box(B, rx + sx * 8.5, rx + sx * 8.8, 13.0, 18.4, z0 + 3.0, z0 + 8.0, 'grille')
        for z in (rz - 4.0, rz + 9.0):
            m.box(B, rx + sx * 8.5, rx + sx * 9.0, 17.0, 17.6, z - 1.0, z + 1.0, 'metal')
    m.box(B, rx - 7.0, rx + 1.0, 13.0, 20.0, z0 - 0.6, z0, 'green', tag='panel_t')
    for x in (rx - 5.0, rx - 1.4):
        m.prism(B, 'z', (x, 16.0, z0 - 1.2), 1.0, 1.2, 'metal_dark', n=6)
    m.box(B, rx + 2.5, rx + 7.0, 12.0, 22.0, z1, z1 + 0.5, 'green', tag='door_t')
    m.box(B, rx + 3.0, rx + 4.0, 16.5, 17.3, z1 + 0.5, z1 + 0.9, 'metal')
    # slewing ring and mast up to the head
    m.prism(B, 'y', (rx, 25.4, rz), 6.6, 1.6, 'metal_dark', n=10)
    m.prism(B, 'y', (rx, 30.7, rz), 2.5, 9.0, 'green', n=8)
    m.prism(B, 'y', (rx, 35.5, rz), 4.2, 1.0, 'metal_dark', n=10)
    for k in range(4):
        s0 = len(m.cubes)
        slab_pts(m, B, (rx + 2.2, 34.6, rz), (rx + 6.0, 26.0, rz), 0.8, 0.8, 'green', tag='frame')
        turn(grab(m, s0), axis_rot('y', 45 + 90 * k), about=(rx, 0, rz))


def trough(m, bone, centre, phi, length=44.0, width=7.0):
    """Slotted-waveguide trough antenna in the XY plane, long axis turned phi about Z."""
    r = axis_rot('z', phi)
    c = np.asarray(centre, float)

    def part(off, size, mat, tag=None):
        return m.oriented(bone, c + r @ np.asarray(off, float), size, r, mat, tag=tag)

    part((0, 0, 0.8), (length, width, 1.6), 'green', 'trough')
    part((0, 0, -0.15), (length - 1.4, width - 1.4, 0.3), 'slots')
    for sy in (-1, 1):
        part((0, sy * (width / 2 - 0.25), -0.9), (length, 0.5, 1.8), 'green', 'trough')
    for sx in (-1, 1):
        part((sx * (length / 2 - 0.25), 0, -0.9), (0.5, width, 1.8), 'green', 'trough')
    part((-(length / 2 + 1.5), 0, 0.2), (3.0, 3.2, 2.6), 'metal_dark')
    for k in (-1, 1):
        part((k * length / 4, 0, 1.9), (1.2, width - 1.0, 0.6), 'green', 'trough')


def radar(m):
    rx, rz = RADAR
    R, A = 'move_radar', 'move_antenna'
    y0 = RADAR_Y
    # rotating head: equipment drum (stepped about Y), cabinet, trunnion yoke
    disc_y(m, R, rx, rz, y0, y0 + 1.2, 5.0, 'metal_dark', steps=3)
    m.box(R, rx - 5.0, rx + 5.0, y0 + 1.2, y0 + 9.0, rz - 4.0, rz + 6.0, 'green', tag='head')
    m.box(R, rx - 5.4, rx + 5.4, y0 + 9.0, y0 + 9.6, rz - 4.4, rz + 6.4, 'green', tag='head')
    m.box(R, rx - 4.0, rx + 4.0, y0 + 2.0, y0 + 8.0, rz + 6.0, rz + 9.4, 'green', tag='head')
    m.box(R, rx - 3.4, rx + 3.4, y0 + 3.0, y0 + 7.0, rz + 9.4, rz + 9.7, 'grille')
    for sx in (-1, 1):
        m.box(R, rx + sx * 3.2, rx + sx * 4.6, y0 + 9.6, ANTENNA_Y + 2.6, rz - 2.4, rz + 1.0, 'green', tag='yoke')
        disc_x(m, R, rx + sx * 4.6, rx + sx * 5.2, ANTENNA_Y, rz - 0.7, 1.6, 'metal_dark', steps=2)
    # TV/optical tracking sight on the head's left side
    m.box(R, rx + 5.0, rx + 8.4, y0 + 4.0, y0 + 8.0, rz - 3.6, rz + 2.4, 'green', tag='head')
    m.prism(R, 'z', (rx + 6.7, y0 + 6.0, rz - 4.4), 1.4, 1.6, 'metal_dark', n=6)
    m.prism(R, 'z', (rx + 6.7, y0 + 6.0, rz - 5.25), 1.0, 0.1, 'lens', n=6)
    # antenna block on the tilt axis: spine, two crossed trough antennas, command-link dish
    zc = rz - 6.0
    m.box(A, rx - 3.2, rx + 3.2, ANTENNA_Y - 2.0, ANTENNA_Y + 2.0, rz - 4.6, rz + 0.6, 'green', tag='spine_a')
    m.box(A, rx - 1.6, rx + 1.6, ANTENNA_Y - 12.0, ANTENNA_Y + 16.0, rz - 4.6, rz - 1.6, 'green', tag='spine_a')
    trough(m, A, (rx, ANTENNA_Y + 2.0, zc), 45.0, length=38.0)
    trough(m, A, (rx, ANTENNA_Y + 2.0, zc - 3.5), -45.0, length=38.0)
    dy = ANTENNA_Y + 14.6
    for c in rod_z(m, A, rx, dy, [(5.9, 6.9, 3.4, 'green'), (6.9, 7.9, 4.6, 'green'), (7.9, 8.8, 5.6, 'dish')],
                   n=8, zref=rz + 0.2):
        if c.mat == 'green':
            c.tag = 'dish_back'
    m.prism(A, 'z', (rx, dy, rz - 11.0), 0.45, 4.6, 'metal', n=4)
    m.prism(A, 'z', (rx, dy, rz - 13.8), 1.1, 1.2, 'metal_dark', n=6)
    m.box(A, rx - 1.0, rx + 1.0, dy - 4.0, dy - 1.6, rz - 7.8, rz - 4.6, 'green', tag='spine_a')


# --------------------------------------------------------------------- build ---
def build():
    m = Model('s125_pechora')
    m.bone('root', None)
    m.bone('base', 'root')
    m.bone('move_turret', 'base', TURRET_PIVOT)
    m.bone('move_launcher', 'move_turret', LAUNCHER_PIVOT)
    for i in range(4):
        m.bone('move_round_%d' % i, 'move_launcher', LAUNCHER_PIVOT)
    for k, (x, z) in enumerate(JACKS):
        m.bone('move_jack_%d' % k, 'base', (x, 10.4, z))
    m.bone('move_radar', 'base', (RADAR[0], RADAR_Y, RADAR[1]))
    m.bone('move_antenna', 'move_radar', (RADAR[0], ANTENNA_Y, RADAR[1] - 0.7))
    m.bone('move_door', 'base', (DOOR_HINGE[0], FLOOR, DOOR_HINGE[1]))
    base(m)
    turntable(m)
    launcher(m)
    booth(m)
    trailer(m)
    radar(m)
    return m


PREVIEW_POSES = {"s125_pechora": {
    "move_turret": axis_rot('y', 35.0),
    "move_launcher": axis_rot('x', 40.0),
    "move_radar": axis_rot('y', -35.0),
    "move_antenna": axis_rot('x', 8.0),
    "move_door": axis_rot('y', -80.0),
}}


# ------------------------------------------------------------------ painting ---
TAN = '#8d8158'
GREEN = '#56603b'
BOOSTER = '#c9cbc5'
SUSTAINER = '#b9bcb7'
BAND = '#ecebe3'
NOSE = '#4a4e4d'
MUD = np.array([88.0, 74.0, 56.0])
DUST = np.array([170.0, 158.0, 128.0])

PALETTE = {
    'metal': '#8e8f88', 'metal_dark': '#3f413b', 'rail': '#9c9d96', 'thread': '#6f6c62', 'pad': '#4a4a42',
    'cable': '#1e1f1c', 'strap': '#7a6a4a', 'hub': '#56603b', 'red': '#a3281e', 'lamp': '#e8dfb0',
    'lens': '#22343c', 'antenna': '#2b2c28', 'tread': '#5a5a52', 'tire': '#1d1d1d',
}

CYR = {  # 5x7 glyphs the shared font lacks (Cyrillic); look-alikes map to Latin glyphs
    'П': ['11111', '10001', '10001', '10001', '10001', '10001', '10001'],
    'Д': ['00110', '01010', '01010', '01010', '01010', '11111', '10001'],
    'Л': ['00111', '01001', '01001', '01001', '01001', '01001', '11001'],
    'У': ['10001', '10001', '10001', '01111', '00001', '10001', '01110'],
    'Я': ['01111', '10001', '10001', '01111', '00101', '01001', '10001'],
    'Ь': ['10000', '10000', '10000', '11110', '10001', '10001', '11110'],
    'Ы': ['10001', '10001', '10001', '11101', '10011', '10011', '11101'],
    'З': ['01110', '10001', '00001', '00110', '00001', '10001', '01110'],
}
LOOK = {'А': 'A', 'В': 'B', 'С': 'C', 'Е': 'E', 'Н': 'H', 'К': 'K', 'М': 'M', 'О': 'O', 'Р': 'P', 'Т': 'T',
        'Х': 'X'}


def tmask(text):
    glyphs = [CYR.get(ch) or FONT[LOOK.get(ch, ch)] for ch in text]
    rows = [[int(b) for g in glyphs for b in (g[r] + '0')][:-1] for r in range(7)]
    return np.array(rows, bool)


def stencil(text, color, pad=0):
    mask = tmask(text)
    if pad:
        mask = np.pad(mask, pad)
    return [(mask, color)]


def text_size(text, height):
    mask = tmask(text)
    return (height * mask.shape[1] / mask.shape[0], height)


def hazard_mask(p, pitch=1.6):
    return np.mod(p[:, 0] + p[:, 1] + p[:, 2], 2 * pitch) < pitch


def _rails_axes(p):
    """Axis (cx, cy) of the round nearest to each point (missile painting at rest)."""
    xs = np.array([LAUNCHER_PIVOT[0] + r[0] for r in RAILS])
    ys = np.array([LAUNCHER_PIVOT[1] + r[1] for r in RAILS])
    k = np.argmin(np.abs(p[:, 0:1] - xs[None, :]), 1)
    return xs[k], ys[k]


def edge_dist(s, t, wh):
    w, h = wh
    return np.minimum(np.minimum(s, 1 - s) * w, np.minimum(t, 1 - t) * h)


def details(p, n, cube):
    """Recessed lines, rivets, dark openings and raised highlights for a body face."""
    N = len(p)
    tag = cube.tag or ''
    x, y, z = p[:, 0], p[:, 1], p[:, 2]
    lines = np.zeros(N, bool)
    rivets = np.zeros(N, bool)
    dark = np.zeros(N, bool)
    side_x = abs(n[0]) > 0.5
    side_z = abs(n[2]) > 0.5
    top = n[1] > 0.5
    if tag in ('leg', 'leg_top'):
        along = np.maximum(np.abs(x), np.abs(z))
        lat = np.minimum(np.abs(x), np.abs(z))
        if top:
            rivets |= (np.abs(lat - 3.1) < 0.28) & (np.mod(along, 1.5) < 0.6)
        lines |= stations_mask(along, [24.0, 36.0], 0.25) & (lat < 3.0)
    elif tag == 'ped':
        ang = np.degrees(np.arctan2(z, x))
        lines |= np.mod(ang + 9.0, 72.0) < 1.2
        rivets |= stations_mask(y, [2.2, 8.8], 0.25) & (np.mod(ang, 6.0) < 1.6)
    elif tag == 'flange_ring' and top:
        rr = np.hypot(x, z)
        ang = np.degrees(np.arctan2(z, x))
        rivets |= (np.abs(rr - 13.5) < 0.45) & (np.mod(ang, 12.0) < 3.2)
    elif tag == 'gear':
        ang = np.degrees(np.arctan2(z, x))
        dark |= (np.mod(ang, 4.0) < 1.6) & (np.abs(n[1]) < 0.5)
    elif tag == 'deck' and top:
        u, v = np.mod(x + z, 1.8), np.mod(x - z, 1.8)
        lines |= (u < 0.35) & (v > 0.6) & (v < 1.2)
        rr = np.hypot(x, z)
        rivets |= (np.abs(rr - 19.0) < 0.35) & (np.mod(np.degrees(np.arctan2(z, x)), 8.0) < 2.4)
        lines |= np.abs(rr - 16.0) < 0.25
    elif tag in ('girder', 'flange', 'chord', 'post', 'frame', 'shield'):
        if tag == 'chord' and side_x:
            # lightening holes along the chords
            q = np.mod(z, 3.6) - 1.8
            yc = cube.origin[1] + cube.size[1] / 2
            dark |= (q * q / 1.0 + ((y - yc) / max(cube.size[1] * 0.32, 0.3)) ** 2) < 1.0
        if tag == 'girder' and side_z:
            q = np.mod(x, 7.0) - 3.5
            dark |= (np.abs(q) < 1.7) & (np.abs(y - 28.4) < 1.0) & (np.abs(x) > 10.0)
            rivets |= (np.abs(np.abs(q) - 2.6) < 0.25) & (np.mod(y, 1.2) < 0.5) & (np.abs(x) > 10.0)
        if tag in ('flange', 'chord') and top:
            rivets |= np.mod(z if tag == 'chord' else x, 1.4) < 0.45
            rivets &= (edge_dist_fake(p, cube) < 0.7)
        if tag == 'shield' and side_x:
            lines |= stations_mask(y, [36.0, 40.0], 0.2)
            rivets |= stations_mask(y, [36.0, 40.0], 0.6) & (np.mod(z, 1.3) < 0.45)
    elif tag in ('col', 'drive', 'cabinet', 'cabinet_r'):
        if tag == 'cabinet' and side_x:
            lines |= _hatch(z, y, -24.2, -18.4, 14.8, 19.8) | _hatch(z, y, -17.6, -11.8, 14.8, 19.8)
        if tag == 'cabinet' and side_z:
            lines |= _hatch(np.abs(x), y, 20.0, 28.0, 14.8, 19.8)
        if tag == 'cabinet_r' and side_x:
            lines |= _hatch(z, y, 5.6, 13.8, 15.0, 23.6)
        if tag == 'drive' and side_x:
            lines |= _hatch(z, y, -14.8, -6.0, 15.0, 22.0)
            rivets |= stations_mask(y, [14.6, 22.4], 0.25) & (np.mod(z, 1.2) < 0.4)
        if tag == 'col' and side_x:
            rivets |= (np.abs(np.hypot(y - 30.0, z) - 3.5) < 0.3) & (np.mod(np.degrees(np.arctan2(y - 30, z)), 30) < 8)
    elif tag in ('booth', 'post', 'roof', 'fvu', 'ac'):
        if tag == 'booth':
            if side_x:
                lines |= np.mod(z - CABIN[2], 7.0) < 0.3
            if side_z:
                lines |= np.mod(x - CABIN[0], 7.5) < 0.3
            lines |= stations_mask(y, [16.6], 0.2)
            rivets |= (stations_mask(y, [16.6], 0.65) | stations_mask(y, [8.0, 31.6], 0.25)) & \
                (np.mod((z if side_x else x), 1.3) < 0.45)
        if tag == 'roof' and top:
            lines |= np.mod(z - CABIN[2], 7.0) < 0.3
        if tag in ('fvu', 'ac'):
            rivets |= edge_dist_fake(p, cube) < 0.45
    elif tag in ('unv', 'roof_t', 'deck_t', 'panel_t', 'door_t'):
        if tag == 'unv':
            if side_x:
                lines |= _hatch(z, y, RADAR[1] - 13.0, RADAR[1] - 3.0, 12.0, 19.2)
                lines |= _hatch(z, y, RADAR[1] + 1.0, RADAR[1] + 13.0, 12.0, 19.2)
                rivets |= stations_mask(y, [11.8, 23.2], 0.25) & (np.mod(z, 1.3) < 0.45)
            if side_z:
                lines |= np.mod(x - RADAR[0], 5.6) < 0.25
    elif tag in ('trough', 'head', 'yoke', 'spine_a', 'dish_back'):
        if tag in ('head', 'yoke'):
            rivets |= edge_dist_fake(p, cube) < 0.5
        if tag == 'trough' and n[2] > 0.5:
            lines |= np.mod(x + y, 5.0) < 0.3
    elif tag == 'skid':
        rivets |= np.mod(z, 2.0) < 0.5
    return lines, rivets, dark


def _hatch(a, b, a0, a1, b0, b1, w=0.28):
    outer = (a > a0 - w) & (a < a1 + w) & (b > b0 - w) & (b < b1 + w)
    core = (a > a0 + w) & (a < a1 - w) & (b > b0 + w) & (b < b1 - w)
    return outer & ~core


def edge_dist_fake(p, cube):
    """Distance (model units) to the nearest edge of an axis-aligned cube, from its bounds."""
    o = np.array(cube.origin)
    s = np.array(cube.size)
    d = np.minimum(p - o, o + s - p)
    d = np.sort(d, 1)
    return d[:, 1]      # smallest is ~0 on the face itself; the next is the in-face edge distance


def weather(p, n, rgb, seed=7, mud_h=11.0, wear_k=1.0):
    g = fbm(p, 18.0, seed + 300, 3)
    k = 1 - 0.18 * np.clip((g - 0.45) * 2.4, 0, 1)
    if abs(n[1]) < 0.5:
        # rain streaks running down the sides
        st = value_noise(p * np.array([1.0, 0.07, 1.0]), 1.3, seed + 310)
        k *= 1 - 0.16 * np.clip((st - 0.55) * 3.0, 0, 1)
    out = rgb * k[:, None]
    # mud splashed up from the ground
    y = p[:, 1]
    splash = fbm(p * np.array([1.0, 1.6, 1.0]), 5.0, seed + 330, 2)
    mud = np.clip((mud_h - y) / mud_h, 0, 1) ** 1.2 * (0.35 + 0.9 * splash)
    if n[1] > 0.5:
        mud *= 1.25
    mud = np.clip(mud, 0, 0.9)
    out = out * (1 - mud[:, None]) + MUD * mud[:, None]
    if n[1] > 0.5:
        d = np.clip((fbm(p, 9.0, seed + 340, 2) - 0.45) * 2.0, 0, 1) * 0.3
        out = out * (1 - d[:, None]) + DUST * d[:, None]
    # rust spots and streaks
    rs = (value_noise(p, 1.1, seed + 350) > 0.82) & (fbm(p, 14.0, seed + 351, 2) > 0.55)
    out[rs] = out[rs] * 0.5 + np.array([104.0, 70.0, 44.0]) * 0.5
    return out


def paint_body(color, decals):
    base = hexrgb(color)

    def f(p, n, cube, face, s, t, wh):
        N = len(p)
        v = fbm(p, 46.0, 11, 3)
        rgb = np.tile(base, (N, 1)) * (0.9 + 0.2 * v)[:, None]
        hue = value_noise(p, 17.0, 12) - 0.5
        rgb[:, 0] += hue * 10
        rgb[:, 2] -= hue * 6
        up = max(float(n[1]), 0.0)
        rgb = rgb * (1 - 0.12 * up) + np.array([186.0, 176.0, 146.0]) * 0.12 * up      # sun-bleached tops
        if n[1] < -0.5:
            rgb *= 0.86
        lines, rivets, dark = details(p, n, cube)
        rgb[rivets & ~lines] = rgb[rivets & ~lines] * 0.84 + 6
        rgb[lines] *= 0.72
        rgb[dark] = rgb[dark] * 0.3
        rgb *= (1 + (value_noise(p, 5.0, 3) - 0.5) * 0.08)[:, None]
        # chipped paint along the edges
        e = edge_dist(s, t, wh)
        chip = (e < 1.6) & (value_noise(p, 0.8, 77) > 0.66)
        rgb[chip] = rgb[chip] * 0.55 + np.array([70.0, 64.0, 54.0]) * 0.45
        rgb = weather(p, n, rgb)
        rgba = np.concatenate([rgb, np.full((N, 1), 255.0)], 1)
        for d in decals:
            rgba = d.apply(p, n, rgba)
        return rgba
    return f


def paint_hazard(p, n, cube, face, s, t, wh):
    rgb = np.where(hazard_mask(p)[:, None], hexrgb('#d7a933'), hexrgb('#22221f'))
    return weather(p, n, rgb * (0.92 + 0.12 * value_noise(p, 2.0, 5))[:, None])


def paint_missile(decals):
    def f(p, n, cube, face, s, t, wh):
        N = len(p)
        sx = Z_REAR - p[:, 2]
        cx, cy = _rails_axes(p)
        ang = np.degrees(np.arctan2(p[:, 1] - cy, p[:, 0] - cx))
        rgb = np.where((sx < 30.0)[:, None], hexrgb(BOOSTER), hexrgb(SUSTAINER))
        rgb = rgb * (0.95 + 0.08 * fbm(p, 12.0, 51, 2))[:, None]
        band = (sx > 69.0) & (sx < 76.0)
        rgb[band] = hexrgb(BAND) * (0.97 + 0.04 * value_noise(p[band], 3.0, 52))[:, None]
        seams = [2.9, 15.6, 28.7, 33.9, 46.5, 60.5, 69.0, 76.0]
        lines = stations_mask(sx, seams, 0.22)
        rivets = stations_mask(sx, seams, 0.75) & ~lines & (np.mod(ang, 10.0) < 3.0)
        hatches = _hatch(sx, np.abs(ang), 63.0, 67.5, 30.0, 60.0) | _hatch(sx, np.abs(ang), 21.5, 27.0, 120.0, 150.0) \
            | _hatch(sx, ang, 64.5, 67.5, -100.0, -80.0)
        lines |= hatches
        rgb[rivets] *= 0.88
        rgb[lines] *= 0.74
        # red warhead band and black arming band
        rgb[(sx > 61.2) & (sx < 62.4)] = hexrgb('#a8302a')
        rgb[(sx > 30.3) & (sx < 31.2)] = hexrgb('#2a2b28')
        # scorch at the booster tail, light grime streaks running aft
        tail = np.clip((4.0 - sx) / 4.0, 0, 1)
        rgb *= (1 - 0.5 * tail)[:, None]
        st = value_noise(p * np.array([1.0, 1.0, 0.06]), 1.2, 53)
        rgb *= (1 - 0.1 * np.clip((st - 0.55) * 3, 0, 1))[:, None]
        rgb *= (1 - 0.08 * np.clip((fbm(p, 16.0, 54, 2) - 0.5) * 2, 0, 1))[:, None]
        rgba = np.concatenate([rgb, np.full((N, 1), 255.0)], 1)
        for d in decals:
            rgba = d.apply(p, n, rgba)
        return rgba
    return f


def paint_fin(p, n, cube, face, s, t, wh):
    N = len(p)
    sx = Z_REAR - p[:, 2]
    cx, cy = _rails_axes(p)
    rho = np.hypot(p[:, 0] - cx, p[:, 1] - cy)
    rgb = np.tile(hexrgb(BOOSTER if cube.tag == 'booster_fin' else SUSTAINER), (N, 1))
    rgb = rgb * (0.95 + 0.08 * fbm(p, 10.0, 55, 2))[:, None]
    lines = np.zeros(N, bool)
    if cube.tag == 'wing':
        lines |= (np.abs(sx - 41.0) < 0.25) & (rho > 6.9)          # aileron hinge
        lines |= (np.abs(rho - 6.9) < 0.25) & (sx < 41.0)
        lines |= np.abs(rho - 4.9) < 0.2
    elif cube.tag == 'booster_fin':
        lines |= (np.abs(rho - 8.6) < 0.22) | ((np.abs(sx - 5.0) < 0.22) & (rho > 6.4))
        rgb[(rho > FIN_R - 1.3)] *= 0.93
    rgb[lines] *= 0.76
    rgb *= (1 - 0.35 * np.clip((3.0 - sx) / 3.0, 0, 1))[:, None]
    return rgb


def paint_radome(p, n, cube, face, s, t, wh):
    sx = Z_REAR - p[:, 2]
    rgb = np.tile(hexrgb(NOSE), (len(p), 1)) * (0.92 + 0.12 * fbm(p, 6.0, 57, 2))[:, None]
    rgb[stations_mask(sx, [80.5, 87.8], 0.18)] *= 0.75
    if n[1] > 0.3:
        rgb = rgb * 1.12 + 6
    return rgb


def paint_window(p, n, cube, face, s, t, wh):
    rgb = np.tile(hexrgb('#1c2a2f'), (len(p), 1)) * (0.85 + 0.25 * t)[:, None]
    g = np.mod(p[:, 0] * 0.6 + p[:, 1] * 1.1 + p[:, 2] * 0.6, 17.0)
    glint = (g < 1.3) | ((g > 3.2) & (g < 3.8))
    rgb[glint] = rgb[glint] * 0.55 + 90
    if abs(n[0]) > 0.5 or abs(n[2]) > 0.5:
        rgb[t < 0.12] *= 0.7
    return rgb


def paint_slots(p, n, cube, face, s, t, wh):
    rgb = np.tile(hexrgb('#30352d'), (len(p), 1))
    w, h = wh
    # staggered radiating slots along the trough
    a = s * w
    b = t * h
    slot = (np.mod(a, 2.6) < 1.5) & (np.abs(b - h / 2 + np.where(np.mod(a, 5.2) < 2.6, 1.0, -1.0)) < 0.7)
    rgb[slot] = hexrgb('#11130f')
    return rgb * (0.92 + 0.1 * value_noise(p, 3.0, 61))[:, None]


def paint_dish(p, n, cube, face, s, t, wh):
    rx, rz = RADAR
    dy = ANTENNA_Y + 14.6
    r = np.hypot(p[:, 0] - rx, p[:, 1] - dy)
    rgb = np.tile(hexrgb('#8f9583'), (len(p), 1))
    if n[2] < -0.5:     # the reflector face: shaded towards the centre, radial ribs
        rgb = rgb * (0.55 + 0.45 * np.clip(r / 5.6, 0, 1))[:, None]
        ang = np.degrees(np.arctan2(p[:, 1] - dy, p[:, 0] - rx))
        rgb[(np.mod(ang, 45.0) < 2.5) & (r > 1.2)] *= 0.8
    return rgb


def paint_grille(p, n, cube, face, s, t, wh):
    rgb = np.tile(hexrgb('#3d4232'), (len(p), 1))
    rgb[np.mod(p[:, 1], 0.9) < 0.4] = hexrgb('#1a1c16')
    return rgb


def paint_metal(key):
    col = hexrgb(PALETTE[key])

    def f(p, n, cube, face, s, t, wh):
        rgb = np.tile(col, (len(p), 1)) * (0.9 + 0.18 * value_noise(p, 2.5, 71))[:, None]
        if key == 'thread':
            rgb[np.mod(p[:, 1], 0.55) < 0.22] *= 0.65
        if key == 'rail':
            if n[1] > 0.5:
                rgb = rgb * 1.15 + 10
        if key in ('pad', 'metal_dark', 'thread', 'metal'):
            rgb = weather(p, n, rgb, seed=9, mud_h=6.0)
        return rgb
    return f


def paint_cable(p, n, cube, face, s, t, wh):
    col = hexrgb(PALETTE[cube.mat])
    rgb = np.tile(col, (len(p), 1)) * (0.9 + 0.2 * value_noise(p, 1.5, 73))[:, None]
    return weather(p, n, rgb, seed=11, mud_h=4.0)


def painter():
    tan_decals, green_decals, round_decals = [], [], []
    # launcher type stencil on the outer beam chords and the cradle girder, unit number on the cabinets
    for sx in (1, -1):
        for rx in (16.0, 40.0):
            c = (sx * (rx + 1.9), 30.0 + (9.0 if rx < 20 else 5.0) - 5.8, -30.0)
            tan_decals.append(Decal(c, (sx, 0, 0), (0, 0, sx), (0, -1, 0), text_size('5П73', 1.3),
                                    stencil('5П73', (236, 232, 214)), max_depth=0.6))
        tan_decals.append(Decal((sx * 29.0, 17.4, -18.0), (sx, 0, 0), (0, 0, sx), (0, -1, 0), text_size('23', 4.2),
                                stencil('23', (232, 228, 210)), max_depth=0.6))
    for sx in (1, -1):
        tan_decals.append(Decal((sx * 27.0, 28.5, -5.4), (0, 0, -1), (1, 0, 0), (0, -1, 0),
                                text_size('НЕ СТОЯТЬ', 2.5), stencil('НЕ СТОЯТЬ', (190, 40, 30)), max_depth=0.5))
    # booth: number on the front, type on the outer side; trailer type on both sides
    x0, x1, z0, z1 = CABIN
    green_decals.append(Decal((x0 + 7.0, 12.4, z0 - 0.2), (0, 0, -1), (1, 0, 0), (0, -1, 0), text_size('214', 4.2),
                              stencil('214', (232, 230, 216)), max_depth=0.8))
    green_decals.append(Decal((x0 - 0.2, 12.0, z0 + 13.5), (-1, 0, 0), (0, 0, -1), (0, -1, 0),
                              text_size('УНК-2', 4.6), stencil('УНК-2', (232, 230, 216)), max_depth=0.8))
    for sx in (1, -1):
        green_decals.append(Decal((RADAR[0] + sx * 8.5, 21.6, RADAR[1]), (sx, 0, 0), (0, 0, sx), (0, -1, 0),
                                  text_size('СНР-125', 3.6), stencil('СНР-125', (232, 230, 216)), max_depth=0.6))
    # rounds: type on the booster, designation and warning on the sustainer, both sides
    for rx, ry in RAILS:
        cx, cy = LAUNCHER_PIVOT[0] + rx, LAUNCHER_PIVOT[1] + ry
        for sx in (1, -1):
            for text, s, h, r, col in (('5В27', 15.0, 4.0, RB, (36, 36, 34)), ('В-601П', 49.5, 4.2, RS, (36, 36, 34))):
                round_decals.append(Decal((cx + sx * r, cy, Z(s)), (sx, 0, 0), (0, 0, sx), (0, -1, 0), text_size(text, h),
                                          stencil(text, col), max_depth=r * 0.45))
    tan = paint_body(TAN, tan_decals)
    green = paint_body(GREEN, green_decals)
    over = {
        'tan': tan, 'green': green, 'hazard': paint_hazard, 'missile': paint_missile(round_decals),
        'fin': paint_fin, 'radome': paint_radome, 'window': paint_window, 'slots': paint_slots,
        'dish': paint_dish, 'grille': paint_grille, 'cable': paint_cable, 'strap': paint_cable,
    }
    for key in ('metal', 'metal_dark', 'rail', 'thread', 'pad', 'tread'):
        over[key] = paint_metal(key)
    return Painter(PALETTE, lambda p, n: np.tile(hexrgb(TAN), (len(p), 1)), overrides=over, glass_alpha=255)


MODELS = {"s125_pechora": (build, painter)}
