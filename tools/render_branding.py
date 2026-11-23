"""Export brand assets directly from the Android vectors. Requires Pillow.

Run from any directory: python tools/render_branding.py
The PNGs illustrate the central 72dp adaptive-icon viewport. Android supplies
the launcher mask. The renderer supports absolute paths with circular arcs.
"""
from pathlib import Path
import math
import re
import xml.etree.ElementTree as ET

from PIL import Image, ImageChops, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res/drawable"
VERSION = "0.5.3"
OUT = ROOT / f"dist/branding-{VERSION}"
A = "{http://schemas.android.com/apk/res/android}"


def paths(filename, element="path"):
    return [dict((k.removeprefix(A), v) for k, v in node.attrib.items())
            for node in ET.parse(RES / filename).getroot().iter(element)]


def flatten(data):
    tokens = re.findall(r"[A-Za-z]|[-+]?(?:\d*\.\d+|\d+)", data)
    points, position = [], 0
    while position < len(tokens):
        command = tokens[position]
        position += 1
        if command == "Z":
            points.append(points[0])
            continue
        count = {"M": 2, "L": 2, "H": 1, "V": 1, "A": 7}[command]
        args = list(map(float, tokens[position:position + count]))
        position += count
        if command in ("M", "L"):
            points.append(tuple(args))
            continue
        if command in ("H", "V"):
            x, y = points[-1]
            points.append((args[0], y) if command == "H" else (x, args[0]))
            continue
        rx, ry, rotation, large, sweep, x1, y1 = args
        assert rx == ry and rotation == 0, "Only circular, unrotated arcs supported"
        x0, y0 = points[-1]
        distance2 = (x0 - x1) ** 2 + (y0 - y1) ** 2
        radius = max(rx, math.sqrt(distance2) / 2)
        factor = math.sqrt(max(0, radius ** 2 / distance2 - .25))
        factor *= -1 if large == sweep else 1
        cx = (x0 + x1) / 2 + factor * (y0 - y1)
        cy = (y0 + y1) / 2 + factor * (x1 - x0)
        start = math.atan2(y0 - cy, x0 - cx)
        end = math.atan2(y1 - cy, x1 - cx)
        delta = (end - start) % (2 * math.pi)
        if not sweep:
            delta -= 2 * math.pi
        steps = max(2, math.ceil(abs(delta) * 80))
        for i in range(1, steps + 1):
            angle = start + delta * i / steps
            points.append((cx + radius * math.cos(angle), cy + radius * math.sin(angle)))
    return points


def rgba(value):
    raw = value.removeprefix("#")
    if len(raw) == 8:
        return tuple(int(raw[i:i + 2], 16) for i in (2, 4, 6, 0))
    return tuple(int(raw[i:i + 2], 16) for i in (0, 2, 4)) + (255,)


COLOR = paths("ic_launcher_foreground.xml")
MONO = paths("ic_launcher_monochrome.xml")
MONO_CLIP, = paths("ic_launcher_monochrome.xml", "clip-path")
BG = ET.parse(RES / "ic_launcher_background.xml").find("solid").get(A + "color")


def icon(size, mask="rounded", monochrome=False):
    scale = size * 4 / 72
    side = size * 4
    image = Image.new("RGBA", (side, side), "#DDEBD9" if monochrome else BG)

    def contours(data):
        for subpath in re.findall(r"M[^M]+", data):
            yield [((x - 18) * scale, (y - 18) * scale) for x, y in flatten(subpath)]

    def fill_mask(data, even_odd=False):
        mask = Image.new("1", image.size)
        for points in contours(data):
            if even_odd:
                contour = Image.new("1", image.size)
                ImageDraw.Draw(contour).polygon(points, fill=1)
                mask = ImageChops.logical_xor(mask, contour)
            else:
                ImageDraw.Draw(mask).polygon(points, fill=1)
        return mask

    clip_mono = fill_mask(MONO_CLIP["pathData"]) if monochrome else None
    for path in MONO if monochrome else COLOR:
        fill = rgba(path.get("fillColor", "#00000000"))
        stroke = rgba(path.get("strokeColor", "#00000000"))
        if monochrome:
            ink = rgba("#294B3D")
            fill = ink if fill[3] else fill
            stroke = ink if stroke[3] else stroke
        if fill[3]:
            fill_area = fill_mask(path["pathData"], path.get("fillType") == "evenOdd")
            if clip_mono is not None:
                fill_area = ImageChops.logical_and(fill_area, clip_mono)
            image.paste(fill, mask=fill_area)
        if stroke[3]:
            draw = ImageDraw.Draw(image)
            width = float(path["strokeWidth"]) * scale
            for points in contours(path["pathData"]):
                draw.line(points, fill=stroke, width=round(width))
                # Round the sampled arc joins without extending the flip seams.
                caps = points if path.get("strokeLineCap") == "round" else points[1:-1]
                for x, y in caps:
                    radius = width / 2
                    draw.ellipse((x - radius, y - radius, x + radius, y + radius), fill=stroke)
    clip = Image.new("L", (side, side), 0)
    boundary = ImageDraw.Draw(clip)
    if mask == "circle":
        boundary.ellipse((0, 0, side - 1, side - 1), fill=255)
    elif mask == "squircle":
        samples = []
        for index in range(721):
            t = index * math.pi / 360
            x, y = math.cos(t), math.sin(t)
            samples.append(((.5 + .5 * math.copysign(abs(x) ** .5, x)) * (side - 1),
                            (.5 + .5 * math.copysign(abs(y) ** .5, y)) * (side - 1)))
        boundary.polygon(samples, fill=255)
    else:
        boundary.rounded_rectangle((0, 0, side - 1, side - 1), radius=side * .225, fill=255)
    image.putalpha(clip)
    return image.resize((size, size), Image.Resampling.LANCZOS)


