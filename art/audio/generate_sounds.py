"""Original quiet foley, synthesized from envelopes and filtered noise; not recordings.
Run from repository root. No external copyrighted samples or hidden downloads.
"""
import math, random, wave, struct
from pathlib import Path
rate=22050
rng=random.Random(166)
out=Path('app/src/main/assets/audio'); out.mkdir(parents=True,exist_ok=True)
def save(name,data):
 with wave.open(str(out/(name+'.wav')),'wb') as w:
  w.setnchannels(1);w.setsampwidth(2);w.setframerate(rate)
  w.writeframes(b''.join(struct.pack('<h',round(max(-1,min(1,v))*32767)) for v in data))
# Gentle paw-on-fabric impact: low-pass noise and a damped low resonance.
data=[]; filtered=0
for i in range(int(.16*rate)):
 t=i/rate; filtered=.78*filtered+.22*rng.uniform(-1,1)
 env=(1-math.exp(-t*600))*math.exp(-t*42)
 data.append(env*(filtered*.28+math.sin(2*math.pi*125*t)*.12))
save('paw',data)
# A cloth-like breath/pant, deliberately soft and non-vocal.
data=[];filtered=0
for i in range(int(.75*rate)):
 t=i/rate;filtered=.65*filtered+.35*rng.uniform(-1,1)
 e=sum(math.exp(-((t-c)/.07)**2) for c in (.16,.43))
 data.append(filtered*e*.10)
save('breath',data)
# A small felt ball landing, two distinct impacts.
data=[]
for i in range(int(.5*rate)):
 t=i/rate;v=0
 for start,vol in ((0,.15),(.22,.07)):
  q=t-start
  if q>=0:v+=vol*math.sin(2*math.pi*(180*q-80*q*q))*math.exp(-q*30)*(1-math.exp(-q*700))
 data.append(v)
save('ball',data)
print('Generated 3 original, quiet foley WAV assets')
