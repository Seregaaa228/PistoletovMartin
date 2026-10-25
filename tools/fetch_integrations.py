#!/usr/bin/env python3
"""Explicitly fetch the exact candidate libraries; never changes instance/mods."""
from __future__ import annotations

import hashlib
from pathlib import Path
import urllib.request
from integration_libraries import ROOT, libraries, verify_libraries


def fetch(root: Path = ROOT) -> None:
    destination = root / 'vendor/integration'
    destination.mkdir(parents=True, exist_ok=True)
    for library in libraries(root):
        target = destination / library['file']
        if target.is_file() and hashlib.sha256(target.read_bytes()).hexdigest() == library['sha256']:
            continue
        bundled = root / 'integration-libraries' / library['file']
        if bundled.is_file():
            data = bundled.read_bytes()
            if len(data) != library['size'] or hashlib.sha256(data).hexdigest() != library['sha256']:
                raise ValueError('Bundled dependency does not match lock: ' + library['file'])
            target.write_bytes(data)
            continue
        if not library['url'].startswith('https://cdn.modrinth.com/'):
            raise ValueError('Unsupported dependency download host')
        request = urllib.request.Request(library['url'], headers={'User-Agent': 'FrontierNations-PIS1/1.0'})
        with urllib.request.urlopen(request, timeout=60) as response:
            data = response.read(library['size'] + 1)
        if len(data) != library['size'] or hashlib.sha256(data).hexdigest() != library['sha256']:
            raise ValueError('Downloaded library does not match lock: ' + library['file'])
        temporary = target.with_suffix('.jar.part')
        temporary.write_bytes(data)
        temporary.replace(target)
    verify_libraries(root)
    print('Verified six candidate integration libraries in vendor/integration; baseline unchanged.')


if __name__ == '__main__':
    fetch()
