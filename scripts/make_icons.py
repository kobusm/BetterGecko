#!/usr/bin/env python3
"""Build every BetterGecko icon from the Noto Emoji gecko (art/noto-gecko-3d.png, Apache 2.0).

Outputs:
  iOS        BetterGecko/Assets.xcassets/AppIcon.appiconset/icon_1024.png  (square, opaque; iOS masks it)
  Play Store android/playstore/icon-512.png                                 (square, opaque; Play masks it)
  Android    android/app/src/main/res/mipmap-*/ic_launcher.png              (rounded square, transparent corners)
  Site       site/icon.png                                                  (rounded square)
  Play Store android/playstore/feature-graphic.png                          (1024x500 banner)
  In-app     GeckoIcon.imageset (iOS splash), res/drawable/gecko_icon.png (Android login/header)

Run from the repo root: python3 scripts/make_icons.py
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parent.parent
ART = ROOT / "art/noto-gecko-3d.png"

TOP = (21, 128, 61)      # green-700
BOTTOM = (8, 74, 36)     # deeper green
GECKO_SCALE = 0.74       # gecko width as a fraction of the icon
CORNER = 0.22            # rounded-corner radius for the non-masked outputs


def square(size: int) -> Image.Image:
    """Opaque full-bleed icon: vertical gradient, soft glow, gecko with a drop shadow."""
    s = 1024  # draw at 1024 and downscale for clean edges
    bg = Image.new("RGB", (s, s))
    d = ImageDraw.Draw(bg)
    for y in range(s):
        t = y / (s - 1)
        d.line([(0, y), (s, y)], fill=tuple(round(a + (b - a) * t) for a, b in zip(TOP, BOTTOM)))

    # Soft light behind the gecko so it separates from the green.
    glow = Image.new("L", (s, s), 0)
    ImageDraw.Draw(glow).ellipse([s * 0.18, s * 0.14, s * 0.82, s * 0.86], fill=70)
    glow = glow.filter(ImageFilter.GaussianBlur(s * 0.09))
    bg = Image.composite(Image.new("RGB", (s, s), (190, 242, 100)), bg, glow)

    gecko = Image.open(ART).convert("RGBA")
    gw = round(s * GECKO_SCALE)
    gecko = gecko.resize((gw, gw), Image.LANCZOS)
    off = ((s - gw) // 2, (s - gw) // 2)

    shadow = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    alpha = gecko.getchannel("A").point(lambda a: a * 0.45)
    shadow.paste((0, 40, 15, 255), (off[0] + 14, off[1] + 22), alpha)
    shadow = shadow.filter(ImageFilter.GaussianBlur(18))

    out = bg.convert("RGBA")
    out.alpha_composite(shadow)
    out.alpha_composite(gecko, off)
    return out.convert("RGB").resize((size, size), Image.LANCZOS)


def rounded(size: int) -> Image.Image:
    img = square(1024).convert("RGBA")
    mask = Image.new("L", (1024, 1024), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, 1023, 1023], radius=round(1024 * CORNER), fill=255)
    img.putalpha(mask)
    return img.resize((size, size), Image.LANCZOS)


def feature_graphic() -> Image.Image:
    """1024x500 Play Store banner: icon on the left, name and tagline on the right."""
    from PIL import ImageFont

    w, h = 1024, 500
    fg = Image.new("RGB", (w, h))
    d = ImageDraw.Draw(fg)
    for y in range(h):
        t = y / (h - 1)
        d.line([(0, y), (w, y)], fill=tuple(round(a + (b - a) * t) for a, b in zip(TOP, BOTTOM)))
    icon = rounded(300)
    fg.paste(icon, (90, 100), icon)

    def font(size: int, weight: str) -> ImageFont.FreeTypeFont:
        f = ImageFont.truetype("/System/Library/Fonts/SFNS.ttf", size)
        try:
            f.set_variation_by_name(weight)
        except Exception:
            pass
        return f

    d.text((440, 160), "BetterGecko", font=font(84, "Bold"), fill="white")
    d.multiline_text((444, 275), "Your solar geyser,\nat a glance.", font=font(40, "Regular"),
                     fill=(232, 246, 236), spacing=10)
    return fg


def main() -> None:
    square(1024).save(ROOT / "BetterGecko/Assets.xcassets/AppIcon.appiconset/icon_1024.png")
    square(512).save(ROOT / "android/playstore/icon-512.png")
    for density, px in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
        rounded(px).save(ROOT / f"android/app/src/main/res/mipmap-{density}/ic_launcher.png")
    rounded(256).save(ROOT / "site/icon.png")
    feature_graphic().save(ROOT / "android/playstore/feature-graphic.png")
    # In-app artwork (splash on iOS, login/header on Android).
    rounded(384).save(ROOT / "BetterGecko/Assets.xcassets/GeckoIcon.imageset/BetterGecko.png")
    rounded(384).save(ROOT / "android/app/src/main/res/drawable/gecko_icon.png")
    print("Icons written.")


if __name__ == "__main__":
    main()
