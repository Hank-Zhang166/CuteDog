"""Validate the recomposable scene, exact registration, contact and encoded playback."""
from pathlib import Path
import hashlib
import json
import re
import subprocess
from collections import Counter
import numpy as np
from PIL import Image
from scipy.ndimage import distance_transform_edt, label
import imageio_ffmpeg
from scenes import HERE,ROOT,RIG,SCENES,placed
from layout import ART,GAIT

def rgba(path):
    return Image.open(path).convert('RGBA')

def visible_diff(a,b):
    a,b=np.array(a),np.array(b)
    visible=(a[:,:,3]!=0)|(b[:,:,3]!=0)
    # Compare the actual two composited endpoints, including soft fur fringes.
    return int(np.any(a[visible]!=b[visible],axis=1).sum())

def entity_overlap(row,folder,prop):
    dog=rgba(folder/'dog'/f"{row['frame']:05}.webp"); layer=Image.new('RGBA',(800,512))
    layer.alpha_composite(dog,tuple(map(round,row['dog']['rootOffset'])))
    obj=next(o for o in row['props'] if o['id']==prop)
    second=Image.new('RGBA',layer.size)
    placed(second,rgba(HERE/'props'/(prop+'.png')),obj['center'],obj['clockwiseDegrees'])
    d,b=np.array(layer.getchannel('A'))>=128,np.array(second.getchannel('A'))>=128
    return {'distancePx':round(float(distance_transform_edt(~d)[b].min()),3),'maskOverlapPx':int((b&d).sum())}

