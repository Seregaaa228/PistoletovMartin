#!/usr/bin/env python3
"""Fit every hung store of the Su-25, F-16 and J-10 against its airframe.

    python3 tools/check_aircraft_store_fit.py [--describe] [--verbose]

The airframe is the shipped bedrock geo (modules/aviation/.../vehicle/<id>.geo.json)
in four poses: flight (gear stowed, doors closed, flaps up), both extremes of
every control surface and air brake, and on the ground (gear, open doors, flaps
down, ground plane y = 0). Stores are the models AircraftRenderer draws: the SBW
projectile models, the DeepStrike Storm Shadow without wings and fins and our
КАБ, read from the pinned jars in instance/mods (or --mods). Each store is
placed with the transforms AircraftRenderer applies, from the station table in
ordnance/StoreStations.java, which this script parses.

Every store a station takes and the jet's vehicle json fires (plus the tablet
Storm Shadow / КАБ) is checked. A store must touch nothing, ground included, and
hang from its own pylon (or, with no pylon bone there, from the wing or belly)
at STORE_CLEARANCE to STORE_CLEARANCE + SLACK below it. Stores on different
stations must not touch. Clearance is the separating-axis gap between convex
pieces (cubes and mesh faces): a lower bound of the true distance, and <= 0
exactly when they overlap. Exit status 1 on any failure.
"""
from __future__ import annotations

import argparse
import itertools
import json
import math
from pathlib import Path
import re
import sys
import zipfile

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
AVIATION = ROOT / 'modules/aviation/src/main'
ASSETS = AVIATION / 'resources/assets/frontier_aviation'
STATIONS = AVIATION / 'java/dev/frontier/aviation/ordnance/StoreStations.java'
PX = 16.0
EPS = 1e-4   # m; contact within this is touching, not intersecting
SLACK = 0.06    # m; a hung store's top may be this much below the clearance, not more (no floating); the J-10 belly curves under the wide КАБ-3000


# ---------------------------------------------------------------- geometry
def rot_matrix(rotation):
    """Bedrock Euler rotation in the geo frame (as art/realistic/kit.py and DeepStrike's baker)."""
    if not rotation or not any(rotation):
        return np.eye(3)
    rx, ry, rz = (math.radians(a) for a in rotation)
    rx, rz = -rx, -rz
    cx, sx, cy, sy, cz, sz = math.cos(rx), math.sin(rx), math.cos(ry), math.sin(ry), math.cos(rz), math.sin(rz)
    mx = np.array([[1, 0, 0], [0, cx, -sx], [0, sx, cx]])
    my = np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]])
    mz = np.array([[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]])
    return mz @ my @ mx


def axis_rotation(axis, degrees):
    """Right-handed rotation about a geo axis."""
    a = math.radians(degrees)
    c, s = math.cos(a), math.sin(a)
    return {'x': np.array([[1, 0, 0], [0, c, -s], [0, s, c]]),
            'y': np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]]),
            'z': np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])}[axis]


def affine(r=None, t=None):
    m = np.eye(4)
    if r is not None:
        m[:3, :3] = r
    if t is not None:
        m[:3, 3] = t
    return m


def about(pivot, r):
    p = np.asarray(pivot, float)
    return affine(t=p) @ affine(r) @ affine(t=-p)


class Piece:
    """A convex piece: its points, face normals and edge directions (metres)."""
    __slots__ = ('bone', 'points', 'normals', 'edges', 'lo', 'hi')

    def __init__(self, bone, points, normals, edges):
        self.bone = bone
        self.points = points
        self.normals = normals
        self.edges = edges
        self.lo = points.min(0)
        self.hi = points.max(0)

    def moved(self, m):
        pts = self.points @ m[:3, :3].T + m[:3, 3]
        return Piece(self.bone, pts, self.normals @ m[:3, :3].T, self.edges @ m[:3, :3].T)


def unit_rows(v):
    n = np.linalg.norm(v, axis=1)
    keep = n > 1e-9
    return v[keep] / n[keep, None]


