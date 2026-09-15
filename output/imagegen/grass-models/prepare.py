"""Prepare generated grass atlas for upright markers and projected ground tiles."""
from pathlib import Path
from PIL import Image, ImageDraw
folder=Path(__file__).resolve().parent
root=folder.parents[2]
dest=root/'frontend/img/map'
im=Image.open(folder/'atlas.png').convert('RGBA')
W,H=im.size
variants=['lush','medium','sparse','plain']
preview=Image.new('RGB',(1024,384),'#8a9469')
for i,name in enumerate(variants):
    crop=im.crop(((i%2)*W//2,(i//2)*H//2,(i%2+1)*W//2,(i//2+1)*H//2))
    # The service returned RGBA with a diffuse low-alpha halo. Keep the
    # authored plant cutout, taper that halo instead of retaining its rectangle.
    alpha=crop.getchannel('A').point(lambda a: round(255*max(0,min(1,(a-105)/140))**1.3))
    crop.putalpha(alpha)
    box=alpha.getbbox()
    crop=crop.crop(box)
    crop.thumbnail((350,222),Image.Resampling.LANCZOS)
    sprite=Image.new('RGBA',(384,384))
    sprite.alpha_composite(crop,((384-crop.width)//2,(384-crop.height)//2))
    sprite.save(dest/f'grass-{name}.webp',quality=93)
    sprite.save(dest/f'grass-{name}-map-embedded.png',optimize=True)
    # Inverse of x=u-v, y=(u+v)/2, centered; projection by the map
    # restores the authored blade angle and avoids a second isometric tilt.
    ground=sprite.transform((384,384),Image.Transform.AFFINE,(1,-1,192,.5,.5,0),Image.Resampling.BICUBIC)
    ground.save(dest/f'grass-{name}-ground.png',optimize=True)
    label={'lush':'Dense meadow','medium':'Medium meadow','sparse':'Sparse meadow','plain':'Open plain'}[name]
    small=sprite.resize((256,256),Image.Resampling.LANCZOS)
    preview.paste(small,(i*256,40),small)
    ImageDraw.Draw(preview).text((i*256+32,306),label,fill='white')
    if name in ('lush','plain'):
        typ='grassland' if name=='lush' else 'plains'
        sprite.save(dest/f'wild-{typ}.webp',quality=93)
        sprite.save(dest/f'wild-{typ}-map.webp',quality=93)
        sprite.save(dest/f'wild-{typ}-map-embedded.png',optimize=True)
preview.save(folder/'models-preview.jpg',quality=93)
print('Prepared four transparent models, four ground stamps and two wild icons.')
