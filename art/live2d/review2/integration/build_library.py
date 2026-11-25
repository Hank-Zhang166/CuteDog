"""Construct the production loose Android frame graph from verified base art and reviewed plates."""
import argparse
import hashlib
import json
import shutil
import sys
from concurrent.futures import ThreadPoolExecutor
from functools import lru_cache
from pathlib import Path

from PIL import Image, ImageOps
from foreground_matte import REPAIRED_MATTES, cleaned

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[3]
sys.path.insert(0,str(REPO/'scripts'))
from clip_integrity import content_sha, state_sha, validate_package

def read(path):
    return json.loads(path.read_text(encoding='utf8'))

def dump(path,data):
    path.write_text(json.dumps(data,separators=(',',':'),ensure_ascii=False,allow_nan=False)+'\n',encoding='utf8',newline='\n')

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def source_text_sha(path):
    # These author inputs are tracked as LF in Git and can be generated as CRLF on Windows.
    return hashlib.sha256(path.read_bytes().replace(b'\r\n',b'\n')).hexdigest()

def sequence(directory):
    paths=sorted(p for p in directory.glob('*') if p.stem.isdigit() and p.suffix in ('.webp','.png'))
    if len(paths)<3 or any(int(p.stem)!=i for i,p in enumerate(paths)):
        raise ValueError(f'Invalid interval sample sequence: {directory}')
    return paths

@lru_cache(maxsize=500)
def rgba_sha(path,mirror=False):
    with Image.open(path) as im:
        if im.size!=(512,512) or im.mode!='RGBA': raise ValueError(f'Invalid RGBA source: {path}')
        return hashlib.sha256((ImageOps.mirror(im) if mirror else im).tobytes()).hexdigest()

def put(path,dst,mirror=False,review_matte=False):
    dst.parent.mkdir(parents=True,exist_ok=True)
    if review_matte:
        with Image.open(path) as im:
            filtered=cleaned(im)
        if filtered is not None:
            if mirror: filtered=ImageOps.mirror(filtered)
            filtered.save(dst,lossless=True,exact=True,method=1)
            return sha(dst)
    if path.suffix=='.webp' and not mirror:
        shutil.copyfile(path,dst)
    else:
        with Image.open(path) as im:
            if im.mode!='RGBA' or im.size!=(512,512): raise ValueError(path)
            if mirror: im=ImageOps.mirror(im)
            im.save(dst,lossless=True,exact=True,method=1)
    return sha(dst)

