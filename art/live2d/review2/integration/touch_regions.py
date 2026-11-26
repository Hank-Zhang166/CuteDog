"""Bake small touch masks from the released frames, in their actual display orientation.

Run with Pillow, NumPy and OpenCV. The numbered polygons use percentages of the painted
animal's bounds in each named reference frame. Transfer them to each released frame using
its silhouette and dense optical flow. This reads artwork without modifying any image.
"""
from itertools import groupby
from pathlib import Path
import gzip
import json
import struct

import cv2
import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[4]
ART = ROOT / 'app/src/main/assets/pet3d'
OUTPUT = ROOT / 'app/src/main/res/raw/pet_touch_regions.gz'
SIDE = 48
TEMPLATE = 76
cv2.setNumThreads(1)

# Inputs are head, tail, then individual raised or planted legs. Body is the remaining coat.
STAND = ('0,0 48,0 49,23 43,43 23,49 0,40', '54,30 57,5 100,2 100,43 86,46 78,36',
         '13,67 49,67 46,100 9,100', '61,68 96,62 100,100 60,100')
SIT = ('0,0 53,0 55,30 42,45 15,47 0,36', '73,51 100,48 100,100 78,98 82,79',
       '9,64 45,67 40,100 6,100', '41,89 66,86 69,100 39,100')
REST = ('0,8 27,0 45,23 41,52 28,65 0,84', '80,34 100,37 100,100 82,100 77,89 84,69',
        '0,88 18,77 51,71 51,95 24,100 0,100', '52,74 72,68 79,88 72,100 50,100')
SLEEP = ('0,15 42,6 54,39 51,62 41,84 28,85 16,75 3,66', '50,83 62,63 82,40 100,44 100,100 44,100',
         '7,73 22,69 42,85 43,100 3,100')
TEMPLATES = {
    'stand': ('stand',0,STAND), 'sit': ('sit_idle',0,SIT),
    'wave': ('wave',24,('12,0 59,0 59,30 52,44 23,48 18,33',SIT[1],
                         '0,22 13,24 22,45 17,63 0,59', '24,69 47,68 44,100 23,100',SIT[3])),
    'bow': ('stretch',29,('8,39 41,37 54,65 41,79 15,81 4,73','54,0 98,0 98,26 80,36 52,35',
                           '0,86 43,76 58,83 58,96 0,100','73,72 100,64 100,100 70,100')),
    'low': ('lie',29,('0,0 45,2 53,33 43,55 17,61 0,52','55,0 100,0 100,36 87,47 66,36',
                       '0,83 45,70 55,86 51,100 0,100','68,79 98,76 98,100 65,100')),
    'lie': ('lie_idle',0,('0,0 47,0 51,34 42,54 12,59 0,54','57,17 100,14 100,60 87,61 74,46 56,44',
                           '0,83 44,71 53,87 48,100 0,100','65,83 91,77 96,100 60,100')),
    'rest': ('side_lie_idle',0,REST), 'sleep': ('sleep',25,SLEEP),
    'belly': ('belly_up',59,('0,14 23,17 35,44 37,76 30,96 0,100','77,33 100,29 100,100 82,100 78,85 86,66',
                              '26,4 47,1 49,31 35,43 25,31','34,44 51,37 57,59 49,73 37,61',
                              '64,0 87,0 83,24 70,36 62,29','65,66 77,52 85,63 83,83 74,95 65,83')),
    'roll': ('belly_up',19,('0,45 23,35 34,54 29,83 20,100 0,100','80,40 100,32 100,100 84,100 79,83 84,65',
                             '24,16 43,16 50,43 32,47','32,41 49,38 53,57 42,67 30,55',
                             '62,0 84,0 83,29 67,43 59,32','68,62 81,55 87,75 80,92 69,85')),
    'roll25': ('belly_up',10,('0,12 26,7 34,37 29,74 24,96 0,100',REST[1],
                               '32,7 49,3 53,27 42,38 32,30','34,42 49,37 54,57 45,67 34,61',REST[3])),
    'scratch': ('scratch',44,('0,0 50,0 50,24 40,35 23,46 0,39',SIT[1],
                              '39,20 48,20 61,48 64,60 48,59 39,41', '0,66 42,68 40,100 0,100',SIT[3])),
    'yawn': ('yawn',31,(SIT[0],SIT[1],'0,66 46,66 40,100 1,100',SIT[3])),
    'groom': ('groom',63,('14,0 75,0 76,28 70,49 41,49 17,36',SIT[1],
                           '0,64 42,64 37,100 0,100',SIT[3])),
    'front': ('turn',28,('0,0 100,0 99,40 79,55 22,55 0,42','', '7,72 95,72 99,100 5,100')),
    'angle': ('turn',14,('0,0 60,0 65,31 58,48 20,55 0,46','60,9 100,5 100,44 86,47 72,38 65,36',
                          '7,70 55,70 61,100 6,100','61,72 99,66 100,100 60,100')),
}

def crop(frame):
    image = np.array(Image.open(frame).convert('RGBA').resize((128,128), Image.Resampling.BOX))
    rgb = image[:,:,:3].astype(np.int16)
    # Red ball and brown snack have saturated paint. Keep shaded and blushing fur.
    prop = (rgb[:,:,0]-rgb[:,:,1] > 52) & (rgb[:,:,1] < 135)
    opaque = ((image[:,:,3] >= 65) & ~prop).astype('uint8')
    n, labels, stats, _ = cv2.connectedComponentsWithStats(opaque)
    if n <= 1: raise ValueError(f'No dog in {frame}')
    selected = 1+int(np.argmax(stats[1:,cv2.CC_STAT_AREA]))
    x,y,w,h = stats[selected,:4]
    dog = np.uint8(labels==selected)
    grey = cv2.cvtColor(image[:,:,:3],cv2.COLOR_RGB2GRAY)
    grey[dog==0] = 96
    return ((x,y,w,h), dog, cv2.resize(grey[y:y+h,x:x+w], (TEMPLATE,TEMPLATE)))

