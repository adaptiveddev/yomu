#!/usr/bin/env python3
"""Six launcher icon concepts for Yomu as adaptive-icon-ready SVGs (108x108 canvas: full-bleed background group,
artwork inside the 66-unit safe circle). Glyphs are converted to outlines, so the files need no fonts.

    python3 make_icons.py   # writes NN-name.svg next to this script
    python3 svg2vd.py ../../app/src/main/res   # turns them into the app's launcher icons
"""
import math
import random
from pathlib import Path

from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTCollection, TTFont

OUT = Path(__file__).resolve().parent
FONTS = Path("/usr/share/fonts/google-noto-cjk")
C = 54.0  # canvas centre


def f(v):
    return f"{v:.2f}".rstrip("0").rstrip(".")


def pts(points):
    return " ".join(f"{f(x)},{f(y)}" for x, y in points)


def svg(name, title, defs, background, foreground, mono):
    """[mono] is the themed-icon layer (Android 13+): white shapes whose alpha is all the launcher keeps."""
    body = f"""<svg xmlns="http://www.w3.org/2000/svg" width="432" height="432" viewBox="0 0 108 108">
  <title>Yomu — {title}</title>
  <defs>{defs}</defs>
  <g id="background">{background}</g>
  <g id="foreground">{foreground}</g>
  <g id="monochrome" display="none">{mono}</g>
</svg>
"""
    (OUT / name).write_text(body)


def font(path, family=None):
    if path.suffix == ".ttc":
        for fnt in TTCollection(str(path)).fonts:
            if family in fnt["name"].getDebugName(1):
                return fnt
        raise SystemExit(f"{family} not in {path}")
    return TTFont(str(path))


def glyph_path(fnt, text, box_x, box_y, box_w, box_h, tracking=0.0):
    """Outline of [text] fitted (keeping proportions) and centred in the box; returns the SVG path data."""
    cmap = fnt.getBestCmap()
    gs = fnt.getGlyphSet()
    advance = 0.0
    parts = []
    for ch in text:
        g = cmap[ord(ch)]
        parts.append((g, advance))
        advance += gs[g].width + tracking
    advance -= tracking
    bp = BoundsPen(gs)
    for g, x in parts:
        gs[g].draw(TransformPen(bp, (1, 0, 0, 1, x, 0)))
    x0, y0, x1, y1 = bp.bounds
    s = min(box_w / (x1 - x0), box_h / (y1 - y0))
    ox = box_x + (box_w - (x1 - x0) * s) / 2 - x0 * s
    oy = box_y + (box_h - (y1 - y0) * s) / 2 + y1 * s
    sp = SVGPathPen(gs, ntos=f)
    for g, x in parts:
        gs[g].draw(TransformPen(sp, (s, 0, 0, -s, ox + x * s, oy)))
    return sp.getCommands()


# 1 ── Spark: an organic, hand-cut asterisk on terracotta (after Claude's mark), rays of uneven length and width.
def spark():
    rnd = random.Random(7)
    rays = []
    n = 11
    for k in range(n):
        a = math.radians(-90 + k * 360 / n + rnd.uniform(-6, 6))
        length = rnd.uniform(24.5, 31.0)
        w0 = rnd.uniform(1.7, 2.3)       # half-width at the centre
        w1 = rnd.uniform(2.9, 3.8)       # half-width at the tip: the rays widen outwards
        slant = rnd.uniform(-1.8, 1.8)   # the tip is cut at an angle
        bev = 0.75
        local = [
            (2.0, -w0), (length - slant - bev, -w1), (length - slant, -w1 + bev),
            (length + slant, w1 - bev), (length + slant - bev, w1), (2.0, w0),
        ]
        ca, sa = math.cos(a), math.sin(a)
        rays.append([(C + u * ca - v * sa, C + u * sa + v * ca) for u, v in local])
    hub = []
    for k in range(13):
        a = math.radians(k * 360 / 13 + rnd.uniform(-8, 8))
        r = rnd.uniform(6.0, 7.2)
        hub.append((C + r * math.cos(a), C + r * math.sin(a)))
    cream = "#FBF3EE"
    fg = "".join(f'<polygon points="{pts(r)}" fill="{cream}" stroke="{cream}" stroke-width="0.6" stroke-linejoin="round"/>' for r in rays)
    fg += f'<polygon points="{pts(hub)}" fill="{cream}"/>'
    svg("01-spark.svg", "Spark", "", '<rect width="108" height="108" fill="#D97757"/>', fg, fg.replace(cream, "#FFFFFF"))


