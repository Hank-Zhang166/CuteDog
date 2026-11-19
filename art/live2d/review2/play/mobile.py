"""Extract the translate-only 512 mobile camera and connect the two posture-specific performances."""
from pathlib import Path
from collections import Counter
import json
import sys
import cv2
import numpy as np
from PIL import Image
from interaction_rig import HERE,ROOT,ART
from layout import PERFORM
from scenes import placed,SCENES

def read(p):return Image.open(p).convert('RGBA')
def cutout(a):
    alpha=a[:,:,3:4]
    rgb=np.where(alpha>1e-6,a[:,:,:3]/np.maximum(1e-6,alpha),0)
    return Image.fromarray(np.round(np.clip(np.concatenate([rgb,alpha],2),0,1)*255).astype('uint8'))
def prem(im):
    a=np.array(im,dtype='float32')/255
    a[:,:,:3]*=a[:,:,3:4]
    return a
def sitting_bridge():
    names=['Stand']+[f'Sit{i:02}' for i in range(1,6)]
    mid=[]
    flow=cv2.DISOpticalFlow_create(cv2.DISOPTICAL_FLOW_PRESET_MEDIUM);flow.setFinestScale(0)
    y,x=np.mgrid[0:512,0:512].astype('f')
    for a,b,steps in zip(names[:-1],names[1:],[5,4,4,4,5]):
        im,other=read(ART/(a+'.png')),read(ART/(b+'.png'));aa,bb=prem(im),prem(other)
        def gray(pm):
            color=pm[:,:,:3]+(1-pm[:,:,3:])*np.array([.30,.36,.39])
            return cv2.cvtColor(np.uint8(np.round(np.clip(color,0,1)*255)),cv2.COLOR_RGB2GRAY)
        fa=flow.calc(gray(aa),gray(bb),None);fb=flow.calc(gray(bb),gray(aa),None)
        for idx in range(steps):
            if idx==0:mid.append(im);continue
            t=idx/steps
            field=fa if t<.5 else fb
            amt=t if t<.5 else 1-t;src=aa if t<.5 else bb;delta=field
            for _ in range(2):
                delta=cv2.remap(field,x-amt*delta[:,:,0],y-amt*delta[:,:,1],cv2.INTER_LINEAR)
            warped=cv2.remap(src,x-amt*delta[:,:,0],y-amt*delta[:,:,1],cv2.INTER_CUBIC)
            mid.append(cutout(warped))
    return mid+[read(ART/'Sit05.png')]

def main():
    cv2.setNumThreads(2)
    clips=list(sys.argv[1:]) or list(SCENES)
    for name in clips:
        folder=HERE/'scenes'/name;mobile=folder/'mobile';mobile.mkdir(exist_ok=True)
        data=json.loads((folder/'scene.json').read_text()); rows=data['frames'];output=[]
        stats={}
        for row in rows:
            i=row['frame'];start=round(row['dog']['rootOffset'][0]);stage=read(folder/'stage'/f'{i:05}.webp')
            output.append(stage.crop((start,0,start+512,512)))
            dog=read(folder/'dog'/f'{i:05}.webp');box=dog.getchannel('A').point(lambda a:255 if a>32 else 0).getbbox()
            dy=round(row['dog']['rootOffset'][1])
            assert box[0]>=0 and box[2]<=512 and box[1]+dy>=0 and box[3]+dy<=512
            for o in row['props']:
                spr=read(HERE/'props'/(o['id']+'.png')).rotate(-o['clockwiseDegrees'],Image.Resampling.BICUBIC,expand=True)
                left,top,right,bottom=spr.getchannel('A').point(lambda a:255 if a>=128 else 0).getbbox()
                px=round(o['center'][0]-spr.width/2)-start; py=round(o['center'][1]-spr.height/2)
                if right+px<=0 or left+px>=512 or top+py>=512 or bottom+py<=0:state='offscreen'
                elif left+px<0 or right+px>512 or top+py<0 or bottom+py>512:state='partial'
                else:state='whole'
                stats.setdefault(o['id'],{}).setdefault(state,[]).append(i)
        if name=='groom':
            pre=sitting_bridge()
            post=list(reversed(pre))[1:]
            extra=len(pre)-1;output=pre[:-1]+output+post
        elif name=='paw_lick':
            def seq(key):return [read(p) for p in sorted((PERFORM/key).glob('*.webp'))]
            pre=seq('lie')[:-1]+seq('side_lie')[:-1];post=seq('side_to_lie')[1:]+seq('lie_to_stand')[1:]
            assert len(pre)==71 and len(post)==70
            extra=len(pre);output=pre+output+post
        else:extra=0
        for i,im in enumerate(output):im.save(mobile/f'{i:05}.webp',lossless=True,method=1,exact=True)
        # For identical endpoint proof encodings across every app action.
        for endpoint in (0,len(output)-1):
            baseline=np.array(read(ART/'Stand.png'))
            diff=np.any(np.array(output[endpoint])!=baseline,axis=2)&(baseline[:,:,3]>0)
            assert not diff.any(),(name,endpoint,int(diff.sum()))
        info={'sourceScene':name,'appAction':'lick_paw' if name=='paw_lick' else name,'fps':24,
              'intervals':len(output)-1,'terminalSample':True,'sourceSceneStart':extra,'sourceIntervals':len(rows)-1,
              'sourceTemplate':'%05d.webp','width':512,'height':512,'orientation':'left',
              'entry':'Stand','exit':'Stand','appRootTravel':0,'clippedDogFrames':0,
              'camera':'x = rounded tracked dog root x; y = 0, preserving lift and contact trajectories',
              'propVisibilityBySourceFrame':stats,'events':[dict(frame=e['frame']+extra,id=e['id']) for e in data['events']]}
        (mobile/'motion.json').write_text(json.dumps(info,indent=2),encoding='utf8')
        print(name,len(output)-1,'dog clipping 0', {p:{k:len(v) for k,v in s.items()} for p,s in stats.items()},flush=True)
if __name__=='__main__':main()