def load_geo(data, hide=(), pose=None):
    """Pieces of a bedrock geo in its own frame (metres, geo axes). pose: {bone: 3x3 rotation about its pivot}."""
    geo = data['minecraft:geometry'][0]
    bones = {b['name']: b for b in geo['bones']}
    pose = pose or {}
    cache = {}

    def world(name):
        if name in cache:
            return cache[name]
        b = bones[name]
        pivot = np.array(b.get('pivot', [0, 0, 0]), float) / PX
        local = about(pivot, rot_matrix(b.get('rotation')))
        if name in pose:
            local = local @ about(pivot, pose[name])
        m = (world(b['parent']) if b.get('parent') in bones else np.eye(4)) @ local
        cache[name] = m
        return m

    def hidden(name):
        while name:
            if name in hide:
                return True
            name = bones[name].get('parent') if bones[name].get('parent') in bones else None
        return False

    pieces = []
    for name, b in bones.items():
        if hidden(name):
            continue
        m = world(name)
        for c in b.get('cubes', []):
            o = np.array(c['origin'], float) / PX
            s = np.array(c['size'], float) / PX
            if min(s) < 0:
                o, s = np.minimum(o, o + s), np.abs(s)
            inflate = c.get('inflate', 0) / PX
            o, s = o - inflate, s + 2 * inflate
            corners = np.array([[o[0] + s[0] * i, o[1] + s[1] * j, o[2] + s[2] * k]
                                for i in (0, 1) for j in (0, 1) for k in (0, 1)])
            r = rot_matrix(c.get('rotation'))
            p = np.array(c.get('pivot', [0, 0, 0]), float) / PX
            cm = m @ about(p, r)
            axes = unit_rows(np.eye(3) @ r.T @ m[:3, :3].T)
            pieces.append(Piece(name, corners @ cm[:3, :3].T + cm[:3, 3], axes, axes))
        mesh = b.get('poly_mesh')
        if mesh:
            pos = np.array(mesh['positions'], float) / PX
            for poly in mesh['polys']:
                idx = list(dict.fromkeys(v[0] for v in poly))
                if len(idx) < 3:
                    continue
                pts = pos[idx] @ m[:3, :3].T + m[:3, 3]
                normal = unit_rows(np.cross(pts[1] - pts[0], pts[2] - pts[0])[None])
                edges = unit_rows(np.roll(pts, -1, 0) - pts)
                if len(normal):
                    pieces.append(Piece(name, pts, normal, edges))
    return pieces


def gap(a, b):
    """Largest separating-axis gap (m). > 0: apart by at least that; <= 0: overlapping."""
    if np.any(a.lo > b.hi + 1) or np.any(b.lo > a.hi + 1):
        return float(max(np.max(a.lo - b.hi), np.max(b.lo - a.hi)))
    cross = np.cross(a.edges[:, None, :], b.edges[None, :, :]).reshape(-1, 3)
    axes = np.concatenate([a.normals, b.normals, unit_rows(cross)])
    pa, pb = a.points @ axes.T, b.points @ axes.T
    return float(np.max(np.maximum(pb.min(0) - pa.max(0), pa.min(0) - pb.max(0))))


def clearance(store, others):
    best = math.inf, None
    lo = np.min([p.lo for p in store], 0) - 0.6
    hi = np.max([p.hi for p in store], 0) + 0.6
    for o in others:
        if np.any(o.lo > hi) or np.any(o.hi < lo):
            continue
        for s in store:
            g = gap(s, o)
            if g < best[0]:
                best = g, o.bone
    return best


# ---------------------------------------------------------------- inputs
def jar_entry(mods, prefix, entry):
    for jar in sorted(mods.glob(prefix + '*.jar')):
        with zipfile.ZipFile(jar) as archive:
            return json.loads(archive.read(entry))
    raise FileNotFoundError(f'{prefix}*.jar in {mods} (link instance/mods or pass --mods)')


def java_doubles(source, name):
    match = re.search(r'%s\s*=\s*\{([^}]*)\}' % re.escape(name), source)
    if not match:
        raise ValueError(f'{name} not found')
    return [float(v) for v in re.findall(r'-?\d+(?:\.\d+)?', match.group(1))]


def parse_stations(source):
    """station(x, y, z, stores...) rows of each layout("id", ...) in StoreStations.java (render frame)."""
    layouts = {}
    heads = list(re.finditer(r'layout\("(\w+)"', source))
    for head, nxt in zip(heads, heads[1:] + [None]):
        body = source[head.end():nxt.start() if nxt else len(source)]
        rows = layouts.setdefault(head.group(1), [])
        for m in re.finditer(r'station\(\s*(-?[\d.]+)\s*,\s*(-?[\d.]+)\s*,\s*(-?[\d.]+)\s*,([\w,\s]+)\)', body):
            x, y, z, stores = m.groups()
            names = set(re.findall(r'\b(STORM|WING|LIGHT)\b', stores))
            classes = {'STORM': {'STORM', 'KAB'}, 'WING': {'UNDERWING'}, 'LIGHT': {'LIGHT_AAM'}}
            rows.append({'x': float(x), 'y': float(y), 'z': float(z),
                         'classes': set().union(*(classes[n] for n in names))})
    if set(layouts) != {'su25', 'f16', 'j10'}:
        raise ValueError(f'unexpected layouts {sorted(layouts)}')
    return layouts


