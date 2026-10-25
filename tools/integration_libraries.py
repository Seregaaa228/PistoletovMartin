"""Read and verify the separate, candidate integration dependency manifest."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import re
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def sha256(path: Path) -> str:
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(block)
    return value.hexdigest()


def libraries(root: Path = ROOT) -> list[dict]:
    manifest = json.loads((root / 'integration-libraries.lock.json').read_text(encoding='utf-8'))
    if (manifest.get('schema'), manifest.get('minecraft'), manifest.get('forge'), manifest.get('java')) != (1, '1.20.1', '47.4.0', 17):
        raise ValueError('Unsupported integration manifest platform')
    result = manifest['libraries']
    names = set()
    for library in result:
        name = library['file']
        if Path(name).name != name or not name.endswith('.jar') or name in names:
            raise ValueError('Invalid or duplicate integration filename: ' + name)
        if not re.fullmatch(r'[a-f0-9]{64}', library['sha256']) or library['size'] < 1:
            raise ValueError('Invalid integration digest/size: ' + name)
        names.add(name)
    return result


def verify_libraries(root: Path = ROOT) -> list[dict]:
    result = libraries(root)
    for library in result:
        path = root / 'vendor/integration' / library['file']
        if not path.is_file():
            raise ValueError('Missing integration library: ' + library['file'] + '; run python3 tools/fetch_integrations.py')
        if path.stat().st_size != library['size'] or sha256(path) != library['sha256']:
            raise ValueError('Integration library hash/size mismatch: ' + library['file'])
        with zipfile.ZipFile(path) as archive:
            if archive.testzip() is not None:
                raise ValueError('Corrupt integration library: ' + library['file'])
    return result


def declared_mod_ids(path: Path) -> list[str]:
    """Read only [[mods]] tables; dependency declarations are not new mods."""
    with zipfile.ZipFile(path) as archive:
        if 'META-INF/mods.toml' not in archive.namelist():
            return []
        text = archive.read('META-INF/mods.toml').decode('utf-8')
    ids = []
    table = ''
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith('['):
            table = stripped.split('#', 1)[0].strip()
        elif table == '[[mods]]':
            match = re.match(r'modId\s*=\s*["\']([a-z0-9_]+)["\']', stripped)
            if match:
                ids.append(match.group(1))
    return ids


def verify_unique_mod_ids(paths: list[Path]) -> None:
    owners = {}
    for path in paths:
        for mod_id in declared_mod_ids(path):
            if mod_id in owners:
                raise ValueError(f'Duplicate mod ID {mod_id}: {owners[mod_id]} and {path.name}')
            owners[mod_id] = path.name