def export_svg(filename, monochrome=False):
    attrs = {"pathData": "d", "fillColor": "fill", "strokeColor": "stroke",
             "strokeWidth": "stroke-width", "strokeLineCap": "stroke-linecap",
             "strokeLineJoin": "stroke-linejoin", "fillType": "fill-rule"}
    lines = ['<svg xmlns="http://www.w3.org/2000/svg" width="1024" height="1024" viewBox="18 18 72 72">',
             '  <title>时栖时钟</title>',
             '  <defs><clipPath id="mask"><rect x="18" y="18" width="72" height="72" rx="16.2"/></clipPath></defs>',
             '  <g clip-path="url(#mask)">',
             f'    <rect x="18" y="18" width="72" height="72" fill="{"#DDEBD9" if monochrome else BG}"/>']
    if monochrome:
        lines.insert(3, f'  <defs><clipPath id="seams"><path d="{MONO_CLIP["pathData"]}"/></clipPath></defs>')
        lines.append('    <g clip-path="url(#seams)">')
    for path in MONO if monochrome else COLOR:
        values = []
        for key, value in path.items():
            if key not in attrs:
                continue
            if key.endswith("Color"):
                color = rgba(value)
                value = "none" if not color[3] else ("#294B3D" if monochrome else value[-6:])
                if value not in ("none", "#294B3D"):
                    value = "#" + value
            if key == "fillType":
                value = {"nonZero": "nonzero", "evenOdd": "evenodd"}[value]
            values.append(f'{attrs[key]}="{value}"')
        lines.append('    <path ' + ' '.join(values) + '/>')
    if monochrome:
        lines.append('    </g>')
    lines.extend(['  </g>', '</svg>'])
    (OUT / filename).write_text('\n'.join(lines) + '\n', encoding="utf-8")


def board():
    scale = 2
    canvas = Image.new("RGB", (1200 * scale, 850 * scale), "#F2F1EA")
    draw = ImageDraw.Draw(canvas)
    fonts = Path("C:/Windows/Fonts")

    def text(x, y, value, size, color="#162F32", bold=False):
        font = ImageFont.truetype(str(fonts / ("msyhbd.ttc" if bold else "msyh.ttc")), size * scale)
        draw.text((x * scale, y * scale), value, font=font, fill=color)

    def paste(x, y, size, **options):
        mark = icon(size * scale, **options)
        canvas.paste(mark, (x * scale, y * scale), mark)

    text(64, 40, "SHIQI  /  DESK CLOCK", 15, "#5F7973")
    text(1000, 40, f"图标设计  {VERSION}", 14, "#5F7973")
    paste(64, 130, 284)
    text(414, 141, "时栖时钟", 65, bold=True)
    text(419, 247, "紧凑翻牌", 28)
    text(420, 314, "深蓝灰底 · 双翻片 · 薄荷绿数字", 19, "#5F7973")
    text(420, 352, "翻片集中在画布中央，适应不同桌面形状。", 19, "#5F7973")
    draw.line((64 * scale, 476 * scale, 1136 * scale, 476 * scale), fill="#D6DDD3", width=2)
    text(64, 505, "图标适配", 18, bold=True)
    samples = [(64, "rounded", False, "圆角方形"), (342, "circle", False, "圆形遮罩"),
               (620, "squircle", False, "连续圆角"), (898, "rounded", True, "系统主题图标")]
    for x, mask, mono, label in samples:
        draw.rounded_rectangle((x * scale, 552 * scale, (x + 238) * scale, 753 * scale),
                               radius=22 * scale, fill="#E7EAE1")
        paste(x + 67, 574, 104, mask=mask, monochrome=mono)
        text(x + 30, 704, label, 17, "#4F6861")
    text(64, 790, "深蓝灰  #10161F     翻片绿  #3D715D     薄荷绿  #D9F4E1", 15, "#5F7973")
    for x, size in [(975, 24), (1020, 32), (1074, 48)]:
        paste(x, 812 - size, size)
    canvas.resize((1200, 850), Image.Resampling.LANCZOS).save(OUT / "brand-preview.png")


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    export_svg("app-icon.svg")
    export_svg("app-icon-monochrome.svg", monochrome=True)
    icon(1024).save(OUT / "app-icon-1024.png")
    icon(512, monochrome=True).save(OUT / "app-icon-monochrome.png")
    for size in (24, 32, 48, 64, 96, 192):
        icon(size).save(OUT / f"app-icon-{size}.png")
    board()
    print(f"Exported Android-vector brand assets to {OUT}")
