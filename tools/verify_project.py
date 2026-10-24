#!/usr/bin/env python3
"""Verify the accepted handoff profile and compatibility inputs; never opens worlds."""
from pathlib import Path
import argparse,hashlib,json,zipfile

ROOT=Path(__file__).resolve().parents[1]

def sha(path):
    value=hashlib.sha256()
    with path.open('rb')as stream:
        for data in iter(lambda:stream.read(1024*1024),b''):value.update(data)
    return value.hexdigest()

def require(ok,message):
    if not ok:raise RuntimeError(message)

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--json',action='store_true')
    args=parser.parse_args()
    lock=json.loads((ROOT/'modpack.lock.json').read_text())
    actual={p.name:sha(p)for p in sorted((ROOT/'instance/mods').glob('*.jar'))}
    require(actual==lock['mods'],'instance/mods differs from the accepted 27-JAR snapshot')
    require(len(actual)==27,'Expected 27 baseline JARs')
    files=json.loads((ROOT/'instance-files.lock.json').read_text())
    for relative,digest in files.items():
        path=ROOT/relative
        require(path.is_file()and sha(path)==digest,'Changed/missing profile file: '+relative)
    for name,digest in json.loads((ROOT/'reference/source-archives.lock.json').read_text()).items():
        path=ROOT/'reference/source-archives'/name
        require(path.is_file()and sha(path)==digest,'Changed/missing historical archive: '+name)
        with zipfile.ZipFile(path)as archive:require(archive.testzip()is None,'Archive CRC failure: '+name)
    preserved=ROOT/'modules/industry/preserved-combat/dev/frontier/industry/combat'
    expected={'NativeCombatEvents.class':'8bdda1168156fa9ce5577498fb0d7f1b42a29938ed2b4ef3851393f45b56f407',
              'ReadyWeapons.class':'9460e139b74ff680895b2d40065fb18dc9a41e4105e2ded05ba2aec28a29eef9'}
    for name,digest in expected.items():require(sha(preserved/name)==digest,'Changed preserved Industry class: '+name)
    for path in (ROOT/'instance').rglob('*'):
        require(not path.is_symlink(),'Symlink in portable profile: '+str(path))
        relative=path.relative_to(ROOT/'instance')
        require(not any(p in {'saves','logs','crash-reports','screenshots','XaeroWaypoints','XaeroWorldMap','accounts'}for p in relative.parts),'Personal runtime state: '+str(relative))
        require(path.name not in {'servers.dat','launcher_accounts.json','accounts.json','launcher_profiles.json'},'Personal launcher file: '+str(relative))
    modules={p.name:len(list((p/'src/main/java').rglob('*.java')))for p in sorted((ROOT/'modules').iterdir())if p.is_dir()}
    require(len(modules)==6 and all(modules.values()),'Expected six nonempty source modules')
    for path in (ROOT/'instance/mods').glob('*.jar'):
        require('test'not in path.name.lower()and 'smoke'not in path.name.lower(),'Test helper in production profile')
        with zipfile.ZipFile(path)as archive:require(archive.testzip()is None,'JAR CRC failure: '+path.name)
    report={'pass':True,'minecraft':lock['minecraft'],'forge':lock['forge'],'java':lock['java'],
            'baselineMods':len(actual),'lockedProfileFiles':len(files),'sourceModules':modules,
            'preservedIndustryClasses':2,'userWorldsIncluded':False,
            'scope':'File hashes, CRCs, source presence and preserved inputs; no new game run implied'}
    print(json.dumps(report,ensure_ascii=False,indent=2)if args.json else
          'PASS: 27 pinned mods, '+str(len(files))+' profile files, 6 source modules, 2 preserved combat classes; archive CRCs verified.')

if __name__=='__main__':main()