# 2 ── Ribbon: a bookmark folded into a "Y" in VS Code blues; the left arm tucks under the fold.
def ribbon():
    defs = """
    <linearGradient id="bg" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#15283F"/><stop offset="1" stop-color="#0A1422"/></linearGradient>
    <linearGradient id="arm" x1="1" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#4CB8FF"/><stop offset="1" stop-color="#1F94EC"/></linearGradient>
    <linearGradient id="stem" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#0B63B5"/><stop offset="0.35" stop-color="#1180D8"/><stop offset="1" stop-color="#0F78CF"/></linearGradient>
    <linearGradient id="back" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#1E86D6"/><stop offset="0.7" stop-color="#0B5AA6"/><stop offset="1" stop-color="#06396F"/></linearGradient>
    """
    t = 6.4
    jx, jy = 54.0, 58.0

    def band(ex, ey, back=0.0):
        dx, dy = ex - jx, ey - jy
        ln = math.hypot(dx, dy)
        ux, uy = dx / ln, dy / ln
        px, py = -uy * t, ux * t
        sx, sy = jx - ux * back, jy - uy * back
        return (ux, uy, px, py), [(sx + px, sy + py), (ex + px, ey + py), (ex - px, ey - py), (sx - px, sy - py)]

    # Right arm: where its two edges meet the stem's sides is the fold line.
    (ux, uy, px, py), _ = band(77.5, 27.5)
    xl, xr = jx - t, jx + t

    def meet(ox, oy, x):  # point of the line (o + s*u) at the given x
        k = (x - ox) / ux
        return x, oy + uy * k

    a = meet(jx - px, jy - py, xl)   # upper edge x stem's left side
    b = meet(jx + px, jy + py, xr)   # lower edge x stem's right side
    end_a = (77.5 - px, 27.5 - py)
    end_b = (77.5 + px, 27.5 + py)
    arm = [a, end_a, end_b, b]
    stem = [a, b, (xr, 85), (jx, 79.5), (xl, 85)]
    _, left = band(30.5, 27.5, back=-2)
    # The left arm darkens where it slides under the front ribbon.
    fit = '<g transform="translate(54 54) scale(0.8) translate(-54 -54.5)">'
    fg = (
        fit
        + f'<polygon points="{pts(left)}" fill="url(#back)"/>'
        f'<polygon points="{pts(stem)}" fill="url(#stem)"/>'
        f'<polygon points="{pts(arm)}" fill="url(#arm)"/>'
        "</g>"
    )
    mono = (
        fit
        + f'<polygon points="{pts(left)}" fill="#FFFFFF" opacity="0.55"/>'
        f'<polygon points="{pts(stem)}" fill="#FFFFFF"/>'
        f'<polygon points="{pts(arm)}" fill="#FFFFFF"/>'
        "</g>"
    )
    svg("02-ribbon.svg", "Ribbon", defs, '<rect width="108" height="108" fill="url(#bg)"/>', fg, mono)


