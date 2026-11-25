"""Create native Cubism controls from registered complete drawings in 512px artwork space."""
import argparse
import json
from pathlib import Path

import numpy as np
from PIL import Image
from scipy import ndimage


def blob(data,dtype='<f4'):
    return list(np.asarray(data,dtype=dtype).tobytes())


def smooth(lo,hi,x):
    t=np.clip((x-lo)/(hi-lo),0,1)
    return t*t*(3-2*t)


def eyes_in_picture(img, name):
    if name.lower().startswith(('sleep','wave','belly','lie','rest','curl')):
        return []
    a=np.asarray(img)
    dark=(a[:,:,:3].max(2)<105)&(a[:,:,3]>180)
    dark[310:]=False
    labels,n=ndimage.label(dark)
    components=[]
    for i,s in enumerate(ndimage.find_objects(labels)):
        if not s: continue
        yy,xx=s
        w,h=xx.stop-xx.start,yy.stop-yy.start
        count=(labels[s]==i+1).sum()
        if count>=7 and 2<=w<=27 and 3<=h<=19:
            components.append((xx.start+w/2,yy.start+h/2,w,h,count))
    if not components:return []
    biggest=[c for c in components if c[4]>=29]
    if not biggest:return []
    first=min(c[1] for c in biggest)
    return [c for c in components if first-7<=c[1]<=first+8 and c[0]>3][:3]


def axis(param,keys):
    return dict(parameter=param,keys=list(keys))


def grid(xs,ys):
    return np.stack(np.meshgrid(xs,ys),axis=-1)


def warp(name,parent,part,axes,rows,forms):
    return dict(id=name,parent_deformer=parent,parent_part=part,visible=True,enabled=True,
        binding=dict(axes=axes),kind={'Warp':dict(rows=rows,cols=rows,quad=True,
        opacities=[1.0]*len(forms),positions=blob(forms))})


