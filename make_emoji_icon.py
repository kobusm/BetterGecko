#!/usr/bin/env python3
"""Render the 🦎 emoji onto a green rounded-rect background for the app icon."""
from PIL import Image, ImageDraw, ImageFont
import os, math

def make_icon(size):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    # Rounded rect background (green gradient via two-pass)
    radius = int(size * 0.22)

    # Draw background using rounded rectangle
    top_color = (22, 163, 74)     # green-600
    bot_color = (15, 118, 54)     # darker green

    # Gradient fill by drawing horizontal lines
    bg = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    bg_draw = ImageDraw.Draw(bg)
    for y in range(size):
        t = y / size
        r = int(top_color[0] + (bot_color[0] - top_color[0]) * t)
        g = int(top_color[1] + (bot_color[1] - top_color[1]) * t)
        b = int(top_color[2] + (bot_color[2] - top_color[2]) * t)
        bg_draw.line([(0, y), (size, y)], fill=(r, g, b, 255))

    # Mask to rounded rect
    mask = Image.new("L", (size, size), 0)
    mask_draw = ImageDraw.Draw(mask)
    mask_draw.rounded_rectangle([0, 0, size - 1, size - 1], radius=radius, fill=255)
    img.paste(bg, (0, 0), mask)

    # Render emoji using the Apple Color Emoji font
    emoji = "🦎"
    font_size = int(size * 0.62)

    font_paths = [
        "/System/Library/Fonts/Apple Color Emoji.ttc",
        "/System/Library/Fonts/AppleColorEmoji.ttf",
    ]
    font = None
    for fp in font_paths:
        if os.path.exists(fp):
            try:
                font = ImageFont.truetype(fp, font_size)
                break
            except Exception:
                pass

    # Draw emoji centred
    draw2 = ImageDraw.Draw(img)
    if font:
        bbox = draw2.textbbox((0, 0), emoji, font=font)
        tw = bbox[2] - bbox[0]
        th = bbox[3] - bbox[1]
        x = (size - tw) // 2 - bbox[0]
        y = (size - th) // 2 - bbox[1]
        y = int(y * 0.95)  # nudge slightly up
        draw2.text((x, y), emoji, font=font, embedded_color=True)
    else:
        # Fallback: draw a large "G" if emoji font not found
        draw2.text((size // 4, size // 6), emoji, fill="white")

    return img

out_dir = "BetterGecko/Assets.xcassets/AppIcon.appiconset"
os.makedirs(out_dir, exist_ok=True)

print("Generating icons...")
for sz in [1024, 180, 120, 60]:
    icon = make_icon(sz)
    path = os.path.join(out_dir, f"icon_{sz}.png")
    icon.save(path, "PNG")
    print(f"  {sz}x{sz} -> {path}")

print("Done.")
