#!/usr/bin/env python3
"""Verify the accepted handoff profile and compatibility inputs; never opens worlds."""
from pathlib import Path
import argparse,hashlib,json,zipfile
from integration_libraries import libraries, verify_unique_mod_ids

ROOT=Path(__file__).resolve().parents[1]

def sha(path):
    value=hashlib.sha256()
    with path.open('rb')as stream:
        for data in iter(lambda:stream.read(1024*1024),b''):value.update(data)
    return value.hexdigest()

def require(ok,message):
    if not ok:raise RuntimeError(message)

def verify_profile(profile):
    profile=profile.resolve()
    manifest=json.loads((profile/'test-profile.json').read_text())
    actual={path.name:sha(path)for path in sorted((profile/'mods').glob('*.jar'))}
    require(actual==manifest['mods'],'Test profile differs from its run manifest')
    baseline=json.loads((ROOT/'modpack.lock.json').read_text())['mods']
    helpers=manifest.get('testHelpers',{})
    allowed_helper='frontier-integration-checks-pis-1.jar'
    require(not helpers or set(helpers)=={allowed_helper},'Unexpected test helper')
    if helpers:
        built=ROOT/'modules/industry/build/integration-tests'/allowed_helper
        require(built.is_file()and helpers[allowed_helper]==sha(built),'Test helper differs from current build')
    test_tools=manifest.get('testTools',{})
    if test_tools:
        pinned_tools={entry['file']:entry['sha256']for entry in json.loads((ROOT/'test-tools.lock.json').read_text())['tools']}
        require(not manifest['baselineOnly'],'Baseline must not contain optional QA tools')
        require(all(name in pinned_tools and digest==pinned_tools[name] and actual.get(name)==digest
                    for name,digest in test_tools.items()),'Unknown or changed optional QA tool')
    if manifest['baselineOnly']:
        require(actual=={**baseline,**helpers},'Baseline test profile changed')
        require(not manifest.get('integrationLibraries'),'Baseline contains integration additions')
    else:
        extra={entry['file']:entry['sha256']for entry in libraries(ROOT)}
        require(set(actual)==set(baseline)|set(extra)|set(helpers)|set(test_tools),'Unexpected/missing profile JARs')
        for name,digest in {**baseline,**extra}.items():
            if not name.startswith('frontier-'):
                require(actual[name]==digest,'Changed pinned profile dependency: '+name)
        for name in baseline:
            if name.startswith('frontier-'):
                built=ROOT/'build/team-mods'/name
                require(built.is_file()and actual[name]==sha(built),'Profile module differs from current build: '+name)
        expected_scripts={path.relative_to(ROOT/'instance').as_posix():sha(path)for path in (ROOT/'instance/kubejs').rglob('*')if path.is_file()}
        require(manifest.get('scripts')==expected_scripts,'Test profile script manifest differs from source')
        actual_scripts={path.relative_to(profile).as_posix():sha(path)for folder in ('server_scripts','client_scripts','startup_scripts','data','assets')
                        for path in (profile/'kubejs'/folder).rglob('*')if path.is_file()}
        locked_scripts={name:digest for name,digest in expected_scripts.items()if len(Path(name).parts)>1 and Path(name).parts[1]in {'server_scripts','client_scripts','startup_scripts','data','assets'}}
        require(actual_scripts==locked_scripts,'Unexpected, missing or changed executable KubeJS file')
    for name,digest in manifest.get('scripts',{}).items():
        path=(profile/name).resolve()
        require(path.is_relative_to(profile)and path.is_file()and sha(path)==digest,'Changed/missing test script: '+name)
    verify_unique_mod_ids(list((profile/'mods').glob('*.jar')))
    require(not any(path.is_symlink()for path in (profile/'mods').rglob('*')),'Symlink in profile mods')
    return {'runId':manifest['runId'],'mods':len(actual),'baselineOnly':manifest['baselineOnly']}

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--json',action='store_true')
    parser.add_argument('--profile',type=Path,help='Additionally verify a fresh test-profile.json, locked libraries, scripts and unique mod IDs')
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
    if args.profile:report['testProfile']=verify_profile(args.profile)
    print(json.dumps(report,ensure_ascii=False,indent=2)if args.json else
          'PASS: 27 pinned mods, '+str(len(files))+' profile files, 6 source modules, 2 preserved combat classes; archive CRCs verified.')

if __name__=='__main__':main()
