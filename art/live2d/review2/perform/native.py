"""Emit the facial landmark rig and the hinged plume as genuine Cubism keyforms."""
import hashlib, os, subprocess, sys
from performance import *

OUT=HERE/'native'
OUT.mkdir(exist_ok=True)
def registered_pixels(image,neutral,head,angle,tip):
    """Recover exact source RGBA on portions untouched by the current native pose."""
    if abs(head)+abs(angle)+abs(tip)<.00001:return STAND.copy()
    a=premul(image);base=premul(neutral)
    delta=np.max(np.abs(a-base),axis=2)
    amount=max(abs(head)/.42,(abs(angle)+abs(tip)*.85)/7)
    motion=smooth(delta/.055)
    validity=(1-motion*(1-math.exp(-amount**2)))[:,:,None]
    raw=np.clip(a+(BASE-base)*validity,0,1)
    corrected=straight(raw)
    static=np.all(image==neutral,axis=2)
    corrected[static]=STAND[static]
    return corrected
def blob(x,typ='<f4'):
    return list(np.asarray(x,dtype=typ).tobytes())
def model():
    face=Face();tail=Tail()
    art=Image.new('RGBA',(2048,2048))
    meshes=[];parts=[]
    params=[('ParamHeadYaw',*face.limits),('ParamTailAngle',-28,28),('ParamTailTip',-5,5)]
    def draw(name,i,coords,triangles,axes,positions,opacity,order):
        # Root meshes have canvas-centered model-space coordinates.
        px=(i%3)*526+4;py=(i//3)*520+4
        coords=np.clip(coords,2,510)
        positions=[np.clip(v,2,510) for v in positions]
        uv=(coords+np.array([px,py]))/np.array(art.size)
        parts.append(dict(id='Part'+name,parent_part=None,visible=True,enabled=True,binding=dict(axes=[]),draw_orders=[500]))
        meshes.append(dict(id=name,parent_part=i,parent_deformer=None,visible=True,enabled=True,texture=0,flags=4,
            vertex_count=len(coords),uvs=blob(uv),indices=blob(triangles,'<u2'),masks=[],
            binding=dict(axes=[dict(parameter=p,keys=list(k)) for p,k in axes]),
            opacities=opacity,draw_orders=[order]*len(positions),positions=blob([(v-256)/512 for v in positions])))
        return px,py
    tailkeys=[-28,-14,0,14,28];tipkeys=[-5,0,5]
    xx=np.linspace(248,460,19);yy=np.linspace(78,278,17)
    coords=np.stack(np.meshgrid(xx,yy),axis=-1).reshape(-1,2)
    ix=np.arange(len(coords)).reshape(len(yy),len(xx))
    tri=np.stack([ix[:-1,:-1],ix[:-1,1:],ix[1:,1:],ix[:-1,:-1],ix[1:,1:],ix[1:,:-1]],axis=-1)
    delta=coords-tail.root
    over=smooth(np.sqrt(np.sum(delta**2,axis=1))/125)
    positions=[]
    for tip in tipkeys:
        for v in tailkeys:
            phase=np.radians(v+tip*over)
            c,s=np.cos(phase),np.sin(phase)
            positions.append(tail.root+np.column_stack([c*delta[:,0]-s*delta[:,1],s*delta[:,0]+c*delta[:,1]]))
    origin=draw('TailPlume',0,coords,tri,[(1,tailkeys),(2,tipkeys)],positions,[1]*len(positions),50)
    art.paste(Image.fromarray(straight(tail.tail)),origin)
    headkeys=[-12,-6,0,8,16]
    origin=draw('HeadNeck',1,face.coords,face.tri,[(0,headkeys)],
                [face.position(face.coords,v) for v in headkeys],[1]*len(headkeys),100)
    art.paste(Image.fromarray(straight(BASE*(1-tail.upper[:,:,None]))),origin)
    art.save(OUT/'Dog.png')
    spec=dict(canvas=dict(pixels_per_unit=512,origin_x=256,origin_y=256,width=512,height=512),
                parameters=[dict(id=n,min=a,max=b,default=0,repeat=False,decimals=3,blend_shape=False) for n,a,b in params],
                parts=parts,art_meshes=meshes,deformers=[],glues=[],draw_groups=[])
    for name,data in [('rig',spec),('textures',['Dog.png']),('CuteDog.model3',{'Version':3,'FileReferences':{'Moc':'CuteDog.moc3','Textures':['Dog.png']}})]:
        (OUT/(name+'.json')).write_text(json.dumps(data,separators=(',',':')),encoding='utf8')

def evaluate():
    author=Path(os.environ.get('CUBISM_REVIEW_EXPORTER',str(ROOT/'work/ayatsuri/target/debug/examples/review_moc.exe')))
    if author.is_file():
        subprocess.run([str(author),str(OUT)],check=True)
    elif (SRC/'model/CuteDog.moc3').is_file():
        import shutil
        actual=hashlib.sha256((OUT/'rig.json').read_bytes()).hexdigest()
        expected=(SRC/'model/rig.sha256').read_text(encoding='ascii').strip()
        if actual!=expected:
            raise RuntimeError('Geometry was edited. Set CUBISM_REVIEW_EXPORTER to rebuild the model.')
        shutil.copyfile(SRC/'model/CuteDog.moc3',OUT/'CuteDog.moc3')
    else:
        raise FileNotFoundError('Set CUBISM_REVIEW_EXPORTER to the review_moc executable.')
    core=Path(os.environ.get('CUBISM_APP','C:/Program Files/Live2D Cubism 5.3/app'))
    build=REPO/'tools/cubism'
    local=OUT/'java';local.mkdir(exist_ok=True)
    jdk=Path(os.environ.get('JAVA_HOME','C:/tools/jdk17/jdk-17.0.2'))
    subprocess.run([str(jdk/'bin/javac.exe'),'--release','17','-cp',str(core/'lib/Live2DCubismCore.jar'),
                    '-d',str(local),str(build/'CoreBake.java')],check=True)
    cp=os.pathsep.join([str(local),str(core/'lib/Live2DCubismCore.jar')])
    for name in ['look_around','tail_wag','look_left','look_right','curious']:
        if name in ('tail_wag','look_around'):m=json.loads((HERE/name/'motion.json').read_text())
        else:
            degree={'look_left':-11.5,'look_right':15.6,'curious':10.5}[name]
            duration={'look_left':39,'look_right':48,'curious':54}[name]
            m=dict(frameCount=duration,keys=[dict(yaw=timed(i/duration,[0,.12,.38,.63,.91,1],
                       [0,0,degree,degree,0,0]),rootDegrees=0,tipLag=0) for i in range(duration+1)])
        if name=='look_around':
            for i,v in enumerate(m['keys']):
                v['yaw']=timed(i/m['frameCount'],[0,.105,.24,.43,.58,.72,.93,1],
                               [0,-11.5,-11.5,9.0,15.5,15.5,0,0])
        directory=OUT/name;directory.mkdir(exist_ok=True);track=directory/'values.tsv'
        keys=m['keys'];curve=[];npnt=0
        for pname,field in [('ParamHeadYaw','yaw'),('ParamTailAngle','rootDegrees'),('ParamTailTip','tipLag')]:
            seg=[0,keys[0][field]]
            for index,v in enumerate(keys[1:],1):seg.extend([0,round(index/FPS,8),v[field]])
            curve.append(dict(Target='Parameter',Id=pname,Segments=seg));npnt+=len(keys)
        motion=dict(Version=3,Meta=dict(Duration=m['frameCount']/FPS,Fps=FPS,Loop=name=='tail_wag',
            AreBeziersRestricted=True,CurveCount=3,TotalSegmentCount=(len(keys)-1)*3,TotalPointCount=npnt,
            UserDataCount=0,TotalUserDataSize=0,FadeInTime=0,FadeOutTime=0),Curves=curve)
        (directory/(name+'.motion3.json')).write_text(json.dumps(motion,separators=(',',':')),encoding='utf8')
        track.write_text('ParamHeadYaw\tParamTailAngle\tParamTailTip\n'+''.join(
            f"{v['yaw']}\t{v['rootDegrees']}\t{v['tipLag']}\n" for v in m['keys']),encoding='utf8')
        cmd=[str(core/'jre/bin/java.exe'),'-Djava.library.path='+str(core/'dll64'),'-cp',cp,'CoreBake',
             str(OUT/'CuteDog.moc3'),str(track),str(directory)]
        with (directory/'native.log').open('w') as log:subprocess.run(cmd,check=True,stdout=log)
        sys.path.insert(0,str(build))
        from render import bake
        bake(OUT/'CuteDog.model3.json',directory,directory/'frames',
             sharp_meshes={'HeadNeck'} if name!='tail_wag' else ())
        frame_paths=sorted((directory/'frames').glob('*.webp'))
        neutral=np.array(Image.open(frame_paths[0]).convert('RGBA'))
        for path,v in zip(frame_paths,keys,strict=True):
            raw=np.array(Image.open(path).convert('RGBA'))
            pixel=registered_pixels(raw,neutral,v['yaw'],v['rootDegrees'],v['tipLag'])
            Image.fromarray(pixel).save(path,lossless=True,exact=True,method=1)
if __name__=='__main__':model();evaluate()
