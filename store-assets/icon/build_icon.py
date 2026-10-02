#!/usr/bin/env python3
"""Builds every LayerLink icon asset from one drawing: "two screens, linked".

A portrait phone (cyan) and a landscape browser window (white) drawn as two chain links
that interlock: the phone passes over the window at the top crossing and under it at the
bottom one. The "under" strand is simply left out where the other crosses it, so the same
paths work in colour, as a one-colour themed icon, and as a status-bar glyph.

Outputs (paths relative to the repo root):
  app/src/main/res/drawable/ic_launcher_foreground.xml   adaptive icon foreground (vector)
  app/src/main/res/drawable/ic_launcher_monochrome.xml   Android 13+ themed icon layer
  app/src/main/res/drawable/ic_notification.xml          status bar / notification glyph
  app/src/main/res/mipmap-*/ic_launcher(_round).png      legacy rasters
  store-assets/icon/layerlink-icon.svg                   master artwork, 108 dp canvas
  store-assets/icon_512.png                              Play Store icon
  store-assets/feature_graphic_1024x500.png              Play Store feature graphic

Run from the repo root:  python3 store-assets/icon/build_icon.py
The vector XMLs need only Python. The PNGs need Playwright with a Chromium build
(PLAYWRIGHT_CHROMIUM=/path/to/chrome to point at a specific binary).
"""
import base64
import glob
import math
import os
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))

NAVY = "#0B1120"
WINDOW = "#F1F5F9"
BAR = "#7DD3FC"
CYAN_LIGHT = "#7DD3FC"
CYAN_DEEP = "#0EA5E9"

# ---- the drawing, in its own units (centre-lines) -----------------------------------------
STROKE = 8.0
PHONE = dict(x=0, y=0, w=30, h=42, r=9)
WINDOW_RECT = dict(x=15, y=28, w=38, h=28, r=8)
GAP_HALF = 5.5  # half of the break left in the under-strand at each crossing
CAMERA = (15, 7.5, 2.4)  # cx, cy, r
BAR_LINE = ((37, 36.5), (45, 36.5), 3.0)  # from, to, stroke


def fit(target_radius, centre):
    """Scale + offset that centres the drawing and fits it inside a circle of target_radius."""
    pts = []
    for q in (PHONE, WINDOW_RECT):
        x, y, w, h, r = q["x"], q["y"], q["w"], q["h"], q["r"]
        for cx, cy in ((x + r, y + r), (x + w - r, y + r), (x + w - r, y + h - r), (x + r, y + h - r)):
            for i in range(0, 360, 2):
                a = math.radians(i)
                pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    half = STROKE / 2
    minx = min(p[0] for p in pts) - half
    maxx = max(p[0] for p in pts) + half
    miny = min(p[1] for p in pts) - half
    maxy = max(p[1] for p in pts) + half
    cx, cy = (minx + maxx) / 2, (miny + maxy) / 2
    rmax = max(math.hypot(p[0] - cx, p[1] - cy) for p in pts) + half
    s = target_radius / rmax
    return s, centre[0] - cx * s, centre[1] - cy * s


def fit_box(size, pad):
    """Scale + offset that fits the drawing's bounding box into a size x size square."""
    minx, maxx = PHONE["x"] - STROKE / 2, WINDOW_RECT["x"] + WINDOW_RECT["w"] + STROKE / 2
    miny, maxy = PHONE["y"] - STROKE / 2, WINDOW_RECT["y"] + WINDOW_RECT["h"] + STROKE / 2
    s = (size - 2 * pad) / max(maxx - minx, maxy - miny)
    return s, size / 2 - (minx + maxx) / 2 * s, size / 2 - (miny + maxy) / 2 * s


def fmt(v):
    return f"{v:.3f}".rstrip("0").rstrip(".")


