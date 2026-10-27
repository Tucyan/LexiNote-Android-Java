"""Compose real screenshot pairs without modifying the raw PNGs. Requires Pillow."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent
FONT = ImageFont.truetype("C:/Windows/Fonts/msyh.ttc", 22)
OUTPUT = ROOT / "pairs"
OUTPUT.mkdir(exist_ok=True)
names = []
for original in sorted((ROOT / "reference").glob("*.png")):
    native = ROOT / "native" / original.name
    if not native.exists():
        continue
    canvas = Image.new("RGB", (1080, 1260), "#e2e8f0")
    draw = ImageDraw.Draw(canvas)
    for col, (label, path) in enumerate(zip(["Expo 原版", "Java 原生版"], [original, native])):
        with Image.open(path) as raw:
            assert raw.size == (1080, 2424), (path, raw.size)
            picture = raw.convert("RGB").resize((540, 1212), Image.Resampling.LANCZOS)
        draw.text((col * 540 + 16, 10), label, fill="#0f172a", font=FONT)
        canvas.paste(picture, (col * 540, 48))
    canvas.save(OUTPUT / original.name)
    names.append(original.stem)

overview = Image.new("RGB", (1200, 1180), "#e2e8f0")
draw = ImageDraw.Draw(overview)
for row, stage in enumerate(["reference", "native"]):
    for col, name in enumerate(["home", "notebooks", "result", "assistant", "settings"]):
        with Image.open(ROOT / stage / f"{name}.png") as raw:
            picture = raw.convert("RGB").resize((240, 539), Image.Resampling.LANCZOS)
        draw.text((col * 240 + 8, row * 590 + 8), ("Expo" if row == 0 else "Java") + " / " + name,
                  font=ImageFont.truetype("C:/Windows/Fonts/segoeui.ttf", 18), fill="black")
        overview.paste(picture, (col * 240, row * 590 + 40))
overview.save(ROOT / "overview-latest.png")
print(f"Verified {len(names)} actual screenshot pairs: {', '.join(names)}")
