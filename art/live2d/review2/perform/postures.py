"""Rest, recovery, scratching and turn artwork for the review timeline."""
from performance import *
from functools import lru_cache
from scipy.interpolate import RBFInterpolator

SUP=HERE/'registered'
SUP.mkdir(exist_ok=True)

def isolate():
    v=np.array(Image.open(SRC/'supplement.png').convert('RGBA'))
    _,lab,stats,_=cv2.connectedComponentsWithStats((v[:,:,3]>23).astype('uint8'))
    components=[(i,r) for i,r in enumerate(stats[1:],1) if r[4]>10000]
    top=sorted([(i,r) for i,r in components if r[1]<460],key=lambda x:x[1][0])
    lower=sorted([(i,r) for i,r in components if r[1]>460],key=lambda x:x[1][0])
    for (i,r),(name,width,cx) in zip(top+lower,[('Bow',377,246),('Low',377,247),('Roll',386,253),('Belly',393,246),('Scratch',305,253),('Yawn',298,250)]):
        x,y,w,h,_=r
        keep=cv2.dilate((lab==i).astype('uint8'),np.ones((9,9),np.uint8))
        patch=v.copy()
        alpha=patch[:,:,3].astype(float)
        patch[:,:,3]=np.rint(np.clip((alpha-4)/250,0,1)*255)*keep
        im=Image.fromarray(patch).crop((x-3,y-3,x+w+3,y+h+3))
        im=im.resize((width,round(im.height*width/im.width)),Image.Resampling.LANCZOS)
        result=Image.new('RGBA',(512,512));result.alpha_composite(im,(round(cx-width/2),431-im.height))
        result.save(SUP/(name+'.png'))
    for n in ['Stand','Sit05','Lie','Rest','Sleep','Turn05']:
        Image.open(POSES/(n+'.png')).save(SUP/(n+'.png'))
    ImageOps.mirror(Image.open(POSES/'Stand.png')).save(SUP/'StandRight.png')

def contact():
    names=['Stand','Bow','Low','Lie','Rest','Sleep','Roll','Belly','Sit05','Scratch','Yawn','Turn05']
    sheet=Image.new('RGB',(4*256,3*265),(89,101,109));from PIL import ImageDraw
    d=ImageDraw.Draw(sheet)
    for i,n in enumerate(names):
        im=Image.open(SUP/(n+'.png'));im.thumbnail((256,256));x=i%4*256;y=i//4*265
        sheet.paste(im,(x,y),im);d.text((x+5,y+4),n)
        for xx in range(0,256,25):
            d.text((x+xx,y+237),str(xx*2),fill=(218,224,225))
        for yy in range(50,240,25):
            d.text((x+1,y+yy),str(yy*2),fill=(218,224,225))
    sheet.save(HERE/'registered.jpg')

