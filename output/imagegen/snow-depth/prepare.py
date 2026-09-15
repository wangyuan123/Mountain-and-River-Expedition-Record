from pathlib import Path
from PIL import Image,ImageFilter,ImageChops,ImageDraw,ImageFont
folder=Path(__file__).resolve().parent; root=folder.parents[2]; dest=root/'frontend/img/map'
preview=Image.new('RGB',(1152,320),'#899267')
for i,kind in enumerate(['thick','medium','thin']):
 source=folder.parent/'snow-rock/snow.png' if kind=='thick' else folder/(kind+'.png')
 im=Image.open(source).convert('RGBA');pixels=[]
 for r,g,b,a in im.getdata():
  spill=min(r,b)-g
  if spill>35:
   a=round(a*max(0,min(1,(90-spill)/55)));r=min(r,g+22);b=min(b,g+22)
  pixels.append((r,g,b,a))
 im.putdata(pixels);im=im.crop(im.getchannel('A').point(lambda a:255 if a>96 else 0).getbbox()).resize((384,192),Image.Resampling.LANCZOS)
 sprite=Image.new('RGBA',(384,384));sprite.alpha_composite(im,(0,96))
 alpha=sprite.getchannel('A');maximum=alpha.getextrema()[1];alpha=alpha.point(lambda a:round(a*255/maximum))
 edge=alpha.filter(ImageFilter.MinFilter(5)).filter(ImageFilter.GaussianBlur(1.1))
 sprite.putalpha(ImageChops.multiply(alpha,edge))
 # Exposed earth recedes into the existing ground, keeping the white cover.
 if kind!='thick':
  data=[];base=.7 if kind=='medium' else .35
  for r,g,b,a in sprite.getdata():
   whiteness=max(0,min(1,(min(r,g,b)-105)/90))
   data.append((r,g,b,round(a*(base+(1-base)*whiteness))))
  sprite.putdata(data)
 sprite.save(dest/f'snow-{kind}.webp',quality=93,method=6)
 sprite.save(dest/f'snow-{kind}-map-embedded.png',optimize=True)
 preview.paste(sprite,(384*i,-30),sprite)
font=ImageFont.truetype('/System/Library/Fonts/PingFang.ttc',23);draw=ImageDraw.Draw(preview)
for i,label in enumerate(['厚雪地','中等雪地','薄雪地']):draw.text((384*i+145,267),label,font=font,fill='white')
preview.save(folder/'models-preview.jpg',quality=94)
print('Exported three snow depths, with feathered edges and fading exposed ground.')
