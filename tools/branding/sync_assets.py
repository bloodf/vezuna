#!/usr/bin/env python3
"""Copies the brand kit into the apps and converts SVG masters to Compose vector drawables.

brand/ is the source of truth. SVG masters (logos, brand/vectors/icons, brand/vectors/illustrations)
become Android vector XML in shared composeResources/drawable, which Compose Multiplatform renders
on Android and iOS alike. The converter uses the standard library only; check_brand.py re-runs it
in memory and fails when a committed drawable drifts from its master.
"""
from pathlib import Path
import re
import shutil
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
BRAND = ROOT / "brand"
SHARED = ROOT / "shared/src/commonMain/composeResources"
ANDROID = ROOT / "composeApp/src/main"
IOS = ROOT / "iosApp/Vezuna"
DRAWABLE = SHARED / "drawable"
NOTES = BRAND / "vectors/conversion-notes.txt"
LOGOS = ("lockup-color", "lockup-dark", "lockup-ink", "lockup-white", "mark-color", "mark-dark", "mark-ink", "mark-white")
ANDROID_NS = "http://schemas.android.com/apk/res/android"
# Colors the masters use; currentColor becomes black so Icon(tint=) can recolor it.
NAMED = {"currentColor": "#000000", "black": "#000000", "white": "#FFFFFF"}
ARITY = {"M": 2, "L": 2, "H": 1, "V": 1, "C": 6, "S": 4, "Q": 4, "T": 2, "A": 7, "Z": 0}
NUMBER = re.compile(r"[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?")


def vector_masters():
    """(master SVG, drawable name) pairs in a stable order."""
    pairs = [(BRAND / f"logos/{name}.svg", f"vezuna_{name.replace('-', '_')}") for name in LOGOS]
    pairs += [(path, "ic_" + path.stem.replace("-", "_")) for path in sorted((BRAND / "vectors/icons").glob("*.svg"))]
    pairs += [(path, "ill_" + path.stem.removeprefix("illustration-").replace("-", "_")) for path in sorted((BRAND / "vectors/illustrations").glob("*.svg"))]
    return pairs


def fmt(value):
    text = f"{value:.4f}".rstrip("0").rstrip(".")
    return "0" if text in ("-0", "") else text


def color(value, where):
    value = NAMED.get(value, value)
    if re.fullmatch(r"#[0-9A-Fa-f]{3}", value):
        value = "#" + "".join(ch * 2 for ch in value[1:])
    if not re.fullmatch(r"#[0-9A-Fa-f]{6}", value):
        raise ValueError(f"{where}: unsupported color {value!r}")
    return value.upper()


def path_tokens(data):
    position = 0
    while position < len(data):
        char = data[position]
        if char.isspace() or char == ",":
            position += 1
        elif char.isalpha():
            yield char
            position += 1
        else:
            match = NUMBER.match(data, position)
            if not match:
                raise ValueError(f"bad path data near {data[position:position + 12]!r}")
            yield match.group()
            position = match.end()


def normalize_path(data):
    """Rewrites path data with explicit separators and commands, which every vector parser accepts.

    Compact arc flags ("a1 1 0 011 1") and implicit repeated commands are expanded here so Android's
    and Compose's path parsers never have to guess.
    """
    out, tokens, command = [], list(path_tokens(data)), None
    index = 0
    while index < len(tokens):
        if tokens[index].isalpha():
            command = tokens[index]
            index += 1
            if command.upper() == "Z":
                out.append(command)
                continue
        elif command is None:
            raise ValueError("path data must start with a command")
        arity = ARITY[command.upper()]
        args = []
        while len(args) < arity:
            if index >= len(tokens) or tokens[index].isalpha():
                raise ValueError(f"command {command} is missing arguments")
            token = tokens[index]
            if command.upper() == "A" and len(args) in (3, 4) and len(token) > 1 and token[0] in "01" and not token.startswith(("0.", "1.")):
                # A compact flag such as "011" holds the flag and the start of the next number.
                tokens[index:index + 1] = [token[0], token[1:]]
                token = token[0]
            args.append(float(token))
            index += 1
        out.append(command + " " + " ".join(fmt(value) for value in args))
        if command in "Mm":
            command = "L" if command == "M" else "l"
    return " ".join(out)


