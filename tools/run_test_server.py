#!/usr/bin/env python3
"""Start the exact dedicated Forge runtime inside an already-created test profile."""
from pathlib import Path
import argparse
import json
import os
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('profile', type=Path)
    parser.add_argument('--bootstrap-only', action='store_true',
                        help='Allow Forge to load mods and report a missing/unaccepted EULA; never writes eula=true')
    parser.add_argument('--dedicated-acceptance', action='store_true',
                        help='Opt into the guarded two-loopback-client Jade/ownership test after EULA acceptance')
    args = parser.parse_args()
    profile = args.profile.resolve()
    if not profile.is_relative_to(ROOT) or profile.is_relative_to(ROOT / 'instance'):
        raise ValueError('Use a separate test profile inside this project')
    manifest = json.loads((profile / 'test-profile.json').read_text())
    if manifest.get('worldsCopied') is not False:
        raise ValueError('Test profile must not contain copied worlds')
    eula = profile / 'eula.txt'
    accepted = eula.is_file() and any(line.strip() == 'eula=true' for line in eula.read_text().splitlines())
    if not accepted and not args.bootstrap_only:
        raise ValueError('The owner must explicitly accept Minecraft EULA in this test profile before the dedicated run')
    if args.dedicated_acceptance and not accepted:
        raise ValueError('Dedicated acceptance requires the owner to accept EULA first')
    filename = 'win_args.txt' if os.name == 'nt' else 'unix_args.txt'
    arguments = profile / 'libraries/net/minecraftforge/forge/1.20.1-47.4.0' / filename
    if not arguments.is_file():
        raise ValueError('Install the pinned Forge installer into this profile with --installServer first')
    command = ['java', '-Xmx3G', '-Xms512M', '-Dfrontier.pis1Smoke=true',
               '-Dfrontier.pis1RunId=' + manifest['runId']]
    if args.dedicated_acceptance:
        command.append('-Dfrontier.pis1DedicatedAcceptance=true')
    command.extend(['@' + str(arguments), 'nogui'])
    return subprocess.run(command, cwd=profile, check=False).returncode


if __name__ == '__main__':
    raise SystemExit(main())
