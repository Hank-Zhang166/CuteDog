"""Face landmark surfaces and articulated tail for the original CuteDog illustration."""
from pathlib import Path
import json, math, os
import numpy as np
import cv2
from scipy.interpolate import PchipInterpolator
from PIL import Image, ImageOps

SRC=Path(__file__).resolve().parent
HERE=Path(os.environ.get('CUTEDOG_REVIEW_PERFORM_OUT',str(SRC))).resolve()
HERE.mkdir(parents=True,exist_ok=True)
ROOT=SRC.parents[2]
REPO=next((p for p in SRC.parents if (p/'art/live2d/poses/Stand.png').is_file()), ROOT/'work/CuteDog')
POSES=REPO/'art/live2d/poses'
FPS=24
Y,X=np.mgrid[:512,:512].astype(np.float32)

def load(name):
    return np.asarray(Image.open(POSES/(name+'.png')).convert('RGBA')).copy()

def smooth(t):
    t=np.clip(t,0,1)
    return t*t*(3-2*t)

def premul(im):
    v=im.astype(np.float32)/255
    v[:,:,:3]*=v[:,:,3:]
    return v

def straight(v):
    a=np.clip(v[:,:,3:],0,1)
    return np.rint(np.clip(np.concatenate([v[:,:,:3]/np.maximum(a,1e-7),a],2),0,1)*255).astype('uint8')

def remap(v,x,y):
    return cv2.remap(v,x.astype('f'),y.astype('f'),cv2.INTER_LINEAR,borderMode=cv2.BORDER_CONSTANT)

STAND=load('Stand')
BASE=premul(STAND)
class Face:
    """Project the original painting on a shallow head surface around the neck.

    The drawing stays in a single UV chart. Each eye and the nose have a locally
    level surface, so yaw changes their relative positions without smearing them.
    Only the ruff between the moving skull and the fixed shoulder takes up twist.
    """
    limits=(-12.,16.)

    def __init__(self):
        # Denser topology around the face; outside the neck the same mesh is static.
        xx=np.unique(np.r_[2,43,np.arange(51,281,6),310,360,430,510])
        yy=np.unique(np.r_[2,44,np.arange(52,285,6),325,375,440,510])
        self.coords=np.stack(np.meshgrid(xx,yy),axis=-1).reshape(-1,2).astype('f')
        ix=np.arange(len(self.coords)).reshape(len(yy),len(xx))
        self.tri=np.stack([ix[:-1,:-1],ix[:-1,1:],ix[1:,1:],
                           ix[:-1,:-1],ix[1:,1:],ix[1:,:-1]],axis=-1).reshape(-1,3)

    def position(self,coords,yaw):
        x,y=coords[:,0],coords[:,1]
        a=np.radians(yaw)
        # Visible eye on the near cheek, partly visible eye on the far cheek.
        depth=39+8*np.tanh((x-125)/35)-.11*(x-150)+.08*(y-145)
        for cx,cy,rx,ry,z in [(89,170,21,17,56),(133,155,17,13,50),
                              (108,150,13,12,46),(104,194,24,18,54),
                              (138,91,16,17,29),(180,94,17,19,44)]:
            r=np.hypot((x-cx)/rx,(y-cy)/ry)
            w=1-smooth((r-.55)/1.10)
            depth=depth*(1-w)+z*w
        # Yaw about the neck, followed by a small coordinated ear/head tilt.
        dx=x-172.;dy=y-211.
        hx=np.cos(a)*dx+np.sin(a)*depth
        roll=.16*a
        ox=172.+np.cos(roll)*hx-np.sin(roll)*dy
        oy=211.+np.sin(roll)*hx+np.cos(roll)*dy
        ruff=smooth((272-y)/59)*smooth((270-x)/68)
        return coords+np.stack([(ox-x)*ruff,(oy-y)*ruff],axis=1)

    def inverse(self,yaw):
        if not yaw:return X,Y
        # Compute the inverse on a fixed 3px grid. Outside the neck the offset
        # is identically zero, including all paws and the transparent boundary.
        gy,gx=np.mgrid[0:514:3,0:514:3].astype('f')
        target=np.column_stack([gx.ravel(),gy.ravel()])
        guess=target.copy()
        for _ in range(7):
            guess+=target-self.position(guess,yaw)
        shift=(guess-target).reshape(gx.shape+(2,))
        delta=cv2.remap(shift,X/3,Y/3,cv2.INTER_CUBIC,borderMode=cv2.BORDER_REPLICATE)
        return X+delta[:,:,0],Y+delta[:,:,1]