def main():
    index={'version':1,'fps':24,'rigModel':'../rig/CuteDog.model3.json','rigEditable':'../rig/Interactions.cmo3',
           'frameOrigin':'top left','worldCanvas':[800,512],'dogSize':[512,512],'clips':[]}
    qa={}
    for name,count in SCENES.items():
        folder=HERE/'scenes'/name;data=json.loads((folder/'scene.json').read_text());rows=data['frames']
        assert len(rows)==count+1 and [r['frame'] for r in rows]==list(range(count+1))
        motion=json.loads((folder/(name+'.motion3.json')).read_text())
        assert abs(motion['Meta']['Duration']-count/24)<.00001
        roles=Counter();strays=0;prev=None;min_iou=1.
        for column,size in [('dog',(512,512)),('stage',(800,512))]:
            listing=sorted((folder/column).glob('*.webp'))
            assert [p.name for p in listing]==[f'{i:05}.webp' for i in range(count+1)]
            for path in listing:
                cur=Image.open(path)
                assert cur.mode=='RGBA' and cur.size==size
                if column=='dog':
                    opaque=np.array(cur.getchannel('A'))>=128
                    labels,cc=label(opaque,structure=np.ones((3,3)))
                    sizes=np.bincount(labels.ravel())[1:]
                    strays+=int((sizes>=6).sum()>1)
                    if prev is not None:min_iou=min(min_iou,float((opaque&prev).sum()/(opaque|prev).sum()))
                    prev=opaque
                roles[column]+=1
        ref=rgba(ART/(data['neutralDrawing']+'.png'))
        diffs=[visible_diff(ref,rgba(folder/'dog'/f'{key:05}.webp')) for key in [0,count]]
        assert max(diffs)==0,(name,diffs)
        probe=subprocess.run([imageio_ffmpeg.get_ffmpeg_exe(),'-hide_banner','-i',str(folder/'review.mp4'),
                              '-map','0:v:0','-f','null','-'],capture_output=True,check=True)
        out=probe.stderr.decode(errors='replace')
        decoded=int(re.findall(r'frame=\s*(\d+)',out)[-1])
        assert decoded==count and '24 fps' in out
        entry={'id':name,'durationSeconds':round(count/24,4),'intervals':count,'manifest':name+'/scene.json',
               'movie':name+'/review.mp4','nativeMotion':name+'/'+name+'.motion3.json'}
        mobile=folder/'mobile'
        if (mobile/'motion.json').is_file():
            mobile_data=json.loads((mobile/'motion.json').read_text())
            length=mobile_data['intervals']
            seq=sorted(mobile.glob('*.webp'))
            assert [p.name for p in seq]==[f'{i:05}.webp' for i in range(length+1)]
            stand=rgba(ART/'Stand.png')
            assert visible_diff(stand,rgba(seq[0]))==0 and visible_diff(stand,rgba(seq[-1]))==0
            entry.update(mobileIntervals=length,mobileManifest=name+'/mobile/motion.json')
        index['clips'].append(entry)
        qa[name]={'intervals':count,'terminalIncluded':True,'files':dict(roles),'decodedMp4Frames':decoded,
                  'firstAndLastDrawingVisiblePixelDifferences':diffs,'minAdjacentDogSilhouetteIoU':round(min_iou,4),
                  'multiComponentOpaqueFramesAt6px':strays}
        if name=='play_ball':
            measures={str(f):entity_overlap(rows[f],folder,'ball') for f in (25,26,27,63,66,109,110,111,112,113)}
            assert measures['25']['distancePx']>5 and measures['26']['maskOverlapPx']>0 and measures['27']['distancePx']>0
            assert measures['66']['maskOverlapPx']>0 and measures['109']['distancePx']>0 and measures['111']['maskOverlapPx']>100 and measures['113']['distancePx']>0
            qa[name]['halfAlphaBallContact']=measures
            qa[name]['chaseDistancePx']=rows[31]['dog']['rootOffset'][0]-rows[61]['dog']['rootOffset'][0]
            source_hash=hashlib.sha256()
            for idx in range(31):
                source=GAIT/'chase'/f'{idx:05}.png';source_hash.update(source.read_bytes())
                assert visible_diff(rgba(source),rgba(folder/'dog'/f'{31+idx:05}.webp'))==0
            assert data['chaseSource']['digestOf31SourcePngs']==source_hash.hexdigest()
            qa[name]['chaseSourceMatchedSamples']=31
            if (mobile/'motion.json').is_file():
                vis=mobile_data['propVisibilityBySourceFrame']['ball']['whole']
                checks=[26,66,94,111]
                assert all(f in vis for f in checks)
                qa[name]['wholeBallVisibleAtMobileContacts']=checks
        if name=='treat':
            prop=lambda f:next(o for o in rows[f]['props'] if o['id']=='biscuit')
            assert prop(56)['z']==1 and 'clipRightX' in prop(56)
            assert rows[56]['dog']['rootOffset'][1]<-30
            qa[name]['biscuitsWhileEating']='Stage-front tracked biscuit with progressive occlusion at the lips.'
        if name=='groom':
            def markers(f):return rows[f]['dog']['markers']
            def distance(a,b):return float(np.linalg.norm(np.array(a)-np.array(b)))
            gaps=[distance(markers(f)['lip'],markers(f)['shoulderCoat']) for f in range(27,61)]
            strokes=[distance(markers(a)['lip'],markers(b)['lip']) for a,b in ((30,36),(46,53))]
            assert max(gaps)<3.5 and min(strokes)>18
            seated=np.array(rgba(ART/'Sit05.png'))[338:]
            for f in range(count+1):
                base=np.array(rgba(folder/'dog'/f'{f:05}.webp'))[338:]
                visible=(base[:,:,3]>0)|(seated[:,:,3]>0)
                assert np.array_equal(seated[visible],base[visible])
            entry.update(rigModel='../groom-rig/CuteDog.model3.json',rigEditable='../groom-rig/Grooming.cmo3')
            ridge_checks=[]
            for f,left,right in ((21,308,332),(35,332,353)):
                alpha=np.array(rgba(folder/'dog'/f'{f:05}.webp'))[:,:,3]
                top=np.asarray([[np.flatnonzero(alpha[:,x]>level)[0] for x in range(left,right)] for level in (25,128,230)])
                step=int(np.abs(np.diff(top[1])).max());width=int((top[2]-top[0]).max())
                assert step<=2 and width<=10
                ridge_checks.append(dict(frame=f,xFirst=left,xLast=right-1,maxVerticalStepPx=step,
                                         maxSoftFurWidthAtAlpha25To230Px=width))
            qa[name].update(contactFramesInclusive=[27,60],maxLipToShoulderMarkerPx=round(max(gaps),3),
                            measuredStrokeDisplacementsPx=np.round(strokes,3).tolist(),fixedPixelsFromY=338,
                            shoulderContours=ridge_checks)
    (HERE/'scenes/index.json').write_text(json.dumps(index,indent=2),encoding='utf8')
    (HERE/'scenes/validation.json').write_text(json.dumps(qa,indent=2),encoding='utf8')
    print(json.dumps(qa,indent=2))
if __name__=='__main__':main()
