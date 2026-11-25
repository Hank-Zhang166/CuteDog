"""Rest → back roll with a skull-local atlas and single-surface facial features."""
from pathlib import Path
from functools import lru_cache
import argparse
import json
import os
import shutil
import sys
import math
import numpy as np
from PIL import Image, ImageDraw
from scipy.interpolate import RBFInterpolator

SOURCE=Path(__file__).resolve().parent
sys.path.insert(0,str(SOURCE.parent/'deps'))
import cv2
Y,X=np.mgrid[:512,:512].astype('f')
GY,GX=np.mgrid[:519:5,:519:5]
GRID=np.column_stack([GX.ravel(),GY.ravel()])
NAMES=('Rest','TurnFace','Roll25','Roll60','Roll','Belly')
# Anatomical far/near iris, rhinarium, far/near commissure, bottom jaw,
# far/near ear, outline at far temple/near ruff, midpoint of crown.
HEAD={
'Rest':[(86.6,358.9),(113.3,351.3),(80.7,388.3),(90,396),(129,381),(101,401),(81,289),(117,266),(73,344),(183,346),(101,302)],
'TurnFace':[(114.7,362.1),(162.6,349.4),(142.7,392.7),(117,393),(182,379),(145,404),(82,284),(150,265),(68,349),(212,336),(127,289)],
'Roll25':[(123.2,363.4),(146.2,337.4),(159.4,356.1),(150,386),(173,366),(164,385),(69,307),(107,277),(70,366),(180,305),(92,310)],
'Roll60':[(136.6,370.7),(127.1,344),(151.6,337.7),(157,374),(174,344),(172,362),(78,414),(65,368),(142,416),(112,325),(87,385)],
'Roll':[(133.1,374.8),(126.6,347.8),(150.5,342.5),(154,380),(174,349),(169,368),(76,420),(65,363),(142,418),(112,328),(87,386)],
'Belly':[(107.6,343),(130,317.8),(139,336),(121,364),(152,341),(149,363),(51,307),(92,264),(67,367),(157,291),(77,303)]}
# Neck, near front toe, far front toe, near hind toe, far hind toe, hip,
# shoulder contour, tip of tail plume, lower ribcage, far rump silhouette.
BODY={
'Rest':[(193,344),(129,417),(83,413),(303,414),(282,400),(329,325),(236,270),(426,390),(240,403),(420,357)],
'TurnFace':[(207,345),(142,418),(95,412),(288,415),(263,398),(334,319),(236,257),(424,386),(238,405),(417,350)],
'Roll25':[(186,366),(213,346),(210,313),(303,411),(282,400),(334,323),(238,276),(425,390),(239,402),(414,341)],
'Roll60':[(187,366),(216,333),(210,296),(352,364),(321,248),(324,296),(243,292),(433,384),(241,403),(423,325)],
'Roll':[(183,373),(216,333),(210,295),(351,364),(322,244),(324,293),(244,296),(434,383),(242,404),(422,320)],
'Belly':[(179,364),(216,321),(204,257),(350,354),(316,238),(325,285),(242,281),(432,382),(241,402),(421,321)]}
SIZES={
'Rest':[(4,7),(18,14),(17,15)],'TurnFace':[(17,9),(19,11),(24,18)],
'Roll25':[(13,14),(11,11),(18,16)],'Roll60':[(11,12),(10,9),(15,14)],
'Roll':[(13,11),(10,8),(14,15)],'Belly':[(13,11),(10,9),(16,15)]}
for d in (HEAD,BODY):
    for k in d:d[k]=np.array(d[k],float)
for n,center in {'Roll25':(162.1,359.2),'Roll60':(153.1,337.5),'Roll':(151.5,343.2),'Belly':(139.2,342.8)}.items():
    HEAD[n][2]=center
STATIC=np.array([[-25,180],[110,153],[270,150],[450,155],[536,225],[-20,340],[533,375],[-20,495],[105,508],[292,505],[530,490]],float)
def rot(a):
    c,s=np.cos(a),np.sin(a);return np.array([[c,s],[-s,c]])
def angle(q):return math.atan2(*(q[1]-q[0])[::-1])
def eased(t):return t*t*(3-2*t)
def semantic(name):
    pts=HEAD[name];bas=rot(angle(pts));result=list(pts)
    for i,(width,height) in enumerate(SIZES[name]):
        for dx,dy in [(-.47,0),(.47,0),(0,-.47),(0,.47)]:
            result.append(pts[i]+np.array([dx*width,dy*height])@bas)
    return np.asarray(result)