class Pen:
    def __init__(self, s, tx, ty):
        self.s, self.tx, self.ty = s, tx, ty
        self.d = []

    def p(self, x, y):
        return f"{fmt(self.s * x + self.tx)},{fmt(self.s * y + self.ty)}"

    def M(self, x, y): self.d.append("M" + self.p(x, y))
    def L(self, x, y): self.d.append("L" + self.p(x, y))

    def A(self, r, x, y):  # quarter arc, clockwise in y-down space
        rr = fmt(self.s * r)
        self.d.append(f"A{rr},{rr} 0 0 1 {self.p(x, y)}")

    def path(self):
        out, self.d = "".join(self.d), []
        return out


def phone_path(pen):
    # Break on the bottom edge where the window's left edge passes over it.
    x, y, w, h, r = PHONE["x"], PHONE["y"], PHONE["w"], PHONE["h"], PHONE["r"]
    gx = WINDOW_RECT["x"]
    pen.M(gx - GAP_HALF, y + h)
    pen.L(x + r, y + h); pen.A(r, x, y + h - r)
    pen.L(x, y + r); pen.A(r, x + r, y)
    pen.L(x + w - r, y); pen.A(r, x + w, y + r)
    pen.L(x + w, y + h - r); pen.A(r, x + w - r, y + h)
    pen.L(gx + GAP_HALF, y + h)
    return pen.path()


def window_path(pen):
    # Break on the top edge where the phone's right edge passes over it.
    x, y, w, h, r = WINDOW_RECT["x"], WINDOW_RECT["y"], WINDOW_RECT["w"], WINDOW_RECT["h"], WINDOW_RECT["r"]
    gx = PHONE["x"] + PHONE["w"]
    pen.M(gx + GAP_HALF, y)
    pen.L(x + w - r, y); pen.A(r, x + w, y + r)
    pen.L(x + w, y + h - r); pen.A(r, x + w - r, y + h)
    pen.L(x + r, y + h); pen.A(r, x, y + h - r)
    pen.L(x, y + r); pen.A(r, x + r, y)
    pen.L(gx - GAP_HALF, y)
    return pen.path()


def camera_path(pen):
    cx, cy, r = CAMERA
    pen.M(cx - r, cy)
    pen.d.append(f"A{fmt(pen.s * r)},{fmt(pen.s * r)} 0 1 1 {pen.p(cx + r, cy)}")
    pen.d.append(f"A{fmt(pen.s * r)},{fmt(pen.s * r)} 0 1 1 {pen.p(cx - r, cy)}Z")
    return pen.path()


def bar_path(pen):
    (x1, y1), (x2, y2), _ = BAR_LINE
    pen.M(x1, y1); pen.L(x2, y2)
    return pen.path()


def geometry(s, tx, ty):
    pen = Pen(s, tx, ty)
    return dict(
        phone=phone_path(pen), window=window_path(pen), camera=camera_path(pen), bar=bar_path(pen),
        stroke=fmt(STROKE * s), bar_stroke=fmt(BAR_LINE[2] * s),
        g0=pen.p(PHONE["x"], PHONE["y"]).split(","), g1=pen.p(PHONE["x"] + PHONE["w"], PHONE["y"] + PHONE["h"]).split(","),
    )


# ---- Android vector drawables -------------------------------------------------------------
HEADER = '<?xml version="1.0" encoding="utf-8"?>\n'


def gradient(g):
    return f'''            <aapt:attr name="{{name}}">
                <gradient
                    android:endX="{g["g1"][0]}"
                    android:endY="{g["g1"][1]}"
                    android:startColor="{CYAN_LIGHT}"
                    android:endColor="{CYAN_DEEP}"
                    android:startX="{g["g0"][0]}"
                    android:startY="{g["g0"][1]}"
                    android:type="linear" />
            </aapt:attr>'''


