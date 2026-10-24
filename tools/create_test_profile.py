#!/usr/bin/env python3
"""Copy a fresh disposable profile, optionally overlaying the six built modules."""
from pathlib import Path
import argparse,hashlib,json,shutil,uuid,zipfile

ROOT=Path(__file__).resolve().parents[1]

def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('destination',type=Path,help='New empty directory; existing paths are refused')
    parser.add_argument('--baseline',action='store_true',help='Copy accepted snapshot rather than rebuilt modules')
    args=parser.parse_args();destination=args.destination.expanduser().absolute()
    if destination.exists():raise RuntimeError('Refusing an existing profile: '+str(destination))
    if destination==ROOT or ROOT.is_relative_to(destination)or destination.is_relative_to(ROOT/'instance'):
        raise RuntimeError('Destination cannot contain this project or be inside the accepted instance')
    lock=json.loads((ROOT/'modpack.lock.json').read_text())['mods']
    actual={p.name:sha(p)for p in (ROOT/'instance/mods').glob('*.jar')}
    if actual!=lock:raise RuntimeError('Accepted baseline differs from modpack.lock.json')
    replacements={}
    if not args.baseline:
        replacements={p.name:p for p in (ROOT/'build/team-mods').glob('*.jar')}
        expected={n for n in lock if n.startswith('frontier-')}
        if set(replacements)!=expected:raise RuntimeError('Build six modules first: python3 tools/build.py')
        for path in replacements.values():
            with zipfile.ZipFile(path)as archive:
                if archive.testzip()is not None:raise RuntimeError('Invalid built JAR: '+path.name)
    destination.parent.mkdir(parents=True,exist_ok=True)
    destination.mkdir()
    # Explicit game-profile inputs only, even if someone later runs the baseline
    # profile and accidentally creates personal saves/logs beside them.
    for name in ['mods','config','defaultconfigs','tacz']:
        if (ROOT/'instance'/name).is_dir():shutil.copytree(ROOT/'instance'/name,destination/name)
    for name in ['options.txt','README-RU.txt']:
        if (ROOT/'instance'/name).is_file():shutil.copy2(ROOT/'instance'/name,destination/name)
    for name,path in replacements.items():shutil.copy2(path,destination/'mods'/name)
    manifest={'runId':str(uuid.uuid4()),'baselineOnly':args.baseline,'worldsCopied':False,
              'mods':{p.name:sha(p)for p in sorted((destination/'mods').glob('*.jar'))},
              'description':'Fresh local game directory; configure a launcher to use it. No game launched.'}
    (destination/'test-profile.json').write_text(json.dumps(manifest,indent=2)+'\n')
    print('Created isolated profile: '+str(destination))

if __name__=='__main__':main()
