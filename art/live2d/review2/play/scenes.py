"""Native interaction choreography, world contacts, foreground review and app layer tracks."""
from pathlib import Path
import bisect
import csv
import hashlib
import json
import math
import os
import shutil
import subprocess
import sys
import tempfile

import numpy as np
import imageio_ffmpeg
from PIL import Image,ImageDraw,ImageFilter
from scipy.interpolate import PchipInterpolator

from interaction_rig import CONTROLS, HERE, ROOT, RIG, NAMES
from layout import REPO,RENDER_HOME,GAIT,INSTALL,java_classes
sys.path.insert(0,str(RENDER_HOME))
from render import bake,Reader

FPS=24
W,H=800,512
SCENES={'play_ball':146,'treat':120,'nuzzle':69,'paw_lick':72,'groom':81}
SCENE_POSE={'play_ball':0,'treat':0,'nuzzle':0,'paw_lick':2,'groom':1}
EVENTS={
 'play_ball':[(0,'attention'),(8,'track_incoming'),(26,'nose_bats_ball'),(31,'chase'),(49,'ground_bounce'),(61,'plant'),(66,'catch'),(71,'carry'),(89,'offer_ball'),(94,'release'),(111,'paw_roll'),(139,'settle')],
 'treat':[(0,'notice_spoon'),(15,'approach'),(23,'first_sniff'),(31,'second_sniff'),(39,'watch_spoon'),(43,'toss'),(46,'takeoff'),(56,'catch'),(65,'landing'),(71,'chew_1'),(80,'chew_2'),(89,'chew_3'),(98,'swallow'),(106,'ask_again')],
 'nuzzle':[(0,'notice_touch'),(11,'approach'),(22,'cheek_touch'),(31,'rub_up'),(43,'rub_back'),(55,'release')],
 'paw_lick':[(0,'rest'),(11,'lower_muzzle'),(19,'lick_1'),(31,'lick_2'),(46,'lick_3'),(57,'rest_chin')],
 'groom':[(0,'sit'),(5,'turn_toward_shoulder'),(17,'lower_muzzle'),(26,'touch_chest_coat'),(31,'comb_first_stroke'),
          (40,'pause_in_contact'),(45,'comb_second_stroke'),(53,'release_coat'),(62,'raise_head'),(77,'sit_settled')]
}
EASE={}
def named_curve(f,points):
    key=tuple(points)
    if key not in EASE:
        xx,yy=zip(*key);EASE[key]=PchipInterpolator(xx,yy)
    return float(EASE[key](min(max(f,key[0][0]),key[-1][0])))
def pulse(f,a,peak,b):
    return named_curve(f,[(a,0),(peak,1),(b,0)])
def envelope(f,a,b):
    if f<=a or f>=b:return 0
    return math.sin(math.pi*(f-a)/(b-a))**2
