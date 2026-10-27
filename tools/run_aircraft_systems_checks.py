#!/usr/bin/env python3
"""Run actual aircraft scenarios in one new project profile and fresh flat world.

Requires the pinned cached runtime, MC Pilot and Xvfb. Never controls a user's
client or accepts a dedicated-server EULA. Raw results stay under vendor.
"""
from __future__ import annotations

import argparse
import ctypes
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
SCENARIOS = ('aim0', 'aim1', 'aim2', 'aim3', 'destruction', 'npc', 'gesture', 'gear', 'seed_wreck', 'restart_wreck')


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--scenarios', nargs='+', choices=SCENARIOS, default=list(SCENARIOS[:6]))
    parser.add_argument('--resume-profile', type=Path, help='Restart a profile created by this runner; never a user profile')
    parser.add_argument('--baseline-aviation', action='store_true', help='Use the frozen pre-change aviation JAR to seed a real old wreck')
    parser.add_argument('--reload', action='store_true', help='Complete a real datapack reload before scenarios')
    args = parser.parse_args()
    helper = ROOT / 'modules/aviation/build/flight-tests/frontier-aircraft-systems-test.jar'
    artifact = ROOT / 'build/team-mods/frontier-light-aviation-1.1.0.jar'
    tool = next(row for row in json.loads((ROOT / 'test-tools.lock.json').read_text())['tools'] if row['name'] == 'mc-pilot')
    pilot = ROOT / f'vendor/mc-pilot/audit-v{tool["cliVersion"]}/mct-client-mod-forge-1.20.1.jar'
    if not helper.is_file() or not artifact.is_file() or not shutil.which('Xvfb') or not pilot.is_file() or sha(pilot) != tool['sha256']:
        raise ValueError('Build production and :aviation:aircraftSystemsTestJar; pinned MC Pilot and Xvfb must be present')
    base = ROOT / 'vendor/aircraft-systems-20261027'
    if args.resume_profile:
        profile = args.resume_profile.resolve()
        if profile.parent != base or not (profile / 'systems-run.json').is_file() or args.baseline_aviation:
            raise ValueError('Resume requires this runner\'s existing isolated profile and current production')
        previous_run = json.loads((profile / 'systems-run.json').read_text())
        marker = json.loads((profile / 'test-profile.json').read_text())
        if marker.get('worldsCopied') is not False or previous_run.get('runId') != marker.get('runId'):
            raise ValueError('Resume marker does not match this runner')
        client_state = ROOT / 'vendor/mc-pilot/home/state/clients.json'
        if client_state.exists():
            entry = json.loads(client_state.read_text()).get('clients', {}).get('aircraft-systems-' + profile.name)
            if entry:
                try: os.kill(entry['pid'], 0)
                except ProcessLookupError: pass
                else: raise ValueError('Existing test client is still active; do not replace its JAR')
    else:
        profile = base / ('run-' + uuid.uuid4().hex[:8])
        subprocess.run(['python3', 'tools/create_test_profile.py', str(profile)], cwd=ROOT, check=True)
    if args.baseline_aviation:
        previous = base / 'baseline-3ff9cca.jar'
        if sha(previous) != 'be2aa9699a4a50e6e5614a2eeaed0b758e50f3c37d132715061ab6d196938b6a':
            raise ValueError('Frozen pre-change baseline does not match')
        shutil.copy2(previous, profile / 'mods' / artifact.name)
    elif args.resume_profile:
        shutil.copy2(artifact, profile / 'mods' / artifact.name)
    shutil.copy2(helper, profile / 'mods' / helper.name)
    shutil.copy2(pilot, profile / 'mods' / tool['file'])
    manifest = json.loads((profile / 'test-profile.json').read_text())
    if manifest.get('worldsCopied') is not False:
        raise ValueError('Only a marked profile without copied worlds is permitted')
    if args.resume_profile and (profile / 'aircraft-systems-results.json').exists():
        (profile / 'aircraft-systems-results.json').rename(profile / ('aircraft-systems-results-before-' + uuid.uuid4().hex[:8] + '.json'))
    manifest['mods'] = {path.name: sha(path) for path in sorted((profile / 'mods').glob('*.jar'))}
    manifest['testHelpers'] = [helper.name]
    manifest['testTools'] = {tool['file']: tool['sha256']}
    (profile / 'test-profile.json').write_text(json.dumps(manifest, indent=2) + '\n')
    settings = {'guiScale': '2', 'renderDistance': '18', 'simulationDistance': '12', 'maxFps': '45',
                'pauseOnLostFocus': 'false', 'enableVsync': 'false', 'onboardAccessibility': 'false',
                'tutorialStep': 'none', 'soundCategory_master': '0.0'}
    options = profile / 'options.txt'
    lines = [line for line in options.read_text().splitlines() if line.split(':', 1)[0] not in settings]
    options.write_text('\n'.join(lines + [key + ':' + value for key, value in settings.items()]) + '\n')
    name = 'aircraft-systems-' + profile.name
    state_path = ROOT / 'vendor/mc-pilot/home/state/clients.json'
    result = {'profile': str(profile.relative_to(ROOT)), 'runId': manifest['runId'],
              'aviationSHA256': manifest['mods'][artifact.name], 'scenarios': args.scenarios,
              'scope': 'one real client and integrated server; fresh world; no simulated RTT'}
    status = profile / 'systems-run.json'
    client = xvfb = None

    def update(stage: str):
        result['stage'] = stage
        status.write_text(json.dumps(result, indent=2) + '\n')
        print(stage, flush=True)

    def call(parts: list[str], timeout: float = 30) -> dict:
        response = subprocess.run(['python3', 'tools/mct.py', '--client', name, *parts], cwd=ROOT,
                                  text=True, capture_output=True, timeout=timeout)
        if response.returncode:
            raise RuntimeError('MC Pilot ' + ' '.join(parts[:2]) + ': ' + response.stdout[-1200:] + response.stderr[-1200:])
        data = json.loads(response.stdout)
        if not data.get('success') or not data.get('data', {}).get('success'):
            raise RuntimeError('MC Pilot failed: ' + response.stdout[-1200:])
        return data['data']['data']

    def await_screen(screen: str | None, timeout: float):
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

    try:
        read_fd, write_fd = os.pipe()
        xvfb = subprocess.Popen(['Xvfb', '-displayfd', str(write_fd), '-screen', '0', '1280x720x24', '-nolisten', 'tcp'],
                                pass_fds=(write_fd,), stdout=(profile / 'xvfb.log').open('w'), stderr=subprocess.STDOUT)
        os.close(write_fd)
        with os.fdopen(read_fd) as stream:
            display = ':' + stream.readline().strip()
        with socket.socket() as probe:
            probe.bind(('127.0.0.1', 0)); port = probe.getsockname()[1]
        env = dict(os.environ, DISPLAY=display, ALSOFT_DRIVERS='null', MCT_CLIENT_NAME=name,
                   MCT_CLIENT_WS_PORT=str(port), MCT_HOME=str(ROOT / 'vendor/mc-pilot/home'),
                   JAVA_TOOL_OPTIONS='-Dfrontier.aircraftSystemsTest=true -Dfrontier.aircraftSystemsRunId=' + manifest['runId'])
        client = subprocess.Popen(['python3', 'tools/run_test_client.py', str(profile), '--username', 'SystemsPilot', '--skip-benchmark'],
                                  cwd=ROOT, env=env, stdin=subprocess.DEVNULL, stdout=(profile / 'console.log').open('w'),
                                  stderr=subprocess.STDOUT, start_new_session=True)
        clients = json.loads(state_path.read_text()) if state_path.exists() else {'clients': {}}
        clients.setdefault('clients', {})[name] = {'pid': client.pid, 'name': name, 'wsPort': port,
            'startedAt': int(time.time() * 1000), 'logPath': str(profile / 'console.log'), 'instanceDir': str(profile)}
        state_path.write_text(json.dumps(clients, indent=2) + '\n')
        result['clientName'] = name
        update('starting fresh aircraft systems client')
        await_screen('TitleScreen', 180)
        # TitleScreen is installed before its fade enables the menu buttons.
        time.sleep(2.5)
        tree = subprocess.check_output(['xwininfo', '-display', display, '-root', '-tree'], text=True)
        window = next(int(line.split()[0], 16) for line in tree.splitlines() if 'Minecraft*' in line)
        x = ctypes.CDLL('libX11.so.6')
        x.XOpenDisplay.argtypes = [ctypes.c_char_p]; x.XOpenDisplay.restype = ctypes.c_void_p
        x.XDefaultRootWindow.argtypes = [ctypes.c_void_p]; x.XDefaultRootWindow.restype = ctypes.c_ulong
        x.XSetInputFocus.argtypes = [ctypes.c_void_p, ctypes.c_ulong, ctypes.c_int, ctypes.c_ulong]
        x.XSync.argtypes = [ctypes.c_void_p, ctypes.c_int]
        connection = x.XOpenDisplay(display.encode())
        if not connection: raise RuntimeError('isolated display unavailable')
        x.XSetInputFocus(connection, x.XDefaultRootWindow(connection), 1, 0); x.XSync(connection, 0)
        time.sleep(.1); x.XSetInputFocus(connection, window, 1, 0); x.XSync(connection, 0)
        call(['input', 'click', '320', '148'])
        if args.resume_profile:
            await_screen('SelectWorldScreen', 60)
            call(['input', 'click', '230', '72']); call(['input', 'click', '240', '318'])
        else:
            await_screen('CreateWorldScreen', 60)
            call(['input', 'click', '320', '65'])
            for _ in range(32):
                call(['input', 'key', 'press', 'BACKSPACE'])
            call(['input', 'type', 'aircraft_systems_' + manifest['runId'][:8]])
            for xy in [(320,108),(320,108),(320,14),(240,65),(452,140),(320,107)]:
                call(['input', 'click', *map(str, xy)])
            call(['input', 'type', '20261027']); call(['input', 'click', '240', '342'])
        await_screen(None, 180)
        worlds = [path for path in (profile / 'saves').iterdir() if path.is_dir()]
        if len(worlds) != 1 or worlds[0].name != 'aircraft_systems_' + manifest['runId'][:8]:
            raise RuntimeError('Created world name does not match the fresh-fixture guard')
        for text in ['gamerule doDaylightCycle false', 'time set day', 'gamerule doMobSpawning false',
                     'difficulty peaceful', 'tp @s 0 -60 0 0 0']:
            command(text)
        time.sleep(5)
        if args.reload:
            log = profile / 'console.log'
            before = log.read_text(errors='replace').count('Server resource reload complete!')
            command('reload')
            deadline = time.monotonic() + 60
            while log.read_text(errors='replace').count('Server resource reload complete!') <= before:
                if time.monotonic() > deadline: raise RuntimeError('Datapack reload did not complete')
                time.sleep(.5)
            result['datapackReloadCompleted'] = True
        report_path = profile / 'aircraft-systems-results.json'
        for scenario in args.scenarios:
            update('running actual scenario ' + scenario)
            for progress_file in ('aircraft-gesture-progress.json', 'aircraft-gear-progress.json', 'aircraft-gear-captured.txt'):
                (profile / progress_file).unlink(missing_ok=True)
            command('aircraft_check ' + scenario)
            performed = set()
            deadline = time.monotonic() + 150
            while time.monotonic() < deadline:
                if client.poll() is not None: raise RuntimeError('Test client exited during ' + scenario)
                gesture_progress = profile / 'aircraft-gesture-progress.json'
                if scenario == 'gesture' and gesture_progress.exists():
                    try: progress = json.loads(gesture_progress.read_text())
                    except json.JSONDecodeError: continue
                    stage = progress.get('stage')
                    if progress.get('runId') == manifest['runId'] and stage not in performed:
                        performed.add(stage)
                        if stage == 'single-ready':
                            call(['input', 'key', 'down', 'use'])
                            try:
                                time.sleep(.4)
                                call(['screenshot', '--output', str(profile / 'missile-ads.png')])
                            finally: call(['input', 'key', 'up', 'use'])
                            call(['input', 'key', 'hold', 'attack', '--duration', '80'])
                        elif stage == 'pair-ready':
                            call(['input', 'key', 'hold', 'attack', '--duration', '500'])
                gear_progress = profile / 'aircraft-gear-progress.json'
                if scenario == 'gear' and gear_progress.exists() and 'gear' not in performed:
                    try: progress = json.loads(gear_progress.read_text())
                    except json.JSONDecodeError: continue
                    if progress.get('runId') == manifest['runId']:
                        performed.add('gear')
                        call(['input', 'key', 'press', 'g'])
                        call(['input', 'key', 'press', 'F5'])
                        time.sleep(1.4)
                        call(['screenshot', '--output', str(profile / 'gear-retracted.png')])
                        (profile / 'aircraft-gear-captured.txt').write_text(manifest['runId'])
                        call(['input', 'key', 'press', 'F5']); call(['input', 'key', 'press', 'F5'])
                if report_path.is_file():
                    try:
                        report = json.loads(report_path.read_text())
                        record = report.get('scenarios', {}).get(scenario)
                        if record is not None:
                            result.setdefault('results', {})[scenario] = record
                            if not record.get('pass'): raise RuntimeError(scenario + ' failed: ' + json.dumps(record))
                            break
                    except json.JSONDecodeError:
                        pass
                time.sleep(.5)
            else: raise RuntimeError('No terminal report for ' + scenario)
            call(['screenshot', '--output', str(profile / (scenario + '.png'))])
        call(['input', 'key', 'press', 'ESCAPE']); call(['input', 'click', '320', '222'])
        await_screen('TitleScreen', 60)
        try: call(['input', 'click', '370', '232'])
        except RuntimeError: pass
        client.wait(timeout=30)
        if client.returncode: raise RuntimeError('test client exit ' + str(client.returncode))
        update('PASS: actual native scenarios completed; inspect raw records and screenshots')
        return 0
    except Exception as failure:
        result['error'] = str(failure)
        try: call(['screenshot', '--output', str(profile / 'failure.png')])
        except Exception: pass
        update('FAIL: ' + str(failure))
        return 1
    finally:
        if client is not None and client.poll() is None:
            os.killpg(client.pid, signal.SIGTERM)
            try: client.wait(timeout=15)
            except subprocess.TimeoutExpired: os.killpg(client.pid, signal.SIGKILL)
        if xvfb is not None and xvfb.poll() is None:
            xvfb.terminate(); xvfb.wait(timeout=10)
        if state_path.exists():
            clients = json.loads(state_path.read_text())
            entry = clients.get('clients', {}).get(name)
            if entry and client is not None and entry.get('pid') == client.pid:
                del clients['clients'][name]
                state_path.write_text(json.dumps(clients, indent=2) + '\n')


if __name__ == '__main__':
    raise SystemExit(main())
