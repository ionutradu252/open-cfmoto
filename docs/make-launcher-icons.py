"""Build OpenCFLink launcher icons from the logo artwork.

Adaptive icons only guarantee the central 66dp of the 108dp layer survives masking, so the art is
scaled to that safe zone rather than dropped in full-bleed (it is 77% tall — a round mask would
clip the top of the ring and the arrow tip).

The backdrop is keyed out by luminance instead of being baked in: the logo's own dark internal
details then fall through to the solid background layer, which is the same navy, so it reads the
same as the source with no seam where a padded rectangle would have ended.
"""
from PIL import Image, ImageDraw, ImageChops
import os

SRC = os.path.join(os.path.dirname(os.path.abspath(__file__)), "logo-source.png")
RES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res")
HERE = os.path.dirname(os.path.abspath(__file__))

# Luminance ramp separating logo from backdrop.
#
# LO must sit ABOVE the brightest backdrop pixel or the key leaves a seam. Measured on this
# artwork: far corners 25, left/right edges 26, bottom 6 — but the glow behind the ring bleeds all
# the way to the TOP edge at 53. A first pass at LO=30 therefore keyed that gradient in as
# semi-opaque, and the edge of its crop rectangle showed as a lighter box against the flat
# background layer. 60 clears the top edge with a little margin; the ring's own halo (brighter than
# that) still fades in smoothly, so there is no hard boundary anywhere.
LO, HI = 60, 120

src = Image.open(SRC).convert("RGBA")
rgb = src.convert("RGB")
lum = rgb.convert("L")

# --- 1. alpha-key the backdrop (whole-image ops; no per-pixel Python) ---
# alpha = clamp((lum - LO) / (HI - LO)) * 255
alpha = lum.point(lambda v: 0 if v <= LO else (255 if v >= HI else int((v - LO) * 255 / (HI - LO))))
art_full = rgb.copy()
art_full.putalpha(alpha)

# --- 2. background navy: median-ish of the true backdrop, floored so it isn't pure black ---
mask_bg = alpha.point(lambda v: 255 if v < 8 else 0)
stat_r, stat_g, stat_b = (ch.histogram(mask_bg) for ch in rgb.split())
def pct(hist, p):
    total = sum(hist)
    acc = 0
    for i, c in enumerate(hist):
        acc += c
        if acc >= total * p:
            return i
    return 0
BG = (max(pct(stat_r, 0.75), 8), max(pct(stat_g, 0.75), 26), max(pct(stat_b, 0.75), 44))
print("background navy: #%02X%02X%02X" % BG)

# --- 3. crop to the visible art, including the full fade-out of the halo ---
# A low threshold here on purpose: cropping where alpha is still ~40 would put a hard edge back.
bbox = alpha.point(lambda v: 255 if v > 6 else 0).getbbox()
art = art_full.crop(bbox)
print("art crop:", bbox, "->", art.size)

def compose(canvas_px, art_frac, mask_circle=False, transparent_bg=False):
    """Art scaled so its longest side is art_frac of the canvas, centred."""
    aw, ah = art.size
    s = (canvas_px * art_frac) / max(aw, ah)
    a2 = art.resize((max(1, round(aw * s)), max(1, round(ah * s))), Image.LANCZOS)
    bg = (0, 0, 0, 0) if transparent_bg else BG + (255,)
    out = Image.new("RGBA", (canvas_px, canvas_px), bg)
    out.alpha_composite(a2, ((canvas_px - a2.width) // 2, (canvas_px - a2.height) // 2))
    if mask_circle:
        m = Image.new("L", (canvas_px, canvas_px), 0)
        ImageDraw.Draw(m).ellipse((0, 0, canvas_px - 1, canvas_px - 1), fill=255)
        out.putalpha(ImageChops.multiply(out.getchannel("A"), m))
    return out

# --- 4. adaptive foreground: 108dp layer, art inside the 66dp safe zone ---
SAFE = 66.0 / 108.0
for name, scale in [("mdpi", 1), ("hdpi", 1.5), ("xhdpi", 2), ("xxhdpi", 3), ("xxxhdpi", 4)]:
    size = round(108 * scale)
    d = os.path.join(RES, "mipmap-" + name)
    os.makedirs(d, exist_ok=True)
    compose(size, SAFE, transparent_bg=True).save(os.path.join(d, "ic_launcher_foreground.png"))
    print("foreground", name, size)

# --- 5. legacy square + round (unused at minSdk 29, kept consistent anyway) ---
for name, size in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
    d = os.path.join(RES, "mipmap-" + name)
    compose(size, 0.86).save(os.path.join(d, "ic_launcher.webp"), "WEBP", quality=95)
    compose(size, 0.70, mask_circle=True).save(os.path.join(d, "ic_launcher_round.webp"), "WEBP", quality=95)
    print("legacy", name, size)

# --- 6. preview of what a launcher actually shows, for eyeballing ---
fg = compose(432, SAFE, transparent_bg=True)
flat = Image.new("RGBA", (432, 432), BG + (255,))
flat.alpha_composite(fg)
circ = flat.copy()
m = Image.new("L", (432, 432), 0)
ImageDraw.Draw(m).ellipse((72, 72, 360, 360), fill=255)     # 72dp mask on a 108dp layer
circ.putalpha(m)
squircle = flat.copy()
m2 = Image.new("L", (432, 432), 0)
ImageDraw.Draw(m2).rounded_rectangle((72, 72, 360, 360), radius=80, fill=255)
squircle.putalpha(m2)
sheet = Image.new("RGBA", (1340, 452), (90, 90, 90, 255))
sheet.alpha_composite(flat, (10, 10))
sheet.alpha_composite(circ, (454, 10))
sheet.alpha_composite(squircle, (898, 10))
sheet.save(os.path.join(HERE, "icon_preview.png"))
print("preview written")
