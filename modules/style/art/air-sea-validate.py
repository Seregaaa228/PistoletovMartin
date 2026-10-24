#!/usr/bin/env python3
"""Validate saved air/sea resources and their native contracts without Minecraft."""
import hashlib
import json
import math
from pathlib import Path
import zipfile

import numpy as np
from PIL import Image

import air_sea_assets as art


def read(path):
    return json.loads(path.read_text())


def main():
    manifest=read(art.HERE/"air-sea-resource-sha256.json")
    assert len(manifest)==29,len(manifest)
    for path,checksum in manifest.items():
        p=art.RES.parent.parent/path
        assert p.is_file(),path
        assert hashlib.sha256(p.read_bytes()).hexdigest()==checksum,path
    report=[]
    with zipfile.ZipFile(art.NATIVE) as native:
        for v in art.VEHICLES:
            profile=read(art.RES/"sbw/vehicles"/(v+".json"))
            assert len(profile["Models"])==2
            assert "LODDistance" not in profile["Models"][0]
            assert profile["Models"][1]["LODDistance"]==64
            assert set(profile)=={"Animation","Models"},(v,"profile contains mechanics")
            animation=read(art.RES/profile["Animation"].split(":",1)[1])["animations"]
            native_geo=json.loads(native.read(f"assets/superbwarfare/models/bedrock/vehicle/{v}.geo.json"))["minecraft:geometry"][0]
            native_bones={b["name"]:b for b in native_geo["bones"]}
            required=set(art.PIVOTS[v])
            for lod,p in enumerate(profile["Models"]):
                assert set(p)<=({"Model","Texture","LODDistance","EmissiveTexture"})
                for key in ("Model","Texture","EmissiveTexture"):
                    if key in p:assert (art.RES/p[key].split(":",1)[1]).is_file()
                geo=read(art.RES/p["Model"].split(":",1)[1])["minecraft:geometry"][0]
                assert geo["description"]["texture_width"]==geo["description"]["texture_height"]==128
                bones={b["name"]:b for b in geo["bones"]}
                assert len(bones)==len(geo["bones"])
                assert required<=set(bones)
                for name in required:
                    assert bones[name]["pivot"]==native_bones[name]["pivot"],(v,lod,name)
                for a in animation.values():assert set(a.get("bones",{}))<=set(bones)
                for name,b in bones.items():
                    seen=set();node=b
                    while "parent" in node:
                        assert node["name"] not in seen
                        seen.add(node["name"]);node=bones[node["parent"]]
                    if name.startswith("flare"):assert not b.get("cubes")
                    for c in b.get("cubes",[]):
                        assert all(math.isfinite(x) for x in c["origin"]+c["size"])
                        assert all(x>0 for x in c["size"])
                        assert set(c["uv"])==set(art.common.FACES)
                        for face in c["uv"].values():
                            assert all(0<=x and x+s<=128 for x,s in zip(face["uv"],face["uv_size"]))
                m=art.Model.__new__(art.Model);m.vehicle=v;m.lod=lod;m.bones=geo["bones"];m.by_name=bones
                tex=Image.open(art.RES/p["Texture"].split(":",1)[1]);assert tex.size==(128,128)
                # Exercise retained rig chains with live rotor/aim/steering poses.
                pose={name:[0,31,0] for name in ("turret","move_rudder") if name in bones}
                pose.update({name:([0,57,0] if name=="move_propeller" else [43,0,0])
                             for name in ("move_propeller","move_tailPropeller") if name in bones})
                if "barrel" in bones:pose["barrel"]=[-17,0,0]
                faces=list(art.transformed_faces(m,pose))
                assert all(np.isfinite(points).all() for points,_,_ in faces)
                if "turret" in bones:assert bones["barrel"]["parent"]=="turret"
                if v=="mi_28":
                    assert bones["move_propeller"]["parent"]=="propellerBase"
                    assert bones["propellerBase"]["rotation"]==[3,0,0]
                if v in ("speedboat","tiny_speedboat"):
                    assert bones["move_control"]["parent"]=="fxp"
                for a in animation.values():
                    for name,track in a.get("bones",{}).items():
                        if not name.startswith("flare"):continue
                        if isinstance(track.get("scale"),dict):
                            keys=track["scale"]
                            assert keys[max(keys,key=float)]==[0,0,0]
                            peak=max(max(s) for s in keys.values())
                            assert peak==(3 if v=="speedboat" else 5.5)
                        else:assert track["scale"]==0
            icon=Image.open(art.RES/"textures/vehicle_icon"/(v+"_icon.png"))
            assert icon.size==(256,64) and icon.mode=="RGBA"
            assert icon.getbbox() and icon.getextrema()[3][0]==0
            if v=="tiny_speedboat":
                for p in profile["Models"]:assert p["EmissiveTexture"].endswith("tiny_speedboat_color.png")
                mask=Image.open(art.RES/"textures/bedrock/vehicle/tiny_speedboat_color.png")
                assert mask.size==(128,128) and mask.getbbox()==(48,48,64,64)
            report.append(f"PASS {v}: saved models, exact native pivots, hierarchy, animation targets/flash reset, UVs, icon and profile references")
    report.append(f"PASS {len(manifest)} shipped resources: SHA-256 manifest verified")
    report.append("PASS two levels per vehicle: native default near + one far profile at 64 blocks")
    report.append("NOT RUN: Minecraft, rendering engine or mod build; verification is static + software projection only")
    (art.HERE/"air-sea-saved-validation.log").write_text("\n".join(report)+"\n")
    print("\n".join(report))


if __name__=="__main__":main()