# Renderer transforms (render frame: x = -geo x, y up, z = geo z, nose -Z).
SBW = ['agm_65', 'ru_9m100_missile', 'ru_9m336_missile', 'wire_guide_missile']
CLASS_KINDS = {'UNDERWING': ['agm_65', 'ru_9m100_missile', 'wire_guide_missile'],
               'LIGHT_AAM': ['ru_9m336_missile'], 'STORM': ['storm_shadow'],
               'KAB': ['kab_500', 'kab_1500', 'kab_3000']}


def to_render(pieces):
    """A baked model: X mirrored, as SBW's and DeepStrike's bakers do."""
    return [p.moved(affine(np.diag([-1.0, 1, 1]))) for p in pieces]


def render_to_geo(pieces):
    return [p.moved(affine(np.diag([-1.0, 1, 1]))) for p in pieces]


def store_models(mods):
    sbw = {k: jar_entry(mods, 'superbwarfare', f'assets/superbwarfare/models/bedrock/projectile/{k}.geo.json') for k in SBW}
    models = {k: to_render(load_geo(v, hide={'flare'})) for k, v in sbw.items()}
    storm = jar_entry(mods, 'dsm', 'assets/deepstrike/geo/storm_shadow.geo.json')
    bones = [b['name'] for b in storm['minecraft:geometry'][0]['bones']]
    models['storm_shadow'] = [p.moved(affine(axis_rotation('y', -90))) for p in
                              to_render(load_geo(storm, hide={b for b in bones if b.startswith(('wing_', 'fin_'))}))]
    kab = json.loads((ASSETS / 'models/bedrock/projectile/kab_500.geo.json').read_text())
    # Folded wings: the renderer's rotateY(-/+88) in the baked (mirrored) frame.
    fold = {'wing_left': axis_rotation('y', 88), 'wing_right': axis_rotation('y', -88)}
    base = to_render(load_geo(kab, pose=fold))
    for name, scale in (('kab_500', 1.0), ('kab_1500', 1.25), ('kab_3000', 1.5)):
        models[name] = [p.moved(affine(np.eye(3) * scale)) for p in base]
    return models


def extent(pieces):
    return np.min([p.lo for p in pieces], 0), np.max([p.hi for p in pieces], 0)


def hang(source):
    """Model-origin offsets the renderer applies under an attach point (render frame)."""
    top = dict(zip(['agm_65', 'ru_9m100_missile', 'ru_9m336_missile', 'wire_guide_missile', 'storm_shadow',
                    'kab_500', 'kab_1500', 'kab_3000'], java_doubles(source, 'STORE_TOP')))
    centre = dict(zip(top, java_doubles(source, 'STORE_CENTER_Z')))
    gapm = float(re.search(r'STORE_CLEARANCE\s*=\s*([\d.]+)', source).group(1))
    return {k: np.array([0.0, -top[k] - gapm, centre[k]]) for k in top}, gapm


# ---------------------------------------------------------------- airframe poses
SURFACES = {   # bone: (geo axis, degrees at full deflection), signs per the renderer's intent
    'aileron_left': ('x', 25), 'aileron_right': ('x', -25), 'elevator_left': ('x', 25), 'elevator_right': ('x', 25),
    'elevon_left': ('x', 40), 'elevon_right': ('x', -40), 'canard_left': ('x', 20), 'canard_right': ('x', 20),
    'f16_aileron_left': ('x', 25), 'f16_aileron_right': ('x', -25),
    'f16_elevator_left': ('x', 25), 'f16_elevator_right': ('x', 25),
}
BRAKES = {'airbrake_left_upper': ('x', -50), 'airbrake_left_lower': ('x', 50),
          'airbrake_right_upper': ('x', -50), 'airbrake_right_lower': ('x', 50),
          'airbrake_left': ('y', 50), 'airbrake_right': ('y', -50),
          'f16_airbrake_left': ('y', 55), 'f16_airbrake_right': ('y', -55)}
GEAR = ('gear_nose', 'gear_main_l', 'gear_main_r')


def poses(geo):
    names = {b['name'] for b in geo['minecraft:geometry'][0]['bones']}
    gear = {n for n in names if n.startswith(GEAR)}
    flaps = {n: axis_rotation('x', 30) for n in ('flap_left', 'flap_right') if n in names}
    # Doors: the free edge swings down. "_l" hinges on its +X edge.
    doors = {n: axis_rotation('z', 85 if n.endswith('_l') else -85) for n in names if n.startswith('gear_door_')}
    out = {'flight': ({}, gear)}
    for sign, label in ((1, 'controls+'), (-1, 'controls-')):
        pose = {n: axis_rotation(a, sign * d) for n, (a, d) in SURFACES.items() if n in names}
        pose.update({n: axis_rotation(a, d) for n, (a, d) in BRAKES.items() if n in names})
        out[label] = (pose, gear)
    out['ground'] = ({**flaps, **doors}, set())
    return out


