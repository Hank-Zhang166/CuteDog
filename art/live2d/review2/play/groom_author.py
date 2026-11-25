"""Author and render the seated shoulder-grooming model from registered whole drawings."""
from pathlib import Path
from functools import lru_cache
import json
import math
import os
import subprocess
import sys
import numpy as np
from PIL import Image,ImageDraw
from scipy.interpolate import RBFInterpolator,PchipInterpolator
from scipy.ndimage import distance_transform_edt,gaussian_filter1d
from layout import ART,REPO,SOURCE,exporter
from interaction_rig import blob
sys.path.insert(0,str(SOURCE.parent/'deps'))
import cv2
GROOM_RIG=SOURCE/'groom-rig'
GUIDES=SOURCE/'groom-guides'
Y,X=np.mgrid[:512,:512].astype('f')
GY,GX=np.mgrid[:519:5,:519:5]
GRID=np.stack([GX.ravel(),GY.ravel()],1)
GUIDE_NAMES=['Sit05','LeftQuarter','Front','Quarter','Scan','Press','Brush']
KEYS=[0,4,7,11,17,23,29]
CONTROLS=['ParamPose','ParamComb','ParamCoat','ParamBreath']
def eased(x):
    t=np.clip(x,0,1);return t*t*(3-2*t)
def rgba(p):return np.array(Image.open(p).convert('RGBA'))
def pm(raw):
    z=raw.astype('f')/255;z[:,:,:3]*=z[:,:,3:];return z
def straight(z):
    a=z[:,:,3:];return np.rint(np.clip(np.concatenate([z[:,:,:3]/np.maximum(a,1e-6),a],2),0,1)*255).astype('u1')
def sample(z,x,y):return cv2.remap(z,x.astype('f'),y.astype('f'),cv2.INTER_LINEAR)
def border(alpha,axis,upper):
    body=alpha>.50
    if not upper:body=np.flip(body,axis)
    edge=body.argmax(axis=axis).astype('f');present=body.any(axis=axis)
    if not upper:edge=body.shape[axis]-1-edge
    edge=np.interp(np.arange(len(edge)),np.flatnonzero(present),edge[present])
    # Only the registration trajectory is smoothed. The actual fur alpha stays intact.
    return gaussian_filter1d(edge,1.7)
def seated_coat(source,sit,nose_x):
    original=border(source[:,:,3],0,True);neutral=border(sit[:,:,3],0,True)
    start=max(292,nose_x+13)
    ease=eased((X-start)/(407-start))
    vertical=(neutral-original)[None,:]*ease*(1-eased((Y-295)/45))
    aligned=sample(source,X,Y-vertical)
    far=border(aligned[:,:,3],1,False);fixed=border(sit[:,:,3],1,False)
    horizontal=(far-fixed)[:,None]*eased((Y-260)/55)*eased((X-339)/62)
    aligned=sample(aligned,X+horizontal,Y)
    # The fixed lower body takes over after its contour has been aligned, away
    # from the muzzle. There is only one sampled outer edge above this junction.
    coverage=eased((Y-297)/37)[:,:,None]
    return aligned*(1-coverage)+sit*coverage