def foreground_xml():
    s, tx, ty = fit(33.0, (54, 54))
    g = geometry(s, tx, ty)
    grad = gradient(g)
    return HEADER + f'''<!-- LayerLink launcher icon, "two screens, linked". Generated by
     store-assets/icon/build_icon.py - edit that, not this file. 108dp adaptive-icon canvas;
     the mark fits inside the 66dp safe zone so no launcher mask crops it. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">

    <!-- The browser window your viewer watches on -->
    <path
        android:pathData="{g["window"]}"
        android:strokeColor="{WINDOW}"
        android:strokeWidth="{g["stroke"]}" />
    <path
        android:pathData="{g["bar"]}"
        android:strokeColor="{BAR}"
        android:strokeLineCap="round"
        android:strokeWidth="{g["bar_stroke"]}" />

    <!-- Your phone -->
    <path
        android:pathData="{g["phone"]}"
        android:strokeWidth="{g["stroke"]}">
{grad.replace("{name}", "android:strokeColor")}
    </path>
    <path android:pathData="{g["camera"]}">
{grad.replace("{name}", "android:fillColor")}
    </path>
</vector>
'''


def mono_xml(name, size, s, tx, ty, comment, with_bar=True):
    g = geometry(s, tx, ty)
    bar = f'''
    <path
        android:pathData="{g["bar"]}"
        android:strokeColor="#FFFFFFFF"
        android:strokeLineCap="round"
        android:strokeWidth="{g["bar_stroke"]}" />''' if with_bar else ""
    return HEADER + f'''<!-- {comment} Generated by store-assets/icon/build_icon.py - edit that, not this file. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="{size}dp"
    android:height="{size}dp"
    android:viewportWidth="{size}"
    android:viewportHeight="{size}">
    <path
        android:pathData="{g["window"]}"
        android:strokeColor="#FFFFFFFF"
        android:strokeWidth="{g["stroke"]}" />{bar}
    <path
        android:pathData="{g["phone"]}"
        android:strokeColor="#FFFFFFFF"
        android:strokeWidth="{g["stroke"]}" />
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="{g["camera"]}" />
</vector>
'''


# ---- SVG master + rasters ------------------------------------------------------------------
def svg(view="0 0 108 108", size=108, clip=None, background=True):
    s, tx, ty = fit(33.0, (54, 54))
    g = geometry(s, tx, ty)
    clip_def = clip_use = ""
    if clip == "circle":
        clip_def = '<clipPath id="c"><circle cx="54" cy="54" r="36"/></clipPath>'
        clip_use = ' clip-path="url(#c)"'
    bg = f'<rect width="108" height="108" fill="{NAVY}"/>' if background else ""
    return f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="{view}" width="{size}" height="{size}">
  <defs>
    <linearGradient id="phone" gradientUnits="userSpaceOnUse" x1="{g["g0"][0]}" y1="{g["g0"][1]}" x2="{g["g1"][0]}" y2="{g["g1"][1]}">
      <stop offset="0" stop-color="{CYAN_LIGHT}"/><stop offset="1" stop-color="{CYAN_DEEP}"/>
    </linearGradient>{clip_def}
  </defs>
  <g{clip_use}>
    {bg}
    <path d="{g["window"]}" fill="none" stroke="{WINDOW}" stroke-width="{g["stroke"]}"/>
    <path d="{g["bar"]}" fill="none" stroke="{BAR}" stroke-width="{g["bar_stroke"]}" stroke-linecap="round"/>
    <path d="{g["phone"]}" fill="none" stroke="url(#phone)" stroke-width="{g["stroke"]}"/>
    <path d="{g["camera"]}" fill="url(#phone)"/>
  </g>