# 3 ── Fox: a flame-gradient fox curled round a violet globe that holds an open book (after Firefox).
def fox():
    defs = """
    <linearGradient id="bg" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#241A4D"/><stop offset="1" stop-color="#130E2C"/></linearGradient>
    <radialGradient id="globe" cx="0.62" cy="0.38" r="0.8"><stop offset="0" stop-color="#B585FF"/><stop offset="0.55" stop-color="#7542E5"/><stop offset="1" stop-color="#3D1896"/></radialGradient>
    <linearGradient id="flame" x1="0.75" y1="0" x2="0.3" y2="1">
      <stop offset="0" stop-color="#FFE84D"/><stop offset="0.25" stop-color="#FFB02B"/><stop offset="0.5" stop-color="#FF7028"/>
      <stop offset="0.75" stop-color="#FF3858"/><stop offset="1" stop-color="#DD1A7F"/>
    </linearGradient>
    <linearGradient id="tail" x1="0" y1="1" x2="0.2" y2="0"><stop offset="0" stop-color="#FF5A3A" stop-opacity="0"/><stop offset="1" stop-color="#FFD43F"/></linearGradient>
    <clipPath id="globeClip"><circle cx="58" cy="60" r="18"/></clipPath>
    """
    fox_path = (
        "M30,54 C26,40 31,30 39,25 L42,13 L50,23.5 C59,21.5 69,26 75.5,33 L81,37.5 "
        "C73,39.5 65,40 58.5,42 C49.5,44.5 43,50.5 41,58.5 C39.5,70 46.5,80.5 58.5,80.5 "
        "C68.5,80.5 76,74.5 79.5,66.5 C81,61 83.5,55.5 88.5,50.5 C89,63 85,76 75,85 "
        "C64,93 46,93.5 36,84.5 C29,78 27,66 30,54 Z"
    )
    # The tail's tip lights up towards yellow, like the flame of the original.
    tail_tip = "M79.5,66.5 C81,61 83.5,55.5 88.5,50.5 C89,58 87.5,66 84,73 C82,70.5 80.5,68.5 79.5,66.5 Z"
    book = (
        '<path d="M60,53 C55.5,50 50.5,49.6 46.5,50.8 L46.5,63.5 C50.5,62.3 55.5,62.7 60,65.7 Z" fill="#E6D8FF"/>'
        '<path d="M60,53 C64.5,50 69.5,49.6 73.5,50.8 L73.5,63.5 C69.5,62.3 64.5,62.7 60,65.7 Z" fill="#CDB8FF"/>'
        '<path d="M49.5,55 L56.5,56.3 M49.5,59 L56.5,60.3 M63.5,56.3 L70.5,55 M63.5,60.3 L70.5,59" stroke="#6B3DD8" stroke-width="1.2" stroke-linecap="round" fill="none"/>'
    )
    fg = (
        '<g transform="translate(54 54) scale(0.75) translate(-58 -53.5)">'
        '<circle cx="58" cy="60" r="18" fill="url(#globe)"/>'
        f'<g clip-path="url(#globeClip)">{book}</g>'
        f'<path d="{fox_path}" fill="url(#flame)"/>'
        f'<path d="{tail_tip}" fill="url(#tail)" opacity="0.85"/>'
        "</g>"
    )
    pages = (
        '<path d="M60,53 C55.5,50 50.5,49.6 46.5,50.8 L46.5,63.5 C50.5,62.3 55.5,62.7 60,65.7 Z" fill="#FFFFFF"/>'
        '<path d="M60,53 C64.5,50 69.5,49.6 73.5,50.8 L73.5,63.5 C69.5,62.3 64.5,62.7 60,65.7 Z" fill="#FFFFFF" opacity="0.8"/>'
    )
    mono = (
        '<g transform="translate(54 54) scale(0.75) translate(-58 -53.5)">'
        '<circle cx="58" cy="60" r="18" fill="#FFFFFF" opacity="0.4"/>'
        f'{pages}'
        f'<path d="{fox_path}" fill="#FFFFFF"/>'
        "</g>"
    )
    svg("03-fox.svg", "Fox", defs, '<rect width="108" height="108" fill="url(#bg)"/>', fg, mono)