class Tail:
    def __init__(self):
        back=np.interp(X[0],[0,248,263,278,298,320,340,354,370,385,399,412,433,512],
                         [200,190,198,204,208,209,205,202,205,217,235,264,281,281])[None,:]
        left=smooth((X-260)/9)
        self.upper=left*smooth((back-Y+1)/3)
        # A separate matching turn-around drawing includes the underside of the
        # curled plume. That fur becomes visible when the tip lifts off the back.
        atlas=REPO/'art/puppet/pose-sources/right-parts-atlas.png'
        full=Image.open(atlas).convert('RGBA')
        side=full.width//3
        source=ImageOps.mirror(full.crop((0,side,side,side*2)))
        box=source.getchannel('A').point(lambda a:255 if a>45 else 0).getbbox()
        source=source.crop((box[0]-3,box[1]-3,box[2]+3,box[3]+3))
        w,h=source.size
        donor=remap(premul(np.asarray(source)),(X-265)*w/160,(Y-111)*h/142)
        edge=smooth((back-Y-1)/11)*left
        self.tail=BASE*edge[:,:,None]+donor*(1-edge[:,:,None])
        self.tail*=smooth((X-256)/12)[:,:,None]
        self.root=np.array([390.,219.])
    def draw(self,im,angle,tip=0):
        if abs(angle)+abs(tip)<.000005:return im
        body=im*(1-self.upper[:,:,None])
        # The plume rotates almost rigidly about its insertion. A small tip lag
        # bends along the curl and subsides at the root, conserving its volume.
        rx=X-self.root[0];ry=Y-self.root[1]
        local=np.radians(angle+tip*smooth(np.sqrt(rx**2+ry**2)/125))
        c=np.cos(local);s=np.sin(local)
        sx=self.root[0]+c*rx+s*ry;sy=self.root[1]-s*rx+c*ry
        moving=remap(self.tail,sx,sy)
        composed=body+moving*(1-body[:,:,3:])
        # Begin and end at the unaltered source; only the previously occluded
        # fringe needs to be revealed over the first three degrees.
        s=smooth((abs(angle)+abs(tip))/3.0)
        return im*(1-s)+composed*s

def timed(t,ts,vs):
    return float(PchipInterpolator(ts,vs)(np.clip(t,ts[0],ts[-1])))

def publish():
    for name,count in [('look_around',100),('tail_wag',80)]:
        dest=HERE/name;dest.mkdir(exist_ok=True)
        meta=[]
        for i in range(count+1):
            t=i/FPS;u=i/count
            if name=='look_around':
                yaw=timed(u,[0,.105,.24,.43,.58,.72,.93,1],[0,-11.5,-11.5,9.,15.5,15.5,0,0])
                angle=tip=0.
            else:
                yaw=0;env=smooth(u/.11)*smooth((1-u)/.15)
                phase=2*np.pi*(1.72*t+.19*np.sin(1.8*t))
                angle=env*(22*np.sin(phase)+2.1*np.sin(2*phase-.45))
                tip=env*4.0*np.sin(phase-.79)
            meta.append(dict(yaw=round(yaw,6),rootDegrees=round(float(angle),5),tipLag=round(float(tip),5)))
        (dest/'motion.json').write_text(json.dumps(dict(fps=FPS,frameCount=count,terminalSample=True,keys=meta),separators=(',',':')),encoding='utf8')

if __name__=='__main__':publish()