def pylon_bones(names):
    return {n for n in names if 'pylon' in n}


# ---------------------------------------------------------------- report
def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument('--mods', type=Path, default=ROOT / 'instance/mods')
    parser.add_argument('--describe', action='store_true', help='print store extents and exit')
    parser.add_argument('--verbose', action='store_true')
    args = parser.parse_args(argv)
    models = store_models(args.mods)
    if args.describe:
        for k, v in models.items():
            lo, hi = extent(v)
            print(f'{k:20s} x {lo[0]:+.3f}..{hi[0]:+.3f}  y {lo[1]:+.3f}..{hi[1]:+.3f}  z {lo[2]:+.3f}..{hi[2]:+.3f}')
        return 0
    source = STATIONS.read_text()
    offsets, allowed = hang(source)
    layouts = parse_stations(source)
    failures, total = evaluate(models, layouts, offsets, allowed, args.verbose)
    print(f'{"PASS" if not failures else "FAIL"}: {total - failures}/{total} store placements and pairs clear')
    return 1 if failures else 0


def carried(jet):
    """Store kinds the jet's native weapons fire (vehicle json), plus the tablet stores."""
    config = json.loads((AVIATION / f'resources/data/frontier_aviation/sbw/vehicles/{jet}.json').read_text())
    shot = {w.get('Projectile', '').split(':')[-1] for w in config['Weapons'].values()}
    return (shot & set(SBW)) | set(CLASS_KINDS['STORM']) | set(CLASS_KINDS['KAB'])


def evaluate(models, layouts, offsets, allowed, verbose=False, kinds_by_jet=None):
    """(failures, checks). offsets: {kind: model origin relative to the attach point}."""
    failures = total = 0
    for jet in ('su25', 'f16', 'j10'):
        kinds = carried(jet) if kinds_by_jet is None else kinds_by_jet[jet]
        geo = json.loads((ASSETS / f'models/bedrock/vehicle/{jet}.geo.json').read_text())
        names = {b['name'] for b in geo['minecraft:geometry'][0]['bones']}
        pylons = pylon_bones(names)
        frames = {label: load_geo(geo, hide=hide, pose=pose) for label, (pose, hide) in poses(geo).items()}
        stations = layouts.get(jet, [])
        placed = {}
        for i, st in enumerate(stations):
            for cls in sorted(st['classes']):
                for kind in (k for k in CLASS_KINDS[cls] if k in kinds):
                    t = np.array([st['x'], st['y'], st['z']]) + offsets[kind]
                    placed[(i, kind)] = render_to_geo([p.moved(affine(t=t)) for p in models[kind]])
        print(f'{jet}: {len(stations)} stations, {len(placed)} station/store combinations')
        bad = 0
        for (i, kind), store in placed.items():
            gx = -stations[i]['x']   # attach point in the geo frame
            mine = {p.bone for p in frames['flight'] if p.bone in pylons and p.lo[0] - 0.05 <= gx <= p.hi[0] + 0.05}
            worst, where, own = math.inf, '', math.inf
            for label, frame in frames.items():
                g, bone = clearance(store, [p for p in frame if p.bone not in mine])
                if g < worst:
                    worst, where = g, f'{bone} ({label})'
                if label == 'flight' and mine:
                    own = clearance(store, [p for p in frame if p.bone in mine])[0]
            ground = min(p.lo[1] for p in store)
            # Hung from its pylon (or, with none, close under the fuselage), touching nothing else.
            hang_gap = own if mine else worst
            ok = worst > EPS and ground > EPS and -EPS <= hang_gap <= allowed + SLACK
            total += 1
            bad += not ok
            if verbose or not ok:
                print(f'  {"ok  " if ok else "FAIL"} st{i} {kind:18s} clear {worst:+.3f} m of {where}; '
                      f'{"pylon" if mine else "no pylon"} {hang_gap:+.3f} m; ground {ground:+.3f} m')
        for (i, a), (j, b) in itertools.combinations(placed, 2):
            if i == j:
                continue
            g = clearance(placed[(i, a)], placed[(j, b)])[0]
            total += 1
            if g <= EPS:
                bad += 1
                print(f'  FAIL st{i} {a} and st{j} {b} overlap ({g:+.3f} m)')
        print(f'  {jet}: {bad} failing')
        failures += bad
    return failures, total


if __name__ == '__main__':
    sys.exit(main())