def head_between(a,b,t):
    h,j=semantic(a),semantic(b);c=h[:2].mean(0);d=j[:2].mean(0)
    ar,br=angle(h),angle(j);delta=(br-ar+np.pi)%(2*np.pi)-np.pi
    # Interpolate shape in skull coordinates. A rotation never carries a pupil through the nose.
    canonical=(1-t)*(h-c)@rot(-ar)+t*(j-d)@rot(-br)
    return (1-t)*c+t*d+canonical@rot(ar+t*delta)
def remap(v,m):
    return cv2.remap(v,m[:,:,0],m[:,:,1],cv2.INTER_LINEAR,borderMode=cv2.BORDER_CONSTANT)
def surface(q,p,smoothing,base=None):
    if base is None:
        q=np.vstack([q,STATIC]);p=np.vstack([p,STATIC]);delta=p-q;origin=GRID
    else:
        r,center,target=base;origin=(GRID-center)@r+target;delta=p-((q-center)@r+target)
    f=RBFInterpolator(q,delta,kernel='thin_plate_spline',smoothing=smoothing)(GRID)
    f=(f+origin).reshape(GX.shape+(2,)).astype('f')
    return cv2.remap(f,X/5,Y/5,cv2.INTER_CUBIC,borderMode=cv2.BORDER_REPLICATE)
def head_map(q,s):
    a,b=angle(q),angle(s);return surface(q,s,3.,(rot(b-a),q[:2].mean(0),s[:2].mean(0)))
def visual(im):
    a=im.astype('f')/255;a[:,:,:3]*=a[:,:,3:];return a
def straight(im):
    a=im[:,:,3:];c=np.where(a>1e-5,im[:,:,:3]/np.maximum(a,1e-5),0)
    return np.uint8(np.round(np.clip(np.concatenate([c,a],axis=2),0,1)*255))
def distance(alpha):
    m=(alpha>.50).astype('u1')
    return cv2.distanceTransform(m,cv2.DIST_L2,3)-cv2.distanceTransform(1-m,cv2.DIST_L2,3)
def feather(points,radius,fade):
    core=np.zeros((512,512),'u1')
    hull=cv2.convexHull(np.rint(points).astype('i4'));cv2.fillConvexPoly(core,hull,1)
    dist=cv2.distanceTransform(1-core,cv2.DIST_L2,5)
    return eased(np.clip((radius+fade-dist)/fade,0,1))
@lru_cache(None)
def drawing(name):
    choices=[SOURCE/'belly-guides'/(name+'.png'),SOURCE/'registered'/(name+'.png'),
             Path(os.environ.get('CUTEDOG_REVIEW_PERFORM_OUT',SOURCE))/'registered'/(name+'.png')]
    return np.array(Image.open(next(p for p in choices if p.exists())).convert('RGBA'))
@lru_cache(None)
def parts(name):
    rgb=drawing(name);head=visual(rgb);mask=np.zeros((512,512),'u1');h=HEAD[name]
    pts=h[[0,1,2,3,4,5]]
    cv2.fillConvexPoly(mask,cv2.convexHull(np.rint(pts).astype('i4')),255)
    mask=cv2.dilate(mask,cv2.getStructuringElement(cv2.MORPH_ELLIPSE,(21,21)))
    # Coat under the face cannot contribute a second iris, lip or nose at an occlusion.
    base=rgb.copy();base[:,:,:3]=cv2.inpaint(np.ascontiguousarray(rgb[:,:,:3]),mask,6,cv2.INPAINT_TELEA)
    return head,visual(base)
def blend_pose(a,b,t,gate=None):
    aa,bb=a[:,:,3],b[:,:,3]
    w=t if gate is None else gate
    if not np.isscalar(w):w=w[:,:,None]
    v=(1-w)*a+w*b
    wsum=v[:,:,3:]
    cov=(1-t)*aa+t*bb
    # Interpolate the one true outline through changing paw and ear visibility.
    target=np.clip(.5+(1-t)*distance(aa)+t*distance(bb),0,1)
    strength=eased(min(1,min(t,1-t)/.11))*eased(np.clip((np.abs(aa-bb)-.045)/.68,0,1))
    cov=(cov+(target-cov)*strength)[:,:,None]
    rgb=v[:,:,:3]/np.maximum(wsum,1e-6)
    if gate is not None:
        regular=(1-t)*a+t*b
        fallback=regular[:,:,:3]/np.maximum(regular[:,:,3:],1e-6)
        reliable=eased(np.clip((wsum-.035)/.36,0,1))
        rgb=rgb*reliable+fallback*(1-reliable)
    return np.concatenate([rgb*cov,cov],axis=2)