</svg>'''


def write(rel, text):
    path = os.path.join(ROOT, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)
    print("wrote", rel)


def font_face(weight, file):
    data = base64.b64encode(open(os.path.join(ROOT, "core/src/main/res/font", file), "rb").read()).decode()
    return f"@font-face{{font-family:'SG';font-weight:{weight};src:url(data:font/ttf;base64,{data}) format('truetype');}}"


def feature_graphic_html():
    icon = svg(view="18 18 72 72", size=300)
    faces = font_face(700, "space_grotesk_bold.ttf") + font_face(400, "space_grotesk_regular.ttf")
    return f'''<!doctype html><html><head><meta charset="utf-8"><style>
{faces}
html,body{{margin:0;width:1024px;height:500px;overflow:hidden;background:{NAVY};}}
.wrap{{position:relative;width:1024px;height:500px;display:flex;align-items:center;gap:56px;padding-left:96px;box-sizing:border-box;
  background:radial-gradient(circle at 250px 250px,#11223f 0,#0d1830 160px,{NAVY} 340px);}}
.mark{{width:300px;height:300px;border-radius:66px;overflow:hidden;flex:none;box-shadow:0 30px 70px -20px rgba(14,165,233,.35),0 0 0 1px rgba(148,163,184,.10);}}
.mark svg{{display:block;width:300px;height:300px;}}
h1{{font-family:'SG';font-weight:700;font-size:92px;line-height:1;margin:0;color:#F8FAFC;letter-spacing:-1px;}}
p{{font-family:'SG';font-weight:400;font-size:31px;margin:18px 0 0;color:#94A3B8;}}
p b{{color:#38BDF8;font-weight:400;}}
</style></head><body><div class="wrap"><div class="mark">{icon}</div>
<div><h1>LayerLink</h1><p>Share your phone screen<br>to <b>any browser</b>.</p></div></div></body></html>'''


def rasters():
    try:
        from playwright.sync_api import sync_playwright
    except ImportError:
        print("playwright not installed - skipping PNGs", file=sys.stderr)
        return
    exe = os.environ.get("PLAYWRIGHT_CHROMIUM") or next(iter(glob.glob("/opt/pw-browsers/chromium-*/chrome-linux/chrome")), None)
    densities = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    with sync_playwright() as p:
        browser = p.chromium.launch(executable_path=exe) if exe else p.chromium.launch()

        def shot(html, w, h, rel, transparent=False):
            page = browser.new_page(viewport={"width": w, "height": h}, device_scale_factor=1)
            page.set_content(html)
            page.screenshot(path=os.path.join(ROOT, rel), omit_background=transparent,
                            clip={"x": 0, "y": 0, "width": w, "height": h})
            page.close()
            print("wrote", rel)

        def bare(markup, size):
            return f'<html><body style="margin:0;background:transparent">{markup}</body></html>'.replace(
                f'width="{size}" height="{size}"', f'width="{size}" height="{size}" style="display:block"')

        for dpi, px in densities.items():
            # Legacy square icon: the visible 72dp area, full bleed (minSdk 26 always uses the
            # adaptive icon, so these are only a fallback for tools that read PNGs).
            shot(bare(svg(view="18 18 72 72", size=px), px), px, px, f"app/src/main/res/mipmap-{dpi}/ic_launcher.png")
            shot(bare(svg(view="18 18 72 72", size=px, clip="circle"), px), px, px,
                 f"app/src/main/res/mipmap-{dpi}/ic_launcher_round.png", transparent=True)
        # Play Store icon: full-bleed 512 square; Play applies its own corner mask.
        shot(bare(svg(view="18 18 72 72", size=512), 512), 512, 512, "store-assets/icon_512.png")
        shot(feature_graphic_html(), 1024, 500, "store-assets/feature_graphic_1024x500.png")
        browser.close()


def main():
    write("app/src/main/res/drawable/ic_launcher_foreground.xml", foreground_xml())
    s, tx, ty = fit(26.0, (54, 54))
    write("app/src/main/res/drawable/ic_launcher_monochrome.xml",
          mono_xml("mono", 108, s, tx, ty,
                   "LayerLink themed-icon layer (Android 13+): one colour, tinted by the system to match the wallpaper."))
    s, tx, ty = fit_box(24, 1.5)
    write("app/src/main/res/drawable/ic_notification.xml",
          mono_xml("notif", 24, s, tx, ty,
                   "LayerLink status-bar and notification glyph. White on transparent, as Android requires.",
                   with_bar=False))
    write("store-assets/icon/layerlink-icon.svg", svg() + "\n")
    rasters()


if __name__ == "__main__":
    main()
