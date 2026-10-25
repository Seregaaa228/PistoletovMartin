#!/usr/bin/env python3
"""Explicit isolated combat checks: dedicated Forge server and two offline loopback clients.

Build `:industry:combatAcceptanceJar` and six production modules first. Uses only
the exact installed runtimes in vendor, new profiles and a fresh disposable world.
"""
from __future__ import annotations

import hashlib
import argparse
import json
import os
from pathlib import Path
import shutil
import signal
import socket
import subprocess
import time
import uuid

import run_map_client as runtime

ROOT = Path(__file__).resolve().parents[1]


def main() -> int:
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--latency-ms',type=int,default=0,help='Added delay in each direction after login (0..500ms)')
    parser.add_argument('--profile',action='store_true',help='Record server JFR during the long-range route stage (requires jcmd)')
    args=parser.parse_args()
    if not 0<=args.latency_ms<=500: raise ValueError('Latency must be 0..500ms')
    if args.profile and not shutil.which('jcmd'): raise ValueError('--profile requires the Java 17 jcmd tool')
    helper = ROOT / 'modules/industry/build/acceptance/frontier-combat-checks.jar'
    if not helper.is_file():
        raise ValueError('Build :industry:combatAcceptanceJar explicitly first')
    if not shutil.which('Xvfb'):
        raise ValueError('This automated runner requires Linux/Xvfb; use manual fresh profiles on other hosts')
    if not (ROOT/'vendor/server-runtime/libraries/net/minecraftforge/forge/1.20.1-47.4.0/unix_args.txt').is_file():
        raise ValueError('Install the pinned Forge47.4.0 server runtime in vendor/server-runtime first')
    label = 'combat-' + uuid.uuid4().hex[:8]
    profiles = {name: ROOT / ('run-' + label + '-' + name) for name in ('server', 'owner', 'guest')}
    state = {'profiles': {name: str(path.relative_to(ROOT)) for name, path in profiles.items()},'latencyMsEachDirection':args.latency_ms}
    for name, profile in profiles.items():
        subprocess.run(['python3', str(ROOT / 'tools/create_test_profile.py'), str(profile)], cwd=ROOT, check=True)
        shutil.copy2(helper, profile / 'mods' / helper.name)
        marker = json.loads((profile / 'test-profile.json').read_text())
        marker['mods'] = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted((profile / 'mods').glob('*.jar'))}
        marker['testHelpers'] = [helper.name]
        (profile / 'test-profile.json').write_text(json.dumps(marker, indent=2) + '\n')
        state.setdefault('runIds', {})[name] = marker['runId']
        if name == 'server':
            (profile / 'libraries').symlink_to(ROOT / 'vendor/server-runtime/libraries', target_is_directory=True)
        else:
            settings = {'guiScale': '2', 'renderDistance': '8', 'simulationDistance': '5', 'maxFps': '30',
                        'pauseOnLostFocus': 'false', 'soundCategory_master': '0.0', 'soundCategory_music': '0.0'}
            options = profile / 'options.txt'
            lines = [line for line in options.read_text().splitlines() if line.split(':', 1)[0] not in settings]
            options.write_text('\n'.join(lines + [key + ':' + value for key, value in settings.items()]) + '\n')
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', 0))
        port = probe.getsockname()[1]
    server_profile = profiles['server']
    (server_profile / 'server.properties').write_text(
        f'server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\ngamemode=creative\ndifficulty=peaceful\n'
        'level-name=combat-fresh\nlevel-seed=20261025\nlevel-type=minecraft:flat\n'
        'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains","structures":{}}\nview-distance=8\n'
        'simulation-distance=5\nspawn-protection=0\nmax-players=2\nallow-flight=true\n'
        'enable-query=false\nenable-rcon=false\nsync-chunk-writes=false\n')
    (server_profile / 'eula.txt').write_text('eula=true\n')
    state['port'] = port
    status = ROOT / 'vendor/combat-runtime-status.json'

    def update(stage: str):
        state['stage'] = stage
        status.write_text(json.dumps(state, indent=2) + '\n')
        print(stage, flush=True)

    # Runtime preparation is cached and validates the pinned SHA-1s itself.
    vanilla, forge = runtime.metadata()
    paths = runtime.prepare(vanilla, forge)
    read_fd, write_fd = os.pipe()
    xvfb_log = (profiles['owner'] / 'xvfb.log').open('w')
    xvfb = subprocess.Popen(['Xvfb', '-displayfd', str(write_fd), '-screen', '0', '1280x720x24', '-nolisten', 'tcp'],
                            pass_fds=(write_fd,), stdout=xvfb_log, stderr=subprocess.STDOUT)
    os.close(write_fd)
    with os.fdopen(read_fd) as stream:
        display = ':' + stream.readline().strip()
    state['display']=display
    env = os.environ.copy()
    env.update(DISPLAY=display, ALSOFT_DRIVERS='null')
    children: list[subprocess.Popen] = []
    server = None

    def flags(name: str) -> list[str]:
        return ['-Dfrontier.combatAcceptance=true', '-Dfrontier.combatRunId=' + state['runIds'][name],
                '-Dfrontier.combatLatencyMs=' + str(args.latency_ms),
                '-Dfrontier.combatPort=' + str(port),
                '-Dfrontier.combatServerReport=' + str(server_profile / 'combat-server-results.json')]

    def launch_client(name: str) -> subprocess.Popen:
        profile = profiles[name]
        username = 'CombatOwner' if name == 'owner' else 'CombatGuest'
        values = {'auth_player_name': username, 'version_name': '1.20.1-forge-47.4.0',
                  'game_directory': str(profile), 'assets_root': str(runtime.RUNTIME / 'assets'),
                  'assets_index_name': vanilla['assetIndex']['id'],
                  'auth_uuid': uuid.uuid3(uuid.NAMESPACE_DNS, 'OfflinePlayer:' + username).hex,
                  'auth_access_token': '0', 'auth_xuid': '', 'clientid': '', 'user_type': 'legacy',
                  'version_type': 'release', 'natives_directory': str(runtime.RUNTIME / 'natives' / runtime.SYSTEM),
                  'launcher_name': 'frontier-isolated-combat', 'launcher_version': '1',
                  'classpath': os.pathsep.join(str(path) for path in paths), 'classpath_separator': os.pathsep,
                  'library_directory': str(runtime.RUNTIME / 'libraries'),
                  'resolution_width': '1280', 'resolution_height': '720'}
        cmd = ['java', '-Xms512M', '-Xmx2G', *flags(name),
               *runtime.expand(vanilla['arguments']['jvm'] + forge['arguments']['jvm'], values), forge['mainClass'],
               *runtime.expand(vanilla['arguments']['game'] + forge['arguments']['game'], values),
               '--width', '1280', '--height', '720']
        child = subprocess.Popen(cmd, cwd=profile, env=env, stdin=subprocess.DEVNULL,
                                 stdout=(profile / 'console.log').open('w'), stderr=subprocess.STDOUT, start_new_session=True)
        children.append(child)
        return child

    try:
        update('starting dedicated Forge server in fresh isolated profile')
        server = subprocess.Popen(['java', '-Xms512M', '-Xmx3G', *flags('server'),
                                   '@libraries/net/minecraftforge/forge/1.20.1-47.4.0/unix_args.txt', 'nogui'],
                                  cwd=server_profile, env=env, stdin=subprocess.PIPE, text=True,
                                  stdout=(server_profile / 'console.log').open('w'), stderr=subprocess.STDOUT, start_new_session=True)
        children.append(server)
        deadline = time.monotonic() + 240
        while 'Done (' not in (server_profile / 'console.log').read_text(errors='replace'):
            if server.poll() is not None:
                raise RuntimeError('Dedicated server exited during startup; inspect console.log')
            if time.monotonic() > deadline:
                raise RuntimeError('Dedicated startup timeout')
            time.sleep(.3)
        update('dedicated server ready; starting two real clients')
        clients = [launch_client('owner'), launch_client('guest')]
        deadline = time.monotonic() + 600
        recorded=False
        profiled=False
        while any(client.poll() is None for client in clients):
            if server.poll() is not None:
                raise RuntimeError('Dedicated server exited during combat checks')
            if time.monotonic() > deadline:
                raise RuntimeError('Combat clients timed out')
            if not (server_profile / 'combat-server-results.json').is_file():
                for name, client in zip(('owner', 'guest'), clients):
                    if client.poll() is not None:
                        raise RuntimeError(f'{name} client exited before server checks completed; inspect its console.log')
            if args.profile and not profiled and (server_profile/'combat-profile-stage.json').is_file():
                with (server_profile/'profiling.log').open('w') as output:
                    subprocess.run(['jcmd',str(server.pid),'JFR.start','name=pis-combat','settings=profile',
                        'duration=120s','dumponexit=true','filename='+str(server_profile/'combat-server.jfr')],
                        stdout=output,stderr=subprocess.STDOUT,timeout=20,check=True)
                profiled=True
                state['profiling']='JFR started at native route tick >=650'
            if not recorded and shutil.which('ffmpeg') and 'COMBAT_MISSILE' in (server_profile/'console.log').read_text(errors='replace'):
                recorded=True
                recorder=subprocess.Popen(['ffmpeg','-y','-f','x11grab','-framerate','15','-video_size','1280x720','-i',display,
                    '-t','50','-c:v','libx264','-preset','veryfast','-crf','25',str(profiles['owner']/'combat-observer.mp4')],
                    cwd=ROOT,stdout=(profiles['owner']/'recording.log').open('w'),stderr=subprocess.STDOUT,start_new_session=True)
                children.append(recorder)
            time.sleep(.5)
        for name, filename in [('server', 'combat-server-results.json'), ('owner', 'combat-client-results.json'), ('guest', 'combat-client-results.json')]:
            path = profiles[name] / filename
            state[name] = json.loads(path.read_text())
        state['timing'] = json.loads((server_profile / 'combat-server-timing.json').read_text()) if (server_profile / 'combat-server-timing.json').exists() else {}
        passed = all(state[name] and all(state[name].values()) for name in profiles)
        update('PASS: dedicated server and two-client combat fixtures' if passed else 'FAIL: inspect assertion results and logs')
        return 0 if passed else 1
    except Exception as failure:
        state['error'] = str(failure)
        update('FAIL: ' + str(failure))
        return 1
    finally:
        if server is not None and server.poll() is None:
            try:
                server.stdin.write('stop\n'); server.stdin.flush(); server.wait(timeout=50)
            except (BrokenPipeError, subprocess.TimeoutExpired):
                pass
        for child in children:
            if child.poll() is None:
                os.killpg(child.pid, signal.SIGTERM)
                try: child.wait(timeout=10)
                except subprocess.TimeoutExpired: os.killpg(child.pid, signal.SIGKILL)
        xvfb.terminate()
        try: xvfb.wait(timeout=5)
        except subprocess.TimeoutExpired: xvfb.kill()


if __name__ == '__main__':
    raise SystemExit(main())
