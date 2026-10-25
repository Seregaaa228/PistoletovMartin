#!/usr/bin/env python3
"""Launch an offline local test client from a fresh project profile, never .minecraft.

Install Forge47.4.0 into vendor/runtime first (BUILD-RU.md). Downloads are
limited to Minecraft's version metadata/libraries/assets, verified by SHA1.
This launcher does not log in, read accounts or use a player's access token.
"""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import subprocess
import tempfile
import urllib.request
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]
RUNTIME = ROOT / 'vendor/runtime'
SYSTEM = {'Linux': 'linux', 'Windows': 'windows', 'Darwin': 'osx'}[platform.system()]
CPU = platform.machine().lower()
NATIVE_CLASSIFIER = {
    'linux': 'linux-arm64' if CPU in {'aarch64', 'arm64'} else 'linux-arm32' if CPU.startswith('arm') else 'linux',
    'windows': 'windows-arm64' if CPU in {'aarch64', 'arm64'} else 'windows-x86' if CPU in {'x86', 'i386', 'i686'} else 'windows',
    'osx': 'macos-arm64' if CPU in {'aarch64', 'arm64'} else 'macos',
}[SYSTEM]


def download(url: str, path: Path, digest: str | None = None) -> Path:
    if path.is_file() and (digest is None or hashlib.sha1(path.read_bytes()).hexdigest() == digest):
        return path
    if not url.startswith('https://'):
        raise ValueError('Runtime download requires HTTPS')
    with urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': 'FrontierNations-Test/1.0'}), timeout=60) as response:
        data = response.read()
    if digest and hashlib.sha1(data).hexdigest() != digest:
        raise ValueError('Runtime download SHA1 mismatch: ' + path.name)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + '.part')
    temporary.write_bytes(data)
    temporary.replace(path)
    return path


def permitted(rules: list[dict] | None) -> bool:
    allowed = not rules
    for rule in rules or []:
        condition = rule.get('os', {})
        matches = condition.get('name', SYSTEM) == SYSTEM
        if 'arch' in condition:
            matches = matches and bool(re.match(condition['arch'], platform.machine()))
        if 'version' in condition:
            matches = matches and bool(re.match(condition['version'], platform.release()))
        if any(value for value in rule.get('features', {}).values()):
            matches = False
        if matches:
            allowed = rule['action'] == 'allow'
    return allowed


def metadata() -> tuple[dict, dict]:
    forge_path = RUNTIME / 'versions/1.20.1-forge-47.4.0/1.20.1-forge-47.4.0.json'
    if not forge_path.is_file():
        raise ValueError('Install the exact Forge client into vendor/runtime first; see BUILD-RU.md')
    manifest_path = download('https://piston-meta.mojang.com/mc/game/version_manifest_v2.json', RUNTIME / 'version_manifest_v2.json')
    entry = next(row for row in json.loads(manifest_path.read_text())['versions'] if row['id'] == '1.20.1')
    vanilla_path = download(entry['url'], RUNTIME / 'versions/1.20.1/1.20.1.json', entry['sha1'])
    return json.loads(vanilla_path.read_text()), json.loads(forge_path.read_text())


