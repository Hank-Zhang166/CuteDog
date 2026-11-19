"""Authored whole-dog 24 Hz performances, anchored to the unchanged standing plate."""
from functools import lru_cache
from pathlib import Path
import argparse, json, math, sys, os
import numpy as np
from PIL import Image, ImageDraw

SRC = Path(__file__).resolve().parent
HERE = Path(os.environ.get("CUTEDOG_EMOTION_OUT", SRC)).resolve()
HERE.mkdir(parents=True, exist_ok=True)
sys.path[:0] = [str(SRC.parent / 'perform'), str(SRC.parent / 'deps')]
import cv2
from scipy.interpolate import PchipInterpolator
from performance import BASE, STAND, Face, Tail, X, Y, premul, straight, remap, smooth, REPO

FPS = 24
COUNTS = dict(excited=44, cuddle=52, sleepy=65, shy=53,
              startled=39, pet_head=48, request_pet=52, seek=64, call=45, follow=68)
EXPRESSION = dict(excited='joy', cuddle='blink', sleepy='sleepy',
                  shy='sleepy', startled='startled', pet_head='blink', request_pet='blink')
# Explicit pairs in the original 3×3 face sheet, measured from each 418px tile.
# Near eye / nose centers produce a nose-locked similarity registration.
TILES = dict(joy=(1,(151,167),(58,197)), blink=(2,(152,173),(59,199)),
             sleepy=(3,(172,164),(85,202)),
             startled=(7,(163,144),(69,185)))
W, H = 200, 194
OX, OY = 61, 62
rx = X[OY:OY+H,OX:OX+W]; ry = Y[OY:OY+H,OX:OX+W]
localx,localy = np.meshgrid(np.arange(W,dtype='f'),np.arange(H,dtype='f'))
def radial(x,y,cx,cy,wx,wy):
    return np.exp(-.5*((x-cx)**2/wx**2 + (y-cy)**2/wy**2)).astype('f')

class Expression:
    def __init__(self):
        atlas = Image.open(REPO/'art/puppet/expressions/head-expressions.png').convert('RGBA')
        self.base = BASE[OY:OY+H,OX:OX+W].copy()
        self.plates = {}
        def gray(im):
            flat = im[:,:,:3]+(1-im[:,:,3:])*.79
            return np.rint(np.clip(cv2.cvtColor(flat,cv2.COLOR_RGB2GRAY),0,1)*255).astype('uint8')
        flow = cv2.DISOpticalFlow_create(cv2.DISOPTICAL_FLOW_PRESET_MEDIUM)
        for name,(cell,eye,nose) in TILES.items():
            x,y=cell%3*418,cell//3*418
            donor=premul(np.array(atlas.crop((x,y,x+418,y+418))))
            # Inverse similarity from stand face into its expression painting.
            eye=np.array(eye); v=np.array(nose)-eye
            orig=np.array([-45.,15.]); a=np.dot(v,orig)/np.dot(orig,orig)
            b=(orig[0]*v[1]-orig[1]*v[0])/np.dot(orig,orig)
            dx,dy=rx-135,ry-154
            d=remap(donor,eye[0]+a*dx-b*dy,eye[1]+b*dx+a*dy)
            eyeMask=smooth((1-((rx-128)/36)**2-((ry-153)/18)**2)*3)
            mouth=smooth((1-((rx-108)/44)**2-((ry-192)/31)**2)*3)
            weight={'joy':eyeMask*.35,'blink':eyeMask,'sleepy':np.maximum(eyeMask,mouth),
                    'startled':np.maximum(eyeMask,mouth)}[name][:,:,None]
            target=self.base*(1-weight)+d*weight
            f=flow.calc(gray(self.base),gray(target),None)
            rev=flow.calc(gray(target),gray(self.base),None)
            self.plates[name]=(target,f,rev)
    @lru_cache(maxsize=64)
    def patch(self,name,level):
        u=level/64
        target,f,rev=self.plates[name]
        src=remap(self.base,localx-f[:,:,0]*u,localy-f[:,:,1]*u)
        dst=remap(target,localx-rev[:,:,0]*(1-u),localy-rev[:,:,1]*(1-u))
        return (src*(1-u)+dst*u-self.base).astype('f')

