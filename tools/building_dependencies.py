"""Download and verify the pinned third-party blocks needed by the factories."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
from urllib.parse import urlparse
from urllib.request import Request, urlopen
import zipfile

ROOT = Path(__file__).resolve().parents[1]
HOSTS = {'edge.forgecdn.net', 'mediafilez.forgecdn.net'}


def building_mods():
    return json.loads((ROOT / 'buildings-dependencies.lock.json').read_text(encoding='utf-8'))['mods']


def verify_building_mods():
    result = []
    for mod in building_mods():
        path = ROOT / 'vendor/building-mods' / mod['file']
        if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != mod['sha256']:
            raise ValueError('Missing or invalid building dependency: ' + mod['file'] + '; run python tools/building_dependencies.py')
        with zipfile.ZipFile(path) as archive:
            if archive.testzip() is not None:
                raise ValueError('Corrupt dependency: ' + mod['file'])
        result.append(path)
    return result


def main():
    destination = ROOT / 'vendor/building-mods'
    destination.mkdir(parents=True, exist_ok=True)
    for mod in building_mods():
        target = destination / mod['file']
        if target.is_file() and hashlib.sha256(target.read_bytes()).hexdigest() == mod['sha256']:
            continue
        url = mod['url']
        if urlparse(url).scheme != 'https' or urlparse(url).hostname not in HOSTS:
            raise ValueError('Unsupported download source: ' + url)
        with urlopen(Request(url, headers={'User-Agent': 'Frontier-Nations-buildings/1.0'}), timeout=60) as response:
            if urlparse(response.url).scheme != 'https' or urlparse(response.url).hostname not in HOSTS:
                raise ValueError('Untrusted redirect: ' + response.url)
            data = response.read(50 * 1024 * 1024 + 1)
        if len(data) > 50 * 1024 * 1024 or hashlib.sha256(data).hexdigest() != mod['sha256']:
            raise ValueError('Checksum mismatch: ' + mod['file'])
        temporary = target.with_suffix('.jar.part')
        temporary.write_bytes(data)
        temporary.replace(target)
    print('Verified', len(verify_building_mods()), 'pinned building dependencies.')


if __name__ == '__main__':
    main()