# Nose, eye, near ear, back of skull, chest, near and far front toes, near and
# far hind toes, hip, tail plume, and belly. The shared landmarks keep the same
# facial features and paws together even when the contact posture changes.
LAND={
'Stand':[(91,169),(135,155),(180,84),(221,141),(128,249),(199,418),(150,414),(390,418),(327,407),(359,260),(341,119),(267,316)],
'Bow':[(127,314),(173,300),(218,239),(239,292),(157,357),(108,410),(81,406),(398,396),(352,387),(363,234),(338,106),(272,369)],
'Low':[(109,278),(150,268),(193,204),(222,250),(149,344),(110,412),(78,401),(364,399),(334,396),(354,320),(350,191),(270,391)],
'Lie':[(96,259),(146,243),(189,177),(217,224),(143,320),(106,415),(84,400),(348,412),(328,402),(358,332),(354,230),(269,395)],
'Rest':[(82,388),(116,350),(125,271),(168,308),(142,383),(132,413),(86,408),(312,410),(285,393),(332,337),(389,380),(252,400)],
'Sleep':[(211,386),(162,371),(176,267),(203,303),(130,370),(181,407),(145,396),(276,402),(248,395),(310,313),(358,373),(226,405)],
'Roll':[(152,344),(128,348),(93,413),(86,387),(180,384),(199,320),(205,283),(348,381),(332,252),(322,362),(387,378),(260,354)],
'Belly':[(136,343),(108,343),(97,271),(72,319),(168,372),(206,326),(199,266),(349,377),(304,248),(320,340),(387,368),(260,352)],
'Sit05':[(110,182),(156,170),(205,89),(237,159),(156,259),(211,408),(152,407),(302,412),(271,407),(311,316),(352,270),(265,370)],
'Scratch':[(135,180),(174,179),(215,142),(243,187),(162,263),(170,408),(126,405),(249,404),(235,228),(287,311),(349,310),(236,368)],
'Yawn':[(111,177),(147,171),(193,128),(210,173),(152,246),(181,413),(138,406),(260,409),(236,403),(287,319),(343,288),(235,374)],
}
STATIC=np.array([(0,0),(128,0),(256,0),(385,0),(511,0),(0,180),(511,180),(0,360),(511,360),(0,511),(127,511),(256,511),(384,511),(511,511)],float)
gy,gx=np.mgrid[0:513:5,0:513:5]
GRID=np.column_stack([gx.ravel(),gy.ravel()])
def field(p,a):
    base=np.vstack([p,STATIC]);d=np.vstack([a-p,np.zeros_like(STATIC)])
    shift=RBFInterpolator(base,d,smoothing=32,kernel='thin_plate_spline')(GRID).reshape(gx.shape+(2,)).astype('f')
    return cv2.remap(shift,(X/5).astype('f'),(Y/5).astype('f'),cv2.INTER_CUBIC,borderMode=cv2.BORDER_REPLICATE)
def grey(pm):
    c=pm[:,:,:3]+(1-pm[:,:,3:])*np.array([.31,.40,.48],np.float32)
    return cv2.cvtColor(np.rint(np.clip(c,0,1)*255).astype('uint8'),cv2.COLOR_RGB2GRAY)
@lru_cache(None)
def texture(name):
    return premul(np.asarray(Image.open(SUP/(name+'.png')).convert('RGBA')))
class Pair:
    def __init__(self,left,right):
        self.a,self.b=texture(left),texture(right)
        self.pa=np.array(LAND[left],float);self.pb=np.array(LAND[right],float)
        mid=(self.pa+self.pb)/2
        fa=field(mid,self.pa);fb=field(mid,self.pb)
        ga=grey(remap(self.a,X+fa[:,:,0],Y+fa[:,:,1]))
        gb=grey(remap(self.b,X+fb[:,:,0],Y+fb[:,:,1]))
        dis=cv2.DISOpticalFlow_create(cv2.DISOPTICAL_FLOW_PRESET_MEDIUM)
        dis.setFinestScale(0)
        self.flow=dis.calc(ga,gb,None)
        rev=dis.calc(gb,ga,None)
        self.flow=np.clip((self.flow-rev)/2,-18,18)
    def frame(self,t):
        if t<=.0001:return self.a.copy()
        if t>=.9999:return self.b.copy()
        p=(1-t)*self.pa+t*self.pb
        fa=field(p,self.pa);fb=field(p,self.pb)
        mx=X+(fa[:,:,0]+fb[:,:,0])/2;my=Y+(fa[:,:,1]+fb[:,:,1])/2
        flow=remap(self.flow,mx,my)
        a=remap(self.a,X+fa[:,:,0]-t*flow[:,:,0],Y+fa[:,:,1]-t*flow[:,:,1])
        b=remap(self.b,X+fb[:,:,0]+(1-t)*flow[:,:,0],Y+fb[:,:,1]+(1-t)*flow[:,:,1])
        # Prefer the visible side at real occlusions; equal-weight dissolution
        # of an exposed paw into an opaque flank would leave a second pale paw.
        def signed_distance(im):
            mask=(im[:,:,3]>.49).astype('uint8')
            dist=cv2.distanceTransform(mask,cv2.DIST_L2,3)-cv2.distanceTransform(1-mask,cv2.DIST_L2,3)
            edge=np.abs(dist)<1.05
            dist[edge]=im[:,:,3][edge]*1.8-.9
            return dist
        aa=a[:,:,3:];bb=b[:,:,3:]
        d=(1-t)*signed_distance(a)+t*signed_distance(b)
        new=smooth(.5+d*.58)[:,:,None]
        denom=(1-t)*aa+t*bb
        strength=smooth(min(t,1-t)/.07)*np.minimum(1,np.abs(aa-bb)*5)
        alpha=denom+strength*(new-denom)
        rgb=(a[:,:,:3]*(1-t)+b[:,:,:3]*t)/np.maximum(denom,1e-6)
        return np.concatenate([rgb*alpha,alpha],axis=2)
