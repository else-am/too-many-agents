#!/usr/bin/env python3
"""Create image acceptance fixtures: uv run --with pillow tools/rich_chat_fixtures.py."""
from pathlib import Path
from PIL import Image, ImageDraw

folder = Path(__file__).resolve().parents[1] / 'scratch/rich-chat'
folder.mkdir(parents=True, exist_ok=True)
image = Image.new('RGBA', (320, 160), (0, 0, 0, 0))
draw = ImageDraw.Draw(image)
for index, color in enumerate(['#ff4040', '#40ff40', '#4040ff']):
    draw.rectangle((index * 100 + 8, 8, index * 100 + 96, 128), fill=color)
draw.text((14, 140), 'PNG / JPEG / GIF / WebP', (255, 255, 255, 255))
for extension in ['png', 'gif', 'webp']:
    image.save(folder / ('colors.' + extension))
image.convert('RGB').save(folder / 'colors.jpg', quality=92)
image.save(folder / 'space in name.png')
for index in range(28):
    image.save(folder / f'cache-{index}.png')
Image.new('RGB', (4097, 1), 'red').save(folder / 'too-wide.png')
Image.new('RGB', (2100, 2000), 'red').save(folder / 'too-many-pixels.png')
(folder / 'broken.png').write_text('not an image')
(folder / 'oversize.png').write_bytes(b'\x89PNG\r\n\x1a\n' + b'x' * (5 * 1024 * 1024))
print(f'Created image format, cache and failure fixtures in {folder}')
