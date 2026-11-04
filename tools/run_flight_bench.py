#!/usr/bin/env python3
"""Repeatable flight benchmark on a fresh copy of a map, on the real GPU.

Creates a disposable profile with the play profile's mods, configs and options, copies
the map template into it (or into the local dedicated test server), joins it with Quick
Play and lets the opt-in client autopilot fly a fixed F-16 route at full throttle. The
client writes flight-bench-result.json and quits; the server tick histogram for the same
window is read from the log. One JSON line per run is appended to
vendor/flight-bench/results.jsonl, so before/after runs of a change can be compared.
"""
from __future__ import annotations

import argparse
import json
import os
import re
from pathlib import Path
import shutil
import signal
import socket
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
STORAGE = Path('/mnt/storage/PistoletovMartin-local-data')
DEFAULT_TEMPLATE = STORAGE / 'worlds/military-island-1.20.1'
BENCH = ROOT / 'vendor/flight-bench'
SERVER = ROOT / 'run-play-server'


def server_ticks(log: Path) -> dict | None:
    """The wholeServerTick line of the window between bench 'measure' and 'finish'."""
    lines = [line for line in log.read_text(errors='replace').splitlines() if 'metric=wholeServerTick window=bench' in line]
    if not lines:
        return None
    fields = dict(part.split('=', 1) for part in lines[-1].split('metric=wholeServerTick', 1)[1].split() if '=' in part)
    samples = int(fields['samples'])
    return {'ticks': samples, 'averageMs': round(int(fields['sum']) / max(1, samples) / 1e6, 2),
            'maxMs': round(int(fields['max']) / 1e6, 1), 'p95UpperMs': int(fields['p95Upper']) / 1e6,
            'p99UpperMs': int(fields['p99Upper']) / 1e6}