def prepare():
    GUIDES.mkdir(exist_ok=True);GROOM_RIG.mkdir(exist_ok=True)
    path=REPO/'art/live2d/review2/perform/care-seated-atlas.png';a=rgba(path);h,w=a.shape[:2]
    sit=pm(rgba(ART/'Sit05.png'));Image.fromarray(straight(sit)).save(GUIDES/'Sit05.png')
    # A seated head turn keeps the feet and lower ribcage on their exact approved pixels.
    for i,n in enumerate(('Scan','Press','Brush')):
        v=a[h//3:2*h//3,i*w//3:(i+1)*w//3].copy()
        _,lab,stats,_=cv2.connectedComponentsWithStats((v[:,:,3]>80).astype('u1'))
        keep=np.argmax(stats[1:,4])+1
        v[:,:,3]*=cv2.dilate((lab==keep).astype('u1'),np.ones((5,5),'u1'))
        ground=np.where(v[:,:,3]>127)[0].max();heel=np.where(v[ground-14,:,3]>127)[0].min()
        dx,dy=123-.93*heel,428-.94*ground
        z=sample(pm(v),(X-dx)/.93,(Y-dy)/.94)
        whole=seated_coat(z,sit,GUIDE_POINTS[n][2][0])
        Image.fromarray(straight(whole)).save(GUIDES/(n+'.png'))
    # Construct the intermediate forward glance from the same dog's approved frontal head.
    front=pm(rgba(ART/'Turn03.png'))
    mask=np.zeros((512,512),'u1')
    points=[[145,210],[150,163],[161,127],[180,104],[192,75],[201,73],[217,96],
            [238,98],[251,73],[261,74],[275,105],[288,124],[300,153],[303,179],
            [300,202],[294,226],[281,252],[265,272],[214,285],[173,267],[148,242]]
    cv2.fillPoly(mask,[np.array(points,'i4')],255)
    head=front*(cv2.GaussianBlur(mask.astype('f')/255,(0,0),2)[:,:,None])
    # Place the skull on the fixed cervical axis; its ruff meets the existing chest.
    head=sample(head,X+47-18*eased((Y-181)/88),Y-12)
    ridge=np.interp(X,[0,112,128,150,174,207,242,269,290,314,335,512],
                    [284,282,252,210,193,196,206,220,245,261,270,270])
    lower=sit*eased((Y-ridge)/9)[:,:,None]
    cover=eased((Y-222)/44)[:,:,None]
    merged=head+lower*(1-head[:,:,3:]);merged=merged*(1-cover*.34)+lower*(cover*.34)
    stationary=eased((Y-271)/40)[:,:,None]
    merged=merged*(1-stationary)+sit*stationary
    Image.fromarray(straight(merged)).save(GUIDES/'Front.png')
    quarter=pm(rgba(ART/'Turn02.png'))
    mask.fill(0)
    points=[[119,190],[122,148],[137,112],[153,82],[167,75],[179,94],[200,100],[217,77],
            [226,75],[242,96],[257,118],[270,137],[279,165],[279,196],[275,224],[263,244],
            [246,263],[212,278],[173,278],[144,261],[125,235]]
    cv2.fillPoly(mask,[np.array(points,'i4')],255)
    head=(quarter*(cv2.GaussianBlur(mask.astype('f')/255,(0,0),2)[:,:,None]))[:,::-1].copy()
    head=sample(head,X+125-14*eased((Y-199)/84),Y-26)
    side=np.interp(Y,[87,104,118,133,144,154,166,180,198,220,250,279,410],
                     [162,157,147,138,130,123,116,111,110,116,126,137,137])
    head*=eased((X-side+2)/4)[:,:,None]
    merged=head+lower*(1-head[:,:,3:])
    merged=merged*(1-stationary)+sit*stationary
    Image.fromarray(straight(merged)).save(GUIDES/'Quarter.png')
    head=quarter*(cv2.GaussianBlur(mask.astype('f')/255,(0,0),2)[:,:,None])
    head=sample(head,X+16-7*eased((Y-189)/93),Y-3)
    merged=head+lower*(1-head[:,:,3:]);merged=merged*(1-stationary)+sit*stationary
    Image.fromarray(straight(merged)).save(GUIDES/'LeftQuarter.png')
def sheet(guides=True):
    names=GUIDE_NAMES if guides else [f'G{i:02}' for i in (0,2,4,7,10,13,16,20,23,26,29)]
    base=GUIDES if guides else GROOM_RIG
    out=Image.new('RGB',(len(names)*190,255),(122,141,141))
    for j,n in enumerate(names):
        im=Image.open(base/(n+'.png')).crop((78,66,336,334));im.thumbnail((190,208))
        out.paste(im,(j*190,7),im);ImageDraw.Draw(out).text((j*190+5,221),n,fill='white')
    out.save(SOURCE/('groom-guide-preview.jpg' if guides else 'groom-sweep.jpg'),quality=96)
# Left/right eye, nose, left/right mouth corner, low lip, left/right ear, crown,
# left/right temple and the lower ruff. Coordinates are registered source pixels.
GUIDE_POINTS={
'Sit05':[(130.8,144.4),(157.4,151.1),(111.5,165.4),(110,182),(160,177),(118,199),
         (164,81),(208,87),(183,100),(116,144),(254,159),(193,233)],
'LeftQuarter':[(146,157.6),(185,159.3),(149,176.1),(144,192),(188,189),(156,212),
               (150,81),(207,81),(177,103),(106,153),(256,168),(191,238)],
'Front':[(161.2,163.3),(201.5,163.4),(183,183.6),(157,193),(210,192),(185,218),
         (149,89),(209,90),(176,109),(110,168),(251,169),(188,247)],
'Quarter':[(185.5,182.2),(224.3,180.5),(221.4,202.1),(180,213),(227,214),(219,236),
           (162,105),(220,104),(191,123),(113,183),(258,190),(195,254)],
'Scan':[(237.5,183.1),(266.5,190.3),(266.8,224.2),(225,216),(266,231),(253,237),
        (224,91),(274,105),(244,113),(150,167),(281,192),(201,251)],
'Press':[(259.7,207.4),(287.2,203.7),(291.5,242),(251,238),(291,246),(287,248),
         (237,111),(287,104),(257,120),(157,182),(297,214),(208,262)],
'Brush':[(277.5,227.7),(300.7,222.7),(305.4,258.1),(256,254),(306,262),(303,263),
         (246,132),(297,120),(269,137),(166,198),(308,230),(219,270)]}
NSIZE={'Sit05':[(4,7),(17,13),(17,16)],'Front':[(15,12),(15,12),(20,15)],
       'LeftQuarter':[(9,11),(18,13),(19,15)],
       'Quarter':[(18,13),(9,11),(19,15)],'Scan':[(16,13),(7,11),(18,14)],
       'Press':[(17,7),(4,7),(17,10)],'Brush':[(13,5),(3,3),(13,7)]}
FUR={'Sit05':(282,249),'LeftQuarter':(282,249),'Front':(282,249),'Quarter':(282,248),'Scan':(286,247),'Press':(289,250),'Brush':(305,264)}
STATIONARY=np.array([[0,0],[255,0],[512,0],[0,147],[512,147],[0,278],[495,280],[80,315],
                     [142,307],[198,307],[251,307],[291,306],[319,282],[344,259],[383,279],
                     [430,314],[44,428],[152,420],[212,423],[352,423],[445,428],[0,510],[512,510]],float)
def rotation(a):
    return np.array([[np.cos(a),np.sin(a)],[-np.sin(a),np.cos(a)]])
def tilt(pts):return math.atan2(*(pts[1]-pts[0])[::-1])
def expand(n):
    pts=np.array(GUIDE_POINTS[n],float);v=list(pts);r=rotation(tilt(pts))
    for i,(w,h) in enumerate(NSIZE[n]):
        for x,y in ((-.48,0),(.48,0),(0,-.48),(0,.48)):
            v.append(pts[i]+np.array([x*w,y*h])@r)
    return np.array(v)
def shape(a,b,t):
    s,r=expand(a),expand(b);ca,cb=s[:2].mean(0),r[:2].mean(0);ar,br=tilt(s),tilt(r)
    local=(1-t)*(s-ca)@rotation(-ar)+t*(r-cb)@rotation(-br)
    return local@rotation((1-t)*ar+t*br)+(1-t)*ca+t*cb
def field(q,src,skull=False):
    if skull:
        c=q[:2].mean(0);z=src[:2].mean(0);basis=rotation(tilt(src)-tilt(q))
        origin=(GRID-c)@basis+z;diff=src-((q-c)@basis+z)
    else:
        q=np.vstack([q,STATIONARY]);src=np.vstack([src,STATIONARY]);origin=GRID;diff=src-q
    delta=RBFInterpolator(q,diff,kernel='thin_plate_spline',smoothing=3.2 if skull else 46.)(GRID)
    maps=(origin+delta).reshape(GX.shape+(2,)).astype('f')
    return cv2.remap(maps,X/5,Y/5,cv2.INTER_CUBIC,borderMode=cv2.BORDER_REPLICATE)
def feather(pts,radius,fade):
    mask=np.zeros((512,512),'u1');cv2.fillConvexPoly(mask,cv2.convexHull(np.rint(pts).astype('i4')),1)
    d=cv2.distanceTransform(1-mask,cv2.DIST_L2,5);return eased((radius+fade-d)/fade)
@lru_cache(None)
def layers(n):
    raw=rgba(GUIDES/(n+'.png'));h=expand(n);skin=raw.copy()
    mask=(feather(h[:6],13,3)*255).astype('u1')
    # Extend clean coat before reconstructing the hidden muzzle surface, so
    # transparent-pixel RGB from the PNG can never enter a turning cheek.
    valid=(raw[:,:,3]>245)&(mask==0)
    nearest=distance_transform_edt(~valid,return_distances=False,return_indices=True)
    filled=raw[:,:,:3][tuple(nearest)]
    restored=cv2.GaussianBlur(filled,(0,0),2.)
    skin[:,:,:3]=np.where(mask[:,:,None]>0,restored,raw[:,:,:3])
    return pm(raw),pm(skin)
def mix(a,b,t):
    al,bl=a[:,:,3],b[:,:,3];z=(1-t)*a+t*b
    def sd(c):
        m=(c>.5).astype('u1');return cv2.distanceTransform(m,cv2.DIST_L2,3)-cv2.distanceTransform(1-m,cv2.DIST_L2,3)
    d=np.clip(.5+(1-t)*sd(al)+t*sd(bl),0,1);w=eased(min(1,min(t,1-t)/.1))*eased((np.abs(al-bl)-.05)/.7)
    cov=z[:,:,3]*(1-w)+w*d
    return np.dstack([z[:,:,:3]/np.maximum(z[:,:,3:],1e-6)*cov[:,:,None],cov])
def shoulder_edge(a,b,t,q,combined):
    raw_a=layers(a)[0];raw_b=layers(b)[0]
    ta=border(raw_a[:,:,3],0,True);tb=border(raw_b[:,:,3],0,True)
    midway=(1-t)*ta+t*tb
    aa=sample(raw_a,X,Y+(ta-midway)[None,:]);bb=sample(raw_b,X,Y+(tb-midway)[None,:])
    aligned=aa*(1-t)+bb*t
    # Keep the dorsal alpha from a real transported fur contour at every pixel.
    # Choose color from both aligned textures only where both have coverage.
    donor=aa if t<.5 else bb
    rgb=aligned[:,:,:3]/np.maximum(aligned[:,:,3:],1e-6)
    edge=np.dstack([rgb*donor[:,:,3:],donor[:,:,3:]])
    start=max(287,q[2,0]+9,GUIDE_POINTS[a][2][0]+8,GUIDE_POINTS[b][2][0]+8)
    coat=eased((X-start)/13)*(1-eased((Y-midway[None,:]-13)/29))*(1-eased((Y-294)/29))
    return combined*(1-coat[:,:,None])+edge*coat[:,:,None]
def picture(i):
    j=next(j for j in range(len(KEYS)-1) if i<=KEYS[j+1]);a,b=GUIDE_NAMES[j:j+2]
    t=(i-KEYS[j])/(KEYS[j+1]-KEYS[j]);q=shape(a,b,t)
    fur=(1-t)*np.array(FUR[a])+t*np.array(FUR[b])
    pts={'nose':q[2],'lip':q[5],'shoulderCoat':fur,'eye':q[0],'otherEye':q[1],'farPaw':(146,416),'nearPaw':(197,419)}
    if t in (0,1):return rgba(GUIDES/((a if t==0 else b)+'.png')),pts
    qa,qb=expand(a),expand(b);la,lb=layers(a),layers(b)
    ids=[6,7,8,9,10,11]
    ma=field(q[ids],qa[ids]);mb=field(q[ids],qb[ids])
    body=mix(sample(la[1],ma[:,:,0],ma[:,:,1]),sample(lb[1],mb[:,:,0],mb[:,:,1]),t)
    aa=field(q,qa,True);bb=field(q,qb,True)
    ha=sample(la[0],aa[:,:,0],aa[:,:,1]);hb=sample(lb[0],bb[:,:,0],bb[:,:,1]);face=mix(ha,hb,t)
    # Transport one complete iris/eye or rhinarium from a single drawing at a time.
    theta=tilt(q);co,si=np.cos(theta),np.sin(theta)
    donor=a if t<.5 else b;hs,tex=expand(donor),layers(donor)[0]
    ang=tilt(hs);c,s=np.cos(ang),np.sin(ang)
    for k in range(3):
        w,h=(1-t)*np.array(NSIZE[a][k])+t*np.array(NSIZE[b][k]);ow,oh=NSIZE[donor][k]
        dx,dy=X-q[k,0],Y-q[k,1];u,v=co*dx+si*dy,-si*dx+co*dy
        original=sample(tex,hs[k,0]+u*ow/w*c-v*oh/h*s,hs[k,1]+u*ow/w*s+v*oh/h*c)
        gate=eased((1.55-np.sqrt((u/(w*.68+2))**2+(v/(h*.78+2))**2))/.52)[:,:,None]
        face=gate*original+(1-gate)*face
    # The outline and ears come exclusively from the continuous skull/torso
    # surface. The fine feature map owns only the central face. It cannot
    # create a second exterior profile beside the rotating nose.
    zone=feather(q[:6],4,12)[:,:,None]*eased((face[:,:,3:]-.18)/.61)
    body_rgb=body[:,:,:3]/np.maximum(body[:,:,3:],1e-5)
    face_rgb=face[:,:,:3]/np.maximum(face[:,:,3:],1e-5)
    rgb=body_rgb*(1-zone)+face_rgb*zone
    combined=np.concatenate([rgb*body[:,:,3:],body[:,:,3:]],2)
    if a=='Sit05':
        alpha=body[:,:,3]>.5
        y0=int(q[0,1]-5);y1=int(q[5,1]+14)
        x0=np.flatnonzero(alpha[y0])[0];x1=np.flatnonzero(alpha[y1])[0]
        cheek=x0+(x1-x0)*np.clip((Y-y0)/(y1-y0),0,1)
        nose_edge=q[2,0]-((1-t)*NSIZE[a][2][0]+t*NSIZE[b][2][0])*.54
        nostril=np.exp(-((Y-q[2,1])/12)**4)
        edge=cheek+nostril*(np.minimum(cheek,nose_edge)-cheek)
        muscle=eased((Y-y0)/8)*eased((y1-Y)/9)*eased(t/.17)
        clipped=eased((X-edge+1.1)/2.2)
        combined*=((1-muscle)+muscle*clipped)[:,:,None]
    combined=shoulder_edge(a,b,t,q,combined)
    lower=eased((Y-297)/37)[:,:,None];combined=(1-lower)*combined+lower*layers('Sit05')[0]
    return straight(combined),pts
def drawing_names():return [f'G{i:02}' for i in range(KEYS[-1]+1)]
def build():
    prepare();directory=GROOM_RIG
    model=dict(canvas=dict(pixels_per_unit=512,origin_x=256,origin_y=256,width=512,height=512),
               parameters=[],parts=[],art_meshes=[],deformers=[],glues=[],draw_groups=[])
    for k,c in enumerate(CONTROLS):
        model['parameters'].append(dict(id=c,min=0 if k==0 else -1,max=29 if k==0 else 1,default=0,repeat=False,decimals=3,blend_shape=False))
    marks={};atlas=[];labels={}
    for i,n in enumerate(drawing_names()):
        raw,points=picture(i);Image.fromarray(raw).save(directory/(n+'.png'));atlas.append(n+'.png')
        labels[n]={k:np.asarray(p).round(3).tolist() for k,p in points.items()}
        points={k:np.rint(p).astype('i4') for k,p in points.items()}
        parent=None
        for k,c in enumerate(CONTROLS[1:],1):
            size=26 if k<3 else 18;g=np.stack(np.meshgrid(np.linspace(0,512,size+1),np.linspace(0,512,size+1)),axis=2)
            xx,yy=g[:,:,0],g[:,:,1];nose=points['nose'];contact=points['shoulderCoat']
            def gauss(center,w,h):
                return np.exp(-((xx-center[0])/w)**2-((yy-center[1])/h)**2)
            if c=='ParamComb':
                head=gauss((nose[0]-24,nose[1]-36),76,62)*(1-eased((yy-266)/35))
                dx,dy=4.2*head,5.5*head
            elif c=='ParamCoat':
                patch=gauss((contact[0]+5,contact[1]+6),29,24);dx,dy=2.7*patch,3.1*patch
            else:
                chest=gauss((216,253),91,64)*(1-eased((yy-280)/35));dx,dy=-.45*chest,1.4*chest
            vals=[-1,0,1];delta=np.stack([dx,dy],2)
            forms=[(g+v*delta-(256 if parent is None else 0))/512 for v in vals]
            model['deformers'].append(dict(id=c[5:]+n,parent_deformer=parent,parent_part=i,visible=True,enabled=True,
                binding=dict(axes=[dict(parameter=k,keys=vals)]),kind={'Warp':dict(rows=size,cols=size,quad=True,opacities=[1.]*3,positions=blob(forms))}))
            parent=len(model['deformers'])-1
        model['parts'].append(dict(id='Part'+n,parent_part=None,visible=True,enabled=True,binding={'axes':[]},draw_orders=[500]))
        bbox=Image.fromarray(raw).getchannel('A').getbbox();left,top,right,bottom=bbox
        xs=np.unique([left-1,right+1]+[v for v in range(0,513,7) if left-1<v<right+1]+[int(v[0]) for v in points.values()])
        ys=np.unique([top-1,bottom+1]+[v for v in range(0,513,7) if top-1<v<bottom+1]+[int(v[1]) for v in points.values()])
        grid=np.stack(np.meshgrid(xs,ys),axis=-1);idx=np.arange(len(xs)*len(ys)).reshape(len(ys),len(xs))
        tri=np.stack([idx[:-1,:-1],idx[:-1,1:],idx[1:,1:],idx[:-1,:-1],idx[1:,1:],idx[1:,:-1]],axis=-1)
        keys=sorted({0,max(0,i-1),i,min(29,i+1),29})
        marks[n]={k:int(idx[np.where(ys==p[1])[0][0],np.where(xs==p[0])[0][0]]) for k,p in points.items()}
        model['art_meshes'].append(dict(id='Dog'+n,parent_part=i,parent_deformer=parent,
              visible=True,enabled=True,texture=i,flags=4,vertex_count=idx.size,uvs=blob(grid/512),indices=blob(tri,'<u2'),masks=[],
              binding=dict(axes=[dict(parameter=0,keys=keys)]),opacities=[float(k==i) for k in keys],
              draw_orders=[500]*len(keys),positions=blob([grid/512]*len(keys))))
    def write(name,obj):(directory/name).write_text(json.dumps(obj,separators=(',',':')),encoding='utf8')
    write('markers.json',marks);write('artist-points.json',labels);write('rig.json',model);write('textures.json',atlas)
    write('CuteDog.model3.json',dict(Version=3,FileReferences={'Moc':'CuteDog.moc3','Textures':atlas}))
    subprocess.run([str(exporter()),str(directory)],check=True)
POSE=PchipInterpolator([0,4,13,17,22,27,30,37,40,44,48,53,57,60,65,71,77,81],
                        [0,0,7,11,17,23,23,29,29,24,24,29,27,23,17,7,0,0])
def parameters(f):
    def puff(a,b,c):
        if f<=a or f>=c:return 0.
        t=(f-a)/(b-a) if f<=b else (c-f)/(c-b);return float(eased(t))
    return dict(ParamPose=round(float(POSE(f))),ParamComb=.64*puff(28,35,39)-.35*puff(39,43,49)+.58*puff(47,52,56),
                ParamCoat=.81*puff(24,35,41)+.91*puff(42,52,59),ParamBreath=.60*puff(3,13,23)-.42*puff(59,68,78))
if __name__=='__main__':
    if '--prep' in sys.argv:prepare();sheet()
    else:build();sheet(False)
