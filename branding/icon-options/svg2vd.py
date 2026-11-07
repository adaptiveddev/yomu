#!/usr/bin/env python3
"""Turns the icon concepts written by make_icons.py into Android launcher icons:

    res/drawable/ic_launcher_<key>_{background,foreground,monochrome}.xml   vector drawables (108 x 108 dp)
    res/mipmap-anydpi/ic_launcher_<key>.xml                                 the adaptive icon

    python3 svg2vd.py ../../app/src/main/res

Handles the SVG subset make_icons.py uses: rect, circle, ellipse, polygon, polyline, line and path (every command),
groups with transform / opacity / fill / clip-path, and linear or radial gradients. Transforms are baked into the
coordinates (arcs survive translate, rotate and uniform scale), group opacity is pushed down into fillAlpha and
strokeAlpha, and runs of plain filled shapes (the halftone dots) are merged into one path.
"""
import math
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

HERE = Path(__file__).resolve().parent
NS = "{http://www.w3.org/2000/svg}"
KEYS = {"01-spark": "spark", "02-ribbon": "ribbon", "03-fox": "fox", "04-hanko": "hanko", "05-eye-book": "gaze", "06-manga": "manga"}
INHERITED = ("fill", "stroke", "stroke-width", "stroke-linecap", "stroke-linejoin", "fill-rule", "fill-opacity", "stroke-opacity")

# ── affine matrices (a, b, c, d, e, f): x' = a*x + c*y + e, y' = b*x + d*y + f
ID = (1.0, 0.0, 0.0, 1.0, 0.0, 0.0)


def mul(m, n):
    """m after n."""
    a, b, c, d, e, f = m
    p, q, r, s, t, u = n
    return (a * p + c * q, b * p + d * q, a * r + c * s, b * r + d * s, a * t + c * u + e, b * t + d * u + f)


def ap(m, x, y):
    return m[0] * x + m[2] * y + m[4], m[1] * x + m[3] * y + m[5]


def similarity(m):
    """(scale, rotation in degrees) when m is translate/rotate/uniform scale, else None."""
    a, b, c, d = m[:4]
    if abs(a - d) < 1e-9 and abs(b + c) < 1e-9:
        return math.hypot(a, b), math.degrees(math.atan2(b, a))
    return None


def parse_transform(s):
    m = ID
    for name, args in re.findall(r"(\w+)\s*\(([^)]*)\)", s or ""):
        v = [float(x) for x in re.split(r"[\s,]+", args.strip()) if x]
        if name == "translate":
            t = (1, 0, 0, 1, v[0], v[1] if len(v) > 1 else 0)
        elif name == "scale":
            t = (v[0], 0, 0, v[1] if len(v) > 1 else v[0], 0, 0)
        elif name == "rotate":
            a = math.radians(v[0])
            r = (math.cos(a), math.sin(a), -math.sin(a), math.cos(a), 0, 0)
            t = mul(mul((1, 0, 0, 1, v[1], v[2]), r), (1, 0, 0, 1, -v[1], -v[2])) if len(v) == 3 else r
        elif name == "matrix":
            t = tuple(v)
        else:
            raise SystemExit(f"unsupported transform {name}")
        m = mul(m, t)
    return m


# ── path data → absolute M / L / C / A / Z
TOKEN = re.compile(r"[MmLlHhVvCcSsQqTtAaZz]|[-+]?(?:\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?")


