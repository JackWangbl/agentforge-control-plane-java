#!/usr/bin/env python3
"""Render local images for the 手之劤 WeChat mini program."""

import math
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parents[1] / "assets"
FONT = "/usr/share/fonts/truetype/wqy/wqy-microhei.ttc"
LATIN = "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf"
LATIN_REG = "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf"

BLUE = (31, 122, 196, 255)
BLUE_DK = (16, 78, 140, 255)
BLUE_LT = (70, 164, 224, 255)
ORANGE = (240, 138, 28, 255)
BLACK = (22, 22, 24, 255)
SILVER = (186, 194, 202, 255)
SILVER_DK = (120, 130, 140, 255)
RUBBER = (28, 30, 34, 255)


def font(size, latin=False, bold=True):
    path = (LATIN if bold else LATIN_REG) if latin else FONT
    return ImageFont.truetype(path, size)


def text(draw, xy, value, size, fill, latin=False, anchor="lt", stroke=0, stroke_fill=None):
    draw.text(
        xy,
        value,
        font=font(size, latin=latin),
        fill=fill,
        anchor=anchor,
        stroke_width=stroke,
        stroke_fill=stroke_fill or fill,
    )


def new_rgba(w, h, color=(0, 0, 0, 0)):
    return Image.new("RGBA", (w, h), color)


def paste(base, sprite, xy):
    base.alpha_composite(sprite, xy)


def rounded_mask(w, h, r):
    mask = Image.new("L", (w, h), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, w - 1, h - 1), r, fill=255)
    return mask