def wait_port(port: int, timeout: float) -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        with socket.socket() as probe:
            if probe.connect_ex(('127.0.0.1', port)) == 0:
                return True
        time.sleep(2)
    return False


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mode', choices=('singleplayer', 'server'), default='singleplayer')
    parser.add_argument('--label', required=True, help='Short name for this run, e.g. baseline or light-budget')
    parser.add_argument('--render-distance', type=int, default=32)
    parser.add_argument('--route', default='-2500,200,0,-90', help='Start x,y,z,yaw; yaw -90 flies east')
    parser.add_argument('--measure-ticks', type=int, default=1800, help='Measured window after a 10 s settle')
    parser.add_argument('--world-template', type=Path, default=DEFAULT_TEMPLATE)
    parser.add_argument('--play-profile', type=Path, default=ROOT / 'play-tablet')
    parser.add_argument('--hold', type=int, default=0,
                        help='Instead of flying, park at the route start this many seconds (e.g. to build LODs)')
    parser.add_argument('--shots', action='store_true', help='Take two screenshots during the measured window')
    parser.add_argument('--save-world', type=Path,
                        help='Singleplayer: keep the world after the run at this new path (e.g. a LOD-baked template)')
    parser.add_argument('--client-property', action='append', default=[], metavar='frontier.KEY=VALUE',
                        help='Extra frontier.* system property for the bench client (repeatable)')
    parser.add_argument('--config-set', action='append', default=[], metavar='FILE:KEY=VALUE',
                        help='Replace one key in a copied config file for this run (repeatable)')
    parser.add_argument('--client-extra-mods', type=Path, nargs='+', default=[],
                        help='Project-local JARs added to the bench client (e.g. a far-terrain LOD mod)')
    parser.add_argument('--client-remove-mods', nargs='+', default=[],
                        help='File-name prefixes of play-profile mods left out of the bench client')
    parser.add_argument('--server-extra-mods', type=Path, nargs='+', default=[],
                        help='Server mode: project-local JARs added to the test server for this run only')
    args = parser.parse_args()
    if not 2 <= args.render_distance <= 32 or not 200 <= args.measure_ticks <= 6000:
        raise ValueError('Render distance 2..32 and a 200..6000 tick window')
    template = args.world_template.resolve()
    if not (template / 'level.dat').is_file():
        raise ValueError('World template has no level.dat: ' + str(template))
    play = args.play_profile.resolve()
    run_id = args.label + '-' + uuid.uuid4().hex[:6]
    profile = BENCH / run_id
    subprocess.run(['python3', 'tools/create_test_profile.py', str(profile)], cwd=ROOT, check=True,
                   stdout=subprocess.DEVNULL)
    # The play profile's exact mods, configs and options, so the bench matches real play.
    for jar in (profile / 'mods').glob('*.jar'):
        jar.unlink()
    for jar in (play / 'mods').glob('*.jar'):
        if not any(jar.name.startswith(prefix) for prefix in args.client_remove_mods):
            shutil.copy2(jar, profile / 'mods' / jar.name)
    for extra in args.client_extra_mods:
        extra = extra.resolve()
        if not extra.is_relative_to(ROOT) or extra.suffix != '.jar' or not extra.is_file():
            raise ValueError('Client extra mods must be project-local JAR files: ' + str(extra))
        shutil.copy2(extra, profile / 'mods' / extra.name)
    shutil.copytree(play / 'config', profile / 'config', dirs_exist_ok=True)
    for change in args.config_set:
        name, assignment = change.split(':', 1)
        key, value = assignment.split('=', 1)
        path = (profile / 'config' / name).resolve()
        if not path.is_relative_to(profile / 'config') or not path.is_file():
            raise ValueError('No such config file in the bench profile: ' + name)
        text, count = re.subn(r'(?m)^(\s*' + re.escape(key) + r'\s*=\s*).*$', lambda m: m.group(1) + value, path.read_text())
        if count != 1:
            raise ValueError(f'{key} matched {count} lines in {name}')
        path.write_text(text)
    settings = {'renderDistance': str(args.render_distance), 'pauseOnLostFocus': 'false',
                'onboardAccessibility': 'false', 'tutorialStep': 'none'}
    lines = [line for line in (play / 'options.txt').read_text().splitlines() if line.split(':', 1)[0] not in settings]
    (profile / 'options.txt').write_text('\n'.join(lines + [k + ':' + v for k, v in settings.items()]) + '\n')
    manifest = json.loads((profile / 'test-profile.json').read_text())
    manifest['benchWorldTemplate'] = str(template)
    manifest['description'] = 'Flight benchmark: play-profile mods and a disposable copy of a map template.'
    (profile / 'test-profile.json').write_text(json.dumps(manifest, indent=2) + '\n')

    commit = subprocess.run(['git', 'rev-parse', '--short', 'HEAD'], cwd=ROOT, text=True, capture_output=True).stdout.strip()
    dirty = bool(subprocess.run(['git', 'status', '--porcelain', '--', 'modules', 'tools'], cwd=ROOT, text=True,
                                capture_output=True).stdout.strip())
    record = {'label': args.label, 'run': run_id, 'mode': args.mode, 'renderDistance': args.render_distance,
              'route': args.route, 'clientExtraMods': [path.name for path in args.client_extra_mods],
              'clientRemovedMods': args.client_remove_mods, 'configSet': args.config_set, 'commit': commit + ('+dirty' if dirty else ''), 'startedAt': time.strftime('%F %T')}
    client_cmd = ['python3', 'tools/run_test_client.py', str(profile), '--skip-benchmark', '--gpu', 'nvidia',
                  '--memory', '12G', '--flight-bench=' + args.route,
                  '--flight-bench-ticks', str(args.measure_ticks), '--flight-bench-hold', str(args.hold)]
    if args.shots:
        client_cmd.append('--flight-bench-shots')
    for prop in args.client_property:
        client_cmd += ['--property', prop]
    record['clientProperties'] = args.client_property
    if args.save_world and (args.mode != 'singleplayer' or args.save_world.exists()):
        raise ValueError('--save-world needs singleplayer and a new path')
    server = None
    client = None
    world_copy = None
    added_server_mods = []
    try:
        if args.mode == 'singleplayer':
            world_copy = profile / 'saves' / 'bench'
            shutil.copytree(template, world_copy)
            client_cmd += ['--quick-play-world', 'bench']
            log = profile / 'logs/latest.log'
        else:
            if not (SERVER / 'eula.txt').is_file() or 'eula=true' not in (SERVER / 'eula.txt').read_text():
                raise ValueError('The local test server needs the owner\'s EULA acceptance first')
            world_copy = STORAGE / 'bench-worlds' / run_id
            shutil.copytree(template, world_copy)
            link = SERVER / 'world'
            if link.exists() and not link.is_symlink():
                raise ValueError('Server world is not a symlink; refusing to replace it')
            previous_world = os.readlink(link) if link.is_symlink() else None
            record['serverWorldBefore'] = previous_world
            link.unlink(missing_ok=True)
            link.symlink_to(world_copy)
            props = (SERVER / 'server.properties').read_text().splitlines()
            props = [p for p in props if not p.startswith(('view-distance=', 'simulation-distance='))]
            props += ['view-distance=' + str(args.render_distance), 'simulation-distance=8']
            (SERVER / 'server.properties').write_text('\n'.join(props) + '\n')
            for extra in args.server_extra_mods:
                extra = extra.resolve()
                if not extra.is_relative_to(ROOT) or extra.suffix != '.jar' or not extra.is_file():
                    raise ValueError('Server extra mods must be project-local JAR files: ' + str(extra))
                target = SERVER / 'mods' / extra.name
                if target.exists():
                    raise ValueError('Already on the test server: ' + extra.name)
                shutil.copy2(extra, target)
                added_server_mods.append(target)
            record['serverExtraMods'] = [path.name for path in added_server_mods]
            server = subprocess.Popen(['python3', 'tools/run_test_server.py', str(SERVER), '--memory', '8G', '--flight-bench'],
                                      cwd=ROOT, stdin=subprocess.PIPE, stdout=(profile / 'server-console.log').open('w'),
                                      stderr=subprocess.STDOUT, start_new_session=True, text=True)
            if not wait_port(25565, 300):
                raise RuntimeError('Local test server did not open port 25565')
            client_cmd += ['--quick-play-server', '127.0.0.1:25565']
            log = SERVER / 'logs/latest.log'
        print('flying', run_id, flush=True)
        client = subprocess.Popen(client_cmd, cwd=ROOT, stdin=subprocess.DEVNULL,
                                  stdout=(profile / 'client-console.log').open('w'), stderr=subprocess.STDOUT,
                                  start_new_session=True)
        result_path = profile / 'flight-bench-result.json'
        deadline = time.monotonic() + 240 + (args.hold or args.measure_ticks / 20)
        while not result_path.exists():
            if client.poll() is not None:
                raise RuntimeError('Client exited without a result; see ' + str(profile / 'logs/latest.log'))
            if time.monotonic() > deadline:
                raise RuntimeError('No bench result in time')
            time.sleep(2)
        record['client'] = json.loads(result_path.read_text())
        try:
            client.wait(timeout=120)
        except subprocess.TimeoutExpired:
            os.killpg(client.pid, signal.SIGTERM)
        if server is not None:
            time.sleep(3)
            server.stdin.write('stop\n'); server.stdin.flush()
            try:
                server.wait(timeout=120)
            except subprocess.TimeoutExpired:
                os.killpg(server.pid, signal.SIGTERM)
            server = None
        record['server'] = server_ticks(log)
    finally:
        if server is not None and server.poll() is None:
            os.killpg(server.pid, signal.SIGTERM)
        if args.mode == 'server' and record.get('serverWorldBefore'):
            link = SERVER / 'world'
            link.unlink(missing_ok=True)
            link.symlink_to(record['serverWorldBefore'])
        for added in added_server_mods:
            added.unlink(missing_ok=True)
        if client is not None and client.poll() is None:
            os.killpg(client.pid, signal.SIGTERM)
            try:
                client.wait(timeout=60)
            except subprocess.TimeoutExpired:
                os.killpg(client.pid, signal.SIGKILL)
        if args.save_world and world_copy is not None and world_copy.exists() and 'client' in record:
            # A template starts without the bench player's position and game mode.
            shutil.rmtree(world_copy / 'playerdata', ignore_errors=True)
            shutil.move(str(world_copy), str(args.save_world))
            record['savedWorld'] = str(args.save_world)
        if world_copy is not None and world_copy.exists():
            shutil.rmtree(world_copy)
    BENCH.mkdir(parents=True, exist_ok=True)
    with (BENCH / 'results.jsonl').open('a') as results:
        results.write(json.dumps(record) + '\n')
    print(json.dumps(record, indent=2))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
