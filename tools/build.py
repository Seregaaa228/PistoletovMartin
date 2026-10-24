#!/usr/bin/env python3
"""Prepare exact snapshot dependencies, then invoke the project Gradle wrapper.

Examples: python3 tools/build.py
          python3 tools/build.py --offline :industry:build
JAVA_HOME must point to Java 17. GRADLE_USER_HOME is optional and never pinned
to a developer's machine. No game process is launched by the default build.
"""
from __future__ import annotations

import os
from pathlib import Path
import subprocess
import sys

from prepare_dependencies import ROOT, prepare


def main() -> int:
    prepare(ROOT / 'instance/mods')
    wrapper = ROOT / ('gradlew.bat' if os.name == 'nt' else 'gradlew')
    arguments = sys.argv[1:] or ['build']
    command = [str(wrapper), *arguments]
    if os.name == 'nt':
        command = ['cmd.exe', '/c', *command]
    else:
        # Works after unpacking an archive even if executable bits were lost.
        command = ['sh', *command]
    return subprocess.run(command, cwd=ROOT, check=False).returncode


if __name__ == '__main__':
    raise SystemExit(main())