def between(f,a,b):return min(1,max(0,(f-a)/(b-a)))
def smooth(t):t=max(0,min(1,t));return t*t*(3-2*t)
def native(name,f):
    u={p:0. for p in CONTROLS};u['ParamPose']=SCENE_POSE[name]
    p=lambda a,c,b:pulse(f,a,c,b)
    k=lambda *keys:named_curve(f,list(keys))
    n=SCENES[name]
    wag=lambda freq,phase=0:math.sin((f/FPS*freq+phase)*2*math.pi)
    if name=='play_ball':
        # Keep the canonical joins exact before and after the gait strip.
        if f<=30:
            u.update(ParamEar=.85*p(0,8,28),ParamPitch=-.57*p(5,16,26)+.41*p(20,25,30),
                     ParamReach=.82*p(18,26,30),ParamSniff=.67*p(22,26,30),
                     ParamWeight=.36*p(14,23,30),ParamTail=.44*wag(2.15)*envelope(f,0,30))
        elif f>=61:
            u.update(ParamPitch=.75*p(61,66,90)-.4*p(87,93,100)+.74*p(97,110,124),
                     ParamReach=.70*p(61,67,87)+.32*p(93,108,124),
                     ParamChew=-.97*p(61,65,68)+.86*p(67,72,90)-.64*p(88,94,98),
                     ParamEar=.69*p(61,69,81)-.67*p(91,96,108)+.5*p(129,134,142),
                     ParamWeight=.52*p(61,68,78)-.32*p(77,84,92)+.74*p(101,110,120),
                     ParamPaw=-.45*p(99,105,111)+.55*p(105,113,120),
                     ParamBlink=.9*p(73,76,79)+.83*p(124,127,130),
                     ParamTail=.78*wag(2.4)*envelope(f,64,95)+.52*wag(2.75)*envelope(f,105,144))
    elif name=='treat':
        jump_drawing={45:0,46:3,47:4,48:5,49:6,50:7,51:8,57:7,58:6,60:5,61:4,63:3,65:0}
        u['ParamPose']=next((pose for key,pose in reversed(jump_drawing.items()) if f>=key),0)
        u.update(ParamReach=.88*p(9,24,36)+.46*p(29,33,39)+.44*p(45,55,66)-.2*p(70,79,96)+.78*p(99,108,118),
                 ParamSniff=.85*p(17,21,25)+.88*p(25,30,35)+.4*p(104,108,114),
                 ParamPitch=.52*p(10,25,36)-.85*p(39,52,62)+.46*p(64,72,81)+.24*p(79,84,89)
                            +.23*p(90,94,99)-.56*p(100,108,118),
                 ParamEar=.68*p(1,8,35)+.86*p(38,45,54)-.47*p(54,59,68)+.65*p(100,107,118),
                 ParamTuck=.10*p(46,54,65),ParamWeight=.98*p(36,45,49)+.93*p(61,66,74)
                            -.31*p(46,52,60)+.24*p(93,99,105),
                 ParamChew=-.99*p(48,55,59)+.9*p(56,61,68)+.98*p(66,71,75)
                            -.55*p(71,76,80)+.99*p(76,81,85)-.49*p(81,86,90)+.91*p(86,91,96)+.57*p(95,100,105),
                 ParamBlink=.66*p(28,31,34)+.88*p(61,65,68)+.93*p(87,91,96),
                 ParamTail=.49*wag(2.4)*envelope(f,2,42)+.77*wag(2.65,.13)*envelope(f,60,116))
    elif name=='nuzzle':
        u.update(ParamEar=-.68*p(8,25,47)+.6*p(49,55,65),ParamReach=.97*p(5,27,58),
            ParamPitch=.52*p(4,23,34)-.72*p(22,36,48)+.4*p(37,48,56),
            ParamWeight=.52*p(9,28,47),ParamSniff=.65*p(11,16,22)+.4*p(16,23,30),
            ParamChew=.57*p(12,34,53),ParamBlink=.99*p(15,26,34)+.8*p(32,43,53),
            ParamTail=.69*wag(2.13)*envelope(f,4,66),ParamPaw=.17*p(8,17,27))
    elif name=='paw_lick':
        l1=p(14,19,24);l2=p(26,32,38);l3=p(40,46,53)
        u.update(ParamPitch=.36*p(2,14,56)+.38*(l1+l2+l3),ParamReach=.54*p(3,17,57),
                 ParamSniff=.3*p(8,12,17),ParamPaw=.59*p(4,22,57)+.1*(l1+l2+l3),
                 ParamChew=-.6*(l1+l2+l3),ParamLick=l1+l2+l3,ParamEar=-.26*p(11,33,54),
                 ParamBlink=.91*p(9,15,27)+.78*p(42,47,57)+.4*p(55,62,71),
                 ParamTail=.17*wag(.95)*envelope(f,0,70),ParamWeight=.22*p(38,55,71))
    for c,val in list(u.items()):
        if c!='ParamPose':u[c]=max(0 if c in ('ParamTuck','ParamBlink','ParamLick') else -1,min(1,val))
    return u

