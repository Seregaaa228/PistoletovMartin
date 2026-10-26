#!/usr/bin/env python3
"""Fetch the pinned optional tactical armor without changing the baseline profile."""
from pathlib import Path
import hashlib
import json
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


def main():
    mod = json.loads((ROOT / 'optional-mods.lock.json').read_text(encoding='utf-8'))['mods']['caps_awim_tactical_equipment']
    destination = ROOT / 'vendor' / 'optional-mods' / mod['file']
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists():
        if hashlib.sha256(destination.read_bytes()).hexdigest() != mod['sha256']:
            raise RuntimeError('Existing file has the wrong hash; refusing to overwrite: ' + str(destination))
        print('Already verified: ' + str(destination))
        return
    request = urllib.request.Request(mod['download'], headers={'User-Agent': 'FrontierNationsOptionalMods/1.0'})
    with urllib.request.urlopen(request, timeout=60) as response:
        data = response.read(mod['bytes'] + 1)
    if len(data) != mod['bytes'] or hashlib.sha256(data).hexdigest() != mod['sha256']:
        raise RuntimeError('Downloaded file size or SHA-256 does not match optional-mods.lock.json')
    with tempfile.NamedTemporaryFile(dir=destination.parent, delete=False) as tmp:
        temporary = Path(tmp.name)
        try:
            tmp.write(data)
            tmp.close()
            # Exclusive creation prevents replacing a file created during download.
            with destination.open('xb') as output:
                output.write(temporary.read_bytes())
        finally:
            temporary.unlink(missing_ok=True)
    print('Downloaded and verified: ' + str(destination))


if __name__ == '__main__':
    main()
