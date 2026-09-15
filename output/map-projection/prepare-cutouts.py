"""Remove edge-connected studio mattes for map-only sprites; keep source art intact."""
from pathlib import Path
from collections import deque
from PIL import Image, ImageFilter
root=Path(__file__).resolve().parents[2]
sources=[root/'frontend/img/cities/harbor.webp',*sorted((root/'frontend/img/map').glob('*.webp'))]
for source in sources:
    if source.stem.endswith('-map'): continue
    im=Image.open(source).convert('RGBA'); w,h=im.size; pix=im.load()
    # The warm gray studio background is almost neutral, including its cast shadow.
    def background(x,y):
        r,g,b,_=pix[x,y]
        return r>85 and 0<=r-g<=17 and 2<=g-b<=20 and r-b<=31
    seen=set(); todo=deque()
    for x in range(w): todo.extend([(x,0),(x,h-1)])
    for y in range(h): todo.extend([(0,y),(w-1,y)])
    while todo:
        x,y=todo.popleft()
        if (x,y) in seen or not(0<=x<w and 0<=y<h) or not background(x,y): continue
        seen.add((x,y)); todo.extend([(x-1,y),(x+1,y),(x,y-1),(x,y+1)])
    alpha=Image.new('L',(w,h),255); a=alpha.load()
    for x,y in seen: a[x,y]=0
    # Clean isolated matte flecks and soften the single-pixel transition.
    alpha=alpha.filter(ImageFilter.MedianFilter(3)).filter(ImageFilter.GaussianBlur(.35))
    im.putalpha(alpha)
    im.save(source.with_name(source.stem+'-map.webp'),quality=92,method=6)
    if source.stem=='harbor':
        preview=Image.new('RGBA',im.size,'#7c8d53');preview.alpha_composite(im);preview.convert('RGB').save('/tmp/harbor-cutout.jpg')