def pulse(u,a,p,b):
    if u<=a or u>=b:return 0.
    return float(smooth((u-a)/(p-a)) if u<p else smooth((b-u)/(b-p)))
def hold(u,a,p,q,b):
    return float(smooth((u-a)/(p-a))*smooth((b-u)/(b-q)))

def score(name,u):
    # Primary controls are pixels, degrees and registered texture strength. The
    # feet stay fixed; chest flex and ear lag are spatial fields on the same coat.
    P=lambda a,p,b:pulse(u,a,p,b)
    H=lambda a,p,q,b:hold(u,a,p,q,b)
    C=dict(yaw=0.,dx=0.,dy=0.,tilt=0.,ears=0.,chest=0.,tail=0.,tip=0.,expr=0.,gaze=0.)
    if name=='excited':
        a=P(.01,.12,.25); b=P(.12,.26,.43); c=P(.29,.44,.62); d=P(.48,.64,.84)
        env=H(0,.14,.74,1)
        C.update(chest=7*a-5*b+4*c-3*d,dx=-3*b-4*d,dy=6*a-8*b+3*c-6*d,ears=4*a-5*b+4*c,
                 tilt=3*b-2*d,expr=.7*env,tail=18*math.sin(2*math.pi*(u*4.5+.06))*env,
                 tip=4*math.sin(2*math.pi*(u*4.5-.09))*env)
    elif name=='cuddle':
        a=H(.01,.28,.54,.94); b=P(.28,.49,.75)
        C.update(dx=-12*a+5*b,dy=-3*a+3*b,tilt=-9*a+4*b,chest=2*a,ears=4*b,
                 expr=.82*a,tail=7*a+6*math.sin(8*u)*a)
    elif name=='sleepy':
        a=H(.04,.32,.64,.97); b=P(.27,.47,.64); c=P(.59,.67,.82)
        C.update(dy=10*a+7*b-7*c,dx=3*a,tilt=-5*a,ears=3*a,chest=3*a,expr=.94*a,tail=-5*a)
    elif name=='shy':
        a=H(.03,.23,.55,.92); b=P(.39,.63,.86); c=P(.14,.27,.50)
        C.update(yaw=.65*b,dx=9*a-4*b,dy=7*a-3*b,tilt=7*a-4*b,ears=5*c,
                 expr=.96*a*(1-.27*b),gaze=-1.6*a,tail=8*c-4*b)
    elif name=='startled':
        a=P(0,.11,.35); b=H(.06,.17,.51,.82); c=P(.43,.58,.82)
        C.update(dx=9*a+5*b,dy=-12*a-4*b+4*c,tilt=5*a,ears=10*a+12*b,chest=-4*a+3*c,
                 expr=b,tail=17*a-6*c,tip=-4*a+3*c,gaze=-1.3*b)
    elif name=='pet_head':
        a=P(.01,.22,.49); b=P(.40,.61,.91); p=a+.92*b
        C.update(dx=2*a-3*b,dy=9*p,tilt=-4*a+5*b,ears=8*p,chest=2*p,expr=.92*p,
                 tail=(6+4*math.sin(16*u))*p)
    elif name=='request_pet':
        a=P(.02,.23,.49); b=H(.34,.56,.72,.96)
        C.update(dx=-5*a-10*b,dy=-7*a-12*b,tilt=-3*a+6*b,ears=-4*b,chest=-3*b,
                 expr=.44*P(.68,.76,.88),tail=(12*math.sin(17*u)+5)*H(0,.18,.77,.99))
    elif name=='seek':
        a=H(.02,.22,.34,.53); b=H(.32,.55,.65,.88); c=P(.65,.78,.95)
        C.update(yaw=2.25*a+.35*b,dx=2*a-7*b,dy=-5*a+7*b-4*c,tilt=3*a-5*b,
                 ears=-3*a+2*c,gaze=1.3*a-1.2*b,tail=5*c)
    elif name=='call':
        a=P(.01,.105,.27); b=H(.08,.29,.58,.95)
        C.update(yaw=1.85*b,dx=-3*a,dy=-8*a-3*b,tilt=3*b,ears=-5*a,
                 tail=(10*math.sin(15*u)-2)*b,tip=2*math.sin(15*u-.7)*b)
    elif name=='follow':
        traj=pointer(u)
        env=H(0,.13,.81,1)
        C.update(yaw=(.12+2.40*traj[0])*env,dx=(traj[0]-.45)*9*env,
                 dy=(traj[1]-.47)*24*env,tilt=(traj[0]-.45)*8*env,
                 gaze=(traj[0]-.45)*2.8*env,ears=2*(traj[1]-.45)*env)
    C['yaw']*=6.0  # Model-space yaw in degrees.
    return C

