"""Evaluate Hermite recovery of the measured incoming Cubism tail velocity in the local Core."""
import argparse
import json
import os
import subprocess
import sys
from pathlib import Path

import numpy as np
from PIL import Image

def main():
    args = argparse.ArgumentParser(description=__doc__)
    args.add_argument('native', type=Path)
    args.add_argument('output', type=Path)
    args.add_argument('--core', type=Path, required=True)
    args.add_argument('--classes', type=Path, required=True)
    opts = args.parse_args()
    repo = Path(__file__).resolve().parents[4]
    sys.path.insert(0, str(repo/'tools/cubism'))
    from render import bake
    source = np.loadtxt(opts.native/'tail_wag/values.tsv', skiprows=1)
    header = (opts.native/'tail_wag/values.tsv').read_text().splitlines()[0]
    limits = json.loads((opts.native/'rig.json').read_text())['parameters']
    lower = np.array([p['min'] for p in limits]); upper = np.array([p['max'] for p in limits])
    values = []; motions = {}
    for anchor in range(6,len(source)-9,6):
        pose = source[anchor]; velocity = pose-source[anchor-1]
        # Shorten the return if a full incoming tangent would overshoot the model's domain.
        for n in range(8,3,-1):
            def hermite(t):
                return (2*t**3-3*t**2+1)*pose + (t**3-2*t**2+t)*n*velocity
            dense = np.array([hermite(t) for t in np.linspace(0,1,301)])
            if np.all(dense >= lower-1e-6) and np.all(dense <= upper+1e-6): break
        else: raise ValueError(f'Unrepresentable incoming tangent at {anchor}')
        rows = np.array([hermite(i/n) for i in range(n+1)])
        # These are the analytical first derivatives of the cubic, per frame.
        assert np.allclose(rows[0],pose) and np.allclose(rows[-1],0)
        h=1e-6
        assert np.allclose((hermite(h)-hermite(0))/(n*h),velocity,atol=1e-4)
        assert np.allclose((hermite(1)-hermite(1-h))/(n*h),0,atol=1e-4)
        name=f'tail_wag_exit_{anchor:03}'
        motions[name]=dict(start=len(values), anchor=anchor, count=n, position=pose.tolist(),
                           velocity=velocity.tolist(), rows=rows.tolist(), terminalVelocity=[0.]*len(pose))
        values.extend(rows)
    opts.output.mkdir(parents=True,exist_ok=True)
    path=opts.output/'values.tsv'
    np.savetxt(path,values,fmt='%.10g',delimiter='\t',header=header,comments='')
    cmd=[str(opts.core/'jre/bin/java.exe'),'-Djava.library.path='+str(opts.core/'dll64'),
         '-cp',os.pathsep.join([str(opts.classes),str(opts.core/'lib/Live2DCubismCore.jar')]),
         'CoreBake',str(opts.native/'CuteDog.moc3'),str(path),str(opts.output)]
    subprocess.run(cmd,check=True)
    bake(opts.native/'CuteDog.model3.json',opts.output,opts.output/'raw')
    sys.path[:0] = [str(opts.native.parent),str(repo/'art/live2d/review2/perform')]
    from native import registered_pixels
    neutralIndex=next(iter(motions.values()))['count']
    neutral=np.array(Image.open(opts.output/'raw'/f'{neutralIndex:05}.webp').convert('RGBA'))
    (opts.output/'frames').mkdir(exist_ok=True)
    for i,(head,angle,tip) in enumerate(values):
        raw=np.array(Image.open(opts.output/'raw'/f'{i:05}.webp').convert('RGBA'))
        corrected=registered_pixels(raw,neutral,head,angle,tip)
        Image.fromarray(corrected).save(opts.output/'frames'/f'{i:05}.webp',lossless=True,exact=True,method=1)
    (opts.output/'bridges.json').write_text(json.dumps(motions,separators=(',',':')),encoding='utf8')

if __name__=='__main__': main()
