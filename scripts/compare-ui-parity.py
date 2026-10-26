"""Build contact sheets from actual screenshots; requires Pillow, never alters originals."""
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1] / 'docs' / 'ui-parity'


def sheet(names, stages, output):
    width, height = 232, 521
    canvas = Image.new('RGB', (len(names) * 240, len(stages) * 560), '#e2e8f0')
    draw = ImageDraw.Draw(canvas)
    for row, stage in enumerate(stages):
        for col, name in enumerate(names):
            path = ROOT / stage / f'{name}.png'
            if not path.exists():
                continue
            picture = Image.open(path).convert('RGB')
            picture.thumbnail((width, height), Image.Resampling.LANCZOS)
            x, y = col * 240 + 4, row * 560
            draw.text((x + 4, y + 8), f'{stage} / {name}', fill='black')
            canvas.paste(picture, (x, y + 30))
    canvas.save(ROOT / output)


sheet(['home', 'guess', 'notebooks', 'review', 'settings'], ['reference', 'after'], 'after-comparison.png')
sheet(['query-image', 'query-mixed', 'assistant-empty'], ['reference', 'after'], 'query-assistant-comparison.png')
sheet(['result', 'assistant', 'notebooks-filled', 'review-filled'], ['reference', 'after'], 'populated-comparison.png')
