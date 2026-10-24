#!/usr/bin/env python3
"""Export the portable team handoff, including pinned binaries but excluding caches."""
from pathlib import Path
import argparse,hashlib,json,os,subprocess,sys,zipfile

ROOT=Path(__file__).resolve().parents[1]
SKIP_DIRS={'.git','.gradle','.idea','__pycache__','build','vendor','node_modules'}
SKIP_NAMES={'launcher_accounts.json','launcher_profiles.json','accounts.json','servers.dat','servers.dat_old'}

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output',type=Path)
    args=parser.parse_args();target=args.output.expanduser().absolute()
    if target.is_relative_to(ROOT):raise RuntimeError('Write the archive outside the project')
    if target.exists():raise RuntimeError('Refusing to overwrite an existing export: '+str(target))
    subprocess.run([sys.executable,str(ROOT/'tools/verify_project.py')],check=True)
    target.parent.mkdir(parents=True,exist_ok=True)
    hashes={};sources=[]
    for directory,subdirs,names in os.walk(ROOT):
        subdirs[:]=sorted(n for n in subdirs if n not in SKIP_DIRS)
        base=Path(directory)
        for name in sorted(names):
            p=base/name;relative=p.relative_to(ROOT)
            if name in SKIP_NAMES or name.startswith('.env')or p.suffix in {'.pyc','.hprof'}:continue
            if relative.parts[0]=='instance'and any(x in {'saves','logs','crash-reports','screenshots','XaeroWaypoints','XaeroWorldMap'}for x in relative.parts):continue
            if p.is_symlink():raise RuntimeError('Symlink cannot be exported: '+str(relative))
            sources.append((p,relative.as_posix()))
    temporary=target.with_name('.'+target.name+'.writing')
    try:
        with zipfile.ZipFile(temporary,'x',zipfile.ZIP_DEFLATED,compresslevel=6)as archive:
            for path,relative in sources:
                data=path.read_bytes();hashes[relative]=hashlib.sha256(data).hexdigest()
                archive.writestr(ROOT.name+'/'+relative,data)
            archive.writestr(ROOT.name+'/EXPORT-FILES-SHA256.json',json.dumps(hashes,ensure_ascii=False,indent=2)+'\n')
        with zipfile.ZipFile(temporary)as archive:
            if archive.testzip()is not None:raise RuntimeError('Export ZIP CRC failure')
            for relative,digest in hashes.items():
                if hashlib.sha256(archive.read(ROOT.name+'/'+relative)).hexdigest()!=digest:
                    raise RuntimeError('Export hash mismatch: '+relative)
        temporary.rename(target)
    finally:
        if temporary.exists():temporary.unlink()
    digest=hashlib.sha256(target.read_bytes()).hexdigest()
    result={'pass':True,'file':target.name,'bytes':target.stat().st_size,'sha256':digest,
            'files':len(hashes),'baselineMods':27,'sourceModules':6,'worldsIncluded':False,
            'gradleCachesIncluded':False,'gitMetadataIncluded':False}
    target.with_suffix(target.suffix+'.sha256').write_text(digest+'  '+target.name+'\n')
    target.with_suffix(target.suffix+'.verification.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result,indent=2))

if __name__=='__main__':main()
