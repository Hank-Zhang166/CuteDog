from hashlib import sha256
from pathlib import Path
import json, os
import numpy as np
from PIL import Image, ImageDraw

src = Path(__file__).resolve().parent
p = Path(os.environ.get("CUTEDOG_EMOTION_OUT", src)).resolve()
stand = Image.open(src.parents[1]/'poses/Stand.png').convert('RGBA')
source = np.array(stand)
tracks={}
for file in p.glob('trajectories-*.json'):
    tracks.update(json.loads(file.read_text()))
peaks=dict(excited=.26,cuddle=.45,sleepy=.45,shy=.36,startled=.18,pet_head=.23,
           request_pet=.56,seek=.52,call=.3,follow=.4)
assert tracks.keys()==peaks.keys()
columns=5
sheet=Image.new('RGB',(180*columns,180*((len(peaks)+columns-1)//columns)),'#dce6e3'); draw=ImageDraw.Draw(sheet)
results={}
for i,(name,peak) in enumerate(peaks.items()):
    track=tracks[name]; files=sorted((p/track['directory']).glob('*.webp'))
    n=track['intervals']; assert len(files)==n+1==len(track['controls'])
    assert track['fps']==24
    digests=[]; prev=None; maxStep=0.
    for f in files:
        with Image.open(f) as im:
            a=np.array(im.convert('RGBA'))
        assert a.shape==source.shape and a[:,:,3].min()==0
        d=sha256(a.tobytes()).hexdigest();digests.append(d)
        # Check the planted toes and record silhouette steps for close visual review.
        assert np.array_equal(a[412:,:,:],source[412:,:,:]), (name,f.name)
        if prev is not None:
            maxStep=max(maxStep,float(np.mean(np.abs(prev.astype('i2')-a[:,:,3].astype('i2')))))
        prev=a[:,:,3]
    assert digests[0]==digests[-1]==sha256(source.tobytes()).hexdigest()
    assert len(set(digests))>n*.65, name
    f=Image.open(files[round(peak*n)]).convert('RGBA').crop((62,60,250,222)).resize((180,156))
    x=i%columns*180;y=i//columns*180;sheet.paste(f,(x,y),f.getchannel('A'));draw.text((x+4,y+157),name,fill='#203b38')
    results[name]=dict(intervals=n,unique=len(set(digests)),maxMeanAlphaStep=round(maxStep,5),peakFrame=round(peak*n))
sheet.save(p/'faces.jpg',quality=94)
(p/'trajectories.json').write_text(json.dumps(tracks,separators=(',',':')),encoding='utf8')
(p/'verified.json').write_text(json.dumps(results,indent=2),encoding='utf8')