def shape_path(tag, attrs, where):
    number = lambda name, default=0.0: float(attrs.get(name, default))
    if tag == "path":
        return normalize_path(attrs["d"])
    if tag == "rect":
        x, y, width, height = number("x"), number("y"), number("width"), number("height")
        rx = number("rx", attrs.get("ry", 0))
        ry = number("ry", attrs.get("rx", 0))
        rx, ry = min(rx, width / 2), min(ry, height / 2)
        if rx == 0 or ry == 0:
            return f"M {fmt(x)} {fmt(y)} H {fmt(x + width)} V {fmt(y + height)} H {fmt(x)} Z"
        arc = f"A {fmt(rx)} {fmt(ry)} 0 0 1"
        return (
            f"M {fmt(x + rx)} {fmt(y)} H {fmt(x + width - rx)} {arc} {fmt(x + width)} {fmt(y + ry)} "
            f"V {fmt(y + height - ry)} {arc} {fmt(x + width - rx)} {fmt(y + height)} H {fmt(x + rx)} "
            f"{arc} {fmt(x)} {fmt(y + height - ry)} V {fmt(y + ry)} {arc} {fmt(x + rx)} {fmt(y)} Z"
        )
    if tag in ("circle", "ellipse"):
        cx, cy = number("cx"), number("cy")
        rx = number("r") if tag == "circle" else number("rx")
        ry = number("r") if tag == "circle" else number("ry")
        arc = f"A {fmt(rx)} {fmt(ry)} 0 1 0"
        return f"M {fmt(cx - rx)} {fmt(cy)} {arc} {fmt(cx + rx)} {fmt(cy)} {arc} {fmt(cx - rx)} {fmt(cy)} Z"
    raise ValueError(f"{where}: unsupported element <{tag}>")


def transform_groups(value, where):
    """One VectorDrawable group per SVG transform function, outermost first, so order is exact."""
    groups = []
    for name, raw in re.findall(r"([a-zA-Z]+)\s*\(([^)]*)\)", value or ""):
        args = [float(item) for item in NUMBER.findall(raw)]
        if name == "translate":
            groups.append({"translateX": args[0], "translateY": args[1] if len(args) > 1 else 0.0})
        elif name == "scale":
            groups.append({"scaleX": args[0], "scaleY": args[1] if len(args) > 1 else args[0]})
        elif name == "rotate":
            group = {"rotation": args[0]}
            if len(args) == 3:
                group.update(pivotX=args[1], pivotY=args[2])
            groups.append(group)
        else:
            raise ValueError(f"{where}: unsupported transform {name}()")
    return groups


INHERITED = ("fill", "stroke", "stroke-width", "stroke-linecap", "stroke-linejoin", "fill-rule", "fill-opacity", "stroke-opacity")


def local(tag):
    return tag.rsplit("}", 1)[-1]


def convert(svg_path):
    """Returns (vector XML text, notes) for one SVG master."""
    root = ET.parse(svg_path).getroot()
    where = svg_path.name
    notes = []
    viewbox = [float(item) for item in NUMBER.findall(root.attrib.get("viewBox", ""))]
    if len(viewbox) != 4 or viewbox[0] != 0 or viewbox[1] != 0:
        raise ValueError(f"{where}: needs a viewBox starting at 0 0")
    width = float(NUMBER.match(root.attrib.get("width", fmt(viewbox[2]))).group())
    height = float(NUMBER.match(root.attrib.get("height", fmt(viewbox[3]))).group())
    lines = [
        f'<vector xmlns:android="{ANDROID_NS}"',
        f'    android:width="{fmt(width)}dp" android:height="{fmt(height)}dp"',
        f'    android:viewportWidth="{fmt(viewbox[2])}" android:viewportHeight="{fmt(viewbox[3])}">',
    ]

    def walk(element, style, alpha, depth):
        for child in element:
            tag = local(child.tag)
            if tag in ("title", "desc", "metadata"):
                continue
            attrs = child.attrib
            if any(key in attrs for key in ("style", "class", "clip-path", "mask", "filter")) or tag in ("image", "use", "text", "linearGradient", "radialGradient"):
                raise ValueError(f"{where}: unsupported <{tag}> or attribute")
            if "stroke-dasharray" in attrs:
                notes.append(f"{where}: stroke-dasharray dropped (VectorDrawable has no dashes); stroke drawn solid")
            child_style = dict(style)
            child_style.update({key: attrs[key] for key in INHERITED if key in attrs})
            child_alpha = alpha * float(attrs.get("opacity", 1))
            groups = transform_groups(attrs.get("transform"), where)
            indent = "    " * depth
            for offset, group in enumerate(groups):
                fields = " ".join(f'android:{key}="{fmt(value)}"' for key, value in group.items())
                lines.append(f"{indent}{'    ' * offset}    <group {fields}>")
            inner = depth + len(groups)
            if tag == "g":
                walk(child, child_style, child_alpha, inner)
            else:
                lines.append(path_xml(tag, attrs, child_style, child_alpha, "    " * inner + "    ", where))
            for offset in reversed(range(len(groups))):
                lines.append(f"{indent}{'    ' * offset}    </group>")

    base = {key: root.attrib[key] for key in INHERITED if key in root.attrib}
    base.setdefault("fill", "#000000")
    walk(root, base, float(root.attrib.get("opacity", 1)), 0)
    lines.append("</vector>")
    return "\n".join(lines) + "\n", notes