PAIR=lru_cache(maxsize=24)(Pair)

def joint(im,name,t,amount):
    if abs(amount)<1e-6:return im
    coords=LAND[name];cx,cy=coords[4]
    xx=X;yy=Y
    def zone(x,y,wx,wy):return np.exp(-((X-x)/wx)**2-((Y-y)/wy)**2)
    if name in ('Roll','Belly'):
        w=zone(*coords[5],39,38);r=zone(*coords[9],75,59)
        xx=X-amount*(6*w-3*r);yy=Y-amount*4*w
    elif name=='Bow':
        w=zone(cx,cy,78,57);xx=X+amount*4*w;yy=Y-amount*8*w
    else:
        w=zone(cx+68,cy-15,125,46);yy=Y+amount*3.3*w
    return remap(im,xx,yy)

# Each continuous segment has a named, physically compatible entry and exit.
CLIPS={
 'lie':[('Stand',0),('Bow',16),('Low',29),('Lie',43)],
 'lie_idle':[('Lie',0),('Lie',48)],
 'lie_to_stand':[('Lie',0),('Low',12),('Bow',24),('Stand',42)],
 'side_lie':[('Lie',0),('Rest',28)],
 'side_lie_idle':[('Rest',0),('Rest',44)],
 'side_to_lie':[('Rest',0),('Lie',28)],
 'curl':[('Rest',0),('Sleep',31)],
 'curl_idle':[('Sleep',0),('Sleep',42)],
 'fall_asleep':[('Rest',0),('Sleep',37),('Sleep',56)],
 'sleep':[('Sleep',0),('Sleep',66)],
 'wake':[('Sleep',0),('Sleep',14),('Rest',36),('Lie',57)],
 'uncurl':[('Sleep',0),('Rest',36)],
 'stretch':[('Stand',0),('Bow',18),('Bow',43),('Stand',63)],
 'belly_up':[('Rest',0),('Roll',19),('Belly',37),('Belly',70),('Roll',89),('Rest',109)],
}

def author(names):
    for n in names:
        if n=="scratch":
            from scratch_repair import render, audit
            render(HERE/n)
            audit(HERE/n,HERE)
            continue
        if n=="yawn":
            from yawn_native import render_native
            render_native(HERE/'yawn-native',HERE/n)
            continue
        if n=="belly_up":
            from belly_repair import render
            render(HERE/n)
            continue
        nodes=CLIPS[n];dest=HERE/n;dest.mkdir(exist_ok=True);end=nodes[-1][1]
        for i in range(end+1):
            k=next((j for j in range(len(nodes)-1) if i<=nodes[j+1][1]),len(nodes)-2)
            a,lo=nodes[k];b,hi=nodes[k+1];t=(i-lo)/(hi-lo)
            if a==b:
                hz={'belly_up':1.65,'sleep':.34}.get(n,.58)
                gate=math.sin(np.pi*t)**.70
                v=joint(texture(a),a,t,math.sin(2*np.pi*(i-lo)/FPS*hz)*gate)
            elif i==lo:v=texture(a)
            elif i==hi:v=texture(b)
            else:
                v=PAIR(a,b).frame(float(smooth(t)))
            Image.fromarray(straight(v)).save(dest/f'{i:05}.webp',lossless=True,exact=True,method=1)
        (dest/'motion.json').write_text(json.dumps(dict(fps=FPS,frameCount=end,terminalSample=True,poses=nodes,entry=nodes[0][0],exit=nodes[-1][0])),encoding='utf8')

if __name__=='__main__':
    import sys
    if len(sys.argv)==1:isolate();contact()
    else:author(sys.argv[1:])
