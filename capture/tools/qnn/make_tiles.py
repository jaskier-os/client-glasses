import os, glob, numpy as np
from PIL import Image

JPG="/tmp/qnncalib/jpg"
OUT="/tmp/qnncalib/raw"
os.makedirs(OUT, exist_ok=True)
tiles=[]
for fp in sorted(glob.glob(os.path.join(JPG,"*.jpg"))):
    im=Image.open(fp).convert("RGB")
    a=np.asarray(im, dtype=np.float32)/255.0   # HWC, [0,1]
    H,W,_=a.shape
    # sample tiles on a grid; keep ones with varied brightness
    ys=list(range(0, max(1,H-256), 256))
    xs=list(range(0, max(1,W-256), 256))
    for y in ys:
        for x in xs:
            t=a[y:y+256, x:x+256, :]
            if t.shape[0]!=256 or t.shape[1]!=256:
                continue
            tiles.append((t.mean(), t.copy()))
# spread across brightness: sort by mean, pick evenly
tiles.sort(key=lambda z: z[0])
N=len(tiles)
pick = min(48, N)
idx = np.linspace(0, N-1, pick).astype(int)
listf=open("/tmp/qnncalib/input_list.txt","w")
for i,k in enumerate(idx):
    t=tiles[k][1].astype(np.float32)   # NHWC with N=1 implied; raw is just HWC*4 bytes
    p=os.path.join(OUT, f"tile_{i:03d}.raw")
    t.tofile(p)
    listf.write(p+"\n")
listf.close()
print("total candidate tiles:", N, "picked:", pick)
print("bytes per tile:", 256*256*3*4)
# sanity: print brightness range
ms=[tiles[k][0] for k in idx]
print("brightness range:", round(min(ms),3), "->", round(max(ms),3))
