#!/usr/bin/env python3
"""Original cube bodies on compatible native animation-anchor skeletons."""
from pathlib import Path
from types import SimpleNamespace
import copy,hashlib,importlib.util,json,zipfile
import numpy as np
from PIL import Image,ImageDraw
HERE=Path(__file__).resolve().parent;RES=HERE.parent/'src/main/resources/resourcepacks/frontier_ifv/assets/superbwarfare'
spec=importlib.util.spec_from_file_location('style_art',HERE/'generate_assets.py');kit=importlib.util.module_from_spec(spec);spec.loader.exec_module(kit)
JAR=HERE.parents[2]/'instance/mods/superbwarfare-0.8.9.1-hotfix-mc1.20.1-993063bed-all.jar'
def save(path,data):path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(data,indent=2)+'\n')
def cube(bones,name,xyz,size,mat='hull',front=None):
 uv={f:kit.tile_uv(mat) for f in kit.FACES}
 if front:uv['north']=kit.tile_uv(front)
 bones[name].setdefault('cubes',[]).append({'origin':xyz,'size':size,'uv':uv})
def native(id):
 with zipfile.ZipFile(JAR)as z:g=json.loads(z.read(f'assets/superbwarfare/geo/{id}.geo.json'))
 model=g['minecraft:geometry'][0]
 # Keep identifiers/pivots/parents used by native animation/render/skin hands.
 for b in model['bones']:b.pop('cubes',None);b.pop('poly_mesh',None)
 model['description']['texture_width']=128;model['description']['texture_height']=128
 return g,{b['name']:b for b in model['bones']}
def drone():
 g,b=native('drone');c=lambda n,p,s,m='hull',f=None:cube(b,n,p,s,m,f)
 c('body',[-2.3,1,-2.6],[4.6,2,5.2]);c('body',[-1.7,3,-2],[3.4,.5,4],'roof')
 c('body',[-1.2,.1,-3.2],[2.4,1.4,1.5],'hull_dark');c('body',[-.7,.3,-3.26],[1.4,.8,.12],'optic')
 c('body',[-1.5,1.4,2.6],[3,.7,.2],'tail')
 for name in ['wingFL','wingFR','wingBL','wingBR']:
  x,y,z=b[name]['pivot'];c('body',[min(0,x),1.6,z-.55],[abs(x),.8,1.1],'hull_dark')
  c(name,[x-.6,y-.8,z-.6],[1.2,.8,1.2],'metal')
  c(name,[x-2.4,y,z-.25],[4.8,.2,.5],'rubber');c(name,[x-.25,y,z-2.4],[.5,.2,4.8],'rubber')
 return g

def rpg(lod=False):
 g,b=native('rpg');c=lambda n,p,s,m='hull',f=None:cube(b,n,p,s,m,f)
 c('Gunbody',[-1.7,-.2,-10],[3.4,3.4,38],'metal','bore')
 c('Gunbody',[-2.25,-.75,24],[4.5,4.5,5],'hull_dark','bore')
 c('Gunbody',[-2.4,-.8,-10.5],[4.8,4.6,3],'metal','bore')
 c('Gunbody',[-2,0,1],[4,3,15],'hatch')
 c('grip',[-1.2,-5.6,-4.2],[2.4,6,2.4],'hull_dark')
 c('grip2',[-1,-5.3,3.5],[2,5.5,2.2],'hull_dark')
 c('hammer',[-.8,-1.4,-3.1],[1.6,1.7,1.2],'metal')
 if not lod:
  c('Gunbody',[-3.5,3,0],[2,4,5],'metal');c('Gunbody',[-3.3,4.1,-.1],[1.6,1.8,.2],'optic')
  c('Gunbody',[-.5,3.2,-9],[1,2.6,1],'metal');c('Gunbody',[-1.8,3.4,15],[3.6,.3,1],'hull_dark')
 for name,length,mat in [('AmmoType0',26,'hull'),('AmmoType1',19,'hull_dark')]:
  c(name,[-.8,.7,-21],[1.6,1.6,26],'metal')
  c(name,[-1.7,-.2,-length-7],[3.4,3.4,length-13],mat)
  c(name,[-1.05,.45,-length-10],[2.1,2.1,4],mat)
 return g