# 4 ── Hanko: 読 ("to read") cut into a vermilion seal, stamped slightly askew on washi paper.
def hanko():
    rnd = random.Random(3)
    serif = font(FONTS / "NotoSerifCJK-Black.ttc", "Noto Serif CJK JP")
    x0, y0, s, r = 30.0, 30.0, 48.0, 8.0

    def rounded_square(x0, y0, s, r, jitter, step=1.6):
        out = []
        corners = [(x0 + s - r, y0 + r, -90), (x0 + s - r, y0 + s - r, 0), (x0 + r, y0 + s - r, 90), (x0 + r, y0 + r, 180)]
        edges = [((x0 + r, y0), (x0 + s - r, y0)), ((x0 + s, y0 + r), (x0 + s, y0 + s - r)),
                 ((x0 + s - r, y0 + s), (x0 + r, y0 + s)), ((x0, y0 + s - r), (x0, y0 + r))]
        for (cx, cy, a0), (ea, eb) in zip(corners, edges[1:] + edges[:1]):
            pass
        # walk: top edge, top-right corner, right edge, ...
        seq = []
        for i in range(4):
            (ax, ay), (bx, by) = edges[i]
            n = max(2, int(math.hypot(bx - ax, by - ay) / step))
            seq += [(ax + (bx - ax) * k / n, ay + (by - ay) * k / n) for k in range(n)]
            cx, cy, a0 = corners[i]
            for k in range(6):
                a = math.radians(a0 + 90 * k / 6)
                seq.append((cx + r * math.cos(a), cy + r * math.sin(a)))
        for x, y in seq:
            out.append((x + rnd.uniform(-jitter, jitter), y + rnd.uniform(-jitter, jitter)))
        return out

    seal = rounded_square(x0, y0, s, r, 0.32)
    frame = rounded_square(x0 + 3.2, y0 + 3.2, s - 6.4, r - 2.8, 0.1)
    inner = rounded_square(x0 + 4.9, y0 + 4.9, s - 9.8, r - 4, 0.1)
    glyph = glyph_path(serif, "読", x0 + 8.6, y0 + 8.6, s - 17.2, s - 17.2)
    specks = "".join(
        f'<circle cx="{f(rnd.uniform(x0 + 2, x0 + s - 2))}" cy="{f(rnd.uniform(y0 + 2, y0 + s - 2))}" r="{f(rnd.uniform(0.18, 0.5))}"/>'
        for _ in range(26)
    )
    paper = "#F4ECDD"
    fg = (
        f'<g transform="rotate(-4 54 54)">'
        f'<polygon points="{pts(seal)}" fill="#C8372D"/>'
        # thin paper-coloured frame inside the edge, as carved seals have
        f'<path d="M{pts(frame)} Z M{pts(inner[::-1])} Z" fill="{paper}" fill-rule="evenodd" opacity="0.95"/>'
        f'<path d="{glyph}" fill="{paper}"/>'
        f'<g fill="{paper}" opacity="0.55">{specks}</g>'
        f"</g>"
    )
    mono = (
        f'<g transform="rotate(-4 54 54)">'
        f'<path d="M{pts(seal)} Z M{pts(frame[::-1])} Z" fill="#FFFFFF" fill-rule="evenodd"/>'
        f'<path d="{glyph}" fill="#FFFFFF"/>'
        f"</g>"
    )
    svg("04-hanko.svg", "Hanko", "", f'<rect width="108" height="108" fill="{paper}"/>', fg, mono)


# 5 ── Eye-book: an open book whose pages form an eye; the iris sits on the spine.
def eye_book():
    defs = """
    <linearGradient id="bg" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#5360D6"/><stop offset="1" stop-color="#2A2D8C"/></linearGradient>
    <radialGradient id="iris" cx="0.4" cy="0.35" r="0.75"><stop offset="0" stop-color="#E4D9FF"/><stop offset="0.55" stop-color="#A994F2"/><stop offset="1" stop-color="#6E58D8"/></radialGradient>
    <clipPath id="eye"><path d="M19,55 C30,40 44,36.5 54,36.5 C64,36.5 78,40 89,55 C78,68 64,72.5 54,72.5 C44,72.5 30,68 19,55 Z"/></clipPath>
    """
    eye = "M19,55 C30,40 44,36.5 54,36.5 C64,36.5 78,40 89,55 C78,68 64,72.5 54,72.5 C44,72.5 30,68 19,55 Z"
    fg = (
        '<g transform="translate(54 54) scale(0.92) translate(-54 -56)">'
        # page edges under the lower lid: the thickness of the book
        '<path d="M22,58.5 C33,70 45,75.5 54,75.5 C63,75.5 75,70 86,58.5" stroke="#C9CCFF" stroke-width="1.6" fill="none" stroke-linecap="round" opacity="0.8"/>'
        '<path d="M25.5,62 C35.5,72.5 46,78.5 54,78.5 C62,78.5 72.5,72.5 82.5,62" stroke="#A9AEFA" stroke-width="1.6" fill="none" stroke-linecap="round" opacity="0.65"/>'
        f'<g clip-path="url(#eye)">'
        '<rect x="0" y="0" width="54" height="108" fill="#FFFFFF"/>'
        '<rect x="54" y="0" width="54" height="108" fill="#E3E6FF"/>'
        '<path d="M27,50 L39,46.5 M25,56 L39,53.5 M27.5,62 L39,60.5 M69,46.5 L81,50 M69,53.5 L83,56 M69,60.5 L80.5,62" stroke="#3F4AB8" stroke-width="1.7" stroke-linecap="round" fill="none"/>'
        '<path d="M54,30 L54,80" stroke="#B9BEF5" stroke-width="1"/>'
        "</g>"
        f'<path d="{eye}" fill="none" stroke="#1E2170" stroke-width="2.2" stroke-linejoin="round"/>'
        '<circle cx="54" cy="54.5" r="12" fill="url(#iris)" stroke="#1E2170" stroke-width="2.2"/>'
        '<circle cx="54" cy="54.5" r="5" fill="#1B1D5C"/>'
        '<circle cx="50.2" cy="50.6" r="2.3" fill="#FFFFFF"/>'
        "</g>"
    )
    mono = (
        '<g transform="translate(54 54) scale(0.92) translate(-54 -56)">'
        '<path d="M22,58.5 C33,70 45,75.5 54,75.5 C63,75.5 75,70 86,58.5" stroke="#FFFFFF" stroke-width="1.6" fill="none" stroke-linecap="round" opacity="0.6"/>'
        '<path d="M27,50 L39,46.5 M25,56 L39,53.5 M27.5,62 L39,60.5 M69,46.5 L81,50 M69,53.5 L83,56 M69,60.5 L80.5,62" stroke="#FFFFFF" stroke-width="1.7" stroke-linecap="round" fill="none" opacity="0.6"/>'
        f'<path d="{eye}" fill="none" stroke="#FFFFFF" stroke-width="2.6" stroke-linejoin="round"/>'
        '<path d="M42,54.5 A12,12 0 1 0 66,54.5 A12,12 0 1 0 42,54.5 Z M49,54.5 A5,5 0 1 1 59,54.5 A5,5 0 1 1 49,54.5 Z" fill="#FFFFFF" fill-rule="evenodd"/>'
        "</g>"
    )
    svg("05-eye-book.svg", "Eye-book", defs, '<rect width="108" height="108" fill="url(#bg)"/>', fg, mono)