def path_xml(tag, attrs, style, alpha, indent, where):
    fields = [f'android:pathData="{shape_path(tag, attrs, where)}"']
    fill, stroke = style.get("fill", "#000000"), style.get("stroke", "none")
    if fill != "none":
        fields.append(f'android:fillColor="{color(fill, where)}"')
        fill_alpha = alpha * float(style.get("fill-opacity", 1))
        if fill_alpha < 1:
            fields.append(f'android:fillAlpha="{fmt(fill_alpha)}"')
        if style.get("fill-rule") == "evenodd":
            fields.append('android:fillType="evenOdd"')
    if stroke != "none":
        fields.append(f'android:strokeColor="{color(stroke, where)}"')
        fields.append(f'android:strokeWidth="{fmt(float(style.get("stroke-width", 1)))}"')
        stroke_alpha = alpha * float(style.get("stroke-opacity", 1))
        if stroke_alpha < 1:
            fields.append(f'android:strokeAlpha="{fmt(stroke_alpha)}"')
        if "stroke-linecap" in style:
            fields.append(f'android:strokeLineCap="{style["stroke-linecap"]}"')
        if "stroke-linejoin" in style:
            fields.append(f'android:strokeLineJoin="{style["stroke-linejoin"]}"')
    return f"{indent}<path {' '.join(fields)} />"


def converted_outputs():
    """{drawable path: XML text} plus the notes file text, for sync and for the brand check."""
    outputs, notes = {}, []
    for master, name in vector_masters():
        xml, master_notes = convert(master)
        outputs[DRAWABLE / f"{name}.xml"] = xml
        notes += master_notes
    header = "# Generated by tools/branding/sync_assets.py. SVG features the vector conversion cannot keep.\n"
    return outputs, header + "".join(f"{note}\n" for note in notes)


def copy(source, target):
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, target)


def main():
    for name in ("NotoSans-Regular.ttf", "NotoSans-Bold.ttf"):
        copy(BRAND / f"fonts/{name}", SHARED / f"font/{name}")
        copy(BRAND / f"fonts/{name}", ANDROID / "res/font" / name.lower().replace("-", "_"))
        copy(BRAND / f"fonts/{name}", IOS / f"Fonts/{name}")
    copy(BRAND / "fonts/NOTO-LICENSE.txt", SHARED / "files/NOTO-LICENSE.txt")
    copy(BRAND / "fonts/NOTO-LICENSE.txt", ANDROID / "assets/NOTO-LICENSE.txt")
    copy(BRAND / "fonts/NOTO-LICENSE.txt", IOS / "Fonts/NOTO-LICENSE.txt")

    for source in (BRAND / "icons/android/res").rglob("*"):
        if source.is_file():
            copy(source, ANDROID / "res" / source.relative_to(BRAND / "icons/android/res"))
    copy(BRAND / "icons/android/res/drawable/ic_vezuna_monochrome.xml", ANDROID / "res/drawable/ic_notification.xml")
    for source in (BRAND / "icons/ios/AppIcon.appiconset").rglob("*"):
        if source.is_file():
            copy(source, IOS / "Assets.xcassets/AppIcon.appiconset" / source.name)

    outputs, notes = converted_outputs()
    DRAWABLE.mkdir(parents=True, exist_ok=True)
    for stale in DRAWABLE.glob("*"):
        if stale not in outputs:
            stale.unlink()
    for path, text in outputs.items():
        path.write_text(text)
    NOTES.write_text(notes)
    print(f"VEZUNA_ASSETS_SYNCED drawables={len(outputs)}")


if __name__ == "__main__":
    main()
