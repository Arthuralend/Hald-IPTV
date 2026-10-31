"""Generates the retro-TV app icon. Run: python3 tools/make_icon.py"""
from PIL import Image, ImageDraw, ImageFilter
import random, os

S = 1024
OUT = "HaldIPTV/Resources/Assets.xcassets/AppIcon.appiconset"


def lin(w, h, c1, c2):
    g = Image.new("RGBA", (w, h))
    d = ImageDraw.Draw(g)
    for i in range(h):
        t = i / max(1, h - 1)
        d.line([(0, i), (w, i)], fill=tuple(int(c1[k] + (c2[k] - c1[k]) * t) for k in range(4)))
    return g


def rmask(w, h, r):
    m = Image.new("L", (w, h), 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, w - 1, h - 1], r, fill=255)
    return m


img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
sh = Image.new("RGBA", (S, S), (0, 0, 0, 0))
ImageDraw.Draw(sh).rounded_rectangle([110, 250, 914, 880], 60, fill=(0, 0, 0, 150))
img.alpha_composite(sh.filter(ImageFilter.GaussianBlur(28)), (0, 22))
d = ImageDraw.Draw(img)
d.rounded_rectangle([170, 850, 854, 895], 14, fill=(28, 20, 14, 255))

# wooden cabinet
x0, y0, x1, y1 = 100, 190, 924, 862
W, H = x1 - x0, y1 - y0
wood = lin(W, H, (186, 104, 48, 255), (112, 56, 22, 255))
grain = Image.new("RGBA", (W, H), (0, 0, 0, 0))
gd = ImageDraw.Draw(grain)
random.seed(3)
for _ in range(160):
    y = random.randint(0, H)
    gd.line([(0, y), (W, y + random.randint(-10, 10))], fill=(60, 26, 6, random.randint(18, 50)),
            width=random.randint(1, 4))
wood.alpha_composite(grain.filter(ImageFilter.GaussianBlur(1.5)))
img.paste(wood, (x0, y0), rmask(W, H, 58))
hl = Image.new("RGBA", (W, H), (0, 0, 0, 0))
ImageDraw.Draw(hl).rounded_rectangle([8, 8, W - 9, 50], 40, fill=(255, 215, 170, 70))
img.alpha_composite(hl.filter(ImageFilter.GaussianBlur(8)), (x0, y0))
d = ImageDraw.Draw(img)
d.rounded_rectangle([x0, y0, x1, y1], 58, outline=(70, 34, 12, 255), width=6)

# screen
bx0, by0, bx1, by1 = 150, 250, 700, 800
d.rounded_rectangle([bx0, by0, bx1, by1], 60, fill=(22, 16, 12, 255))
d.rounded_rectangle([bx0 + 8, by0 + 8, bx1 - 8, by1 - 8], 54, fill=(48, 40, 34, 255))
sx0, sy0, sx1, sy1 = bx0 + 42, by0 + 42, bx1 - 42, by1 - 42
sw, shh = sx1 - sx0, sy1 - sy0
scr = Image.new("RGBA", (sw, shh))
px = scr.load()
cx, cy = sw * 0.45, shh * 0.42
for yy in range(shh):
    for xx in range(sw):
        t = min(1, (((xx - cx) / (sw * 0.62)) ** 2 + ((yy - cy) / (shh * 0.62)) ** 2) ** 0.5)
        px[xx, yy] = (int(228 - 125 * t), int(242 - 112 * t), int(255 - 70 * t), 255)
m = rmask(sw, shh, 80)
img.paste(scr, (sx0, sy0), m)
gl = Image.new("RGBA", (sw, shh), (0, 0, 0, 0))
ImageDraw.Draw(gl).polygon([(20, 20), (sw * 0.75, 20), (20, shh * 0.55)], fill=(255, 255, 255, 55))
gl2 = Image.new("RGBA", (sw, shh), (0, 0, 0, 0))
gl2.paste(gl.filter(ImageFilter.GaussianBlur(14)), (0, 0), m)
img.alpha_composite(gl2, (sx0, sy0))
d = ImageDraw.Draw(img)
d.rounded_rectangle([sx0, sy0, sx1, sy1], 80, outline=(20, 30, 45, 255), width=5)

# control panel
d.rounded_rectangle([730, 262, 890, 792], 24, fill=(120, 62, 26, 255), outline=(70, 34, 12, 255), width=4)


def knob(cx, cy, r):
    d.ellipse([cx - r - 10, cy - r - 10, cx + r + 10, cy + r + 10], fill=(70, 58, 50, 255))
    k = Image.new("RGBA", (2 * r, 2 * r))
    kd = ImageDraw.Draw(k)
    for i in range(r, 0, -1):
        c = int(235 - 120 * (i / r))
        kd.ellipse([r - i, r - i, r + i, r + i], fill=(c, c, c, 255))
    mk = Image.new("L", (2 * r, 2 * r), 0)
    ImageDraw.Draw(mk).ellipse([0, 0, 2 * r - 1, 2 * r - 1], fill=255)
    img.paste(k, (cx - r, cy - r), mk)
    d.ellipse([cx - r * .45, cy - r * .45, cx + r * .45, cy + r * .45], fill=(205, 40, 40, 255),
              outline=(120, 15, 15, 255), width=4)
    d.ellipse([cx - r * .2, cy - r * .32, cx + r * .02, cy - r * .1], fill=(255, 160, 160, 255))


knob(810, 355, 52)
knob(810, 505, 52)
for i in range(12):
    y = 595 + i * 14
    d.rounded_rectangle([760, y, 860, y + 6], 3, fill=(60, 28, 10, 255))
d.rounded_rectangle([772, 770, 848, 790], 5, fill=(225, 220, 210, 255))

os.makedirs(OUT, exist_ok=True)
img.save("tools/icon_1024.png")
for s in [16, 32, 128, 256, 512]:
    for sc in [1, 2]:
        img.resize((s * sc, s * sc), Image.LANCZOS).save(f"{OUT}/icon_{s}x{s}@{sc}x.png")