def facial_patches(face,a,b,t,q):
    # The nose is a small bounded material patch. Keep both nostrils from the
    # same painting in each frame, including the turn from profile to front.
    direction=angle(q);cs,ss=math.cos(direction),math.sin(direction)
    source=a if t<.5 else b
    for k in (0,1,2):
        cx,cy=q[k];u=(X-cx)*cs+(Y-cy)*ss;v=-(X-cx)*ss+(Y-cy)*cs
        dims=(1-t)*np.array(SIZES[a][k])+t*np.array(SIZES[b][k]);w,h=dims
        ow,oh=SIZES[source][k];mx,my=HEAD[source][k]
        phi=angle(HEAD[source]);co,si=math.cos(phi),math.sin(phi)
        sx,sy=u*ow/w,v*oh/h
        color=remap(parts(source)[0],np.stack([mx+sx*co-sy*si,my+sx*si+sy*co],2).astype('f'))
        d=np.sqrt((u/(w*.66+2))**2+(v/(h*.78+2))**2)
        mask=eased(np.clip((1.45-d)/.48,0,1))[:,:,None]
        face=face*(1-mask)+color*mask
    return face
def over(fg,bg):return fg+bg*(1-fg[:,:,3:])
def raised_hindleg(t):
    # The far hind leg starts tucked behind the rump and swings into view around
    # the hip. Inverse-mapping the far toe straight through the spine folds fur.
    leg=parts('Roll60')[1]
    ridge=np.interp(X,[248,264,285,305,325,345,365,378,392,410,435],[292,288,282,280,282,286,289,292,298,311,340])
    span=eased(np.clip((X-248)/21,0,1))*eased(np.clip((440-X)/18,0,1))
    hidden=span*eased(np.clip((ridge+1-Y)/4,0,1))
    poly=np.array([(283,223),(304,209),(338,213),(361,230),(384,257),(384,288),(366,314),
                   (345,330),(325,331),(308,318),(312,297),(313,282),(300,263),(287,248)],'i4')
    mask=np.zeros((512,512),'u1');cv2.fillPoly(mask,[poly],255)
    exterior=cv2.GaussianBlur(mask.astype('f')/255,(0,0),1.1)
    angle0=-1.91*(1-t);c,s=np.cos(angle0),np.sin(angle0)
    dx,dy=X-342,Y-328
    uv=np.stack([342+c*dx+s*dy,328-s*dx+c*dy],2).astype('f')
    return remap(leg*exterior[:,:,None],uv),leg*(1-hidden[:,:,None])
def between(a,b,t):
    if t<=1e-5:return drawing(a)
    if t>=1-1e-5:return drawing(b)
    qa,qb=semantic(a),semantic(b);q=head_between(a,b,t);sa,sb=parts(a),parts(b)
    if a=='Roll60' and b=='Roll25':return between(b,a,1-t)
    lifting=a=='Roll25' and b=='Roll60'
    body_a,body_b=BODY[a].copy(),BODY[b].copy()
    left,right=sa[1],sb[1]
    if lifting:
        body_a=np.delete(body_a,4,axis=0);body_b=np.delete(body_b,4,axis=0)
        body_b[4]=[329,321]
        moving,right=raised_hindleg(t)
    body=(1-t)*body_a+t*body_b;head_ids=[6,7,8,9]
    dst=np.vstack([body,q[head_ids]])
    ma=surface(dst,np.vstack([body_a,qa[head_ids]]),38.)
    mb=surface(dst,np.vstack([body_b,qb[head_ids]]),38.)
    body_owner=t
    if lifting:
        toes=eased(np.clip((X-239)/35,0,1))*eased(np.clip((Y-336)/27,0,1))
        body_owner=t+(float(t>=.5)-t)*toes
    elif set((a,b)) & {'Rest','TurnFace'}:
        toes=eased(np.clip((Y-380)/17,0,1))*eased(np.clip((255-X)/44,0,1))
        body_owner=t+(float(t>=.5)-t)*toes
    underneath=blend_pose(remap(left,ma),remap(right,mb),t,body_owner)
    if lifting:underneath=over(underneath,moving)
    ha,hb=remap(sa[0],head_map(q,qa)),remap(sb[0],head_map(q,qb))
    # Each eye and the nose have a single image owner. Only the aligned fur between
    # their local patches changes paint weight over the wider transition.
    focus=feather(q[[0,1,2,3,4,5]],5,9)
    owner=eased(np.clip((t-.40)/.20,0,1))
    face=facial_patches(blend_pose(ha,hb,t,t+(owner-t)*focus),a,b,t,q)
    area=feather(q[:11],6,21)[:,:,None]
    if set((a,b)) & {'Rest','TurnFace'}:
        # Keep the two resting front paws on the body surface just under the chin.
        normal=np.array([-math.sin(angle(q)),math.cos(angle(q))])
        d=(X-q[5,0])*normal[0]+(Y-q[5,1])*normal[1]
        area*=eased(np.clip((8-d)/8,0,1))[:,:,None]
    return straight(underneath*(1-area)+face*area)

