"""SendAhead Play screenshots in the same light 'callout' style as Halo: eyebrow + big headline
with a highlighter swoosh, phone bleeding off the bottom, zoomed callout cards with soft shadows,
handwritten notes and arrows. Indigo + peach to match the app.

usage: python3 docs/play_screenshots.py <dir with home/compose/unlock.png (1080x2424)> <out dir>
then:  for f in <out dir>/*.svg; do rsvg-convert "$f" -o "${f%.svg}.png"; done
"""
import base64, os, re, sys
from PIL import ImageFont

SRC, OUT = sys.argv[1], sys.argv[2]
os.makedirs(OUT, exist_ok=True)
W, H = 1080, 1920
SW, SH = 1080, 2424
INK, QUIET, INDIGO, PEACH, HANDC, BG = "#1B1B21", "#5E5C6B", "#3E3C8F", "#FFB59A", "#3E3C8F", "#F5F2FA"
HAND = "TeX Gyre Chorus"
_FONT = "/usr/share/fonts/opentype/inter/InterDisplay-Bold.otf"
_BOLD = "/usr/share/fonts/opentype/inter/Inter-Bold.otf"

# Brand mark straight from the launcher icon, so the screenshots never drift from the app.
_ICON = os.path.join(os.path.dirname(__file__), "..", "app/src/main/res/drawable/ic_launcher_foreground.xml")
_paths = re.findall(r'android:pathData="([^"]+)"', open(_ICON).read())
COOKIE, RING, HAND1, HAND2, PLANE = _paths

_cache = {}
def img(name):
    if name not in _cache:
        with open(os.path.join(SRC, name), "rb") as f:
            _cache[name] = "data:image/png;base64," + base64.b64encode(f.read()).decode()
    return _cache[name]

def head():
    grid = "".join(f'<line x1="{x}" y1="0" x2="{x}" y2="{H}"/>' for x in range(60, W, 60)) + \
           "".join(f'<line x1="0" y1="{y}" x2="{W}" y2="{y}"/>' for y in range(60, H, 60))
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">
<defs>
  <filter id="shadow" x="-30%" y="-30%" width="160%" height="160%">
    <feGaussianBlur in="SourceAlpha" stdDeviation="22"/><feOffset dy="18" result="b"/>
    <feComponentTransfer><feFuncA type="linear" slope="0.20"/></feComponentTransfer>
    <feMerge><feMergeNode/><feMergeNode in="SourceGraphic"/></feMerge>
  </filter>
  <filter id="soft" x="-30%" y="-30%" width="160%" height="160%">
    <feGaussianBlur in="SourceAlpha" stdDeviation="10"/><feOffset dy="8" result="b"/>
    <feComponentTransfer><feFuncA type="linear" slope="0.18"/></feComponentTransfer>
    <feMerge><feMergeNode/><feMergeNode in="SourceGraphic"/></feMerge>
  </filter>