def main(a):
    r,base,bake,out = a.review,a.base,a.base_bake,a.output
    if out.exists(): raise ValueError('Build to a new output directory')
    target=out/'pet3d'; (target/'proofs').mkdir(parents=True)
    old={c['id']:c for c in read(base/'manifest.json')['clips']}
    if len(old)!=59 or 'stand_exit_006' not in old: raise ValueError('Expected the original verified Cubism asset pack')
    proof0=read(base/'phase-proof.json')
    sources={}; oldStates={}; internal={}; canonical={}; posefiles={}
    def add(name,paths,entry,exit,loop=False,travel=None,variant=None,mirror=False,heading=None,exitHeading=None,origin=''):
        if name in sources: raise ValueError('Duplicate motion '+name)
        n=len(paths)-1
        if n<2: raise ValueError(name)
        if travel is not None and len(travel)!=n: raise ValueError('Travel size '+name)
        heading=heading or 'left'; exitHeading=exitHeading or heading
        sources[name]=(paths,entry,exit,loop,travel,variant,mirror,heading,exitHeading,origin)
    # 29 original user clips and the verified base bridge data are never counted twice.
    unchanged=['stand','breathe','happy','tilt','wave','blink','expect','relax','soothe','focus','sit','sit_idle','stand_up','hop']
    sys.path.insert(0,str(HERE.parent/'perform'))
    from foreground_actions import wave, hop
    foreground=out/'foreground'
    wave(bake/'wave',foreground/'wave')
    hop(foreground/'hop')
    for name in unchanged:
        c=old[name]
        paths=sequence(bake/name)
        if [sha(x) for x in paths[:-1]]!=c['frameSha256']: raise ValueError('Base bake differs: '+name)
        kind='baseline Cubism'
        if name in ('wave','hop'):
            paths=sequence(foreground/name)
            kind='registered foreground/'+name
        add(name,paths,c['entryPose'],c['exitPose'],c['loop'],origin=kind)
        oldStates[name]=c
    add('stand_right',sources['stand'][0],'stand_right','stand_right',True,variant='stand',mirror=True,heading='right',origin='reflected native stand')
    canonical['stand_left']=old['stand']['entryStateSha256']
    canonical['stand_right']=state_sha(dict(mirrorOf=canonical['stand_left'],horizontalReflection=True))
    for name,c in old.items():
        if 'phaseEntry' in c and c['phaseEntry']['sourceClip']!='tail_wag':
            source=c['phaseEntry']['sourceClip']; anchor=c['phaseEntry']['atFrame']
            paths=sequence(bake/name)
            if [sha(x) for x in paths[:-1]]!=c['frameSha256']: raise ValueError(name)
            internal[name]=(source,anchor,paths,False,c['entryStateSha256'])
            if source=='stand':
                rid=f'stand_right_exit_{anchor:03}'
                internal[rid]=('stand_right',anchor,paths,True,state_sha(dict(mirrorOf=c['entryStateSha256'],horizontalReflection=True)))

    gi=read(r/'gait/index.json')['clips']
    for raw,g in gi.items():
        if raw=='turn_to_right': continue # an identity alias of the same authored turn
        name=raw.replace('forward_hop','jump').replace('slip_recover','slide_correct')
        def pose(value): return value.replace('forward_hop_','air_').replace('@','_')
        entry,exit=pose(g['entryPose']),pose(g['exitPose'])
        head='right' if '_right' in entry else 'left'; tailHead='right' if '_right' in exit else 'left'
        right=raw.endswith('_right') and head==tailHead
        paths=sequence(r/'gait'/g['directory']); travel=g['projectedTravel']
        if len(paths)!=g['intervals']+1 or rgba_sha(paths[0])!=g['entryRgbaSha256'] or rgba_sha(paths[-1])!=g['exitRgbaSha256']:
            raise ValueError('Gait source does not match its index: '+name)
        add(name,paths,entry,exit,g['loop'],travel,name.removesuffix('_right') if right else None,heading=head,exitHeading=tailHead,origin='gait/'+raw)
        if raw.startswith('forward_hop'):
            suffix='_right' if right else ''
            landing=gi['land'+suffix]['entryPose']
            prefix,mark,frame=landing.partition('@')
            if prefix!='forward_hop_'+head or not mark or not frame.isdigit():
                raise ValueError('Invalid landing phase: '+landing)
            end=int(frame)
            if not 1<end<len(paths)-1: raise ValueError('Landing frame outside forward hop')
            add('jump_takeoff'+suffix,paths[:end+1],entry,pose(landing),False,travel[:end],
                'jump_takeoff' if right else None,heading=head,origin=f'first {end} intervals of {raw}')

    labels=dict(Stand='stand_left',Sit05='sit_left',Lie='lie_left',Rest='rest_left',Sleep='sleep_left')
    posture_names=('belly_up','curl','curl_idle','fall_asleep','lie','lie_idle','lie_to_stand',
                   'scratch','side_lie','side_lie_idle','side_to_lie','sleep','stretch','uncurl','wake','yawn')
    for name in posture_names:
        file=r/'perform'/name/'motion.json'
        m=read(file); paths=sequence(file.parent)
        def rest_pose(plate):
            if plate=='Sleep' and name in ('curl','curl_idle','uncurl'): return 'curl_left'
            return labels[plate]
        add(name,paths,rest_pose(m['entry']),rest_pose(m['exit']),name in ('lie_idle','side_lie_idle','curl_idle','sleep'),origin='registered posture/'+name)
    expressions=read(r/'emotions/trajectories.json')
    approved=('excited','cuddle','sleepy','shy','startled','pet_head','request_pet','seek','call','follow')
    if set(expressions)!=set(approved): raise ValueError('Expression sampling does not match the current review')
    for name in approved:
        m=expressions[name]
        add(name,sequence(r/'emotions'/m['directory']),'stand_left','stand_left',origin='expression/'+name)
    for file in sorted((r/'play/scenes').glob('*/mobile/motion.json')):
        m=read(file);name=m['appAction']
        add(name,sequence(file.parent),'stand_left','stand_left',origin='mobile prop scene/'+file.parent.parent.name)
    if not {'groom','nuzzle','treat','play_ball','lick_paw'} <= sources.keys():
        raise ValueError('Missing composed mobile interaction')
    for name in ['look_left','look_right','curious','look_around','tail_wag']:
        add(name,sequence(a.head/name/'frames'),'stand_left','stand_left',name=='tail_wag',origin='Cubism head/tail/'+name)
    for name,m in read(a.exits/'bridges.json').items():
        paths=[a.exits/'frames'/f'{j:05}.webp' for j in range(m['start'],m['start']+m['count']+1)]
        internal[name]=('tail_wag',m['anchor'],paths,False,state_sha(dict(position=m['position'],velocity=m['velocity'])))

    # Each pose gets one exact encoded reference. Equal images may have different lossless encodings.
    for name,(paths,entry,exit,loop,travel,variant,mir,heading,endHeading,origin) in sources.items():
        for label,path in [(entry,paths[0]),(exit,paths[-1])]:
            pixels=rgba_sha(path,mir)
            if label in posefiles and pixels!=posefiles[label][2]:
                raise ValueError(f'RGBA boundary mismatch for {label}: {name} {path} versus {posefiles[label][0]}')
            posefiles.setdefault(label,(path,mir,pixels))
    canonicalFiles={}
    for label,(path,mirror,px) in posefiles.items():
        dst=out/'canonical'/f'{label}.webp'
        put(path,dst,mirror);canonicalFiles[label]=dst
    origins={}; clips={}
    def build(item):
        name,(paths,entry,exit,loop,travel,variant,mir,heading,endHeading,origin)=item
        directory=target/name;directory.mkdir()
        digests=[]
        for i,p in enumerate(paths[:-1]):
            if i==0: p=canonicalFiles[entry]; reflect=False
            else: reflect=mir
            digests.append(put(p,directory/f'{i:05}.webp',reflect,i>0 and name in REPAIRED_MATTES))
        if len(set(digests))<2:raise ValueError('Static artwork in '+name)
        distances=travel or [0.]*len(digests); backward=any(v<0 for v in distances)
        if backward and any(v>0 for v in distances):raise ValueError('Mixed step direction '+name)
        c=dict(id=name,entryPose=entry,exitPose=exit,loop=loop,interrupt='boundary' if loop else 'never',
               heading=heading,exitHeading=endHeading,canTravel=any(v!=0 for v in distances),fps=24,
               sampling='interval_starts',travelUnits='canvas_width_fraction',projectedTravel=[abs(v) for v in distances],
               frames=[f'pet3d/{name}/{i:05}.webp' for i in range(len(digests))],frameSha256=digests)
        if backward: c['backwardTravel']=True
        if variant: c['variantOf']=variant
        if name in oldStates:
            for key in ('entryStateSha256','exitStateSha256'):
                if key in oldStates[name]:c[key]=oldStates[name][key]
        if entry in canonical and (digests[0]==digests[1] or rgba_sha(paths[0],mir)==rgba_sha(paths[1],mir)):
            c['entryStateSha256']=canonical[entry]
        if exit in canonical and rgba_sha(paths[-1],mir)==rgba_sha(paths[-2],mir):
            c['exitStateSha256']=canonical[exit]
        if name=='tail_wag':
            # Its piecewise-linear native curve starts moving at frame zero; only
            # the separately measured recovery exit has zero terminal velocity.
            c.pop('entryStateSha256',None)
        return name,c
    with ThreadPoolExecutor(max_workers=a.workers) as pool:
        for name,c in pool.map(build,sources.items()): clips[name]=c
    for name,c in clips.items():
        c['transitions']=[other for other,next in clips.items() if c['exitPose']==next['entryPose']]
        if not c['transitions']:raise ValueError('No exit '+name)
        origins[name]=dict(source=sources[name][-1],playbackIntervals=len(c['frames']),
                           entryRgba=posefiles[c['entryPose']][2],terminalRgba=posefiles[c['exitPose']][2])
    joins=sum(len(c['transitions']) for c in clips.values())
    rows=[]
    for name,(src,anchor,paths,mirror,esh) in internal.items():
        source=clips[src]; pose=source['exitPose']; heading=source['heading']
        if rgba_sha(paths[0],mirror)!=rgba_sha(sources[src][0][anchor],sources[src][6]):
            raise ValueError('Phase entry image differs '+name)
        if rgba_sha(paths[-1],mirror)!=posefiles[pose][2]:raise ValueError('Phase terminal differs '+name)
        directory=target/name;directory.mkdir(); frames=[]; hashes=[]
        for i,p in enumerate(paths[:-1]):
            dst=directory/f'{i:05}.webp'
            if i==0: shutil.copyfile(out/source['frames'][anchor],dst); hashes.append(sha(dst))
            else:hashes.append(put(p,dst,mirror))
            frames.append(f'pet3d/{name}/{i:05}.webp')
        endFile=target/'proofs'/f'{name}.webp';shutil.copyfile(canonicalFiles[pose],endFile)
        term=dict(path='pet3d/proofs/'+endFile.name,sha256=sha(endFile),stateSha256=canonical[pose])
        direct=[n for n,c in clips.items() if 'phaseEntry' not in c and c['entryPose']==pose and c.get('entryStateSha256')==canonical[pose]]
        if not direct:raise ValueError('No stationary direct target '+name)
        entry=dict(sourceClip=src,atFrame=anchor,sourceClipSha256=content_sha(512,512,source),
                   sourceFrameSha256=hashes[0],entryStateSha256=esh)
        c=dict(id=name,entryPose=f'phase_{src}_{anchor}',exitPose=pose,loop=False,interrupt='never',
               transitions=direct,heading=heading,exitHeading=heading,canTravel=False,fps=24,sampling='interval_starts',
               frames=frames,frameSha256=hashes,projectedTravel=[0.]*len(frames),travelUnits='canvas_width_fraction',
               entryStateSha256=esh,exitStateSha256=canonical[pose],terminalProof=term,phaseEntry=entry)
        clips[name]=c
        rows.append(dict(bridge=name,sourceClip=src,atFrame=anchor,sourceClipSha256=entry['sourceClipSha256'],
                         bridgeClipSha256=content_sha(512,512,c),sourceFrameSha256=hashes[0],entryStateSha256=esh,
                         terminalFrameSha256=term['sha256'],exitStateSha256=canonical[pose]))
    shutil.copyfile(base/'native-provenance.json',target/'native-provenance.json')
    shutil.copyfile(a.exits/'bridges.json',target/'tail-recoveries.json')
    originsFile=target/'review-provenance.json'
    art=HERE.parent
    care_inputs=['perform/care-seated-atlas.png','perform/postures.py','perform/scratch_repair.py','perform/scratch-tip-track.json',
                 'perform/yawn_native.py','perform/yawn_repair.py','perform/yawn-model/yawn.motion3.json',
                 'perform/editor/Yawn.cmo3','play/groom_author.py','play/scenes.py','play/mobile.py','play/groom-rig/Grooming.cmo3',
                 'play/scenes/groom/groom.motion3.json','play/scenes/groom/scene.json']
    care_inputs.extend(['perform/foreground_actions.py','perform/wave-reference.png',
                        'integration/foreground_matte.py','foreground.py','emotions/emotions.py','gait/turn.py','gait/locomotion.py',
                        'play/mouth_contact.py','play/muzzle-reference.png','play/ball-approach.json','play/scenes/play_ball/scene.json',
                        'play/scenes/treat/scene.json','play/scenes/treat/treat.motion3.json'])
    for name in ['perform/yawn-model/Yawn.model3.json','play/groom-rig/CuteDog.model3.json']:
        model=read(art/name)['FileReferences']
        care_inputs.append(name)
        for reference in [model['Moc'], *model['Textures']]:
            care_inputs.append((Path(name).parent/reference).as_posix())
    care={name:(source_text_sha if name.endswith(('.py','.json')) else sha)(art/name) for name in care_inputs}
    dump(originsFile,dict(schema='cutedog-reviewed-art-v2',baseNative=sha(base/'native-provenance.json'),
                          gaitIndex=source_text_sha(r/'gait/index.json'),expressions=source_text_sha(r/'emotions/trajectories.json'),
                          tailCurves=sha(a.exits/'bridges.json'),tailCoreModel=sha(a.head/'CuteDog.moc3'),
                          tailCoreTexture=sha(a.head/'Dog.png'),headRenderer=sha(REPO/'tools/cubism/render.py'),
                          nativeMotionCurves={
                              name:source_text_sha(a.head/name/(name+'.motion3.json')) for name in
                              ('look_left','look_right','curious','look_around','tail_wag')},
                          postureInputs={name:source_text_sha(r/'perform'/name/'motion.json') for name in posture_names},
                          mobileSceneInputs={p.parent.parent.name:source_text_sha(p) for p in
                              sorted((r/'play/scenes').glob('*/mobile/motion.json'))},
                          careSources=care,producer=source_text_sha(Path(__file__)),clips=origins))
    inputs={**proof0['inputs'],'reviewProvenanceSha256':sha(originsFile),
            'tailCoreBridgeCurvesSha256':sha(a.exits/'bridges.json')}
    proof=dict(version=1,continuityOrder=1,inputs=inputs,bindings=rows)
    proofFile=target/'phase-proof.json';dump(proofFile,proof)
    ref=dict(path='pet3d/phase-proof.json',sha256=sha(proofFile))
    for c in clips.values():
        if 'phaseEntry' in c:c['phaseEntry']['proof']=ref
    manifest=dict(version=2,width=512,height=512,initialClip='stand',clips=list(clips.values()))
    validate_package(manifest,out,proof)
    mf=target/'manifest.json';dump(mf,manifest)
    if mf.stat().st_size>2_000_000: raise ValueError('Manifest too large')
    print(f'{len(sources)} ordinary and directional clips; {joins} pixel-matched ordinary transitions; '
          f'{len(rows)} measured phase exits; {sum(len(c["frames"]) for c in clips.values())} playback frames',flush=True)

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    for flag in ['review','base','base-bake','head','exits','output']:p.add_argument('--'+flag,type=Path,required=True)
    p.add_argument('--workers',type=int,default=5)
    main(p.parse_args())