def regions(polygons):
    mask = np.ones((TEMPLATE,TEMPLATE),np.uint8)
    for index, text in enumerate(polygons):
        if text:
            points=np.array([[float(n) for n in pair.split(',')] for pair in text.split()])
            cv2.fillPoly(mask,[np.int32(np.round(points*(TEMPLATE-1)/100))],2 if index==0 else 4 if index==1 else 3)
    return mask

def thumbnail(grey):
    return cv2.resize(grey, (20,20), interpolation=cv2.INTER_AREA).astype('float32')

def prop_cells(frame, scene, sprites):
    # Exclude the photographed biscuit, spoon and ball using the same mobile camera and
    # lip-contact coordinates as the artwork compositor. A conservative one-cell border
    # keeps touches on a small toy from spilling into a neighbouring head cell.
    projected=Image.new('L',(512,512))
    camera=round(scene['dog']['rootOffset'][0])
    contact=scene.get('mouthContact',{})
    for prop in scene['props']:
        name=prop['id']
        if name in ('ball','biscuit') and contact.get('visibleObjectPixels')==0: continue
        x,y=contact.get('projectedCenter',prop['center']) if name in ('ball','biscuit') else prop['center']
        sprite=sprites[name].rotate(-prop['clockwiseDegrees'],Image.Resampling.BICUBIC,expand=True)
        projected.paste(255,(round(x-sprite.width/2)-camera,round(y-sprite.height/2),
                             round(x-sprite.width/2)-camera+sprite.width,round(y-sprite.height/2)+sprite.height),sprite)
    rgb=np.asarray(Image.open(frame).convert('RGB'),dtype=np.int16)
    painted=((rgb[:,:,0]-rgb[:,:,2]>20) & (rgb[:,:,0]-rgb[:,:,1]>12))
    visible=(np.asarray(projected)>24) & painted
    return cv2.resize(visible.astype('float32'),(SIDE,SIDE),interpolation=cv2.INTER_AREA)>.035

def run():
    ref=[]
    for clip, index, polygons in TEMPLATES.values():
        _,_,grey=crop(ART/clip/f'{index:05}.webp')
        ref.append((grey,thumbnail(grey),regions(polygons)))
    flow = cv2.DISOpticalFlow_create(cv2.DISOPTICAL_FLOW_PRESET_FAST)
    yy,xx=np.mgrid[:TEMPLATE,:TEMPLATE].astype(np.float32)
    compressed={}; clips={}
    props=ROOT/'art/live2d/review2/play'
    scenes={n:json.loads((props/'scenes'/n/'scene.json').read_text())['frames'] for n in ['play_ball','treat']}
    sprites={p.stem:Image.open(p).getchannel('A') for p in (props/'props').glob('*.png')}
    for clip in json.loads((ART/'manifest.json').read_text())['clips']:
        ids=[]
        for i,name in enumerate(clip['frames']):
            (x,y,w,h),dog,current=crop(ART.parent/name)
            right=clip['heading']=='right'
            if clip['heading']!=clip['exitHeading'] and i > len(clip['frames'])/2: right=not right
            if right: current=np.ascontiguousarray(current[:,::-1])
            small=thumbnail(current)
            grey,_,mask=min(ref,key=lambda item:np.mean((item[1]-small)**2))
            shift=flow.calc(current,grey,None)
            shifted=cv2.remap(mask,xx+shift[:,:,0],yy+shift[:,:,1],cv2.INTER_NEAREST,borderMode=cv2.BORDER_REPLICATE)
            if right: shifted=shifted[:,::-1]
            full=np.zeros_like(dog);full[y:y+h,x:x+w]=cv2.resize(shifted,(w,h),interpolation=cv2.INTER_NEAREST)
            # Only the touched dog's connected painted surface participates.
            grid=cv2.resize(full*dog,(SIDE,SIDE),interpolation=cv2.INTER_NEAREST_EXACT)
            if clip['id'] in scenes: grid[prop_cells(ART.parent/name,scenes[clip['id']][i],sprites)]=0
            runs=bytearray()
            for value,group in groupby(grid.flat):
                length=sum(1 for _ in group)
                while length:
                    step=min(32,length);runs.append((int(value)<<5)|(step-1));length-=step
            key=bytes(runs); ids.append(compressed.setdefault(key,len(compressed)))
        clips[clip['id']]=ids
    OUTPUT.parent.mkdir(parents=True,exist_ok=True)
    with OUTPUT.open('wb') as raw, gzip.GzipFile(filename='',fileobj=raw,mode='wb',mtime=0) as stream:
        def ushort(value): stream.write(struct.pack('>H',value))
        stream.write(b'CDT1');stream.write(bytes([SIDE]));ushort(len(compressed))
        for data in compressed: ushort(len(data));stream.write(data)
        ushort(len(clips))
        for clip, ids in clips.items():
            name=clip.encode('ascii');ushort(len(name));stream.write(name);ushort(len(ids))
            stream.write(struct.pack('>'+str(len(ids))+'H',*ids))
    print(f'{sum(map(len,clips.values()))} frames; {len(compressed)} unique masks; {OUTPUT.stat().st_size} bytes')

if __name__ == '__main__': run()
