"""Convert generated map dioramas to compact transparent WebP assets."""
from pathlib import Path
from collections import deque
from PIL import Image,ImageFilter,ImageDraw
import shutil
root=Path(__file__).resolve().parents[3]
folder=Path(__file__).resolve().parent
backup=folder/'previous';backup.mkdir(exist_ok=True)
for source in sorted(folder.glob('*.png')):
    if not source.stem.startswith(('npc-','wild-')): continue
    im=Image.open(source).convert('RGBA');w,h=im.size
    rgb=im.load(); seen=bytearray(w*h);pending=deque()
    def bg(x,y):
        r,g,b,_=rgb[x,y]
        return max(r,g,b)-min(r,g,b)<20 and min(r,g,b)>108
    for x in range(w):pending.extend(((x,0),(x,h-1)))
    for y in range(h):pending.extend(((0,y),(w-1,y)))
    while pending:
        x,y=pending.popleft()
        if not(0<=x<w and 0<=y<h):continue
        i=y*w+x
        if seen[i] or not bg(x,y):continue
        seen[i]=1;pending.extend(((x+1,y),(x-1,y),(x,y+1),(x,y-1)))
    alpha=Image.frombytes('L',(w,h),bytes(0 if v else 255 for v in seen))
    alpha=alpha.filter(ImageFilter.MedianFilter(3))
    im.putalpha(alpha)
    im.resize((384,384),Image.Resampling.LANCZOS).save(folder/(source.stem+'-transparent.webp'),quality=92,method=6)
    for suffix in ('','-map'):
        dest=root/'frontend/img/map'/(source.stem+suffix+'.webp')
        if dest.exists() and not (backup/dest.name).exists():shutil.copy2(dest,backup/dest.name)
        shutil.copy2(folder/(source.stem+'-transparent.webp'),dest)
    print(source.stem,im.size,'transparent pixels',sum(seen))
files=sorted(folder.glob('*-transparent.webp'))
out=Image.new('RGB',(1000,600),'#85935d');d=ImageDraw.Draw(out)
for i,p in enumerate(files):
    im=Image.open(p).convert('RGBA');im.thumbnail((250,260));out.paste(im,((i%4)*250,(i//4)*300),im)
    d.text(((i%4)*250+12,(i//4)*300+273),p.stem.replace('-transparent',''),fill='white')
out.save(folder/'comparison.jpg',quality=92)
