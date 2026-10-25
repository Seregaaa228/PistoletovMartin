#!/usr/bin/env python3
"""Copy a fresh disposable profile, optionally overlaying the six built modules."""
from pathlib import Path
import argparse,hashlib,json,shutil,uuid,zipfile
from integration_libraries import verify_libraries, verify_unique_mod_ids

ROOT=Path(__file__).resolve().parents[1]

def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('destination',type=Path,help='New empty directory; existing paths are refused')
    parser.add_argument('--baseline',action='store_true',help='Copy accepted snapshot rather than rebuilt modules')
    parser.add_argument('--integrations',action='store_true',help='Include candidate integrations (already the default for rebuilt modules)')
    parser.add_argument('--test-helper',action='store_true',help='Explicitly add the separately built opt-in PIS-1 test helper')
    args=parser.parse_args();destination=args.destination.expanduser().resolve()
    if args.baseline and args.integrations:raise RuntimeError('Choose baseline or rebuilt integrations')
    if destination.exists():raise RuntimeError('Refusing an existing profile: '+str(destination))
    if destination==ROOT or ROOT.is_relative_to(destination)or destination.is_relative_to(ROOT/'instance'):
        raise RuntimeError('Destination cannot contain this project or be inside the accepted instance')
    lock=json.loads((ROOT/'modpack.lock.json').read_text())['mods']
    actual={p.name:sha(p)for p in (ROOT/'instance/mods').glob('*.jar')}
    if actual!=lock:raise RuntimeError('Accepted baseline differs from modpack.lock.json')
    replacements={}
    additions=[]
    if not args.baseline:
        replacements={p.name:p for p in (ROOT/'build/team-mods').glob('*.jar')}
        expected={n for n in lock if n.startswith('frontier-')}
        if set(replacements)!=expected:raise RuntimeError('Build six modules first: python3 tools/build.py')
        for path in replacements.values():
            with zipfile.ZipFile(path)as archive:
                if archive.testzip()is not None:raise RuntimeError('Invalid built JAR: '+path.name)
        additions=[ROOT/'vendor/integration'/library['file'] for library in verify_libraries(ROOT)]
    helpers=[]
    if args.test_helper:
        helper=ROOT/'modules/industry/build/integration-tests/frontier-integration-checks-pis-1.jar'
        if not helper.is_file():raise RuntimeError('Build :industry:integrationTestJar first')
        helpers=[helper]
    inputs=[replacements.get(name,ROOT/'instance/mods'/name)for name in lock]+additions+helpers
    verify_unique_mod_ids(inputs)
    scripts=ROOT/'instance/kubejs'
    if not args.baseline and scripts.exists():
        if any(path.is_symlink()for path in scripts.rglob('*')):raise RuntimeError('Symlink in integration scripts')
    destination.parent.mkdir(parents=True,exist_ok=True)
    destination.mkdir()
    # Explicit game-profile inputs only, even if someone later runs the baseline
    # profile and accidentally creates personal saves/logs beside them.
    for name in ['mods','config','defaultconfigs','tacz']:
        if (ROOT/'instance'/name).is_dir():shutil.copytree(ROOT/'instance'/name,destination/name)
    for name in ['options.txt','README-RU.txt']:
        if (ROOT/'instance'/name).is_file():shutil.copy2(ROOT/'instance'/name,destination/name)
    for name,path in replacements.items():shutil.copy2(path,destination/'mods'/name)
    for path in additions:shutil.copy2(path,destination/'mods'/path.name)
    for path in helpers:shutil.copy2(path,destination/'mods'/path.name)
    if not args.baseline and scripts.is_dir():shutil.copytree(scripts,destination/'kubejs')
    manifest={'runId':str(uuid.uuid4()),'baselineOnly':args.baseline,'worldsCopied':False,
              'mods':{p.name:sha(p)for p in sorted((destination/'mods').glob('*.jar'))},
              'integrationLibraries':[path.name for path in additions],
              'testHelpers':{path.name:sha(path)for path in helpers},
              'scripts':{path.relative_to(destination).as_posix():sha(path)for path in sorted((destination/'kubejs').rglob('*'))if path.is_file()},
              'description':'Fresh local game directory; configure a launcher to use it. No game launched.'}
    (destination/'test-profile.json').write_text(json.dumps(manifest,indent=2)+'\n')
    print('Created isolated profile: '+str(destination))

if __name__=='__main__':main()