def publish_native(path: Path, data: bytes) -> None:
    # Another client may have this inode mapped as executable code. Never
    # truncate it: keep identical files, atomically replace changed cache files.
    if path.is_file() and path.read_bytes() == data:
        return
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=path.parent, prefix=path.name + '.', suffix='.tmp', delete=False) as stream:
            temporary = Path(stream.name)
            stream.write(data)
        temporary.replace(path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def prepare(vanilla: dict, forge: dict) -> list[Path]:
    # Forge replaces some libraries by group/artifact/classifier, preserving exact versions.
    merged = {}
    for library in vanilla['libraries'] + forge['libraries']:
        if permitted(library.get('rules')):
            pieces = library['name'].split(':')
            if len(pieces) > 3 and pieces[3].startswith('natives-') and pieces[3] != 'natives-' + NATIVE_CLASSIFIER:
                continue
            key = (pieces[0], pieces[1], pieces[3] if len(pieces) > 3 else '')
            merged[key] = library
    paths = []
    jobs = []
    for library in merged.values():
        artifact = library.get('downloads', {}).get('artifact')
        if not artifact:
            continue
        path = RUNTIME / 'libraries' / artifact['path']
        paths.append(path)
        jobs.append((artifact['url'], path, artifact.get('sha1')))
    client = vanilla['downloads']['client']
    # Forge's ignoreList explicitly names version_name.jar. Keep the inherited
    # raw client under that name so ModLauncher does not load it as a second mod.
    paths.append(RUNTIME / 'versions/1.20.1-forge-47.4.0/1.20.1-forge-47.4.0.jar')
    inherited_client = RUNTIME / 'versions/1.20.1/1.20.1.jar'
    if not paths[-1].exists() and inherited_client.is_file() and hashlib.sha1(inherited_client.read_bytes()).hexdigest() == client['sha1']:
        paths[-1].write_bytes(inherited_client.read_bytes())
    jobs.append((client['url'], paths[-1], client['sha1']))
    index = vanilla['assetIndex']
    index_path = download(index['url'], RUNTIME / 'assets/indexes' / (index['id'] + '.json'), index['sha1'])
    for value in json.loads(index_path.read_text())['objects'].values():
        digest = value['hash']
        jobs.append(('https://resources.download.minecraft.net/' + digest[:2] + '/' + digest,
                     RUNTIME / 'assets/objects' / digest[:2] / digest, digest))
    # Deduplicate content-addressed assets before workers use temporary files.
    jobs = list({str(job[1]): job for job in jobs}.values())
    with ThreadPoolExecutor(max_workers=8) as executor:
        list(executor.map(lambda job: download(*job), jobs))
    natives = RUNTIME / 'natives' / SYSTEM
    natives.mkdir(parents=True, exist_ok=True)
    for path in paths:
        if 'natives-' not in path.name:
            continue
        with zipfile.ZipFile(path) as archive:
            for name in archive.namelist():
                if name.endswith(('.so', '.dll', '.dylib')):
                    publish_native(natives / Path(name).name, archive.read(name))
    print(f'Prepared {len(paths)} exact runtime libraries and {len(jobs) - len(paths)} assets.', flush=True)
    return paths


def expand(arguments: list, values: dict) -> list[str]:
    result = []
    for argument in arguments:
        if isinstance(argument, dict):
            if not permitted(argument.get('rules')):
                continue
            argument = argument['value']
        for part in argument if isinstance(argument, list) else [argument]:
            result.append(re.sub(r'\$\{([^}]+)\}', lambda match: values.get(match.group(1), ''), part))
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('profile', type=Path)
    parser.add_argument('--restart', action='store_true')
    parser.add_argument('--prepare-only', action='store_true')
    parser.add_argument('--port', type=int, default=25565)
    parser.add_argument('--username', default='XaeroTester')
    parser.add_argument('--acceptance', action='store_true', help='Explicitly enable the separate map acceptance helper')
    parser.add_argument('--guest', action='store_true', help='Acceptance peer connecting only to loopback')
    parser.add_argument('--dedicated', action='store_true', help='Acceptance owner connects to a separate loopback server')
    parser.add_argument('--peer-report', type=Path)
    args = parser.parse_args()
    profile = args.profile.resolve()
    if not profile.is_relative_to(ROOT) or profile.is_relative_to(ROOT / 'instance'):
        raise ValueError('Test profile must be inside this project, outside instance')
    run = json.loads((profile / 'test-profile.json').read_text())
    if run.get('worldsCopied') is not False or not re.fullmatch(r'[A-Za-z0-9_-]{8,64}', run['runId']):
        raise ValueError('Not a fresh test profile')
    vanilla, forge = metadata()
    paths = prepare(vanilla, forge)
    if args.prepare_only:
        return 0
    values = {
        'auth_player_name': args.username, 'version_name': '1.20.1-forge-47.4.0',
        'game_directory': str(profile), 'assets_root': str(RUNTIME / 'assets'),
        'assets_index_name': vanilla['assetIndex']['id'],
        'auth_uuid': uuid.uuid3(uuid.NAMESPACE_DNS, 'OfflinePlayer:' + args.username).hex,
        'auth_access_token': '0', 'auth_xuid': '', 'clientid': '', 'user_type': 'legacy',
        'version_type': 'release', 'natives_directory': str(RUNTIME / 'natives' / SYSTEM),
        'launcher_name': 'frontier-isolated-tests', 'launcher_version': '1',
        'classpath': os.pathsep.join(str(path) for path in paths),
        'classpath_separator': os.pathsep, 'library_directory': str(RUNTIME / 'libraries'),
        'resolution_width': '1280', 'resolution_height': '720',
    }
    properties = []
    if (args.guest or args.dedicated) and not args.acceptance:
        raise ValueError('--guest/--dedicated require --acceptance')
    if args.acceptance:
        if not any(path.name == 'frontier-map-checks.jar' for path in (profile / 'mods').glob('*.jar')):
            raise ValueError('Build and explicitly install the separate map acceptance helper first')
        properties = ['-Dfrontier.mapAcceptance=true', '-Dfrontier.mapRunId=' + run['runId'],
                      '-Dfrontier.mapGuest=' + str(args.guest).lower(),
                      '-Dfrontier.mapRestart=' + str(args.restart).lower(),
                      '-Dfrontier.mapPort=' + str(args.port)]
        if args.peer_report:
            peer_report = args.peer_report.resolve()
            if not peer_report.is_relative_to(ROOT) or peer_report.is_relative_to(ROOT / 'instance'):
                raise ValueError('Peer report must be in an isolated project profile')
            properties.append('-Dfrontier.mapPeerReport=' + str(peer_report))
        if args.dedicated:
            properties.append('-Dfrontier.mapDedicated=true')
    command = ['java', '-Xmx3G', '-Xms512M', *properties,
               *expand(vanilla['arguments']['jvm'] + forge['arguments']['jvm'], values),
               forge['mainClass'], *expand(vanilla['arguments']['game'] + forge['arguments']['game'], values),
               '--width', '1280', '--height', '720']
    print('Launching isolated offline test client: ' + profile.name, flush=True)
    if args.acceptance:
        (profile / 'acceptance-launch.json').write_text(json.dumps({'runId': run['runId'], 'guest': args.guest, 'restart': args.restart}) + '\n')
    return subprocess.run(command, cwd=profile, check=False).returncode


if __name__ == '__main__':
    raise SystemExit(main())