def build(images,names,out):
    out.mkdir(parents=True,exist_ok=True)
    parameters=[('ParamPose',0,max(1,len(names)-1)),('ParamBreath',-1,1),
                ('ParamHead',-1,1),('ParamTail',-1,1),('ParamLift',0,1),('ParamBlink',0,1)]
    model=dict(canvas=dict(pixels_per_unit=512,origin_x=256,origin_y=256,width=512,height=512),
        parameters=[dict(id=n,min=lo,max=hi,default=0,repeat=False,decimals=3,blend_shape=False) for n,lo,hi in parameters],
        parts=[],art_meshes=[],deformers=[],glues=[],draw_groups=[])
    g=grid(np.linspace(0,512,3),np.linspace(0,512,3))
    model['deformers'].append(warp('Lift',None,None,[axis(4,[0,1])],2,
        [(g-[256,256+40*lift])/512 for lift in [0,1]]))
    g=grid(np.linspace(0,512,9),np.linspace(0,512,9))
    chest=(1-smooth(260,391,g[:,:,1]))*.95+.05*(1-smooth(320,401,g[:,:,1]))
    model['deformers'].append(warp('Breathing',0,None,[axis(1,[-1,0,1])],8,
        [(g+np.stack(((g[:,:,0]-256)*.009*chest,-4.5*chest),axis=-1)*b)/512 for b in [-1,0,1]]))
    # Each 512px canvas gets a four-pixel texture gutter. This keeps interpolation
    # inside the source drawing when mipmapping the Android desktop-size portrait.
    side=2048
    slots=(side//520)**2
    atlases=[]
    for i,name in enumerate(names):
        pic=Image.open(images/(name+'.png')).convert('RGBA')
        assert pic.size==(512,512)
        page=i//slots
        if page==len(atlases):atlases.append(Image.new('RGBA',(side,side)))
        col,row=(i%slots)%3,(i%slots)//3
        ax,ay=4+520*col,4+520*row
        atlases[page].paste(pic,(ax,ay))
        l,t,r,b=pic.getchannel('A').getbbox()
        l,t,r,b=max(1,l-1),max(1,t-1),min(511,r+1),min(511,b+1)
        eyes=eyes_in_picture(pic,name)
        ex=np.mean([e[0] for e in eyes]) if eyes else l+.32*(r-l)
        ey=np.mean([e[1] for e in eyes]) if eyes else t+.25*(b-t)
        direction=1 if ex<(r+l)/2 else -1
        gx=grid(np.linspace(0,512,13),np.linspace(0,512,13))
        x,y=gx[:,:,0],gx[:,:,1]
        nx,ny=ex+41*direction,ey+66
        face=np.exp(-((x-ex)/93)**4)*(1-smooth(ey+38,ey+112,y))
        near_front=abs(ex-(r+l)/2)<36
        tailcenter=(l+.74*(r-l)) if direction==1 else (r-.74*(r-l))
        ty=t+.40*(b-t)
        tail=np.exp(-((x-tailcenter)/70)**4)*np.exp(-((y-(ty-35))/72)**4)*(1-face)
        if near_front:tail*=.30
        if any(v in name.lower() for v in ('sleep','lie','curl','belly')):tail*=.18
        forms=[]
        for wag in (-1,0,1):
            for turn in (-1,0,1):
                angle=.066*direction*turn
                dx=face*((np.cos(angle)-1)*(x-nx)-np.sin(angle)*(y-ny))
                dy=face*(np.sin(angle)*(x-nx)+(np.cos(angle)-1)*(y-ny))
                dx+=wag*tail*(7.5+.055*(ty-y))*direction
                dy-=wag*tail*(2.6+.04*(x-tailcenter)*direction)
                forms.append((gx+np.stack([dx,dy],axis=-1))/512)
        model['parts'].append(dict(id='Part'+name,parent_part=None,visible=True,enabled=True,
            binding=dict(axes=[]),draw_orders=[500]))
        model['deformers'].append(warp('FurAndHead'+name,1,i,[axis(2,[-1,0,1]),axis(3,[-1,0,1])],12,forms))
        # Double resolution around each eye permits a blink without stretching muzzle hair.
        xs=list(np.arange(l,r,16))+[r]
        ys=list(np.arange(t,b,16))+[b]
        for xx,yy,w,h,_ in eyes:
            xs+=list(np.arange(max(l,xx-w),min(r,xx+w),3))
            ys+=list(np.arange(max(t,yy-h),min(b,yy+h),2))
        xs,ys=np.unique(xs),np.unique(ys)
        g=grid(xs,ys)
        u,v=g[:,:,0],g[:,:,1]
        eyelid=np.zeros_like(v)
        for xx,yy,w,h,_ in eyes:
            weight=(1-smooth(.89,1.8,abs(u-xx)/max(w*.55,2.5)))*(1-smooth(.92,2.1,abs(v-yy)/max(h*.64,3.0)))
            eyelid+=(.94*(yy-v)+.6)*weight
        pkeys=sorted(set([0,max(0,i-1),i,min(len(names)-1,i+1),len(names)-1]))
        blinkkeys=[0,1] if eyes else [0]
        bindings=[axis(0,pkeys)]+([axis(5,blinkkeys)] if eyes else [])
        positions=[];alphas=[]
        for blink in blinkkeys:
            displaced=np.stack([u,v+blink*eyelid],axis=-1)/512
            for k in pkeys:
                positions.append(displaced)
                alphas.append(float(k==i))
        idx=np.arange(len(xs)*len(ys)).reshape(len(ys),len(xs))
        triangles=np.stack([idx[:-1,:-1],idx[:-1,1:],idx[1:,1:],idx[:-1,:-1],idx[1:,1:],idx[1:,:-1]],axis=-1)
        model['art_meshes'].append(dict(id='Dog'+name,parent_part=i,parent_deformer=2+i,
            visible=True,enabled=True,texture=page,flags=4,vertex_count=int(idx.size),
            uvs=blob(np.stack([(u+ax)/side,(v+ay)/side],axis=-1)),indices=blob(triangles,'<u2'),
            masks=[],binding=dict(axes=bindings),opacities=alphas,draw_orders=[500]*len(alphas),
            positions=blob(positions)))
    paths=[]
    for j,atlas in enumerate(atlases):
        fn=f'coat-{j}.png'
        atlas.save(out/fn)
        paths.append(fn)
    for fn,obj in [('rig',model),('pose-index',{n:i for i,n in enumerate(names)}),('textures',paths),
             ('CuteDog.model3',dict(Version=3,FileReferences=dict(Moc='CuteDog.moc3',Textures=paths)))]:
        (out/(fn+'.json')).write_text(json.dumps(obj,separators=(',',':')),encoding='utf8')


if __name__=='__main__':
    cli=argparse.ArgumentParser()
    cli.add_argument('images',type=Path);cli.add_argument('out',type=Path);cli.add_argument('names',nargs='+')
    a=cli.parse_args()
    build(a.images,a.names,a.out)