</defs>
<rect width="{W}" height="{H}" fill="{BG}"/>
<g stroke="#E7E3EF" stroke-width="2">{grid}</g>'''

def text_w(s, size, spacing=None):
    spacing = -size * 0.035 if spacing is None else spacing
    return ImageFont.truetype(_FONT, int(size)).getlength(s) + spacing * len(s)

def headline(lines, size, y0, eyebrow=None, mark=None):
    """mark = (line index, words) to underline with a peach highlighter swoosh."""
    out = []
    y = y0
    if eyebrow:
        out.append(f'<text x="{W/2}" y="{y}" text-anchor="middle" font-family="Inter" font-weight="800" font-size="36" letter-spacing="5" fill="{INDIGO}">{eyebrow}</text>')
        y += size * 1.05
    for i, line in enumerate(lines):
        if mark and mark[0] == i:
            lw = text_w(line, size)
            start = W / 2 - lw / 2 + text_w(line[: line.index(mark[1])], size)
            ww = text_w(mark[1], size)
            out.append(f'<path d="M{start-6:.0f},{y+18} Q{start+ww/2:.0f},{y+4} {start+ww+10:.0f},{y+14}" fill="none" stroke="{PEACH}" stroke-width="24" stroke-linecap="round" opacity="0.85"/>')
        out.append(f'<text x="{W/2}" y="{y}" text-anchor="middle" font-family="Inter Display" font-weight="700" font-size="{size}" letter-spacing="{-size*0.035:.1f}" fill="{INK}">{line}</text>')
        y += size * 1.02
    return "".join(out), y

def phone(x, y, w, name, cid):
    h = w * SH / SW
    b = 16
    return (f'<g filter="url(#shadow)"><rect x="{x-b}" y="{y-b}" width="{w+2*b}" height="{h+2*b}" rx="{78+b}" fill="#101014"/></g>'
            f'<clipPath id="{cid}"><rect x="{x}" y="{y}" width="{w}" height="{h}" rx="78"/></clipPath>'
            f'<image href="{img(name)}" x="{x}" y="{y}" width="{w}" height="{h}" clip-path="url(#{cid})" preserveAspectRatio="none"/>'
            f'<circle cx="{x+w/2}" cy="{y+30}" r="13" fill="#0A0A0C"/>')

def callout(name, crop, x, y, w, rot, cid, rx=44, pad=0, fill="#FFFFFF"):
    """A zoomed piece of a screenshot as a floating rounded card."""
    cx, cy, cw, ch = crop
    h = w * ch / cw
    return (f'<g transform="rotate({rot} {x+w/2} {y+h/2})"><g filter="url(#shadow)">'
            f'<rect x="{x-pad}" y="{y-pad}" width="{w+2*pad}" height="{h+2*pad}" rx="{rx+pad}" fill="{fill}"/></g>'
            f'<clipPath id="{cid}"><rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{rx}"/></clipPath>'
            f'<svg x="{x}" y="{y}" width="{w}" height="{h}" viewBox="{cx} {cy} {cw} {ch}" preserveAspectRatio="none" clip-path="url(#{cid})">'
            f'<image href="{img(name)}" width="{SW}" height="{SH}"/></svg></g>'), h

def hand(text, x, y, size, rot, anchor="start", color=HANDC):
    return (f'<text x="{x}" y="{y}" transform="rotate({rot} {x} {y})" text-anchor="{anchor}" font-family="{HAND}" '
            f'font-size="{size}" fill="{color}" stroke="{color}" stroke-width="{size*0.025:.1f}">{text}</text>')

def arrow(d, color=HANDC):
    return f'<path d="{d}" fill="none" stroke="{color}" stroke-width="7" stroke-linecap="round" stroke-linejoin="round"/>'

def badge(n, cx, cy):
    return (f'<g filter="url(#soft)"><circle cx="{cx}" cy="{cy}" r="30" fill="{INDIGO}"/></g>'
            f'<text x="{cx}" y="{cy+12}" text-anchor="middle" font-family="Inter" font-weight="700" font-size="32" fill="#FFFFFF">{n}</text>')

def mark(cx, cy, size, bg=INDIGO, shape=PEACH, ink=INDIGO):
    """The SendAhead icon: clock + plane on the peach cookie, on an indigo rounded square."""
    s = size / 108
    return (f'<g filter="url(#shadow)"><rect x="{cx-size/2}" y="{cy-size/2}" width="{size}" height="{size}" rx="{size*0.3}" fill="{bg}"/></g>'
            f'<g transform="translate({cx-size/2} {cy-size/2}) scale({s}) translate(54 54) scale(0.95) translate(-54 -54)">'
            f'<path d="{COOKIE}" fill="{shape}"/>'
            + "".join(f'<path d="{d}" fill="none" stroke="{ink}" stroke-width="5.2" stroke-linecap="round"/>' for d in (RING, HAND1, HAND2))
            + f'<path d="{PLANE}" fill="{ink}"/></g>')

def pill(cx, y, label, dark=True):
    w = ImageFont.truetype(_BOLD, 34).getlength(label) + 150
    x = cx - w / 2
    bg, fg = (INDIGO, "#FFFFFF") if dark else ("#FFFFFF", INK)
    return (f'<g filter="url(#shadow)"><rect x="{x}" y="{y}" width="{w}" height="104" rx="52" fill="{bg}"/></g>'
            f'<circle cx="{x+58}" cy="{y+52}" r="24" fill="{PEACH}"/><path d="M{x+47},{y+52} l8,9 l16,-18" fill="none" stroke="{INDIGO}" stroke-width="6" stroke-linecap="round" stroke-linejoin="round"/>'
            f'<text x="{x+100}" y="{y+64}" font-family="Inter" font-weight="700" font-size="34" fill="{fg}">{label}</text>')

def save(n, body):
    open(os.path.join(OUT, f"{n}.svg"), "w").write(head() + body + "</svg>")


# 1. Hero: the promise, the scheduled list as a floating card, three steps in handwriting.
t, _ = headline(["Write it now,", "send it later"], 140, 250, mark=(1, "later"))
s = t
c, ch = callout("home.png", (42, 786, 996, 714), 90, 600, 900, -2, "c1a", rx=48, pad=14)
s += c
y0 = 600 + ch + 120
s += badge(1, 150, y0) + hand("write it,", 205, y0 + 22, 90, -5)
s += badge(2, 150, y0 + 125) + hand("pick a time,", 205, y0 + 147, 90, -5)
s += badge(3, 150, y0 + 250) + hand("it sends itself!", 205, y0 + 272, 90, -5)
s += arrow(f"M760,{y0+215} Q860,{y0+170} 850,{y0+60} M826,{y0+82} l24,-24 l22,26")
s += pill(W / 2, 1770, "No account. Works offline.")
save(1, s)

# 2. Compose: phone on the left, the date/time tiles zoomed out to the right.
t, _ = headline(["Pick the moment"], 96, 170, eyebrow="SCHEDULE", mark=(0, "moment"))
PX, PW = 60, 620
s = t + phone(PX, 470, PW, "compose.png", "p2")
c, ch = callout("compose.png", (40, 1745, 1000, 345), 470, 1000, 580, 3, "c2a", rx=44, pad=16)
s += c
c2, ch2 = callout("compose.png", (40, 2108, 1000, 104), 430, 1330, 620, -2, "c2b", rx=30, pad=12)
s += c2
s += hand("or just", 715, 1600, 96, -8)
s += hand("one tap!", 735, 1700, 96, -8)
s += arrow("M880,1540 Q910,1500 890,1470 M870,1490 l20,-22 l22,20")
save(2, s)

# 3. Groups: the recipient picker big, with a note pointing at the group icon.
t, _ = headline(["Contacts and", "groups too"], 104, 190, eyebrow="ANYONE", mark=(1, "groups"))
s = t
c, ch = callout("compose.png", (30, 430, 1020, 430), 70, 560, 940, -2, "c3a", rx=48, pad=16)
s += c
s += hand("picked right", 470, 1130, 88, -6)
s += hand("from WhatsApp!", 440, 1230, 88, -6)
s += arrow("M930,1080 Q990,1010 960,960 M938,982 l22,-24 l20,26")
c2, ch2 = callout("compose.png", (40, 1505, 330, 110), 110, 1370, 380, 4, "c3b", rx=36, pad=12)
s += c2
s += hand("+ photos &amp; files too", 120, 1620, 84, -4)
s += pill(W / 2, 1750, "WhatsApp and Business")
save(3, s)

# 4. Locked phone: the auto-unlock sheet on the phone, PIN field zoomed out.
t, _ = headline(["Sends even", "when locked"], 104, 190, eyebrow="ON TIME", mark=(1, "locked"))
s = t + phone(PX, 560, PW, "unlock.png", "p4")
c, ch = callout("unlock.png", (55, 1135, 970, 330), 480, 1130, 570, 3, "c4a", rx=40, pad=16)
s += c
s += hand("types", 735, 1500, 92, -8)
s += hand("your PIN", 715, 1590, 92, -8)
s += hand("for you", 735, 1680, 92, -8)
s += arrow("M860,1420 Q890,1390 870,1360 M850,1380 l20,-22 l22,20")
s += pill(W / 2, 1770, "Or waits for your unlock", dark=False)
save(4, s)

# 5. Privacy: floating promise cards.
t, _ = headline(["Nothing leaves", "your phone"], 110, 190, eyebrow="PRIVATE", mark=(1, "phone"))
s = t
rows = [("No internet permission", "SendAhead can't send anything out", -3),
        ("No account, no ads", "Nothing to sign up for, nothing tracked", 2),
        ("Open source", "Read every line on GitHub", -2)]
y = 640
for title, body, rot in rows:
    s += (f'<g transform="rotate({rot} {W/2} {y+110})"><g filter="url(#shadow)"><rect x="110" y="{y}" width="860" height="220" rx="56" fill="#FFFFFF"/></g>'
          f'<circle cx="230" cy="{y+110}" r="54" fill="{INDIGO}"/><path d="M204,{y+110} l18,20 l34,-38" fill="none" stroke="{PEACH}" stroke-width="12" stroke-linecap="round" stroke-linejoin="round"/>'
          f'<text x="320" y="{y+100}" font-family="Inter" font-weight="700" font-size="46" fill="{INK}">{title}</text>'
          f'<text x="320" y="{y+156}" font-family="Inter" font-size="34" fill="{QUIET}">{body}</text></g>')
    y += 290
s += hand("promise!", 600, 1620, 120, -10)
s += mark(240, 1640, 170)
save(5, s)
