#!/usr/bin/env python3
"""Genkan's app icon, from its SVG sources here to the adaptive icon's vector drawables.

    python3 Design/icons/render.py

Each layer (noren_background.svg, noren_foreground.svg, noren_monochrome.svg) becomes
app/src/main/res/drawable/ic_launcher_<layer>.xml. The sources keep to the SVG Android's
VectorDrawable can say directly: a 108x108 viewBox (the adaptive icon's canvas: the middle 66
units always show), and rect, line and path elements, in g groups that give them their fill,
stroke, stroke-width, stroke-linecap, stroke-linejoin, fill-rule and opacity. Anything else stops
the script, so a source can't drift into what the drawable doesn't draw.
"""
import pathlib
import sys
import xml.etree.ElementTree as ET

HERE = pathlib.Path(__file__).resolve().parent
RES = HERE.parent.parent / "app" / "src" / "main" / "res" / "drawable"
SVG = "{http://www.w3.org/2000/svg}"
LAYERS = ("background", "foreground", "monochrome")
INHERITED = ("fill", "stroke", "stroke-width", "stroke-linecap", "stroke-linejoin", "fill-rule", "opacity")


def color(value, opacity):
    """An SVG colour (#rgb, #rrggbb) with an opacity, as Android's #aarrggbb."""
    value = value.strip()
    if value.startswith("#") and len(value) == 4:
        value = "#" + "".join(c * 2 for c in value[1:])
    if not (value.startswith("#") and len(value) == 7):
        sys.exit(f"colour {value!r}: only #rgb and #rrggbb")
    return "#%02X%s" % (round(opacity * 255), value[1:].upper())


def rect_path(e):
    x, y, w, h = (float(e.get(k, 0)) for k in ("x", "y", "width", "height"))
    r = min(float(e.get("rx", 0)), w / 2, h / 2)
    if r == 0:
        return f"M{x:g},{y:g}h{w:g}v{h:g}h{-w:g}z"
    return (f"M{x + r:g},{y:g}h{w - 2 * r:g}a{r:g},{r:g} 0 0 1 {r:g},{r:g}v{h - 2 * r:g}"
            f"a{r:g},{r:g} 0 0 1 {-r:g},{r:g}h{-(w - 2 * r):g}a{r:g},{r:g} 0 0 1 {-r:g},{-r:g}"
            f"v{-(h - 2 * r):g}a{r:g},{r:g} 0 0 1 {r:g},{-r:g}z")


def shape(e, style):
    tag = e.tag.removeprefix(SVG)
    if tag == "rect":
        data = rect_path(e)
    elif tag == "line":
        data = "M{x1},{y1}L{x2},{y2}".format(**{k: e.get(k) for k in ("x1", "y1", "x2", "y2")})
    elif tag == "path":
        data = " ".join(e.get("d").split())
    else:
        sys.exit(f"<{tag}>: only rect, line and path")
    opacity = float(style.get("opacity", 1))
    attrs = {"android:pathData": data}
    # A line has no inside to fill: only its stroke draws.
    fill = "none" if tag == "line" else style.get("fill", "#000")
    if fill != "none":
        attrs["android:fillColor"] = color(fill, opacity)
        if style.get("fill-rule") == "evenodd":
            attrs["android:fillType"] = "evenOdd"
    stroke = style.get("stroke", "none")
    if stroke != "none":
        attrs["android:strokeColor"] = color(stroke, opacity)
        attrs["android:strokeWidth"] = style.get("stroke-width", "1")
        if "stroke-linecap" in style:
            attrs["android:strokeLineCap"] = style["stroke-linecap"]
        if "stroke-linejoin" in style:
            attrs["android:strokeLineJoin"] = style["stroke-linejoin"]
    return attrs


def walk(e, style, out):
    style = dict(style)
    for k in INHERITED:
        if e.get(k) is not None:
            style[k] = e.get(k) if k != "opacity" else str(float(style.get("opacity", 1)) * float(e.get(k)))
    tag = e.tag.removeprefix(SVG)
    if tag in ("svg", "g"):
        for child in e:
            walk(child, style, out)
    elif tag in ("defs",):
        sys.exit("<defs>: gradients and the like aren't drawn; keep the sources to flat colours")
    else:
        out.append(shape(e, style))


def render(layer):
    root = ET.parse(HERE / f"noren_{layer}.svg").getroot()
    if root.get("viewBox") != "0 0 108 108":
        sys.exit(f"noren_{layer}.svg: the viewBox must be 0 0 108 108")
    paths = []
    walk(root, {}, paths)
    lines = [
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>",
        f"<!-- Genkan's app icon, {layer}: made from Design/icons/noren_{layer}.svg by Design/icons/render.py. Edit that, not this. -->",
        "<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"",
        "    android:width=\"108dp\"",
        "    android:height=\"108dp\"",
        "    android:viewportWidth=\"108\"",
        "    android:viewportHeight=\"108\">",
    ]
    for attrs in paths:
        lines.append("    <path")
        lines += [f"        {k}=\"{v}\"" for k, v in attrs.items()]
        lines[-1] += " />"
    lines.append("</vector>")
    target = RES / f"ic_launcher_{layer}.xml"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text("\n".join(lines) + "\n")
    print(f"{target.relative_to(HERE.parent.parent)}: {len(paths)} paths")


if __name__ == "__main__":
    for layer in LAYERS:
        render(layer)
