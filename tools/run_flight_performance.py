#!/usr/bin/env python3
"""Reproducible opt-in 1/8/16 nearby idle-aircraft probe in a new local client/world.

Build :aviation:flightPerformanceTestJar first. Uses the pinned MC Pilot and
exact cached Forge runtime; never controls an existing user's client. The
whole-game timings include other mods and are distinct from Java microbenchmarks.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import socket
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--label', required=True, choices=['before', 'after'])
    parser.add_argument('--aviation-jar', type=Path, required=True)
    parser.add_argument('--seconds', type=int, default=20)
    args = parser.parse_args()
    if not 15 <= args.seconds <= 90:
        raise ValueError('Use a bounded 15..90 second measurement per phase')
    artifact = args.aviation_jar.resolve()
    if not artifact.is_relative_to(ROOT) or not artifact.is_file():
        raise ValueError('Use an existing project-local Aviation artifact')
    helper = ROOT / 'modules/aviation/build/flight-tests/frontier-flight-performance-test.jar'
    if not helper.is_file() or not shutil.which('Xvfb'):
        raise ValueError('Build the opt-in probe and install Xvfb first')
    tool = next(t for t in json.loads((ROOT / 'test-tools.lock.json').read_text())['tools'] if t['name'] == 'mc-pilot')
    pilot = ROOT / f'vendor/mc-pilot/audit-v{tool["cliVersion"]}/mct-client-mod-forge-1.20.1.jar'
    if not pilot.is_file() or sha(pilot) != tool['sha256']:
        raise ValueError('Pinned MC Pilot jar is absent or differs from test-tools.lock.json')
    label = args.label + '-' + uuid.uuid4().hex[:8]
    profile = ROOT / 'vendor/flight-performance-20261027' / label
    subprocess.run(['python3', 'tools/create_test_profile.py', str(profile)], cwd=ROOT, check=True)
    shutil.copy2(artifact, profile / 'mods/frontier-light-aviation-1.1.0.jar')
    shutil.copy2(helper, profile / 'mods' / helper.name)
    shutil.copy2(pilot, profile / 'mods' / tool['file'])
    manifest = json.loads((profile / 'test-profile.json').read_text())
    manifest['mods'] = {p.name: sha(p) for p in sorted((profile / 'mods').glob('*.jar'))}
    manifest['testHelpers'] = [helper.name]
    manifest['testTools'] = {tool['file']: tool['sha256']}
    (profile / 'test-profile.json').write_text(json.dumps(manifest, indent=2) + '\n')
    settings = {'guiScale': '2', 'renderDistance': '6', 'simulationDistance': '5', 'maxFps': '60',
                'pauseOnLostFocus': 'false', 'enableVsync': 'false', 'onboardAccessibility': 'false',
                'tutorialStep': 'none', 'soundCategory_master': '0.0'}
    options = profile / 'options.txt'
    lines = [line for line in options.read_text().splitlines() if line.split(':', 1)[0] not in settings]
    options.write_text('\n'.join(lines + [key + ':' + value for key, value in settings.items()]) + '\n')
    state_path = ROOT / 'vendor/mc-pilot/home/state/clients.json'
    client_name = 'flight-' + label
    result = {'label': args.label, 'profile': str(profile.relative_to(ROOT)), 'runId': manifest['runId'],
              'aviationSha256': manifest['mods']['frontier-light-aviation-1.1.0.jar'], 'secondsPerPhase': args.seconds,
              'scenario': 'one local manual pilot; 1/8/16 loaded nearby idle F16; integrated server; no simulated RTT'}
    status = profile / 'performance-run.json'

    def update(stage: str):
        result['stage'] = stage
        status.write_text(json.dumps(result, indent=2) + '\n')
        print(stage, flush=True)

    def call(parts: list[str], timeout: float = 25) -> dict:
        response = subprocess.run(['python3', 'tools/mct.py', '--client', client_name, *parts],
                                  cwd=ROOT, text=True, capture_output=True, timeout=timeout)
        if response.returncode:
            raise RuntimeError('MC Pilot ' + ' '.join(parts[:2]) + ': ' + response.stdout[-1200:] + response.stderr[-1200:])
        data = json.loads(response.stdout)
        if not data.get('success') or not data.get('data', {}).get('success'):
            raise RuntimeError('MC Pilot failed: ' + response.stdout[-1200:])
        return data['data']['data']

    def await_screen(screen: str | None, timeout: float = 120):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if client.poll() is not None:
                raise RuntimeError('Test client exited; inspect console.log')
            try:
                gui = call(['gui', 'info'])
                if (screen is None and not gui.get('open')) or gui.get('type') == screen:
                    return
            except (RuntimeError, subprocess.TimeoutExpired):
                pass
            time.sleep(.5)
        raise RuntimeError('Timed out waiting for ' + str(screen))

    def command(text: str):
        call(['chat', 'command', text])

    xvfb = client = None
    try:
        read_fd, write_fd = os.pipe()
        xvfb = subprocess.Popen(['Xvfb', '-displayfd', str(write_fd), '-screen', '0', '1280x720x24', '-nolisten', 'tcp'],
                                pass_fds=(write_fd,), stdout=(profile / 'xvfb.log').open('w'), stderr=subprocess.STDOUT)
        os.close(write_fd)
        with os.fdopen(read_fd) as stream:
            display = ':' + stream.readline().strip()
        with socket.socket() as probe:
            probe.bind(('127.0.0.1', 0)); port = probe.getsockname()[1]
        env = dict(os.environ, DISPLAY=display, ALSOFT_DRIVERS='null', MCT_CLIENT_NAME=client_name,
                   MCT_CLIENT_WS_PORT=str(port), MCT_HOME=str(ROOT / 'vendor/mc-pilot/home'),
                   JAVA_TOOL_OPTIONS='-Dfrontier.flightDiagnostics=true -Dfrontier.flightPerformance=true '
                   '-Dfrontier.flightBenchmark=true -Dfrontier.flightBenchmarkRunId=' + manifest['runId'])
        client = subprocess.Popen(['python3', 'tools/run_test_client.py', str(profile), '--username', 'FlightProbe', '--skip-benchmark'],
                                  cwd=ROOT, env=env, stdin=subprocess.DEVNULL, stdout=(profile / 'console.log').open('w'),
                                  stderr=subprocess.STDOUT, start_new_session=True)
        clients = json.loads(state_path.read_text()) if state_path.exists() else {'clients': {}}
        clients.setdefault('clients', {})[client_name] = {'pid': client.pid, 'name': client_name, 'wsPort': port,
            'startedAt': int(time.time() * 1000), 'logPath': str(profile / 'console.log'), 'instanceDir': str(profile)}
        state_path.write_text(json.dumps(clients, indent=2) + '\n')
        update('starting pinned test client')
        await_screen('TitleScreen', 180)
        # A bare Xvfb display has no window manager, so there is no WM_TAKE_FOCUS
        # handshake to wait on; MC Pilot's injected input does not depend on real
        # X11 focus here. The screen reports "open" slightly before the render
        # thread has caught up enough to accept input, so give it a moment.
        time.sleep(2)
        call(['gui', 'info'])
        call(['input', 'click', '320', '148']); await_screen('CreateWorldScreen', 45)
        for xy in [(320,108),(320,108),(320,14),(240,65),(452,140),(320,107)]:
            call(['input', 'click', *map(str, xy)])
        call(['input', 'type', '20261027']); call(['input', 'click', '240', '342'])
        await_screen(None, 120)
        update('fresh flat world loaded; preparing real manual pilot')
        for text in ['gamerule doDaylightCycle false','time set day','gamerule doMobSpawning false',
                     'difficulty peaceful','tp @s 0 -60 0 0 0','give @s frontier_aviation:f16']:
            command(text)
        call(['inventory', 'hotbar', '1']); call(['block', 'interact', '0', '-61', '5'])
        command('ride @s mount @e[type=frontier_aviation:f16,sort=nearest,limit=1]')
        positions = [(x, z) for x in (-48,-16,16,48) for z in (-28,4,36,68) if (x,z) != (-16,4)]
        spawned = 0
        for count in (1, 8, 16):
            while spawned < count - 1:
                x, z = positions[spawned]
                command(f'summon frontier_aviation:f16 {x} -60 {z} '
                        '{NoGravity:1b,Tags:["flight_perf_extra"],ForgeData:{"frontier_aviation:flight_controls":'
                        '{Version:1,Ignition:"OFF",TargetThrottle:0.0f}}}')
                spawned += 1
            nearby = call(['entity', 'list', '--radius', '128'])
            planes = [entity for entity in nearby['entities'] if entity['type'] == 'frontier_aviation:f16' and entity['alive']]
            if len(planes) != count:
                raise RuntimeError(f'Expected {count} loaded nearby F16, observed {len(planes)}')
            result.setdefault('observedAircraftCounts', []).append(len(planes))
            update(f'warming {count} idle aircraft')
            time.sleep(10)
            command(f'flight_benchmark begin aircraft_{count}')
            update(f'measuring {count} idle aircraft for {args.seconds}s')
            time.sleep(args.seconds)
            command('flight_benchmark end')
            call(['screenshot', '--output', str(profile / f'aircraft-{count}.png')])
        time.sleep(1)
        reports = {}
        for side in ('server', 'client'):
            records = [json.loads(line) for line in (profile / f'flight-benchmark-{side}.jsonl').read_text().splitlines()]
            if [r['label'] for r in records] != ['aircraft_1','aircraft_8','aircraft_16'] or any(r['samples'] < 100 for r in records):
                raise RuntimeError('missing or undersampled benchmark phases on ' + side)
            reports[side] = records
        result['measurements'] = reports
        # Actual flight after measurements exercises input, prediction and gear.
        update('checking real engine, takeoff, gear and focus after the idle phases')
        call(['input', 'key', 'hold', 'I', '--duration', '250'])
        call(['input', 'key', 'hold', 'W', '--duration', '18000'])
        call(['input', 'key', 'hold', 'G', '--duration', '250'])
        result['flightEntities'] = call(['entity', 'list', '--radius', '128'])
        flown = [entity for entity in result['flightEntities']['entities']
                 if entity['type'] == 'frontier_aviation:f16' and entity['pos']['y'] > -50 and entity['alive']]
        if not flown:
            raise RuntimeError('No live airborne F16 observed after actual I/W input')
        call(['screenshot', '--output', str(profile / 'flight-after-phases.png')])
        call(['input', 'key', 'press', 'ESCAPE']); call(['input', 'click', '320', '222'])
        await_screen('TitleScreen', 45)
        try: call(['input', 'click', '370', '232'])
        except RuntimeError: pass  # Quit closes the socket before an acknowledgement.
        client.wait(timeout=30)
        if client.returncode != 0: raise RuntimeError('test launcher exited with ' + str(client.returncode))
        update('PASS: measured three idle phases and exercised real flight; see saved raw reports')
        return 0
    except Exception as failure:
        result['error'] = str(failure); update('FAIL: ' + str(failure)); return 1
    finally:
        if client is not None and client.poll() is None:
            os.killpg(client.pid, signal.SIGTERM)
            try: client.wait(timeout=15)
            except subprocess.TimeoutExpired: os.killpg(client.pid, signal.SIGKILL)
        if xvfb is not None and xvfb.poll() is None:
            xvfb.terminate(); xvfb.wait(timeout=10)
        if state_path.exists():
            clients = json.loads(state_path.read_text())
            entry = clients.get('clients', {}).get(client_name)
            if entry and client is not None and entry.get('pid') == client.pid:
                del clients['clients'][client_name]
                state_path.write_text(json.dumps(clients, indent=2) + '\n')


if __name__ == '__main__':
    raise SystemExit(main())