def native_render(name,frames,out,rig=RIG,draw_names=NAMES):
    install=INSTALL
    controls=tuple(frames[0])
    compare=out/'native';compare.mkdir(exist_ok=True)
    tsv=compare/'parameters.tsv'
    with tsv.open('w',newline='',encoding='utf8') as h:
        writer=csv.writer(h,delimiter='\t',lineterminator='\n');writer.writerow(controls)
        for params in frames:writer.writerow([round(params[p],8) for p in controls])
    # Export standard editable parameter curves; the stage root stays separate.
    curves=[]
    for c in controls:
        vals=np.array([row[c] for row in frames])
        tick=np.arange(len(vals))/FPS
        keys=[0]+[i for i in range(1,len(vals)-1) if vals[i-1]!=vals[i] or vals[i]!=vals[i+1]]+[len(vals)-1]
        if max(vals)==min(vals):keys=[0,len(vals)-1]
        seg=[0,float(vals[0])]
        for i in keys[1:]:seg.extend([2 if c=='ParamPose' else 0,round(float(tick[i]),8),round(float(vals[i]),8)])
        curves.append({'Target':'Parameter','Id':c,'Segments':seg})
    segcount=sum((len(c['Segments'])-2)//3 for c in curves)
    api={'Version':3,'Meta':{'Duration':round((len(frames)-1)/FPS,8),'Fps':FPS,'Loop':False,'AreBeziersRestricted':True,'FadeInTime':0,'FadeOutTime':0,'CurveCount':len(curves),'TotalSegmentCount':segcount,'TotalPointCount':segcount+len(curves)},'Curves':curves}
    jwrite(out/(name+'.motion3.json'),api)
    with tempfile.TemporaryDirectory(dir=compare) as td:
        td=Path(td)
        subprocess.run([str(install/'jre/bin/java.exe'),'-Djava.library.path='+str(install/'dll64'),'-cp',os.pathsep.join([str(java_classes()),str(install/'lib/Live2DCubismCore.jar')]),'CoreBake',str(rig/'CuteDog.moc3'),str(tsv),str(td)],stdout=subprocess.DEVNULL,check=True)
        bake(rig/'CuteDog.model3.json',td,out/'dog')
        # Mouth and paw attachments come from evaluated native vertices on every frame.
        top=Reader(td/'topology.bin');w,h,ox,oy,ppu=top.array(5,'>f4'); meshes=[]
        for _ in range(top.integer()):
            id=top.string();atlas,flag,mode,n=top.array(4,'>i4');top.array(2*n,'>f4');top.array(top.integer(),'>u2');top.array(top.integer(),'>i4');meshes.append((id,n))
        top.end(); stream=Reader(td/'frames.bin')
        markers=json.loads((rig/'markers.json').read_text());bound=[]
        for f in range(stream.integer()):
            pts={}
            for mesh,num in meshes:
                order=stream.integer();op=stream.array(1,'>f4')[0];stream.array(8,'>f4');v=stream.array(num*2,'>f4').reshape(-1,2)
                if mesh=='Dog'+draw_names[int(frames[f]['ParamPose'])]:
                    assert op>.999
                    pts={k:[float(ox+v[i,0]*ppu),float(oy-v[i,1]*ppu)] for k,i in markers[mesh[3:]].items()}
            bound.append(pts)
        stream.end()
    return bound

def jwrite(p,data):
    p.write_text(json.dumps(data,ensure_ascii=False,separators=(',',':'),allow_nan=False)+'\n',encoding='utf-8')

def prepared_props():
    path=HERE/'props';path.mkdir(exist_ok=True)
    source=Image.open(REPO/'art/live2d/toy-props.png').convert('RGBA')
    sprites={}
    for name,box,width in [('ball',(0,0,887,887),37),('biscuit',(887,0,1774,887),37)]:
        im=source.crop(box);mask=np.asarray(im.getchannel('A'))>50; yy,xx=np.where(mask);im=im.crop((int(xx.min())-5,int(yy.min())-5,int(xx.max())+6,int(yy.max())+6));im=im.resize((width,round(im.height*width/im.width)),Image.Resampling.LANCZOS)
        im.save(path/(name+'.png'));sprites[name]=im
    # A long shallow wooden feeding spoon gives the waiting biscuit visible support before the toss.
    sw,sh=372,54; scale=3
    X,Y=np.meshgrid(np.arange(sw*scale)/scale,np.arange(sh*scale)/scale)
    handle=(Y-(35-9*X/335))**2 < (5.35-2.0*np.minimum(1,X/260))**2
    bowl=((X-328)/40)**2+((Y-26)/11.2)**2
    inside=(handle & (X<327)) | (bowl<1)
    rng=np.random.default_rng(121); grain=2*np.sin(Y*4+.02*X)+1.6*np.sin(Y*1.3+.013*X)+rng.normal(0,1,X.shape)
    rim=7*np.exp(-((bowl-.78)/.18)**2)
    trough=(1-np.clip(bowl,0,1))
    lum=grain+rim-10*trough+7*(1-Y/sh)-5*(Y>28)
    rgb=np.stack([166+lum,115+lum,75+lum,255*inside],axis=-1).clip(0,255).astype('u1')
    spoon=Image.fromarray(rgb).resize((sw,sh),Image.Resampling.LANCZOS)
    spoon.save(path/'spoon.png');sprites['spoon']=spoon
    return sprites

def parabola(a,b,u,h):return [a[0]*(1-u)+b[0]*u,a[1]*(1-u)+b[1]*u-4*h*u*(1-u)]
def arr(p):return np.array(p,dtype=float)
def pt(v):return [round(float(x),3) for x in v]
def minus(p,dx,dy):return [p[0]+dx,p[1]+dy]
def prop(i,c,angle=0):return {'id':i,'center':pt(c),'clockwiseDegrees':round(angle,2),'z':0}
def spoon_at(bowl,angle):
    # Source bowl pivot at (328,26); place sprite center so rotation is about that pivot.
    r=math.radians(angle);v=arr([186-328,27-26]);R=np.array([[math.cos(r),-math.sin(r)],[math.sin(r),math.cos(r)]])
    return prop('spoon',arr(bowl)+R@v,angle)

def scene_data(name,n,bound,out):
    rootx=248. if name=='play_ball' else (196. if name=='treat' else 146.)
    roots=[[rootx,0.] for i in range(n+1)]
    if name=='play_ball':
        gait=GAIT/'chase'
        meta=json.loads((gait/'metadata.json').read_text())
        assert meta['frameIntervals']==30 and meta['fps']==FPS
        for f in range(31,n+1):
            idx=min(30,f-31);roots[f][0]-=meta['landmarks'][idx]['travelPx']
            if f<=61:
                im=Image.open(gait/f'{idx:05}.png').convert('RGBA')
                im.save(out/'dog'/f'{f:05}.webp',lossless=True,method=1,exact=True)
    if name=='treat':
        for f in range(46,66):
            u=between(f,46,65);roots[f][1]=-4*39*u*(1-u)
    world=[{k:pt(arr(v)+roots[f]) for k,v in marks.items()} for f,marks in enumerate(bound)]
    data=[]
    for f in range(n+1):
        beat=next(b for t,b in reversed(EVENTS[name]) if t<=f);props=[]
        if name=='play_ball':
            hit=minus(world[26]['nose'],-16,-2);caught=minus(world[66]['lip'],-8,2)
            # Preserve horizontal flight through the first ground impact.
            bounce=[hit[0]+(caught[0]-hit[0])*(49-26)/(66-26),410]
            if f<=26:
                pos=parabola([-23,315],hit,between(f,0,26),102);ang=f*13
            elif f<=49:
                pos=parabola(hit,bounce,between(f,26,49),94);ang=338-(f-26)*17
            elif f<=66:
                u=between(f,49,66);pos=parabola(bounce,caught,u,50);ang=-53-(f-49)*17
            elif f<94:
                pos=minus(world[f]['lip'],-8,2);ang=-342+6*math.sin(math.pi*between(f,66,94))
            elif f<=111:
                release=minus(world[94]['lip'],-8,2);paw=world[111]['paw']
                dest=[paw[0]-19,410];pos=parabola(release,dest,between(f,94,111),50);ang=-342+(f-94)*8
            else:
                start=world[111]['paw'][0]-19;u=between(f,111,142)
                x=start+(-24-start)*(1-(1-u)**1.35)
                pos=[x,410-3.2*math.sin(math.pi*between(f,111,119))];ang=-206+(x-start)*180/(math.pi*18.5)
            if -22<pos[0]<W+22:props=[prop('ball',pos,ang)]
        elif name=='treat':
            # The owner offers the biscuit twice before flicking it gently off the spoon.
            offered=minus(world[23]['nose'],-19,12)
            a=between(f,0,19);retreat=between(f,27,31);advance=between(f,31,38);toss=between(f,38,43)
            bx=-100+(offered[0]+100)*smooth(a)-13*smooth(retreat)+12*smooth(advance)+5*smooth(toss)
            by=offered[1]+13+13*(1-smooth(a))+3*smooth(retreat)-10*smooth(toss)
            bowl=[bx,by]
            if f>43:
                exit_t=between(f,43,59);bowl=[bx-340*smooth(exit_t),by+31*smooth(exit_t)]
            if f<60:props.append(spoon_at(bowl,-2-9*smooth(toss)+6*smooth(between(f,43,59))))
            start=[offered[0]+4,offered[1]-8];hit=minus(world[56]['lip'],-5,1)
            if f<=43:
                food=minus(bowl,0,-10);angle=-8*toss
            elif f<=56:
                u=between(f,43,56);food=parabola(start,hit,u,35);angle=-8+208*u
            else:
                move=4*between(f,56,66)+6*between(f,69,75)+7*between(f,78,84)+15*between(f,87,95)
                food=minus(world[f]['lip'],-5+move,1-.22*move);angle=200+8*between(f,56,66)
            if -24<food[0]<W+24 and f<100:
                biscuit=prop('biscuit',food,angle)
                # The toss passes in front of the three-quarter muzzle. Beyond contact,
                # hide the bitten end under the moving lips, progressively with each chew.
                if f>43:biscuit['z']=1
                if f>=56:
                    lip=world[f]['lip']
                    biscuit['clipRightX']=round(lip[0]+24-23*smooth(between(f,56,63)),3)
                props.append(biscuit)
        if name=='play_ball' and 31<=f<=61:
            marks={'rootOffset':pt(roots[f]),'gaitFrame':f-31,'toeContacts':meta['landmarks'][f-31]}
        else:marks={'rootOffset':pt(roots[f]),'markers':world[f]}
        data.append({'frame':f,'beat':beat,'dog':marks,'props':props})
    return data

def background():
    x,y=np.meshgrid(np.linspace(0,1,W),np.linspace(0,1,H))
    base=(np.array([213,222,210])[None,None,:]*(1-y[:,:,None])+np.array([247,237,218])[None,None,:]*y[:,:,None])
    sun=6*np.exp(-((x-.18)/.39)**2-((y-.20)/.48)**2)
    return Image.fromarray(np.clip(base+sun[:,:,None],0,255).astype('u1')).convert('RGBA')
def placed(scene,sprite,coord,degrees,right=None):
    im=sprite.rotate(-degrees,Image.Resampling.BICUBIC,expand=True)
    x,y=round(coord[0]-im.width/2),round(coord[1]-im.height/2)
    if right is not None:
        alpha=np.asarray(im.getchannel('A')).astype(float)
        coverage=np.clip(right-x-np.arange(im.width)+.5,0,1)
        im.putalpha(Image.fromarray(np.round(alpha*coverage).astype('u1')))
    scene.alpha_composite(im,(x,y))
def final_stage(name,frames,records,sprites,out):
    stage=out/'stage';stage.mkdir(exist_ok=True)
    plate=background(); ffmpeg=Path(imageio_ffmpeg.get_ffmpeg_exe())
    pipe=subprocess.Popen([str(ffmpeg),'-hide_banner','-loglevel','error','-y','-f','rawvideo','-pix_fmt','rgb24','-s',f'{W}x{H}','-r',str(FPS),'-i','pipe:0','-c:v','libx264','-preset','medium','-crf','15','-pix_fmt','yuv420p','-movflags','+faststart',str(out/'review.mp4')],stdin=subprocess.PIPE)
    for row in records:
        f=row['frame'];rx,ry=row['dog']['rootOffset']
        fr=Image.open(out/'dog'/f'{f:05}.webp').convert('RGBA');comp=Image.new('RGBA',(W,H))
        for obj in row['props']:
            if obj['z']==0:placed(comp,sprites[obj['id']],obj['center'],obj['clockwiseDegrees'])
        comp.alpha_composite(fr,(round(rx),round(ry)))
        for obj in row['props']:
            if obj['z']==1:placed(comp,sprites[obj['id']],obj['center'],obj['clockwiseDegrees'],obj.get('clipRightX'))
        comp.save(stage/f'{f:05}.webp',lossless=True,method=1,exact=True)
        if f==len(records)-1:continue
        bg=plate.copy();shadow=Image.new('RGBA',(W,H))
        near=1-abs(ry)/100;xx=rx+262
        dr=ImageDraw.Draw(shadow)
        dr.ellipse((xx-128*near,422,xx+133*near,435),fill=(73,61,35,int(47*near)))
        for obj in row['props']:
            if obj['id']=='ball':
                X,Y=obj['center'];k=max(.12,1-(410-Y)/285);dr.ellipse((X-16*k,427,X+16*k,430),fill=(87,74,63,int(44*k)))
        bg.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(4)));bg.alpha_composite(comp)
        pipe.stdin.write(bg.convert('RGB').tobytes())
    pipe.stdin.close()
    if pipe.wait():raise RuntimeError('Preview video encoding failed')