def idle(t):
    im=visual(drawing('Belly'));amount=math.sin(2*np.pi*(t*33/24)*1.6)*math.sin(np.pi*t)**1.1
    pad=np.exp(-((X-206)/43)**2-((Y-321)/45)**2)
    rib=np.exp(-((X-309)/77)**2-((Y-339)/55)**2)
    return straight(remap(im,np.stack([X-amount*(5.5*pad-1.9*rib),Y-amount*(3.5*pad+2*rib)],axis=2).astype('f')))
KNOTS=[(0,'Rest'),(4,'TurnFace'),(10,'Roll25'),(16,'Roll60'),(19,'Roll'),(37,'Belly'),
       (70,'Belly'),(89,'Roll'),(93,'Roll60'),(99,'Roll25'),(105,'TurnFace'),(109,'Rest')]
def render(destination):
    destination.mkdir(parents=True,exist_ok=True);cv2.setNumThreads(2)
    for f in range(110):
        j=next(j for j in range(len(KNOTS)-1) if f<=KNOTS[j+1][0])
        (start,a),(end,b)=KNOTS[j:j+2];t=(f-start)/(end-start)
        if a==b:img=idle(t)
        else:
            # Keep velocity through the painted quarter-roll poses.
            v=eased(t) if a=='Belly' or b=='Belly' else t*.82+.18*eased(t)
            img=between(a,b,v)
        path=destination/f'{f:05}.webp'
        canonical=destination.parent/'side_lie_idle/00000.webp'
        if f in (0,109) and canonical.is_file():
            sample=np.array(Image.open(canonical).convert('RGBA'));present=np.maximum(sample[:,:,3],img[:,:,3])>0
            if not np.array_equal(sample[present],img[present]):raise ValueError('Unexpected Rest endpoint')
            shutil.copyfile(canonical,path)
        else:Image.fromarray(img).save(path,lossless=True,exact=True,method=1)
    contract=dict(fps=24,frameCount=109,terminalSample=True,poses=[[n,f] for f,n in KNOTS],entry='Rest',exit='Rest')
    (destination/'motion.json').write_text(json.dumps(contract,indent=2),encoding='utf8')
def sheet(folder,out):
    samples=[0,2,4,6,8,10,12,14,16,19,23,27,32,37]
    sheet=Image.new('RGB',(7*177,2*175),(122,135,134))
    for i,f in enumerate(samples):
        im=Image.open(folder/f'{f:05}.webp').crop((44,248,222,420))
        x,y=i%7*177,i//7*175;sheet.paste(im,(x,y),im);ImageDraw.Draw(sheet).text((x+6,y+3),str(f),fill=(250,245,231))
    sheet.save(out,quality=95)
def validate(folder):
    errors=[]
    for f in range(110):
        j=next(j for j in range(len(KNOTS)-1) if f<=KNOTS[j+1][0])
        (lo,a),(hi,b)=KNOTS[j:j+2];t=(f-lo)/(hi-lo)
        v=eased(t) if 'Belly' in (a,b) else .82*t+.18*eased(t)
        q=head_between(a,b,v);im=np.array(Image.open(folder/f'{f:05}.webp').convert('RGBA'))
        alpha=im[:,:,3];lum=im[:,:,:3].mean(2)
        count,*_=cv2.connectedComponentsWithStats((alpha>=128).astype('u1'));assert count==2,(f,count)
        offsets=[]
        for i,pt in enumerate(q[:3]):
            weights=np.exp(-((X-pt[0])**2+(Y-pt[1])**2)/(2*(6 if i<2 else 7)**2))*np.clip((135-lum)/80,0,1)*(alpha/255)
            assert weights.sum()>2
            offsets.append(float(np.linalg.norm([((X-pt[0])*weights).sum()/weights.sum(),((Y-pt[1])*weights).sum()/weights.sum()])))
        errors.append(offsets)
    mx=np.max(errors,axis=0);assert mx.max()<4.2,mx
    assert (folder/'00000.webp').read_bytes()==(folder/'00109.webp').read_bytes()
    return {'fps':24,'intervals':109,'samples':110,'byteIdenticalRestEndpoints':True,'detachedHalfOpaqueFrames':0,
            'maxLocalDarkCentroidOffsetPx':dict(zip(('farEye','nearEye','nose'),np.round(mx,3).tolist()))}
if __name__=='__main__':
    ap=argparse.ArgumentParser();ap.add_argument('--output',type=Path,default=Path(os.environ.get('CUTEDOG_REVIEW_PERFORM_OUT',SOURCE))/'belly_up')
    ap.add_argument('--sheet',type=Path);ap.add_argument('--qa',type=Path);args=ap.parse_args()
    render(args.output)
    if args.sheet:sheet(args.output,args.sheet)
    if args.qa:args.qa.write_text(json.dumps(validate(args.output),indent=2),encoding='utf8')
