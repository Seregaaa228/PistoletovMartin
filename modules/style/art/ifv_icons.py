#!/usr/bin/env python3
"""Matching original container/HUD icons projected from the shipped IFV bodies."""
from pathlib import Path
from types import SimpleNamespace
import hashlib,json
from PIL import Image
import air_sea_assets as art

HERE=Path(__file__).resolve().parent
RES=HERE.parent/'src/main/resources/resourcepacks/frontier_ifv/assets/superbwarfare'
hashes={}
for vehicle in ('bmp_2','bradley'):
    bones=json.loads((RES/f'models/bedrock/vehicle/{vehicle}.geo.json').read_text())['minecraft:geometry'][0]['bones']
    model=SimpleNamespace(bones=bones,by_name={b['name']:b for b in bones})
    texture=Image.open(RES/f'textures/bedrock/vehicle/{vehicle}.png').convert('RGBA')
    for suffix,size,yaw,elevation in [(f'textures/vehicle_icon/{vehicle}_icon.png',(256,64),80,.14),
                                      (f'textures/vehicle_icon/container/{vehicle}.png',(128,128),35,.62)]:
        path=RES/suffix;path.parent.mkdir(parents=True,exist_ok=True)
        art.render(model,texture,(size[0]*3,size[1]*3),yaw=yaw,elevation=elevation).resize(size,Image.Resampling.LANCZOS).save(path)
        hashes[suffix]=hashlib.sha256(path.read_bytes()).hexdigest()
(HERE/'ifv-icon-sha256.json').write_text(json.dumps(hashes,indent=2)+'\n')
print('Generated four matching IFV icons')
