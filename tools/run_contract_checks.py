#!/usr/bin/env python3
"""Run deterministic Java17 production-rule checks without a game installation."""
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / 'modules/industry/src/main/java/dev/frontier/industry'
TEST = ROOT / 'modules/industry/src/test/java/dev/frontier/industry'


def main():
    classes = [
        ('energy/EnergyLedger.java', 'energy/GridConnection.java', 'energy/EnergyLedgerTest.java'),
        ('repair/RepairRules.java', 'repair/RepairRulesTest.java'),
        ('client/ui/UiViewport.java', 'client/ui/UiViewportTest.java'),
        ('client/ui/UiPage.java', 'client/ui/UiPageTest.java'),
        ('client/ui/TooltipBounds.java', 'client/ui/TooltipBoundsTest.java'),
        ('client/FactoryUiState.java', 'client/FactoryUiStateTest.java'),
    ]
    output = ROOT / 'build/contract-checks'
    output.mkdir(parents=True, exist_ok=True)
    for group in classes:
        sources = [str((TEST if name.endswith('Test.java') else MAIN) / name) for name in group]
        subprocess.run(['javac', '--release', '17', '-encoding', 'UTF-8', '-d', str(output), *sources], check=True)
        name = 'dev.frontier.industry.' + group[-1].removesuffix('.java').replace('/', '.')
        subprocess.run(['java', '-cp', str(output), name], check=True)


if __name__ == '__main__':
    main()