def parse_path(d):
    toks = TOKEN.findall(d)
    out, i, cmd = [], 0, None
    cx = cy = sx = sy = 0.0
    prev_c = prev_q = None  # reflected control points for S / T

    def take(n):
        nonlocal i
        v = [float(t) for t in toks[i:i + n]]
        i += n
        return v

    while i < len(toks):
        if toks[i].isalpha():
            cmd = toks[i]
            i += 1
            if cmd in "Zz":
                out.append(("Z",))
                cx, cy = sx, sy
                prev_c = prev_q = None
                continue
        rel, C = cmd.islower(), cmd.upper()
        ox, oy = (cx, cy) if rel else (0.0, 0.0)
        if C == "M":
            x, y = take(2)
            cx, cy = sx, sy = x + ox, y + oy
            out.append(("M", cx, cy))
            cmd = "l" if rel else "L"
            prev_c = prev_q = None
        elif C == "L":
            x, y = take(2)
            cx, cy = x + ox, y + oy
            out.append(("L", cx, cy))
            prev_c = prev_q = None
        elif C == "H":
            (x,) = take(1)
            cx = x + ox
            out.append(("L", cx, cy))
            prev_c = prev_q = None
        elif C == "V":
            (y,) = take(1)
            cy = y + oy
            out.append(("L", cx, cy))
            prev_c = prev_q = None
        elif C in "CS":
            if C == "C":
                x1, y1, x2, y2, x, y = take(6)
                x1, y1 = x1 + ox, y1 + oy
            else:
                x2, y2, x, y = take(4)
                x1, y1 = (2 * cx - prev_c[0], 2 * cy - prev_c[1]) if prev_c else (cx, cy)
            x2, y2, x, y = x2 + ox, y2 + oy, x + ox, y + oy
            out.append(("C", x1, y1, x2, y2, x, y))
            prev_c, prev_q = (x2, y2), None
            cx, cy = x, y
        elif C in "QT":
            if C == "Q":
                qx, qy, x, y = take(4)
                qx, qy = qx + ox, qy + oy
            else:
                x, y = take(2)
                qx, qy = (2 * cx - prev_q[0], 2 * cy - prev_q[1]) if prev_q else (cx, cy)
            x, y = x + ox, y + oy
            out.append(("C", cx + 2 / 3 * (qx - cx), cy + 2 / 3 * (qy - cy), x + 2 / 3 * (qx - x), y + 2 / 3 * (qy - y), x, y))
            prev_q, prev_c = (qx, qy), None
            cx, cy = x, y
        elif C == "A":
            rx, ry, rot, large, sweep, x, y = take(7)
            out.append(("A", rx, ry, rot, int(large), int(sweep), x + ox, y + oy))
            cx, cy = x + ox, y + oy
            prev_c = prev_q = None
        else:
            raise SystemExit(f"bad path command {cmd}")
    return out