def main():
    sprites=prepared_props(); selected=sys.argv[1:] or list(SCENES)
    for name in selected:
        out=HERE/'scenes'/name;out.mkdir(parents=True,exist_ok=True);n=SCENES[name]
        if name=='groom':
            from groom_author import GROOM_RIG, drawing_names, parameters
            frames=[parameters(f) for f in range(n+1)]
            bound=native_render(name,frames,out,GROOM_RIG,drawing_names())
        else:
            frames=[native(name,f) for f in range(n+1)]
            bound=native_render(name,frames,out)
        data=scene_data(name,n,bound,out)
        schema={'version':1,'id':name,'fps':FPS,'intervals':n,'canvasSize':[W,H],'dogSize':[512,512],'neutralDrawing':NAMES[SCENE_POSE[name]],'units':'source_pixels','coordinates':'top-left origin, x right, y down','sampling':'interval starts with an additional terminal','dogFrames':'dog/%05d.webp','stageFrames':'stage/%05d.webp','dogComposite':'whole RGBA surface, translate only. Draw z=0 props, dog, then z=1 props. clipRightX clips the bitten edge in stage pixels.','propSprites':{k:'../../props/'+k+'.png' for k in sprites},'events':[{'frame':f,'id':b} for f,b in EVENTS[name]],'frames':data}
        if name=='groom':
            schema.update(neutralDrawing='Sit05',rigModel='../../groom-rig/CuteDog.model3.json',
                          rigEditor='../../groom-rig/Grooming.cmo3')
        if name=='play_ball':
            gait=GAIT/'chase';source_hash=hashlib.sha256()
            for i in range(31):
                source_hash.update((gait/f'{i:05}.png').read_bytes())
            schema['chaseSource']={'firstSceneFrame':31,'lastSceneFrame':61,'intervals':30,
                'digestOf31SourcePngs':source_hash.hexdigest(),
                'gaitMetadataSha256':hashlib.sha256((gait/'metadata.json').read_bytes()).hexdigest(),
                'travelPx':round(data[31]['dog']['rootOffset'][0]-data[61]['dog']['rootOffset'][0],5)}
        jwrite(out/'scene.json',schema)
        final_stage(name,frames,data,sprites,out)
        print(f'Ready {name}, {n} intervals at {FPS}fps',flush=True)
if __name__=='__main__':main()
