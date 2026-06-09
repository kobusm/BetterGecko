#!/usr/bin/env python3
"""Generate BetterGecko app icon PNG files using only stdlib."""
import struct, zlib, math, os

def png_bytes(width, height, pixels):
    """pixels: list of (r,g,b,a) tuples, row-major."""
    def chunk(tag, data):
        c = zlib.crc32(tag + data) & 0xffffffff
        return struct.pack('>I', len(data)) + tag + data + struct.pack('>I', c)

    raw = b''
    for y in range(height):
        raw += b'\x00'
        for x in range(width):
            r, g, b, a = pixels[y * width + x]
            raw += bytes([r, g, b, a])

    ihdr = struct.pack('>IIBBBBB', width, height, 8, 2, 0, 0, 0)  # RGB not RGBA
    # Use RGBA: color type 6
    ihdr = struct.pack('>II', width, height) + bytes([8, 6, 0, 0, 0])
    compressed = zlib.compress(raw, 9)

    sig = b'\x89PNG\r\n\x1a\n'
    return (sig
            + chunk(b'IHDR', ihdr)
            + chunk(b'IDAT', compressed)
            + chunk(b'IEND', b''))

def lerp(a, b, t):
    return a + (b - a) * t

def clamp(v, lo, hi):
    return max(lo, min(hi, v))

def dist(x, y, cx, cy):
    return math.sqrt((x - cx)**2 + (y - cy)**2)

