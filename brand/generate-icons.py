#!/usr/bin/env python3
"""Generate the full BNM Admin logo/icon asset set from the master PNG."""
import os, shutil, subprocess
from PIL import Image, ImageChops, ImageDraw

SRC = "/Users/dineshkumarr/Downloads/Untitled - 13 June 2026 at 12.33.27.PNG"
REPO = "/Users/dineshkumarr/BNM/BNMAdmin"
ANDROID_RES = f"{REPO}/composeApp/src/androidMain/res"
COMMON_DRAWABLE = f"{REPO}/composeApp/src/commonMain/composeResources/drawable"
ICONS = f"{REPO}/composeApp/icons"
IOS_ICON = f"{REPO}/iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/app-icon-1024.png"
WHITE = (255, 255, 255, 255)

os.makedirs(ICONS, exist_ok=True)
os.makedirs(COMMON_DRAWABLE, exist_ok=True)
os.makedirs(f"{ANDROID_RES}/drawable-nodpi", exist_ok=True)

# ---- 1. Load + knock out the near-white background (and interior whites) ----
im = Image.open(SRC).convert("RGBA")
r, g, b, _ = im.split()
mn = ImageChops.darker(ImageChops.darker(r, g), b)          # per-pixel min channel
alpha = mn.point(lambda v: 0 if v >= 244 else 255)          # white-ish -> transparent
im.putalpha(alpha)

full = im.crop(im.getbbox())                                 # tight: mark + "For Business"
print("full logo crop:", full.size)

# ---- 2. Split the BNM mark (upper band) from the "For Business" text ----
small = full.resize((600, int(600 * full.height / full.width)))
sa = small.split()[3]
rows = [sum(sa.getpixel((x, y)) for x in range(0, small.width, 3)) for y in range(small.height)]
thr = max(rows) * 0.04
content = [y for y, v in enumerate(rows) if v > thr]
# find the gap between the two content runs
gap_start = gap_end = None
prev = content[0]
for y in content[1:]:
    if y - prev > small.height * 0.04:        # a real vertical gap
        gap_start, gap_end = prev, y
        break
    prev = y
split_full = full.height if gap_start is None else int((gap_start + gap_end) / 2 * full.width / 600)
mark = full.crop((0, 0, full.width, split_full))
mark = mark.crop(mark.getbbox())
print("BNM mark crop:", mark.size, "aspect %.2f" % (mark.width / mark.height))

# ---- helpers ----
def square_on_white(symbol, size, frac, opaque=False):
    canvas = Image.new("RGBA", (size, size), WHITE)
    tw = int(size * frac)
    th = max(1, round(symbol.height * tw / symbol.width))
    if th > size * frac:                       # guard (mark is wide, rarely hits)
        th = int(size * frac); tw = round(symbol.width * th / symbol.height)
    s = symbol.resize((tw, th), Image.LANCZOS)
    canvas.alpha_composite(s, ((size - tw) // 2, (size - th) // 2))
    return canvas.convert("RGB") if opaque else canvas

def circle_icon(symbol, size, frac):
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(canvas)
    d.ellipse([0, 0, size - 1, size - 1], fill=WHITE)
    tw = int(size * frac)
    th = max(1, round(symbol.height * tw / symbol.width))
    s = symbol.resize((tw, th), Image.LANCZOS)
    canvas.alpha_composite(s, ((size - tw) // 2, (size - th) // 2))
    return canvas

def transparent_fg(symbol, size, frac):
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    tw = int(size * frac)
    th = max(1, round(symbol.height * tw / symbol.width))
    s = symbol.resize((tw, th), Image.LANCZOS)
    canvas.alpha_composite(s, ((size - tw) // 2, (size - th) // 2))
    return canvas

# ---- 3. Android legacy mipmaps (square + round), API 24/25 fallback ----
MIPMAP = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
for dens, sz in MIPMAP.items():
    d = f"{ANDROID_RES}/mipmap-{dens}"
    os.makedirs(d, exist_ok=True)
    square_on_white(mark, sz, 0.80).save(f"{d}/ic_launcher.png")
    circle_icon(mark, sz, 0.62).save(f"{d}/ic_launcher_round.png")

# ---- 4. Android adaptive foreground (single hi-res, transparent) ----
transparent_fg(mark, 432, 0.56).save(f"{ANDROID_RES}/drawable-nodpi/ic_launcher_foreground.png")
old_fg = f"{ANDROID_RES}/drawable-v24/ic_launcher_foreground.xml"
if os.path.exists(old_fg):
    os.remove(old_fg); print("removed", old_fg)

# ---- 5. iOS app icon (opaque white, no alpha) ----
square_on_white(mark, 1024, 0.80, opaque=True).save(IOS_ICON)

# ---- 6. Desktop icons: .icns (mac), .ico (win), .png (linux) ----
square_on_white(mark, 512, 0.80).save(f"{ICONS}/bnm-512.png")
square_on_white(mark, 256, 0.80).convert("RGB").save(
    f"{ICONS}/bnm.ico", sizes=[(16,16),(32,32),(48,48),(64,64),(128,128),(256,256)])
iconset = "/tmp/bnm.iconset"
shutil.rmtree(iconset, ignore_errors=True); os.makedirs(iconset)
for base in (16, 32, 128, 256, 512):
    square_on_white(mark, base, 0.80, opaque=True).save(f"{iconset}/icon_{base}x{base}.png")
    square_on_white(mark, base*2, 0.80, opaque=True).save(f"{iconset}/icon_{base}x{base}@2x.png")
subprocess.run(["iconutil", "-c", "icns", iconset, "-o", f"{ICONS}/bnm.icns"], check=True)

# ---- 7. In-app Compose resources (transparent bg) ----
full.resize((1400, round(1400 * full.height / full.width)), Image.LANCZOS).save(f"{COMMON_DRAWABLE}/bnm_logo.png")
mark.resize((1000, round(1000 * mark.height / mark.width)), Image.LANCZOS).save(f"{COMMON_DRAWABLE}/bnm_logo_mark.png")

print("DONE")
