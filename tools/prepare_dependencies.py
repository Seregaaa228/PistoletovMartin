#!/usr/bin/env python3
"""Create a content-addressed local Maven repo from the shipped runtime snapshot.

Python 3.10+, standard library only. No network access and no Minecraft install.
The six editable modules are excluded; SBW's nested model APIs are extracted
only for the compiler, without changing the runtime JARs.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import zipfile
from integration_libraries import verify_libraries

ROOT = Path(__file__).resolve().parents[1]
CUSTOM = {
    'frontier-arcade-vehicles-1.7.0.jar',
    'frontier-nations-1.20.1-0.1.2-alpha.jar',
    'frontier-industry-1.20.1-0.4.0.jar',
    'frontier-arcade-style-1.1.0.jar',
    'frontier-light-aviation-1.1.0.jar',
    'frontier-flamingo-launch-fix-1.3.0.jar',
}
SBW = 'superbwarfare-0.8.9.1-hotfix-mc1.20.1-993063bed-all.jar'


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def publish(artifacts: list[dict], name: str, data: bytes, source: str, module: str | None = None) -> None:
    digest = sha256(data)
    artifact = re.sub(r'[^a-z0-9._-]', '-', name.lower().removesuffix('.jar'))
    version = digest[:16]
    target = ROOT / 'vendor/maven/dev/frontier/snapshot' / artifact / version
    target.mkdir(parents=True, exist_ok=True)
    jar = target / f'{artifact}-{version}.jar'
    if not jar.is_file() or sha256(jar.read_bytes()) != digest:
        jar.write_bytes(data)
    pom = target / f'{artifact}-{version}.pom'
    pom.write_text(
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>'
        f'<groupId>dev.frontier.snapshot</groupId><artifactId>{artifact}</artifactId>'
        f'<version>{version}</version><packaging>jar</packaging></project>\n', encoding='utf-8')
    artifacts.append({'coordinate': f'dev.frontier.snapshot:{artifact}:{version}',
                      'source': source, 'sha256': digest, 'size': len(data)})
    if module:
        artifacts[-1]['module'] = module


def prepare(mods: Path) -> dict:
    if not mods.is_dir():
        raise ValueError(f'Missing runtime snapshot: {mods}')
    names = {p.name for p in mods.glob('*.jar')}
    lock = json.loads((ROOT / 'modpack.lock.json').read_text(encoding='utf-8'))['mods']
    if names != set(lock):
        raise ValueError('Runtime JAR set differs from modpack.lock.json')
    for name, digest in lock.items():
        if sha256((mods / name).read_bytes()) != digest:
            raise ValueError(f'Runtime snapshot hash differs from modpack.lock.json: {name}')
    if SBW not in names:
        raise ValueError(f'The exact pinned SBW build is required: {SBW}')
    missing = CUSTOM - names
    if missing:
        raise ValueError(f'Incomplete runtime snapshot; missing custom JARs: {sorted(missing)}')
    artifacts: list[dict] = []
    for jar in sorted(mods.glob('*.jar')):
        if jar.name in CUSTOM:
            continue
        data = jar.read_bytes()
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            if archive.testzip() is not None:
                raise ValueError(f'Corrupt dependency JAR: {jar.name}')
        publish(artifacts, jar.name, data, f'instance/mods/{jar.name}')

    with zipfile.ZipFile(mods / SBW) as outer:
        nested = [n for n in outer.namelist() if n.startswith('META-INF/jarjar/')
                  and 'simplebedrockmodel' in n.lower() and n.endswith('.jar')]
        if len(nested) != 1:
            raise ValueError('Expected exactly one bundled SimpleBedrockModel API')
        bedrock = outer.read(nested[0])
        publish(artifacts, 'sbw-simplebedrockmodel.jar', bedrock, f'{SBW}!/{nested[0]}')
    with zipfile.ZipFile(io.BytesIO(bedrock)) as inner:
        nested = [n for n in inner.namelist() if n.endswith('.jar') and '/mae-' in n]
        if len(nested) != 1:
            raise ValueError('Expected exactly one bundled MAE model-animation API')
        publish(artifacts, 'sbw-mae.jar', inner.read(nested[0]), f'simplebedrockmodel!/{nested[0]}')
    overlay = io.BytesIO()
    with zipfile.ZipFile(overlay, 'w', zipfile.ZIP_DEFLATED) as archive:
        for name in ('NativeCombatEvents', 'ReadyWeapons'):
            path = f'dev/frontier/industry/combat/{name}.class'
            data = (ROOT / 'modules/industry/preserved-combat' / path).read_bytes()
            with zipfile.ZipFile(mods / 'frontier-industry-1.20.1-0.4.0.jar') as production:
                if production.read(path) != data:
                    raise ValueError(f'Preserved Industry class differs from production: {name}')
            info = zipfile.ZipInfo(path, (1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, data)
    publish(artifacts, 'industry-preserved-combat.jar', overlay.getvalue(),
            'modules/industry/preserved-combat', module='industry')
    for library in verify_libraries(ROOT):
        path = ROOT / 'vendor/integration' / library['file']
        publish(artifacts, library['file'], path.read_bytes(), f'vendor/integration/{library["file"]}')
    index = {'format': 'frontier-build-dependencies/1', 'minecraft': '1.20.1',
             'forge': '47.4.0', 'artifacts': artifacts}
    output = ROOT / 'vendor/dependencies.json'
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(index, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    return index


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mods', type=Path, default=ROOT / 'instance/mods',
                        help='Runtime snapshot directory (default: project instance/mods)')
    args = parser.parse_args()
    index = prepare(args.mods.resolve())
    print(f'Prepared {len(index["artifacts"])} exact local dependencies in vendor/maven')


if __name__ == '__main__':
    main()