def arc_to_cubics(x1, y1, rx, ry, phi, fa, fs, x2, y2):
    """SVG arc as cubic Béziers (SVG 1.1 implementation notes F.6.5)."""
    if rx == 0 or ry == 0:
        return [("L", x2, y2)]
    p = math.radians(phi)
    cp, sp = math.cos(p), math.sin(p)
    dx, dy = (x1 - x2) / 2, (y1 - y2) / 2
    x1p, y1p = cp * dx + sp * dy, -sp * dx + cp * dy
    rx, ry = abs(rx), abs(ry)
    lam = x1p ** 2 / rx ** 2 + y1p ** 2 / ry ** 2
    if lam > 1:
        rx, ry = rx * math.sqrt(lam), ry * math.sqrt(lam)
    num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
    den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
    k = math.sqrt(max(0.0, num / den)) * (-1 if fa == fs else 1)
    cxp, cyp = k * rx * y1p / ry, -k * ry * x1p / rx
    cx, cy = cp * cxp - sp * cyp + (x1 + x2) / 2, sp * cxp + cp * cyp + (y1 + y2) / 2

    def ang(ux, uy, vx, vy):
        return math.atan2(ux * vy - uy * vx, ux * vx + uy * vy)

    t1 = ang(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry)
    dt = ang((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
    if not fs and dt > 0:
        dt -= 2 * math.pi
    elif fs and dt < 0:
        dt += 2 * math.pi
    n = max(1, math.ceil(abs(dt) / (math.pi / 2) - 1e-9))
    d = dt / n
    kk = 4 / 3 * math.tan(d / 4)

    def mp(u, v):
        u, v = rx * u, ry * v
        return cp * u - sp * v + cx, sp * u + cp * v + cy

    out = []
    for j in range(n):
        a1, a2 = t1 + j * d, t1 + (j + 1) * d
        c1, s1, c2, s2 = math.cos(a1), math.sin(a1), math.cos(a2), math.sin(a2)
        out.append(("C", *mp(c1 - kk * s1, s1 + kk * c1), *mp(c2 + kk * s2, s2 - kk * c2), *mp(c2, s2)))
    return out


def flatten_arcs(cmds):
    out, cx, cy, sx, sy = [], 0.0, 0.0, 0.0, 0.0
    for c in cmds:
        if c[0] == "A":
            out += arc_to_cubics(cx, cy, *c[1:])
        else:
            out.append(c)
        if c[0] == "M":
            sx, sy = c[1], c[2]
        if c[0] == "Z":
            cx, cy = sx, sy
        else:
            cx, cy = c[-2], c[-1]
    return out


def transform(cmds, m):
    sim = similarity(m)
    if sim is None:
        cmds = flatten_arcs(cmds)
    out = []
    for c in cmds:
        if c[0] == "Z":
            out.append(c)
        elif c[0] == "A":
            s, rot = sim
            x, y = ap(m, c[6], c[7])
            out.append(("A", c[1] * s, c[2] * s, c[3] + rot, c[4], c[5], x, y))
        else:
            v = list(c[1:])
            for k in range(0, len(v), 2):
                v[k], v[k + 1] = ap(m, v[k], v[k + 1])
            out.append((c[0], *v))
    return out


def bbox(cmds):
    xs, ys = [], []
    cx = cy = 0.0
    for c in flatten_arcs(cmds):
        if c[0] in "ML":
            xs.append(c[1]); ys.append(c[2])
        elif c[0] == "C":
            for k in range(17):
                t = k / 16
                mt = 1 - t
                xs.append(mt ** 3 * cx + 3 * mt * mt * t * c[1] + 3 * mt * t * t * c[3] + t ** 3 * c[5])
                ys.append(mt ** 3 * cy + 3 * mt * mt * t * c[2] + 3 * mt * t * t * c[4] + t ** 3 * c[6])
        if c[0] != "Z":
            cx, cy = c[-2], c[-1]
    return min(xs), min(ys), max(xs) - min(xs), max(ys) - min(ys)


def fmt(v):
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return "0" if s in ("-0", "") else s


def path_data(cmds):
    parts = []
    for c in cmds:
        if c[0] == "Z":
            parts.append("Z")
        elif c[0] == "A":
            parts.append(f"A{fmt(c[1])},{fmt(c[2])} {fmt(c[3])} {c[4]},{c[5]} {fmt(c[6])},{fmt(c[7])}")
        else:
            v = c[1:]
            parts.append(c[0] + " ".join(f"{fmt(v[k])},{fmt(v[k + 1])}" for k in range(0, len(v), 2)))
    return " ".join(parts)


def num(el, name, default=0.0):
    v = el.get(name)
    return float(v) if v is not None else default


def shape(el):
    tag = el.tag.replace(NS, "")
    if tag == "path":
        return parse_path(el.get("d"))
    if tag == "rect":
        x, y, w, h = num(el, "x"), num(el, "y"), num(el, "width"), num(el, "height")
        return [("M", x, y), ("L", x + w, y), ("L", x + w, y + h), ("L", x, y + h), ("Z",)]
    if tag in ("circle", "ellipse"):
        cx, cy = num(el, "cx"), num(el, "cy")
        rx = num(el, "r") if tag == "circle" else num(el, "rx")
        ry = num(el, "r") if tag == "circle" else num(el, "ry")
        return [("M", cx - rx, cy), ("A", rx, ry, 0, 1, 0, cx + rx, cy), ("A", rx, ry, 0, 1, 0, cx - rx, cy), ("Z",)]
    if tag in ("polygon", "polyline"):
        v = [float(t) for t in re.split(r"[\s,]+", el.get("points").strip())]
        cmds = [("M", v[0], v[1])] + [("L", v[k], v[k + 1]) for k in range(2, len(v), 2)]
        return cmds + [("Z",)] if tag == "polygon" else cmds
    if tag == "line":
        return [("M", num(el, "x1"), num(el, "y1")), ("L", num(el, "x2"), num(el, "y2"))]
    return None


NAMED = {"white": "#FFFFFF", "black": "#000000"}


def color(v):
    v = NAMED.get(v, v)
    if re.fullmatch(r"#[0-9a-fA-F]{3}", v):
        v = "#" + "".join(ch * 2 for ch in v[1:])
    if not re.fullmatch(r"#[0-9a-fA-F]{6}", v):
        raise SystemExit(f"unsupported colour {v}")
    return v.upper()


def offset(v):
    return float(v[:-1]) / 100 if v.endswith("%") else float(v)


class Converter:
    def __init__(self, root):
        self.defs = {el.get("id"): el for el in root.iter() if el.get("id")}

    def paint(self, value, cmds, ctm):
        """('solid', '#RRGGBB') or ('gradient', xml) for a fill or stroke value."""
        m = re.fullmatch(r"url\(#([^)]+)\)", value)
        if not m:
            return "solid", color(value)
        g = self.defs[m.group(1)]
        if g.get("gradientTransform"):
            raise SystemExit("gradientTransform is not supported")
        stops = []
        for st in g.findall(f"{NS}stop"):
            a = float(st.get("stop-opacity", "1"))
            stops.append((offset(st.get("offset", "0")), f"#{round(a * 255):02X}{color(st.get('stop-color', '#000000'))[1:]}"))
        units = g.get("gradientUnits", "objectBoundingBox")
        bx, by, bw, bh = bbox(cmds) if units == "objectBoundingBox" else (0, 0, 1, 1)
        to_user = mul(ctm, (bw, 0, 0, bh, bx, by)) if units == "objectBoundingBox" else ctm
        items = "".join(f'\n                <item android:offset="{fmt(o)}" android:color="{c}" />' for o, c in stops)
        if g.tag == f"{NS}linearGradient":
            x1, y1 = float(g.get("x1", "0")), float(g.get("y1", "0"))
            x2, y2 = float(g.get("x2", "1")), float(g.get("y2", "0"))
            # SVG keeps the colour bands perpendicular to the vector in gradient space; after a non-uniform bounding
            # box they lean, so rebuild an equivalent user-space vector whose bands follow the mapped perpendicular.
            sx, sy = ap(to_user, x1, y1)
            ex, ey = ap(to_user, x2, y2)
            px, py = -(y2 - y1), (x2 - x1)
            wx, wy = to_user[0] * px + to_user[2] * py, to_user[1] * px + to_user[3] * py
            nx, ny = wy, -wx
            ln = math.hypot(nx, ny)
            nx, ny = nx / ln, ny / ln
            k = (ex - sx) * nx + (ey - sy) * ny
            ex, ey = sx + nx * k, sy + ny * k
            head = (f'<gradient android:type="linear" android:startX="{fmt(sx)}" android:startY="{fmt(sy)}" '
                    f'android:endX="{fmt(ex)}" android:endY="{fmt(ey)}">')
        else:
            cx, cy, r = float(g.get("cx", "0.5")), float(g.get("cy", "0.5")), float(g.get("r", "0.5"))
            ux, uy = ap(to_user, cx, cy)
            scale = math.sqrt(abs(to_user[0] * to_user[3] - to_user[1] * to_user[2]))
            head = (f'<gradient android:type="radial" android:centerX="{fmt(ux)}" android:centerY="{fmt(uy)}" '
                    f'android:gradientRadius="{fmt(r * scale)}">')
        return "gradient", head + items + "\n            </gradient>"

    def convert(self, layer):
        nodes = self.walk(layer, ID, {"fill": "#000000", "stroke": "none", "stroke-width": "1"}, 1.0)
        return merge(nodes)

    def walk(self, el, ctm, style, opacity):
        style = dict(style)
        for k, v in parse_style(el).items():
            if k in INHERITED:
                style[k] = v
        opacity *= float(el.get("opacity", "1"))
        ctm = mul(ctm, parse_transform(el.get("transform")))
        tag = el.tag.replace(NS, "")
        out = []
        if tag == "g":
            for child in el:
                if child.get("display") == "none":
                    continue
                out += self.walk(child, ctm, style, opacity)
        else:
            cmds = shape(el)
            if cmds is None:
                return []
            node = {"d": path_data(transform(cmds, ctm))}
            sim = similarity(ctm)
            s = sim[0] if sim else math.sqrt(abs(ctm[0] * ctm[3] - ctm[1] * ctm[2]))
            if style["fill"] != "none":
                node["fill"] = self.paint(style["fill"], cmds, ctm)
                node["fillAlpha"] = opacity * float(style.get("fill-opacity", "1"))
                if style.get("fill-rule") == "evenodd":
                    node["fillType"] = "evenOdd"
            if style["stroke"] != "none":
                node["stroke"] = self.paint(style["stroke"], cmds, ctm)
                node["strokeAlpha"] = opacity * float(style.get("stroke-opacity", "1"))
                node["strokeWidth"] = float(style["stroke-width"]) * s
                if "stroke-linecap" in style:
                    node["strokeLineCap"] = style["stroke-linecap"]
                if "stroke-linejoin" in style:
                    node["strokeLineJoin"] = style["stroke-linejoin"]
            out.append(node)
        clip = el.get("clip-path")
        if clip and out:
            cp = self.defs[re.fullmatch(r"url\(#([^)]+)\)", clip).group(1)]
            data = " ".join(path_data(transform(shape(c), mul(ctm, parse_transform(c.get("transform"))))) for c in cp)
            out = [{"clip": data, "children": out}]
        return out


def parse_style(el):
    attrs = dict(el.attrib)
    for part in (el.get("style") or "").split(";"):
        if ":" in part:
            k, v = part.split(":", 1)
            attrs[k.strip()] = v.strip()
    return attrs


# aapt2 cannot store a string longer than 32767 bytes (it writes "STRING_TOO_LARGE" instead, and the drawable then
# fails to inflate), so merged path data is split well below that.
MAX_PATH_DATA = 12000


def merge(nodes):
    """Consecutive plain filled paths with the same paint become one path: the halftone is a few nodes, not hundreds."""
    out = []
    for n in nodes:
        if "children" in n:
            out.append({"clip": n["clip"], "children": merge(n["children"])})
            continue
        prev = out[-1] if out else None
        plain = "stroke" not in n and n.get("fill", ("", ""))[0] == "solid" and "fillType" not in n
        if plain and prev and "children" not in prev and "stroke" not in prev and "fillType" not in prev \
                and prev.get("fill") == n["fill"] and abs(prev.get("fillAlpha", 1) - n.get("fillAlpha", 1)) < 1e-6 \
                and len(prev["d"]) + len(n["d"]) < MAX_PATH_DATA:
            prev["d"] += " " + n["d"]
        else:
            out.append(dict(n))
    return out


def emit(nodes, indent="    "):
    xml = []
    for n in nodes:
        if "children" in n:
            xml.append(f'{indent}<group>\n{indent}    <clip-path android:pathData="{n["clip"]}" />')
            xml.append(emit(n["children"], indent + "    "))
            xml.append(f"{indent}</group>")
            continue
        attrs = [f'android:pathData="{n["d"]}"']
        inner = []
        for key in ("fill", "stroke"):
            if key not in n:
                continue
            kind, value = n[key]
            name = "fillColor" if key == "fill" else "strokeColor"
            if kind == "solid":
                attrs.append(f'android:{name}="{value}"')
            else:
                inner.append(f'{indent}    <aapt:attr name="android:{name}">\n{indent}        {value}\n{indent}    </aapt:attr>')
            alpha = n.get(key + "Alpha", 1.0)
            if alpha < 0.999:
                attrs.append(f'android:{key}Alpha="{fmt(alpha)}"')
        for key in ("fillType", "strokeWidth", "strokeLineCap", "strokeLineJoin"):
            if key in n:
                v = n[key]
                attrs.append(f'android:{key}="{fmt(v) if isinstance(v, float) else v}"')
        a = f"\n{indent}    ".join(attrs)
        if inner:
            xml.append(f"{indent}<path\n{indent}    {a}>\n" + "\n".join(inner) + f"\n{indent}</path>")
        else:
            xml.append(f"{indent}<path\n{indent}    {a} />")
    return "\n".join(xml)


def vector(nodes, comment):
    body = emit(nodes)
    aapt = ' xmlns:aapt="http://schemas.android.com/aapt"' if "aapt:attr" in body else ""
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        f"<!-- {comment} Generated by branding/icon-options/svg2vd.py; edit make_icons.py instead. -->\n"
        f'<vector xmlns:android="http://schemas.android.com/apk/res/android"{aapt}\n'
        '    android:width="108dp"\n    android:height="108dp"\n'
        '    android:viewportWidth="108"\n    android:viewportHeight="108">\n'
        f"{body}\n</vector>\n"
    )


def main(res):
    (res / "drawable").mkdir(parents=True, exist_ok=True)
    (res / "mipmap-anydpi").mkdir(parents=True, exist_ok=True)
    for stem, key in KEYS.items():
        root = ET.parse(HERE / f"{stem}.svg").getroot()
        title = root.find(f"{NS}title").text
        conv = Converter(root)
        for layer in ("background", "foreground", "monochrome"):
            g = root.find(f".//{NS}g[@id='{layer}']")
            nodes = conv.convert(g)
            (res / "drawable" / f"ic_launcher_{key}_{layer}.xml").write_text(vector(nodes, f"{title}: {layer} layer."))
        (res / "mipmap-anydpi" / f"ic_launcher_{key}.xml").write_text(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            f"<!-- {title} (generated by branding/icon-options/svg2vd.py) -->\n"
            '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
            f'    <background android:drawable="@drawable/ic_launcher_{key}_background" />\n'
            f'    <foreground android:drawable="@drawable/ic_launcher_{key}_foreground" />\n'
            f'    <monochrome android:drawable="@drawable/ic_launcher_{key}_monochrome" />\n'
            "</adaptive-icon>\n"
        )
        print(key, "ok")


if __name__ == "__main__":
    main(Path(sys.argv[1]) if len(sys.argv) > 1 else HERE.parents[1] / "app" / "src" / "main" / "res")