def igla(lod=False):
 g,b=native('igla_9k38');c=lambda n,p,s,m='hull',f=None:cube(b,n,p,s,m,f)
 c('main',[-6,4,-22],[4,4,53],'hull','bore')
 c('main',[-6.6,3.4,27],[5.2,5.2,5],'hull_dark','bore');c('main',[-6.4,3.6,-23],[4.8,4.8,3],'metal','bore')
 c('main',[-5.3,-1,-6],[2.6,5.3,3],'hull_dark');c('main',[-5.8,1,-11],[3.6,4,6],'hull_dark')
 c('main',[-7.2,8.1,-10],[6.4,2.5,9],'metal');c('main',[-5.8,8.4,-10.1],[3.4,1.7,.2],'optic')
 if not lod:
  c('main',[-6.4,3.6,-12],[4.8,4.8,1],'hull_dark');c('main',[-6.4,3.6,18],[4.8,4.8,1],'hull_dark')
  c('main',[-2,5,-8],[.3,2.2,4],'stencil');c('main',[-4.3,9,-20],[.6,2,.7],'metal')
 return g

def image(model,texture,size=(620,460)):
 # Original software projection, with a neutral background and no source imagery.
 bones=model['minecraft:geometry'][0]['bones'];W,H=size;cam=np.array([.62,.45,-.64]);cam/=np.linalg.norm(cam);right=np.cross([0,1,0],cam);right/=np.linalg.norm(right);up=np.cross(cam,right)
 faces=[];allpts=[]
 for b in bones:
  for c in b.get('cubes',[]):
   for face,corners in kit.FACE_CORNERS.items():
    n=np.array(kit.NORMALS[face]);pts=np.array(corners)*np.array(c['size'])+np.array(c['origin']);allpts.extend(pts)
    if n@cam>0:
     uv=c['uv'][face]['uv'];col=texture.getpixel((int(uv[0]+8),int(uv[1]+8)));shade=.7+.3*max(0,float(n@np.array([-.3,.9,-.6])));faces.append((float(np.mean(pts@cam)),pts,tuple(int(x*shade)for x in col[:3])))
 a=np.array(allpts);xy=np.stack([a@right,a@up],axis=1);lo=xy.min(axis=0);hi=xy.max(axis=0);scale=min((W-70)/max(1,hi[0]-lo[0]),(H-65)/max(1,hi[1]-lo[1]));center=(lo+hi)/2
 im=Image.new('RGBA',size,(225,229,216,255));d=ImageDraw.Draw(im)
 for depth,pts,color in sorted(faces,key=lambda x:x[0]):
  d.polygon([((p@right-center[0])*scale+W/2,H/2-(p@up-center[1])*scale)for p in pts],fill=color)
 return im

def main():
 texture=kit.atlas('bmp_2');models={'drone':drone(),'rpg':rpg(),'igla_9k38':igla()};metrics={};paths=[]
 for id,g in models.items():
  path=RES/f'geo/{id}.geo.json';save(path,g);paths.append(path)
  tex=RES/(f'textures/entity/{id}.png'if id=='drone'else f'textures/item/{id}.png');tex.parent.mkdir(parents=True,exist_ok=True);texture.save(tex);paths.append(tex)
  preview=image(g,texture);preview.save(HERE/f'small-{id}-preview.png')
  if id=='drone':
   icon=RES/'textures/item/drone.png';icon.parent.mkdir(parents=True,exist_ok=True);image(g,texture,(128,128)).save(icon);paths.append(icon)
  else:
   far=rpg(True)if id=='rpg'else igla(True);fp=RES/f'geo/lod/{id}.geo.json';save(fp,far);paths.append(fp)
   tp=RES/f'textures/item/lod/{id}.png';tp.parent.mkdir(parents=True,exist_ok=True);texture.save(tp);paths.append(tp)
  native_geo,native_bones=native(id);geo=g['minecraft:geometry'][0];bones=geo['bones'];names={b['name']for b in bones};assert names==set(native_bones)
  for b in bones:
   assert b.get('pivot')==native_bones[b['name']].get('pivot');assert not b.get('poly_mesh')
   for c in b.get('cubes',[]):
    assert all(x>0for x in c['size'])
    for uv in c['uv'].values():assert all(0<=uv['uv'][i]and uv['uv'][i]+uv['uv_size'][i]<=128for i in [0,1])
  count=sum(len(b.get('cubes',[]))for b in bones);metrics[id]={'cubes':count,'triangles':count*12,'retainedNativeAnimationAnchors':len(bones),'originalCubeGeometry':True}
 save(HERE/'small-metrics.json',metrics);save(HERE/'small-resource-sha256.json',{str(p.relative_to(RES)):hashlib.sha256(p.read_bytes()).hexdigest()for p in paths});print(json.dumps(metrics,indent=2))
if __name__=='__main__':main()
