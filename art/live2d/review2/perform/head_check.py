from pathlib import Path
import numpy as np
from PIL import Image,ImageDraw
import cv2,json
from performance import Face,load,HERE
p=HERE
row=Face(); dest=p/'native/look_around'; values=np.loadtxt(dest/'values.tsv',skiprows=1)
tiles=Image.new('RGB',(5*265,2*245),(82,97,85));d=ImageDraw.Draw(tiles)
src=load('Stand'); angles=[]; measure=[]
def composite_gray(pixels):
    alpha=pixels[:,:,3:]/255.
    rgb=np.rint(pixels[:,:,:3]*alpha+95*(1-alpha)).astype('uint8')
    return cv2.cvtColor(rgb,cv2.COLOR_RGB2GRAY)
for i,angle in enumerate(values[:,0]):
    f=np.array(Image.open(dest/'frames'/f'{i:05}.webp').convert('RGBA'))
    assert np.array_equal(f[275:],src[275:]) and np.array_equal(f[:,275:],src[:,275:]), i
    # Track the eyes and nose at their projected positions; use eye gradients
    # and a small normalized cross-correlation window for the source features.
    entries={}
    for name,xx,yy,w,h in [('eye',135,155,10,7),('nose',91,170,10,7),('far_eye',108,151,4,6)]:
        expected=row.position(np.array([[xx,yy]],dtype='f'),angle)[0]
        x,y=np.rint(expected).astype(int)
        ref=composite_gray(src[yy-h:yy+h+1,xx-w:xx+w+1])
        active=composite_gray(f[y-h-3:y+h+4,x-w-3:x+w+4])
        corr=cv2.matchTemplate(active,ref,cv2.TM_CCOEFF_NORMED)
        _,score,_,where=cv2.minMaxLoc(corr)
        candidate=active[where[1]:where[1]+2*h+1,where[0]:where[0]+2*w+1]
        edge=lambda a:(cv2.Sobel(a,cv2.CV_32F,1,0)**2+cv2.Sobel(a,cv2.CV_32F,0,1)**2).mean()**.5
        entries[name]=[round(score,4),round(float(edge(candidate)/edge(ref)),4)]
    angles.append(entries)
    if i in (0,11,24,31,37,43,50,62,82,91):
        index=len(measure);measure.append(i);x=index%5*265;y=index//5*245
        im=Image.fromarray(f).crop((61,66,235,219)).resize((251,217),Image.Resampling.LANCZOS)
        tiles.paste(im,(x+6,y),im);d.text((x+12,y+217),f'{i:02}  yaw {angle:+.1f}',fill='white')
tiles.save(p/'head-revision-inspect.jpg',quality=96)
points=np.array([[x,y] for y in range(68,277,3) for x in range(62,270,3)],float)
for a in [-12,16]:
    pt=row.position(points,a)
    dx=row.position(points+[.1,0],a)-pt;dy=row.position(points+[0,.1],a)-pt
    det=(dx[:,0]*dy[:,1]-dx[:,1]*dy[:,0])/.01
    assert det.min()>.65 and det.max()<1.5,(a,det.min(),det.max())
out={name:{'minimum_template_correlation':min(a[name][0] for a in angles),'minimum_edge_strength_ratio':min(a[name][1] for a in angles)} for name in angles[0]}
out['samples']=len(angles);out['static_body_identical']=True
(p/'head-revision-qa.json').write_text(json.dumps(out,indent=2))
print(out)