def pointer(u):
    keys=[0,.15,.36,.55,.76,1]
    return tuple(float(PchipInterpolator(keys,l)(u)) for l in
                 ([.2,.12,.89,.75,.25,.43],[.45,.76,.29,.21,.66,.46]))

class Actor:
    def __init__(self):
        self.face=Face();self.tail=Tail();self.expr=Expression()
        self.head=smooth((265-X)/59)*smooth((297-Y)/69)
        self.ears=smooth((146-Y)/42)*self.head
        self.chest=radial(X,Y,211,241,94,95)*smooth((412-Y)/81)
        self.eyes=radial(X,Y,135,154,10,7)+.46*radial(X,Y,107,149,4,4)
    @lru_cache(maxsize=6)
    def rotated(self,angle):
        field=self.face.inverse(angle/64)
        im=cv2.remap(BASE,*field,cv2.INTER_CUBIC,borderMode=cv2.BORDER_CONSTANT)
        return im,field
    def image(self,name,c):
        if max(map(abs,c.values()))<1e-6:return STAND
        base,field=self.rotated(round(c['yaw']*64))
        im=base.copy()
        if name in EXPRESSION and c['expr']>.008:
            patch=self.expr.patch(EXPRESSION[name],min(64,round(c['expr']*64)))
            if c['yaw']<.008: im[OY:OY+H,OX:OX+W]+=patch
            else:
                im+=remap(patch,field[0]-OX,field[1]-OY)
        im=self.tail.draw(im,c['tail'],c['tip'])
        t=math.radians(c['tilt'])
        sx=X-self.head*(c['dx']-t*(Y-215))-self.eyes*c['gaze']-self.ears*c['ears']*.35
        sy=Y-self.head*(c['dy']+t*(X-175))-self.chest*c['chest']-self.ears*c['ears']
        return straight(remap(im,sx,sy))

def generate(ids,qa=False):
    actor=Actor()
    rows=[]; full={}
    for name in ids:
        directory=HERE/'frames'/name
        directory.mkdir(parents=True,exist_ok=True)
        n=COUNTS[name];track=[];thumbs=[]
        samples={round(n*u) for u in [0,.16,.33,.52,.69,.83,1]}
        for i in range(n+1):
            u=i/n;c=score(name,u)
            if name=='follow': c.update(targetX=pointer(u)[0],targetY=pointer(u)[1])
            track.append({k:round(v,5) for k,v in c.items()})
            if qa and i not in samples:continue
            im=Image.fromarray(STAND if i in (0,n) else actor.image(name,c))
            if not qa: im.save(directory/f'{i:05}.webp',lossless=True,exact=True,method=1)
            if i in samples:
                thumb=Image.new('RGB',im.size,'#dce6e3');thumb.paste(im,mask=im.getchannel('A'))
                thumbs.append(thumb.resize((100,100),Image.Resampling.LANCZOS))
        row=Image.new('RGB',(7*100+100,100),'#dce6e3');ImageDraw.Draw(row).text((6,42),name,fill='#203b38')
        for j,im in enumerate(thumbs):row.paste(im,((j+1)*100,0))
        rows.append(row)
        full[name]=dict(fps=FPS,intervals=n,entry='stand_left',exit='stand_left',terminalSample=True,
                        directory='frames/'+name,expression=EXPRESSION.get(name),controls=track)
        print(name,n,flush=True)
    sheet=Image.new('RGB',(800,len(rows)*100))
    for i,row in enumerate(rows):sheet.paste(row,(0,i*100))
    sheet.save(HERE/('audit-'+ids[0]+'.jpg'),quality=93)
    if not qa: (HERE/('trajectories-'+ids[0]+'.json')).write_text(json.dumps(full,separators=(',',':')),encoding='utf8')

if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('ids',nargs='*');parser.add_argument('--qa',action='store_true')
    args=parser.parse_args()
    cv2.setNumThreads(1)
    generate(args.ids or list(COUNTS),args.qa)
