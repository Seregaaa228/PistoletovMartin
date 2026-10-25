#!/usr/bin/env python3
"""Run the pinned, project-local MC Pilot without using personal client state."""
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
CLI = ROOT / 'vendor/mc-pilot/cli/node_modules/@kzheart_/mc-pilot/dist/index.js'

if not CLI.is_file():
    raise SystemExit('Install MC Pilot 0.20.2 as described in docs/testing/MC-PILOT-RU.md')
environment = dict(os.environ, MCT_HOME=str(ROOT / 'vendor/mc-pilot/home'),
                   MCT_CACHE_DIR=str(ROOT / 'vendor/mc-pilot/cache'))
node = os.environ.get('MCT_NODE', 'node')
raise SystemExit(subprocess.run([node, str(CLI), *sys.argv[1:]], cwd=ROOT, env=environment).returncode)