def draw_icon(size):
    pixels = []
    cx, cy = size / 2, size / 2
    r = size / 2
    pad = size * 0.08

    # Colours
    bg1 = (22, 160, 80)    # gecko green top
    bg2 = (10, 100, 50)    # darker bottom
    sun_col = (255, 210, 60)
    body_col = (255, 255, 255)
    shadow_col = (180, 255, 200)

    def bg_color(nx, ny):
        t = ny
        return (
            int(lerp(bg1[0], bg2[0], t)),
            int(lerp(bg1[1], bg2[1], t)),
            int(lerp(bg1[2], bg2[2], t)),
        )

    for y in range(size):
        for x in range(size):
            nx = x / size
            ny = y / size

            # Rounded rect mask (corner radius ~22%)
            cr = size * 0.22
            dx = max(0, max(cr - x, x - (size - 1 - cr)))
            dy = max(0, max(cr - y, y - (size - 1 - cr)))
            corner_dist = math.sqrt(dx*dx + dy*dy)
            if corner_dist > cr:
                pixels.append((0, 0, 0, 0))
                continue

            bg = bg_color(nx, ny)
            r_out, g_out, b_out = bg
            a_out = 255

            # Anti-alias the corner
            edge_aa = clamp(cr - corner_dist, 0, 1)
            if corner_dist > cr - 1:
                a_out = int(255 * edge_aa)

            # Sun in top-right area
            sun_cx, sun_cy = size * 0.68, size * 0.28
            sun_r = size * 0.18
            sun_d = dist(x, y, sun_cx, sun_cy)
            # Sun rays
            sun_angle = math.atan2(y - sun_cy, x - sun_cx)
            ray_pattern = abs(math.sin(sun_angle * 8)) * 0.5
            ray_r = sun_r * (1.3 + ray_pattern * 0.4)
            if sun_d < sun_r:
                t = clamp(1 - sun_d / sun_r, 0, 1)
                r_out = int(lerp(r_out, sun_col[0], t * 0.9))
                g_out = int(lerp(g_out, sun_col[1], t * 0.9))
                b_out = int(lerp(b_out, sun_col[2], t * 0.9))
            elif sun_d < ray_r and sun_d > sun_r * 1.1:
                t = clamp(1 - (sun_d - sun_r) / (ray_r - sun_r), 0, 1) * ray_pattern
                r_out = int(lerp(r_out, sun_col[0], t * 0.4))
                g_out = int(lerp(g_out, sun_col[1], t * 0.4))
                b_out = int(lerp(b_out, sun_col[2], t * 0.4))

            # Gecko body: simplified lizard silhouette using ellipses + tail
            # Main body ellipse
            bx, by = size * 0.44, size * 0.55
            bw, bh = size * 0.22, size * 0.14
            angle = math.radians(-15)
            lx = (x - bx) * math.cos(angle) + (y - by) * math.sin(angle)
            ly = -(x - bx) * math.sin(angle) + (y - by) * math.cos(angle)
            body_d = (lx/bw)**2 + (ly/bh)**2
            in_body = body_d < 1.0

            # Head ellipse
            hx, hy = size * 0.58, size * 0.46
            hw, hh = size * 0.12, size * 0.085
            angle_h = math.radians(-20)
            lx2 = (x - hx) * math.cos(angle_h) + (y - hy) * math.sin(angle_h)
            ly2 = -(x - hx) * math.sin(angle_h) + (y - hy) * math.cos(angle_h)
            head_d = (lx2/hw)**2 + (ly2/hh)**2
            in_head = head_d < 1.0

            # Tail: curved thin ellipse going bottom-left
            tail_points = [(size*0.35, size*0.58), (size*0.25, size*0.65), (size*0.18, size*0.72)]
            in_tail = False
            for tp in tail_points:
                td = dist(x, y, tp[0], tp[1])
                tail_w = size * 0.045 * (1 - tail_points.index(tp) * 0.25)
                if td < tail_w:
                    in_tail = True
                    break

            # Legs: small ellipses
            legs = [
                (size*0.50, size*0.63, size*0.035, size*0.07, -30),
                (size*0.38, size*0.62, size*0.035, size*0.07, 30),
                (size*0.55, size*0.55, size*0.06, size*0.03, -60),
                (size*0.34, size*0.53, size*0.06, size*0.03, 60),
            ]
            in_leg = False
            for lx3, ly3, lw3, lh3, ang3 in legs:
                ang3r = math.radians(ang3)
                llx = (x - lx3) * math.cos(ang3r) + (y - ly3) * math.sin(ang3r)
                lly = -(x - lx3) * math.sin(ang3r) + (y - ly3) * math.cos(ang3r)
                if (llx/lw3)**2 + (lly/lh3)**2 < 1.0:
                    in_leg = True
                    break

            if in_body or in_head or in_tail or in_leg:
                # White gecko with slight green tint
                gc = body_col
                alpha = 1.0
                if in_body:
                    alpha = clamp(1 - body_d * 0.1, 0.85, 1.0)
                elif in_head:
                    alpha = clamp(1 - head_d * 0.1, 0.85, 1.0)
                r_out = int(lerp(r_out, gc[0], alpha * 0.92))
                g_out = int(lerp(g_out, gc[1], alpha * 0.92))
                b_out = int(lerp(b_out, gc[2], alpha * 0.92))

            # Eye
            ex, ey = size * 0.615, size * 0.435
            ed = dist(x, y, ex, ey)
            if ed < size * 0.022:
                r_out, g_out, b_out = 30, 30, 30

            pixels.append((clamp(r_out,0,255), clamp(g_out,0,255), clamp(b_out,0,255), a_out))

    return png_bytes(size, size, pixels)


# Generate icons
out_dir = "BetterGecko/Assets.xcassets/AppIcon.appiconset"
os.makedirs(out_dir, exist_ok=True)

sizes = [1024]
print("Generating 1024x1024 icon...")
data = draw_icon(1024)
path = os.path.join(out_dir, "icon_1024.png")
with open(path, 'wb') as f:
    f.write(data)
print(f"  -> {path} ({len(data)//1024} KB)")

# Also generate smaller sizes for simulator
for sz in [60, 120, 180]:
    data = draw_icon(sz)
    p = os.path.join(out_dir, f"icon_{sz}.png")
    with open(p, 'wb') as f:
        f.write(data)
    print(f"  -> {p}")

print("Done.")
