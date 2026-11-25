"""Native whole-surface close-up rig for food, fetch and quiet interactions."""
from pathlib import Path
import json
import math
import shutil
import subprocess
import numpy as np
from PIL import Image, ImageDraw, ImageFilter
from layout import ART,RIG,ROOT,HERE,exporter
NAMES=['Stand','Wave','Rest','Launch01','Launch02','Launch03','Launch04','Launch05','Jump']
CONTROLS=('ParamPose','ParamWeight','ParamTail','ParamPitch','ParamReach','ParamEar','ParamPaw','ParamTuck','ParamSniff','ParamChew','ParamBlink','ParamLick')
# Native art landmarks, expressed in original texture pixels.
POINTS={
 'Stand':{'nose':(84,169),'lip':(94,193),'cheek':(127,186),'eye':(135,154),'paw':(146,416),'pawnear':(194,417),'neck':(167,247)},
 'Wave':{'nose':(206,177),'lip':(201,201),'cheek':(175,197),'eye':(192,157),'paw':(113,192),'pawnear':(207,419),'neck':(207,258)},
 'Rest':{'nose':(79,389),'lip':(83,403),'cheek':(108,380),'eye':(114,355),'paw':(75,418),'pawnear':(137,420),'neck':(180,369)}
}
for pose,eye,nose in zip(NAMES[3:],[(144,156),(154,160),(164,164),(174,168),(183,171),(192,174)],
                         [(98,175),(107,176),(122,178),(134,180),(145,182),(155,185)]):
    x,y=nose
    ground=Image.open(ART/(pose+'.png')).getchannel('A').point(lambda a:255 if a>100 else 0).getbbox()[3]-2
    POINTS[pose]={'nose':(x-8,y-3),'lip':(x+1,y+21),'cheek':(x+26,y+18),'eye':eye,
                  'paw':(148,ground-3),'pawnear':(194,ground-4),'neck':(eye[0]+28,eye[1]+90)}

def smooth(a,b,x):
    t=np.clip((x-a)/(b-a),0,1);return t*t*(3-2*t)
def bell(x,y,cx,cy,sx,sy):
    return np.exp(-((x-cx)/sx)**4-((y-cy)/sy)**4)
def rotate(g,center,angle):
    x=g[...,0]-center[0];y=g[...,1]-center[1]
    c,s=math.cos(angle),math.sin(angle)
    return np.stack(((c-1)*x-s*y,s*x+(c-1)*y),axis=-1)
def warp_delta(g,pose,param):
    x,y=g[...,0],g[...,1];z=x*0
    wave=pose=='Wave';rest=pose=='Rest';stand=not(wave or rest)
    p=POINTS[pose];ex,ey=p['eye'];nx,ny=p['nose'];lx,ly=p['lip'];cx,cy=p['neck']
    face=(1-smooth(cy-8,cy+62,y))*(1-smooth(ex+51,ex+142,x))
    if rest: face=bell(x,y,115,348,97,97)
    if wave: face=bell(x,y,216,171,108,95)
    def vector(dx,dy):return np.stack((z+dx,z+dy),axis=-1)
    if param=='ParamWeight':
        # Strongest over the chest, zero at all planted toes.
        chest=(1-smooth(283,426,y))*(.48+.52*(1-smooth(172,365,x)))
        if rest:chest=.40*(1-smooth(366,426,y))
        return vector(-6*chest,13*chest)
    if param=='ParamTail':
        tx,ty,tbx,tby=((355,169,325,230) if stand else (346,306,303,344) if wave else (344,362,307,395))
        w=bell(x,y,tx,ty,83,79)*(1-face)
        return rotate(g,(tbx,tby),-.19)*w[...,None]
    if param=='ParamPitch':
        # Head moves around the neck; feet and hindquarters stay planted.
        angle=(-.24 if stand else -.21 if wave else -.10)
        return rotate(g,(cx,cy),angle)*face[...,None]
    if param=='ParamReach':
        return vector(-22*face,4*face) if not rest else vector(-6*face,4*face)
    if param=='ParamEar':
        if rest:return vector(2.7*bell(x,y,116,294,40,29),-3.6*bell(x,y,116,294,40,29))
        if wave:return vector(4*bell(x,y,238,108,66,42),-3*bell(x,y,238,108,66,42))
        w=bell(x,y,160,101,58,34);return vector(-5*w,-5*w)
    if param=='ParamPaw':
        if wave:
            # Raise the waving paw into the near cheek for a face wipe.
            w=np.exp(-((x-115)/60)**2-((y-202)/91)**2)
            return rotate(g,(140,283),.45)*w[...,None]+vector(6*w,-3*w)
        if rest:
            w=bell(x,y,106,414,52,19);return vector(4*w,-5*w)
        w=bell(x,y,149,397,31,70)
        return vector(-13*w,-20*w)
    if param=='ParamTuck':
        if not stand:return vector(z,z)
        front=bell(x,y,177,396,72,76);rear=bell(x,y,363,397,65,74)
        return vector(-22*front-14*rear,-33*front-21*rear)
    if param=='ParamSniff':
        w=bell(x,y,nx+7,ny,25,19)
        return vector(-3.0*w,-1.5*w)
    if param=='ParamChew':
        # Preserve the top of the muzzle. Lower lip/tongue and chin follow the jaw.
        w=bell(x,y,lx+14,ly+8,41,33)*smooth(ly-10,ly+5,y)
        if rest:w=bell(x,y,lx+5,ly,19,13)
        return vector(1.8*w,-8.8*w if not rest else -2.3*w)
    if param=='ParamBlink':
        if wave:return vector(z,z)
        w=bell(x,y,ex,ey,12,7)
        out=vector(z,.87*(ey-y)*w)
        if stand:
            w=bell(x,y,ex-29,ey-5,7,6)
            out+=vector(z,.74*(ey-5-y)*w)
        return out
    raise KeyError(param)

