"""Cut out generated terrain, match 2:1 ground footprints, export map assets."""
from pathlib import Path
from PIL import Image,ImageFilter,ImageChops,ImageDraw,ImageFont
folder=Path(__file__).resolve().parent
root=folder.parents[2]; dest=root/'frontend/img/map'
preview=Image.new('RGB',(768,336),'#899267')
for i,(kind,source,height) in enumerate([('snow','snow',175),('rock','rock-dry',236)]):
 im=Image.open(folder/(source+'.png')).convert('RGBA')
 pixels=[]
 for r,g,b,a in im.getdata():
  spill=min(r,b)-g
  if spill>35:
   a=round(a*max(0,min(1,(90-spill)/55)))
   r=min(r,g+22); b=min(b,g+22)
  pixels.append((r,g,b,a))
 im.putdata(pixels)
 box=im.getchannel('A').getbbox();assert box
 im=im.crop(box).resize((350,height),Image.Resampling.LANCZOS)
 sprite=Image.new('RGBA',(384,384));sprite.alpha_composite(im,(17,280-height))
 # Feather the soil perimeter into the existing map surface, with no plinth.
 alpha=sprite.getchannel('A')
 maximum=alpha.getextrema()[1]
 alpha=alpha.point(lambda a: round(a*255/maximum))
 inset=alpha.filter(ImageFilter.MinFilter(5)).filter(ImageFilter.GaussianBlur(1.25))
 sprite.putalpha(ImageChops.multiply(alpha,inset))
 sprite.save(dest/f'wild-{kind}.webp',quality=93,method=6)
 sprite.save(dest/f'wild-{kind}-map.webp',quality=93,method=6)
 sprite.save(dest/f'wild-{kind}-map-embedded.png',optimize=True)
 low,high=sprite.getchannel('A').getextrema();assert low==0 and high>240
 preview.paste(sprite,(384*i,-40),sprite)
font=ImageFont.truetype('/System/Library/Fonts/PingFang.ttc',23)
draw=ImageDraw.Draw(preview)
for i,label in enumerate(['雪地','岩石']):draw.text((i*384+168,278),label,font=font,fill='white')
preview.save(folder/'models-preview.jpg',quality=94)
print('Exported snow and rock models with transparent edges and matching ground footprints.')
