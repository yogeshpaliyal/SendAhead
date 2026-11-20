"""Build the Play feature graphic (1024x500) and store icon (512x512) for SendAhead.
Screenshots: docs/play_screenshots.py.

usage: python3 docs/play_assets.py <dir with home.png (1080x2424)> <out dir>
then render each SVG with rsvg-convert at its own size.
"""
import base64, os, re, sys

SRC, OUT = sys.argv[1], sys.argv[2]
os.makedirs(OUT, exist_ok=True)
SHOT_W, SHOT_H = 1080, 2424
INDIGO, DEEP, PEACH, LILAC = "#2D2A7B", "#1E1B5C", "#FFB59A", "#C3C0FF"

_ICON = os.path.join(os.path.dirname(__file__), "..", "app/src/main/res/drawable/ic_launcher_foreground.xml")
COOKIE, RING, HAND1, HAND2, PLANE = re.findall(r'android:pathData="([^"]+)"', open(_ICON).read())


def embed(name):
    with open(os.path.join(SRC, name), "rb") as f:
        return "data:image/png;base64," + base64.b64encode(f.read()).decode()


def glyph(ink):
    return ("".join(f'<path d="{d}" fill="none" stroke="{ink}" stroke-width="5.2" stroke-linecap="round"/>' for d in (RING, HAND1, HAND2))
            + f'<path d="{PLANE}" fill="{ink}"/>')


def phone(x, y, w, img, cid):
    h = w * SHOT_H / SHOT_W
    pad = 12
    return (f'<rect x="{x-pad}" y="{y-pad}" width="{w+2*pad}" height="{h+2*pad}" rx="64" fill="#0B0A12" stroke="#4A46A8" stroke-width="4"/>'
            f'<clipPath id="{cid}"><rect x="{x}" y="{y}" width="{w}" height="{h}" rx="54"/></clipPath>'
            f'<image href="{img}" x="{x}" y="{y}" width="{w}" height="{h}" clip-path="url(#{cid})" preserveAspectRatio="xMidYMid slice"/>')


def feature_graphic():
    W, H = 1024, 500
    s = 300 / 108
    svg = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">',
           '<defs><radialGradient id="glow" cx="0.78" cy="0.5" r="0.7">'
           f'<stop offset="0" stop-color="#5A56C8" stop-opacity="0.65"/><stop offset="1" stop-color="{DEEP}" stop-opacity="0"/></radialGradient>'
           '<linearGradient id="title" x1="0" x2="1"><stop offset="0" stop-color="#FFFFFF"/>'
           f'<stop offset="1" stop-color="{PEACH}"/></linearGradient></defs>',
           f'<rect width="{W}" height="{H}" fill="{DEEP}"/><rect width="{W}" height="{H}" fill="url(#glow)"/>',
           # Big peach cookie behind the phone: the brand shape.
           f'<g transform="translate(530 250) scale({s}) translate(54 54) scale(1.6) translate(-54 -54)"><path d="{COOKIE}" fill="{PEACH}" opacity="0.95"/></g>',
           '<text x="64" y="226" font-family="Inter Display" font-weight="700" font-size="104" letter-spacing="-4" fill="url(#title)">SendAhead</text>',
           f'<text font-family="Inter" font-size="34" fill="{LILAC}"><tspan x="68" y="290">Write it now.</tspan><tspan x="68" y="334">Send it later.</tspan></text>',
           # small mark under the tagline
           f'<g transform="translate(64 372) scale({64/108})"><rect width="108" height="108" rx="32" fill="{INDIGO}"/>'
           f'<g transform="translate(54 54) scale(0.95) translate(-54 -54)"><path d="{COOKIE}" fill="{PEACH}"/>{glyph(INDIGO)}</g></g>',
           f'<text x="146" y="414" font-family="Inter" font-weight="700" font-size="24" fill="#FFFFFF">Offline. Open source.</text>',
           # phone bleeding off the bottom edge
           phone(690, 70, 270, embed("home.png"), "fg"),
           '</svg>']
    open(os.path.join(OUT, "featureGraphic.svg"), "w").write("".join(svg))


def icon():
    # Full-bleed square; Play applies its own mask. Matches the adaptive launcher icon.
    s = 512 / 108
    svg = (f'<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 512 512">'
           f'<rect width="512" height="512" fill="{INDIGO}"/>'
           f'<g transform="scale({s}) translate(54 54) scale(0.7) translate(-54 -54)"><path d="{COOKIE}" fill="{PEACH}"/>{glyph(INDIGO)}</g></svg>')
    open(os.path.join(OUT, "icon.svg"), "w").write(svg)


feature_graphic()
icon()