def blob(data,dt='<f4'):
    return list(np.asarray(data,dtype=dt).tobytes())
def build():
    RIG.mkdir(parents=True,exist_ok=True)
    model=dict(canvas=dict(pixels_per_unit=512,origin_x=256,origin_y=256,width=512,height=512),
        parameters=[],parts=[],art_meshes=[],deformers=[],glues=[],draw_groups=[])
    for c in CONTROLS:
        lo,hi=(0,len(NAMES)-1) if c=='ParamPose' else (0,1) if c in ('ParamBlink','ParamTuck','ParamLick') else (-1,1)
        model['parameters'].append(dict(id=c,min=lo,max=hi,default=0,repeat=False,decimals=3,blend_shape=False))
    marks={};last_deformers={}
    for i,pose in enumerate(NAMES):
        pic=Image.open(ART/(pose+'.png')).convert('RGBA')
        pic.save(RIG/(pose+'.png'))
        parent=None
        for c in CONTROLS[1:-1]:
            n=40 if c in ('ParamChew','ParamBlink','ParamSniff') else 24
            if c=='ParamBlink':n=80 if i<3 else 56
            g=np.stack(np.meshgrid(np.linspace(0,512,n+1),np.linspace(0,512,n+1)),axis=-1)
            delta=warp_delta(g,pose,c);vals=[0,1] if c in ('ParamBlink','ParamTuck') else [-1,0,1]
            forms=[(g+v*delta-(np.array([256,256]) if parent is None else 0))/512 for v in vals]
            model['deformers'].append(dict(id=c[5:]+pose,parent_deformer=parent,parent_part=i,visible=True,enabled=True,
                binding=dict(axes=[dict(parameter=CONTROLS.index(c),keys=vals)]),
                kind={'Warp':dict(rows=n,cols=n,quad=True,opacities=[1.]*len(vals),positions=blob(forms))}))
            parent=len(model['deformers'])-1
        model['parts'].append(dict(id='Part'+pose,parent_part=None,visible=True,enabled=True,binding={'axes':[]},draw_orders=[500]))
        last_deformers[pose]=parent
        # Dense facial vertices preserve the existing pore, lip and iris texture under the deformations.
        pts=POINTS[pose];ex,ey=pts['eye'];lx,ly=pts['lip']
        xs=np.unique(list(range(0,513,8))+list(range(max(0,min(ex,lx)-22),min(512,max(ex,lx)+32),3))+[v[0] for v in pts.values()])
        ys=np.unique(list(range(0,513,8))+list(range(max(0,min(ey,ly)-16),min(512,max(ey,ly)+20),2))+[v[1] for v in pts.values()])
        left,top,right,bottom=pic.getchannel('A').getbbox()
        xs=np.unique(np.r_[xs[(xs>=left-1)&(xs<=right+1)],left-1,right+1])
        ys=np.unique(np.r_[ys[(ys>=top-1)&(ys<=bottom+1)],top-1,bottom+1])
        grid=np.stack(np.meshgrid(xs,ys),axis=-1); idx=np.arange(len(xs)*len(ys)).reshape(len(ys),len(xs))
        tris=np.stack([idx[:-1,:-1],idx[:-1,1:],idx[1:,1:],idx[:-1,:-1],idx[1:,1:],idx[1:,:-1]],axis=-1)
        marks[pose]={key:int(idx[np.where(ys==v[1])[0][0],np.where(xs==v[0])[0][0]]) for key,v in pts.items()}
        pose_keys=sorted({0,max(0,i-1),i,min(len(NAMES)-1,i+1),len(NAMES)-1})
        model['art_meshes'].append(dict(id='Dog'+pose,parent_part=i,parent_deformer=parent,
            visible=True,enabled=True,texture=i,flags=4,vertex_count=idx.size,uvs=blob(grid/512),indices=blob(tris,'<u2'),masks=[],
            binding=dict(axes=[dict(parameter=0,keys=pose_keys)]),opacities=[float(k==i) for k in pose_keys],
            draw_orders=[500]*len(pose_keys),positions=blob([grid/512]*len(pose_keys))))
    def jsonw(name,obj): (RIG/name).write_text(json.dumps(obj,separators=(',',':')),encoding='utf-8')
    # The small tongue is sampled from this dog's own original, attached at the resting lip.
    source=Image.open(ART/'Stand.png').convert('RGBA')
    mask=Image.new('L',(512,512)); d=ImageDraw.Draw(mask)
    d.polygon([(94,191),(99,188),(112,188),(114,191),(108,198),(102,204),(99,206),(96,206),(93,204),(92,201),(92,196)],fill=255)
    source.putalpha(mask.filter(ImageFilter.GaussianBlur(.6)))
    tongue=source.crop((90,185,116,208)).resize((17,16),Image.Resampling.LANCZOS)
    page=Image.new('RGBA',(512,512)); page.alpha_composite(tongue,(76,397)); page.save(RIG/'Tongue.png')
    xs=[74,80,86,92,95];ys=[395,399,403,408,414,416];g=np.stack(np.meshgrid(xs,ys),axis=-1)
    idx=np.arange(len(xs)*len(ys)).reshape(len(ys),len(xs))
    tris=np.stack([idx[:-1,:-1],idx[:-1,1:],idx[1:,1:],idx[:-1,:-1],idx[1:,1:],idx[1:,:-1]],axis=-1)
    forms=[];opacity=[]
    rest_keys=[0,1,2,3,len(NAMES)-1]
    for extension in (0,1):
        for body in rest_keys:
            geom=g.astype(float).copy()
            # Retraction follows the edge of the lower lip.
            geom[:,:,1]-=(1-extension)*(1.5+6*smooth(397,413,geom[:,:,1]));geom[:,:,0]+=(1-extension)*3
            forms.append(geom/512);opacity.append(float(body==2 and extension==1))
    model['art_meshes'].append(dict(id='LickTongue',parent_part=2,parent_deformer=last_deformers['Rest'],visible=True,enabled=True,texture=len(NAMES),flags=4,vertex_count=idx.size,
        uvs=blob(g/512),indices=blob(tris,'<u2'),masks=[],binding=dict(axes=[dict(parameter=0,keys=rest_keys),dict(parameter=CONTROLS.index('ParamLick'),keys=[0,1])]),opacities=opacity,draw_orders=[510]*len(forms),positions=blob(forms)))
    textures=[name+'.png' for name in NAMES]+['Tongue.png']
    jsonw('rig.json',model);jsonw('textures.json',textures);jsonw('markers.json',marks)
    jsonw('CuteDog.model3.json',dict(Version=3,FileReferences={'Moc':'CuteDog.moc3','Textures':textures}))
    subprocess.run([str(exporter()),str(RIG)],check=True)

if __name__=='__main__':build()