def streak_background(w, h):
    small = Image.new("RGB", (48, 48))
    pix = small.load()
    for y in range(48):
        for x in range(48):
            nx = x / 47 - 0.42
            ny = y / 47 - 0.58
            dist = math.sqrt(nx * nx * 1.15 + ny * ny)
            t = max(0.0, 1 - dist * 1.25)
            pix[x, y] = (
                int(5 + 22 * t),
                int(12 + 40 * t),
                int(28 + 78 * t),
            )
    bg = small.resize((w, h), Image.Resampling.BICUBIC).convert("RGBA")
    overlay = new_rgba(w, h)
    draw = ImageDraw.Draw(overlay)
    cx, cy = int(w * 0.15), int(h * 0.95)
    for i in range(18):
        rad = int(w * (0.28 + i * 0.055))
        alpha = 150 if i % 3 == 0 else 70
        color = (190, 225, 255, alpha) if i % 2 == 0 else (80, 150, 220, alpha // 2)
        box = [cx - rad, cy - rad, cx + rad, cy + rad]
        draw.arc(box, start=286, end=348, fill=color, width=max(2, 5 - i // 6))
    # soft core glow behind the product
    glow = new_rgba(w, h)
    g = ImageDraw.Draw(glow)
    g.ellipse([w * 0.18, h * 0.22, w * 0.92, h * 0.92], fill=(40, 110, 190, 50))
    glow = glow.filter(ImageFilter.GaussianBlur(28))
    bg = Image.alpha_composite(bg, glow)
    bg = Image.alpha_composite(bg, overlay)
    return bg


def shadow_under(base, box, alpha=90):
    layer = new_rgba(*base.size)
    d = ImageDraw.Draw(layer)
    d.ellipse(box, fill=(0, 0, 0, alpha))
    layer = layer.filter(ImageFilter.GaussianBlur(18))
    base.alpha_composite(layer)


def brand_mark(draw, xy, scale=1):
    x, y = xy
    text(draw, (x, y), "手之劤", int(22 * scale), (230, 244, 255, 255))


def battery(draw, box, label="108V"):
    x0, y0, x1, y1 = box
    draw.rounded_rectangle(box, 10, fill=(48, 62, 78, 255))
    draw.rounded_rectangle((x0 + 6, y0 + 6, x1 - 6, y1 - 6), 8, fill=(28, 40, 56, 255))
    draw.rounded_rectangle((x0 + 4, y0 - 8, x0 + 22, y0 + 4), 3, fill=ORANGE)
    text(draw, ((x0 + x1) / 2, (y0 + y1) / 2), label, 22, (255, 168, 48, 255), latin=True, anchor="mm")


def paint_grinder(canvas):
    w, h = canvas.size
    s = w / 800
    d = ImageDraw.Draw(canvas)
    shadow_under(canvas, [int(90 * s), int(560 * s), int(700 * s), int(700 * s)])
    d = ImageDraw.Draw(canvas)

    def sc(x, y):
        return (int(x * s), int(y * s))

    d.pieslice([sc(30, 340), sc(290, 600)], 200, 30, fill=(176, 184, 192, 255))
    d.ellipse([sc(78, 400), sc(250, 572)], fill=(24, 26, 30, 255))
    d.ellipse([sc(108, 430), sc(220, 542)], fill=(86, 92, 100, 255))
    d.ellipse([sc(142, 464), sc(186, 508)], fill=(30, 32, 36, 255))
    d.rounded_rectangle([sc(190, 360), sc(340, 500)], 26, fill=(148, 156, 164, 255))
    d.rounded_rectangle([sc(250, 300), sc(600, 455)], 78, fill=BLUE)
    d.polygon([sc(290, 328), sc(540, 322), sc(500, 385), sc(290, 392)], fill=BLUE_LT)
    d.rounded_rectangle([sc(455, 322), sc(545, 392)], 14, fill=ORANGE)
    d.rounded_rectangle([sc(390, 420), sc(530, 575)], 40, fill=RUBBER)
    for i in range(5):
        y = 448 + i * 20
        d.line([sc(408, y), sc(512, y)], fill=(68, 72, 78, 255), width=max(2, int(4 * s)))
    battery(d, [int(500 * s), int(340 * s), int(690 * s), int(510 * s)])
    brand_mark(d, sc(320, 350), s)
    text(d, sc(48, 300), "Mod:SZR-108", int(40 * s), (255, 255, 255, 255), latin=True)


def paint_wrench(canvas, model, frontal=False):
    w, h = canvas.size
    s = w / 800
    d = ImageDraw.Draw(canvas)
    shadow_under(canvas, [int(180 * s), int(600 * s), int(680 * s), int(740 * s)])
    d = ImageDraw.Draw(canvas)

    def sc(x, y):
        return (int(x * s), int(y * s))

    # socket / anvil pointing up-left or up
    if frontal:
        d.rounded_rectangle([sc(310, 40), sc(500, 230)], 36, fill=(28, 30, 34, 255))
        d.ellipse([sc(338, 68), sc(472, 202)], outline=(170, 176, 184, 255), width=max(8, int(12 * s)))
        d.ellipse([sc(382, 112), sc(428, 158)], fill=(96, 102, 110, 255))
        body = [sc(230, 180), sc(580, 500)]
    else:
        d.rounded_rectangle([sc(300, 36), sc(470, 210)], 40, fill=(26, 28, 32, 255))
        d.ellipse([sc(328, 64), sc(442, 178)], outline=(176, 182, 190, 255), width=max(8, int(12 * s)))
        body = [sc(250, 150), sc(590, 470)]
    d.rounded_rectangle(body, int(78 * s), fill=BLUE)
    x0, y0 = body[0]
    x1, y1 = body[1]
    d.polygon([(x0 + int(30 * s), y0 + int(24 * s)), (x1 - int(80 * s), y0 + int(20 * s)),
               (x1 - int(120 * s), y0 + int(90 * s)), (x0 + int(40 * s), y0 + int(100 * s))], fill=BLUE_LT)
    # orange collar
    d.rounded_rectangle([x0 + int(20 * s), y0 + int(10 * s), x0 + int(150 * s), y0 + int(70 * s)], 16, fill=ORANGE)
    # grip
    gx0 = x0 + int(70 * s)
    d.rounded_rectangle([gx0, y1 - int(30 * s), x1 - int(40 * s), y1 + int(150 * s)], int(40 * s), fill=RUBBER)
    for i in range(4):
        yy = y1 + int((10 + i * 28) * s)
        d.line([(gx0 + int(16 * s), yy), (x1 - int(60 * s), yy)], fill=(64, 68, 74, 255), width=max(2, int(4 * s)))
    battery(d, [x0 + int(40 * s), y1 + int(90 * s), x1 - int(70 * s), y1 + int(230 * s)])
    brand_mark(d, (x0 + int(150 * s), y0 + int(80 * s)), s)
    text(d, sc(46, 340 if frontal else 300), f"Mod:{model}", int(40 * s), (255, 255, 255, 255), latin=True)
    if frontal:
        badge(canvas, sc(70, 520), "LED", (255, 186, 72, 255))
        badge(canvas, sc(190, 560), "散热", (255, 122, 48, 255))
        text(d, sc(70, 690), "前置LED照明", int(18 * s), (220, 230, 240, 255))
        text(d, sc(190, 730), "后置散热", int(18 * s), (220, 230, 240, 255))


def badge(canvas, center, label, color):
    layer = new_rgba(*canvas.size)
    d = ImageDraw.Draw(layer)
    x, y = center
    r = 46
    d.ellipse([x - r, y - r, x + r, y + r], fill=(8, 12, 20, 230), outline=color, width=4)
    text(d, (x, y), label, 20 if len(label) > 2 else 22, color, latin=label.isascii(), anchor="mm")
    canvas.alpha_composite(layer)


def paint_chainsaw(canvas):
    w, h = canvas.size
    s = w / 800
    d = ImageDraw.Draw(canvas)
    shadow_under(canvas, [int(80 * s), int(560 * s), int(720 * s), int(700 * s)])
    d = ImageDraw.Draw(canvas)

    def sc(x, y):
        return (int(x * s), int(y * s))

    # bar
    d.polygon([sc(40, 470), sc(430, 330), sc(470, 390), sc(80, 540)], fill=(196, 202, 208, 255))
    d.polygon([sc(70, 490), sc(410, 360), sc(430, 385), sc(95, 515)], fill=(150, 158, 166, 255))
    # chain teeth
    for i in range(12):
        t = i / 11
        x = 70 + t * 340
        y = 500 - t * 130
        d.polygon([sc(x, y), sc(x + 16, y - 16), sc(x + 28, y + 4)], fill=(230, 234, 238, 255))
    # body
    d.rounded_rectangle([sc(300, 250), sc(640, 470)], 40, fill=BLUE)
    d.polygon([sc(330, 275), sc(600, 275), sc(560, 340), sc(330, 350)], fill=BLUE_LT)
    d.rounded_rectangle([sc(470, 300), sc(600, 390)], 20, fill=(36, 42, 50, 255))
    # top handle
    d.arc([sc(340, 150), sc(620, 340)], 200, 10, fill=RUBBER, width=max(14, int(28 * s)))
    d.rounded_rectangle([sc(360, 360), sc(520, 520)], 30, fill=RUBBER)
    battery(d, [int(500 * s), int(400 * s), int(690 * s), int(560 * s)])
    brand_mark(d, sc(360, 310), s)
    text(d, sc(470, 600), "12寸无刷链条锯", int(28 * s), (255, 255, 255, 255), anchor="ra")
    text(d, sc(470, 642), "Mod:SZR-107", int(26 * s), (255, 255, 255, 255), latin=True, anchor="ra")


def paint_drill(canvas, model):
    w, h = canvas.size
    s = w / 800
    d = ImageDraw.Draw(canvas)
    shadow_under(canvas, [int(120 * s), int(560 * s), int(700 * s), int(720 * s)])
    d = ImageDraw.Draw(canvas)

    def sc(x, y):
        return (int(x * s), int(y * s))

    # bit
    d.polygon([sc(80, 300), sc(250, 250), sc(250, 330)], fill=(210, 216, 222, 255))
    d.line([sc(100, 292), sc(230, 270)], fill=(150, 156, 164, 255), width=3)
    d.rounded_rectangle([sc(220, 250), sc(340, 360)], 20, fill=(40, 44, 50, 255))
    d.rounded_rectangle([sc(300, 220), sc(620, 430)], 60, fill=BLUE)
    d.polygon([sc(340, 245), sc(580, 245), sc(540, 310), sc(340, 320)], fill=BLUE_LT)
    d.rounded_rectangle([sc(430, 250), sc(520, 320)], 14, fill=ORANGE)
    d.rounded_rectangle([sc(400, 400), sc(540, 560)], 36, fill=RUBBER)
    battery(d, [int(430 * s), int(500 * s), int(640 * s), int(670 * s)])
    brand_mark(d, sc(380, 300), s)
    text(d, sc(46, 180), f"Mod:{model}", int(40 * s), (255, 255, 255, 255), latin=True)


def paint_blower(canvas):
    w, h = canvas.size
    s = w / 800
    d = ImageDraw.Draw(canvas)
    shadow_under(canvas, [int(140 * s), int(580 * s), int(680 * s), int(730 * s)])
    d = ImageDraw.Draw(canvas)

    def sc(x, y):
        return (int(x * s), int(y * s))

    d.polygon([sc(40, 300), sc(280, 230), sc(300, 390), sc(60, 420)], fill=BLUE_LT)
    d.ellipse([sc(220, 180), sc(520, 480)], fill=BLUE)
    d.ellipse([sc(300, 260), sc(440, 400)], fill=(12, 28, 48, 255))
    d.ellipse([sc(340, 300), sc(400, 360)], fill=(180, 210, 240, 255))
    d.rounded_rectangle([sc(430, 300), sc(560, 560)], 40, fill=RUBBER)
    battery(d, [int(470 * s), int(470 * s), int(670 * s), int(650 * s)])
    brand_mark(d, sc(250, 210), s)
    text(d, sc(46, 140), "Mod:SZR-109", int(40 * s), (255, 255, 255, 255), latin=True)


def paint_hammer(canvas):
    w, h = canvas.size
    s = w / 800
    d = ImageDraw.Draw(canvas)
    shadow_under(canvas, [int(160 * s), int(600 * s), int(680 * s), int(740 * s)])
    d = ImageDraw.Draw(canvas)

    def sc(x, y):
        return (int(x * s), int(y * s))

    d.rounded_rectangle([sc(360, 60), sc(470, 240)], 18, fill=SILVER)
    d.rectangle([sc(395, 40), sc(435, 90)], fill=SILVER_DK)
    d.rounded_rectangle([sc(250, 190), sc(590, 470)], 50, fill=BLUE_DK)
    d.rounded_rectangle([sc(270, 210), sc(570, 360)], 40, fill=BLUE)
    d.rounded_rectangle([sc(300, 430), sc(470, 600)], 40, fill=RUBBER)
    d.rounded_rectangle([sc(180, 300), sc(270, 360)], 16, fill=ORANGE)
    battery(d, [int(300 * s), int(540 * s), int(520 * s), int(700 * s)])
    brand_mark(d, sc(330, 250), s)
    text(d, sc(46, 160), "Mod:SZR-110", int(40 * s), (255, 255, 255, 255), latin=True)


def paint_recip(canvas):
    w, h = canvas.size
    s = w / 800
    d = ImageDraw.Draw(canvas)
    shadow_under(canvas, [int(100 * s), int(560 * s), int(700 * s), int(720 * s)])
    d = ImageDraw.Draw(canvas)

    def sc(x, y):
        return (int(x * s), int(y * s))

    d.polygon([sc(40, 300), sc(260, 270), sc(250, 310), sc(40, 340)], fill=(220, 224, 228, 255))
    for i in range(8):
        x = 50 + i * 24
        d.polygon([sc(x, 338), sc(x + 10, 352), sc(x + 18, 338)], fill=(90, 96, 102, 255))
    d.rounded_rectangle([sc(220, 230), sc(620, 430)], 46, fill=BLUE)
    d.polygon([sc(260, 255), sc(560, 255), sc(520, 320), sc(260, 330)], fill=BLUE_LT)
    d.rounded_rectangle([sc(360, 400), sc(500, 560)], 34, fill=RUBBER)
    battery(d, [int(430 * s), int(470 * s), int(650 * s), int(650 * s)])
    brand_mark(d, sc(340, 280), s)
    text(d, sc(46, 150), "Mod:SZR-112", int(40 * s), (255, 255, 255, 255), latin=True)


def product_image(painter, name):
    img = streak_background(800, 800)
    painter(img)
    out = ROOT / "products" / name
    rgb = img.convert("RGB")
    rgb.save(out, "JPEG", quality=86, optimize=True)
    print(name, out.stat().st_size)


def _limb(draw, p1, p2, width, fill):
    draw.line([p1, p2], fill=fill, width=width)
    r = width / 2
    for p in (p1, p2):
        draw.ellipse([p[0] - r, p[1] - r, p[0] + r, p[1] + r], fill=fill)


def draw_plier(scale=1.0):
    w, h = int(860 * scale), int(340 * scale)
    im = new_rgba(w, h)
    d = ImageDraw.Draw(im)
    s = scale

    def P(x, y):
        return (x * s, y * s)

    _limb(d, P(70, 250), P(390, 175), int(58 * s), (62, 66, 72, 255))
    _limb(d, P(70, 78), P(390, 145), int(58 * s), (16, 16, 18, 255))
    for i in range(6):
        x = 90 + i * 42
        d.line([P(x, 110), P(x + 6, 230)], fill=(110, 114, 120, 160), width=max(2, int(3 * s)))
    _limb(d, P(400, 150), P(800, 46), int(50 * s), (206, 212, 218, 255))
    _limb(d, P(400, 178), P(790, 230), int(50 * s), (154, 162, 170, 255))
    d.polygon([P(470, 150), P(760, 78), P(748, 108), P(470, 168)], fill=(96, 104, 112, 255))
    for i in range(8):
        x = 500 + i * 32
        d.line([P(x, 92 + i * 3), P(x + 14, 132 + i * 2)], fill=(230, 234, 238, 255), width=max(2, int(3 * s)))
    d.line([P(430, 132), P(780, 58)], fill=(255, 255, 255, 180), width=max(2, int(4 * s)))
    d.ellipse([P(348, 108)[0], P(348, 108)[1], P(468, 228)[0], P(468, 228)[1]], fill=(72, 78, 86, 255))
    d.ellipse([P(378, 138)[0], P(378, 138)[1], P(438, 198)[0], P(438, 198)[1]], fill=(226, 230, 234, 255))
    d.ellipse([P(398, 158)[0], P(398, 158)[1], P(418, 178)[0], P(418, 178)[1]], fill=(36, 38, 42, 255))
    return im


def place_rotated(base, sprite, xy, angle):
    turned = sprite.rotate(angle, expand=True, resample=Image.Resampling.BICUBIC)
    x = xy[0] - turned.size[0] // 2
    y = xy[1] - turned.size[1] // 2
    base.alpha_composite(turned, (x, y))


def slogan_block(draw, origin, cell, gap):
    rows = [("精工制造", (False, False, True, True)), ("品质卓越", (False, False, True, True))]
    x0, y0 = origin
    for r, (chars, flags) in enumerate(rows):
        for c, ch in enumerate(chars):
            x = x0 + c * (cell + gap)
            y = y0 + r * (cell + gap)
            if flags[c]:
                draw.rectangle([x, y, x + cell, y + cell], fill=(10, 10, 10, 255))
                text(draw, (x + cell / 2, y + cell / 2 + 2), ch, int(cell * 0.72), (255, 255, 255, 255), anchor="mm")
            else:
                text(draw, (x + cell / 2, y + cell / 2 + 2), ch, int(cell * 0.72), (16, 16, 16, 255), anchor="mm")


def draw_tags(draw, tags, xy, height=46):
    x, y = xy
    for tag in tags:
        tw = int(font(24).getlength(tag)) + 36
        draw.rounded_rectangle([x, y, x + tw, y + height], height // 2, outline=(45, 45, 45, 255), width=2)
        text(draw, (x + tw / 2, y + height / 2), tag, 24, (32, 32, 32, 255), anchor="mm")
        x += tw + 14


def logo(draw, xy):
    x, y = xy
    text(draw, (x, y), "手之劤", 54, (20, 20, 20, 255), anchor="lt")
    seal = [x + 168, y + 4, x + 214, y + 50]
    draw.ellipse(seal, fill=(196, 32, 36, 255))
    text(draw, ((seal[0] + seal[2]) / 2, (seal[1] + seal[3]) / 2), "品", 22, (255, 255, 255, 255), anchor="mm")
    text(draw, (x + 224, y + 6), "HAND", 18, (30, 30, 30, 255), latin=True)
    text(draw, (x + 224, y + 28), "KANO", 16, (90, 90, 90, 255), latin=True)


def banner_craft():
    w, h = 1500, 640
    img = new_rgba(w, h, (214, 216, 219, 255))
    grad = Image.new("RGB", (1, h))
    gp = grad.load()
    for y in range(h):
        t = y / (h - 1)
        v = int(186 + 28 * (1 - abs(t - 0.45)))
        gp[0, y] = (v - 6, v - 4, v)
    img.alpha_composite(grad.resize((w, h)).convert("RGBA"))
    plier = draw_plier(0.78)
    place_rotated(img, plier, (230, 210), -14)
    place_rotated(img, plier, (390, 250), -4)
    place_rotated(img, plier, (560, 275), 7)
    # white curve panel
    panel = new_rgba(w, h)
    pd = ImageDraw.Draw(panel)
    pts = [(w, 0), (860, 0)]
    for i in range(33):
        t = i / 32
        x = 820 + math.sin(t * math.pi) * 70 - t * 40
        y = t * h
        pts.append((x, y))
    pts += [(w, h)]
    pd.polygon(pts, fill=(255, 255, 255, 255))
    img.alpha_composite(panel)
    d = ImageDraw.Draw(img)
    logo(d, (1040, 36))
    slogan_block(d, (980, 150), 108, 8)
    draw_tags(d, ["合金钢材", "坚韧耐用", "工业级别", "高温淬火"], (48, 560), 48)
    return img.convert("RGB")


def banner_campaign(title, subtitle, painter, tags, dark=True):
    w, h = 1500, 640
    if dark:
        img = streak_background(w, h)
    else:
        img = new_rgba(w, h, (244, 245, 247, 255))
    tool = new_rgba(800, 800)
    if not dark:
        # give the painter a transparent canvas; it paints its own shapes assuming streak bg
        pass
    painter(tool if dark else streak_background(800, 800))
    if not dark:
        tool = streak_background(800, 800)
        painter(tool)
    tool.thumbnail((560, 560), Image.Resampling.LANCZOS)
    img.alpha_composite(tool, (40, 40))
    d = ImageDraw.Draw(img)
    if dark:
        text(d, (700, 150), title, 84, (255, 255, 255, 255))
        text(d, (700, 270), subtitle, 36, (190, 220, 245, 255))
        x = 700
        y = 470
        for tag in tags:
            tw = int(font(26).getlength(tag)) + 40
            d.rounded_rectangle([x, y, x + tw, y + 52], 26, outline=(220, 235, 255, 255), width=2)
            text(d, (x + tw / 2, y + 26), tag, 26, (240, 246, 255, 255), anchor="mm")
            x += tw + 16
    else:
        text(d, (680, 180), title, 80, (20, 20, 20, 255))
        text(d, (680, 290), subtitle, 34, (80, 80, 80, 255))
        draw_tags(d, tags, (680, 500), 48)
    logo_layer = new_rgba(w, h)
    logo(ImageDraw.Draw(logo_layer), (1120, 36))
    if dark:
        # redraw logo in white-ish by a simple second pass
        ld = ImageDraw.Draw(img)
        text(ld, (1120, 36), "手之劤", 48, (255, 255, 255, 255))
        ld.ellipse([1290, 40, 1334, 84], fill=(196, 32, 36, 255))
        text(ld, (1312, 62), "品", 20, (255, 255, 255, 255), anchor="mm")
    else:
        img.alpha_composite(logo_layer)
    return img.convert("RGB")


def line_icon(draw_fn, color, name):
    size = 324
    im = new_rgba(size, size)
    d = ImageDraw.Draw(im)
    draw_fn(d, size, color)
    out = im.resize((81, 81), Image.Resampling.LANCZOS)
    path = ROOT / "tab" / name
    out.save(path, "PNG")
    print(name, path.stat().st_size)


def icon_home(d, s, color):
    w = 18
    d.line([(s * 0.5, s * 0.16), (s * 0.18, s * 0.42), (s * 0.82, s * 0.42), (s * 0.5, s * 0.16)], fill=color, width=w, joint="curve")
    d.rounded_rectangle([s * 0.26, s * 0.40, s * 0.74, s * 0.82], 8, outline=color, width=w)
    d.rounded_rectangle([s * 0.42, s * 0.56, s * 0.58, s * 0.82], 6, outline=color, width=w)


def icon_grid(d, s, color):
    w = 16
    boxes = [(0.18, 0.18), (0.54, 0.18), (0.18, 0.54), (0.54, 0.54)]
    for x, y in boxes:
        d.rounded_rectangle([s * x, s * y, s * (x + 0.28), s * (y + 0.28)], 18, outline=color, width=w)


def icon_cart(d, s, color):
    w = 16
    d.line([(s * 0.22, s * 0.24), (s * 0.34, s * 0.24), (s * 0.42, s * 0.58), (s * 0.74, s * 0.58)], fill=color, width=w)
    d.line([(s * 0.36, s * 0.34), (s * 0.78, s * 0.34), (s * 0.70, s * 0.58)], fill=color, width=w)
    d.ellipse([s * 0.40, s * 0.66, s * 0.52, s * 0.78], outline=color, width=w)
    d.ellipse([s * 0.62, s * 0.66, s * 0.74, s * 0.78], outline=color, width=w)


def icon_user(d, s, color):
    w = 16
    d.ellipse([s * 0.34, s * 0.14, s * 0.66, s * 0.46], outline=color, width=w)
    d.arc([s * 0.18, s * 0.48, s * 0.82, s * 0.98], 200, 340, fill=color, width=w)


def glyph(draw_fn, name, size=96):
    im = new_rgba(size, size)
    d = ImageDraw.Draw(im)
    icon_phone.sprite = None
    draw_fn(d, size, (255, 255, 255, 255))
    if getattr(icon_phone, "sprite", None) is not None and draw_fn is icon_phone:
        sprite = icon_phone.sprite
        x = (size - sprite.size[0]) // 2
        y = (size - sprite.size[1]) // 2
        im.alpha_composite(sprite, (x, y))
    path = ROOT / "icons" / name
    im.resize((96, 96), Image.Resampling.LANCZOS).save(path, "PNG")


def icon_phone(d, s, color):
    sprite = new_rgba(int(s), int(s))
    sd = ImageDraw.Draw(sprite)
    sd.rounded_rectangle([s * 0.28, s * 0.08, s * 0.72, s * 0.28], int(s * 0.08), fill=color)
    sd.rounded_rectangle([s * 0.42, s * 0.20, s * 0.58, s * 0.80], int(s * 0.06), fill=color)
    sd.rounded_rectangle([s * 0.28, s * 0.72, s * 0.72, s * 0.92], int(s * 0.08), fill=color)
    turned = sprite.rotate(-42, resample=Image.Resampling.BICUBIC, expand=False)
    # ImageDraw has no alpha paste; stash the sprite for glyph().
    icon_phone.sprite = turned


def icon_headset(d, s, color):
    w = 10
    d.arc([s * 0.18, s * 0.16, s * 0.82, s * 0.78], 200, 340, fill=color, width=w)
    d.rounded_rectangle([s * 0.12, s * 0.46, s * 0.30, s * 0.74], 10, fill=color)
    d.rounded_rectangle([s * 0.70, s * 0.46, s * 0.88, s * 0.74], 10, fill=color)
    d.line([(s * 0.78, s * 0.70), (s * 0.78, s * 0.84), (s * 0.62, s * 0.84)], fill=color, width=w)


def icon_search(d, s, color):
    w = 12
    d.ellipse([s * 0.12, s * 0.12, s * 0.68, s * 0.68], outline=color, width=w)
    d.line([(s * 0.60, s * 0.60), (s * 0.86, s * 0.86)], fill=color, width=w)


def main():
    for folder in ("banners", "products", "tab", "icons"):
        (ROOT / folder).mkdir(parents=True, exist_ok=True)

    banners = {
        "craft.jpg": banner_craft(),
        "series.jpg": banner_campaign(
            "108V无刷系列",
            "角磨 · 扳手 · 链条锯",
            paint_grinder,
            ["无刷电机", "长续航", "工业扭矩", "锂电平台"],
        ),
        "torque.jpg": banner_campaign(
            "大扭矩输出",
            "无刷扳手 紧固更稳",
            lambda c: paint_wrench(c, "SZR-106"),
            ["合金钢材", "坚韧耐用", "工业级别"],
        ),
        "cut.jpg": banner_campaign(
            "12寸切割",
            "无刷链条锯 进给顺畅",
            paint_chainsaw,
            ["高温淬火", "锋利耐用", "108V"],
        ),
    }
    for name, img in banners.items():
        path = ROOT / "banners" / name
        img.save(path, "JPEG", quality=86, optimize=True)
        print(name, path.stat().st_size)

    product_image(paint_grinder, "szr-108.jpg")
    product_image(lambda c: paint_wrench(c, "SZR-106"), "szr-106.jpg")
    product_image(paint_chainsaw, "szr-107.jpg")
    product_image(lambda c: paint_wrench(c, "SZR-1033", frontal=True), "szr-1033.jpg")
    product_image(lambda c: paint_drill(c, "SZR-105"), "szr-105.jpg")
    product_image(lambda c: paint_drill(c, "SZR-104"), "szr-104.jpg")
    product_image(paint_blower, "szr-109.jpg")
    product_image(paint_hammer, "szr-110.jpg")
    product_image(paint_recip, "szr-112.jpg")

    gray = (196, 196, 196, 255)
    blue = (74, 158, 237, 255)
    line_icon(icon_home, gray, "home.png")
    line_icon(icon_home, blue, "home-active.png")
    line_icon(icon_grid, gray, "category.png")
    line_icon(icon_grid, blue, "category-active.png")
    line_icon(icon_cart, gray, "order.png")
    line_icon(icon_cart, blue, "order-active.png")
    line_icon(icon_user, gray, "mine.png")
    line_icon(icon_user, blue, "mine-active.png")

    glyph(icon_phone, "phone.png")
    try:
        import cairosvg

        cairosvg.svg2png(
            bytestring=(
                '<svg xmlns="http://www.w3.org/2000/svg" width="96" height="96" viewBox="0 0 24 24">'
                '<path fill="#ffffff" d="M6.62 10.79c1.44 2.83 3.76 5.14 6.59 6.59l2.2-2.2c.27-.27.67-.36 1.02-.24 1.12.37 2.33.57 3.57.57.55 0 1 .45 1 1V20c0 .55-.45 1-1 1-9.39 0-17-7.61-17-17 0-.55.45-1 1-1h3.5c.55 0 1 .45 1 1 0 1.25.2 2.45.57 3.57.11.35.03.74-.25 1.02l-2.2 2.2z"/>'
                "</svg>"
            ).encode(),
            write_to=str(ROOT / "icons" / "phone.png"),
        )
    except Exception as exc:
        print("phone icon fallback", exc)
    glyph(icon_headset, "service.png")
    service = Image.open(ROOT / "icons" / "service.png").convert("RGBA")
    pixels = service.load()
    for y in range(service.height):
        for x in range(service.width):
            red, green, blue, alpha = pixels[x, y]
            if alpha:
                pixels[x, y] = (90, 90, 90, alpha)
    service.save(ROOT / "icons" / "service-dark.png")
    # search icon is gray, draw directly
    im = new_rgba(96, 96)
    icon_search(ImageDraw.Draw(im), 96, (180, 180, 180, 255))
    (ROOT / "icons" / "search.png").write_bytes(b"") if False else im.save(ROOT / "icons" / "search.png", "PNG")
    glyph(icon_cart, "cart.png")
    print("done", ROOT)


if __name__ == "__main__":
    main()