# 6 ── Manga: a speech bubble saying よむ ("read!") over a halftone screentone, inked like a manga panel.
def manga():
    sans = font(FONTS / "NotoSansJP-Black.otf")
    cx, cy, rx, ry = 57.0, 50.5, 27.0, 21.0

    def ep(deg):
        a = math.radians(deg)
        return cx + rx * math.cos(a), cy + ry * math.sin(a)

    p1, p2 = ep(118), ep(146)
    bubble = (
        f"M{f(p2[0])},{f(p2[1])} A{rx},{ry} 0 1 1 {f(p1[0])},{f(p1[1])} "
        f"Q44,74.5 34,80 Q40.5,71.5 {f(p2[0])},{f(p2[1])} Z"
    )
    text = glyph_path(sans, "よむ", cx - 17.5, cy - 9.5, 35, 19, tracking=40)
    # Screentone: dots on a 45-degree grid, larger towards the bottom-left.
    dots = []
    step = 4.2
    for i in range(-30, 30):
        for j in range(-30, 30):
            x = C + (i - j) * step / math.sqrt(2) * 1.0
            y = C + (i + j) * step / math.sqrt(2) * 1.0
            if not (-2 <= x <= 110 and -2 <= y <= 110):
                continue
            t = ((108 - x) + y) / 216  # 0 top-right .. 1 bottom-left
            r = 0.35 + 1.05 * t
            dots.append(f'<circle cx="{f(x)}" cy="{f(y)}" r="{f(r)}"/>')
    bg = '<rect width="108" height="108" fill="#FF6B8B"/>' + f'<g fill="#E8436B">{"".join(dots)}</g>'
    # speed lines behind the bubble, from the top-right
    lines = "".join(
        f'<path d="M{f(108 - k * 9)},{f(-2)} L{f(80 - k * 7.5)},{f(26 + k * 2)}" stroke="#FFFFFF" stroke-width="1.4" stroke-linecap="round" opacity="0.5"/>'
        for k in range(4)
    )
    fit = '<g transform="translate(54 54) scale(0.92) translate(-55.5 -53)">'
    fg = (
        lines
        + fit
        + f'<path d="{bubble}" fill="#000" transform="translate(2.2 2.6)" opacity="0.28"/>'
        + f'<path d="{bubble}" fill="#FFFFFF" stroke="#16121A" stroke-width="2.8" stroke-linejoin="round"/>'
        + f'<path d="{text}" fill="#16121A"/>'
        + "</g>"
    )
    mono = (
        fit
        + f'<path d="{bubble}" fill="none" stroke="#FFFFFF" stroke-width="3" stroke-linejoin="round"/>'
        + f'<path d="{text}" fill="#FFFFFF"/>'
        + "</g>"
    )
    svg("06-manga.svg", "Manga", "", bg, fg, mono)


if __name__ == "__main__":
    spark()
    ribbon()
    fox()
    hanko()
    eye_book()
    manga()
    print("written:", ", ".join(sorted(p.name for p in OUT.glob("0*.svg"))))
