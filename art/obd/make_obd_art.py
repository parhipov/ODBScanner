"""
Illustrations for the «Где разъём OBD» block of the guide tab: the driver's footwell of each car
with the OBD socket lit up and an ELM327 adapter showing where it goes.

    python art/obd/make_obd_art.py            # all cars
    python art/obd/make_obd_art.py polo cts   # some

Writes art/obd/obd_<car>.svg (source) and app/src/main/res/drawable-nodpi/obd_<car>.webp, rendered
with headless Edge (needs Pillow for the WebP step). No text inside the pictures: captions are in res/values/guide.xml (translatable).
Socket positions: klavkarr.com and owners' forums (see GuideScreen.kt); Polo — checked on the car.
"""
import math
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
RES = os.path.join(ROOT, "app", "src", "main", "res", "drawable-nodpi")
EDGE = r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"

W, H = 1080, 660
COLUMN = 600

# Bottom edge of the knee panel: quadratic from P0 (right) through control P1 to P2 (left).
P0, P1, P2 = (1080, 262), (560, 350), (100, 300)


def lip_y(x):
    """y of the knee panel's bottom edge at x (solves the quadratic for t)."""
    lo, hi = 0.0, 1.0
    for _ in range(60):
        t = (lo + hi) / 2
        bx = (1 - t) ** 2 * P0[0] + 2 * (1 - t) * t * P1[0] + t * t * P2[0]
        if bx > x:
            lo = t
        else:
            hi = t
    t = (lo + hi) / 2
    return (1 - t) ** 2 * P0[1] + 2 * (1 - t) * t * P1[1] + t * t * P2[1]


CARS = {
    # Door side of the column, above the footrest; automatic.
    "cts": dict(dash="#2c2e33", dash_hi="#3b3e45", carpet="#1c1d20", pedals=2, socket=(300, None), adapter=(0, 215)),
    # Below and left of the wheel (checked on the car); manual.
    "polo": dict(dash="#373a40", dash_hi="#484c54", carpet="#242528", pedals=3, socket=(455, None), adapter=(-205, 200)),
    # Near the door, right of the hood release, tucked under the fuse block.
    "rav4": dict(dash="#45443f", dash_hi="#57554f", carpet="#2b2a27", pedals=2, socket=(300, None), hood=160, fuse=(190, 150, 200, 110), adapter=(30, 215)),
    # Left under the column, by the hood release, under the fuse/relay block; manual.
    "vesta": dict(dash="#2a2c30", dash_hi="#393c42", carpet="#1e1f22", pedals=3, socket=(370, None), hood=175, fuse=(270, 150, 200, 110), adapter=(-120, 215)),
    # Left under the wheel, above the footrest, behind the fuse box cover; manual.
    "solaris": dict(dash="#3a3e45", dash_hi="#4b5058", carpet="#242629", pedals=3, socket=(285, 232), covered=(175, 170, 220, 125), adapter=(20, 310)),
}


def shade(hex_color, k):
    """Lighter (k > 0) or darker (k < 0) version of a colour."""
    c = [int(hex_color[i:i + 2], 16) for i in (1, 3, 5)]
    c = [min(255, max(0, int(v + (255 - v) * k if k > 0 else v * (1 + k)))) for v in c]
    return "#%02x%02x%02x" % tuple(c)


def pedal(x, y, w, h, arm_top, arm_x=None):
    """A hanging pedal: metal arm from under the dash, rubber pad with ribs."""
    ax = arm_x if arm_x is not None else x
    ribs = "".join(
        f'<line x1="{x - w / 2 + 10}" y1="{y + 12 + i * (h - 24) / 5:.1f}" x2="{x + w / 2 - 10}" y2="{y + 12 + i * (h - 24) / 5:.1f}" '
        f'stroke="#0b0c0e" stroke-width="3" stroke-linecap="round" opacity=".55"/>'
        for i in range(6)
    )
    return f"""
    <path d="M{ax - 9},{arm_top} L{ax + 9},{arm_top} L{x + 8},{y + 6} L{x - 8},{y + 6} Z" fill="url(#metal)" mask="url(#armFade)"/>
    <g filter="url(#softShadow)">
      <rect x="{x - w / 2}" y="{y}" width="{w}" height="{h}" rx="14" fill="url(#rubber)"/>
    </g>
    {ribs}
    <rect x="{x - w / 2}" y="{y}" width="{w}" height="{h}" rx="14" fill="none" stroke="#ffffff" stroke-opacity=".06" stroke-width="2"/>"""


def socket(cx, cy, scale=1.0):
    """The 16-pin OBD-II socket: black trapezoid bezel, recess, two rows of pins."""
    w, h = 118 * scale, 54 * scale
    inset = 12 * scale
    pins = []
    for row in range(2):
        for i in range(8):
            px = cx - w / 2 + 22 * scale + i * (w - 44 * scale) / 7
            py = cy - 8 * scale + row * 16 * scale
            pins.append(f'<rect x="{px - 3.2 * scale:.1f}" y="{py - 4 * scale:.1f}" width="{6.4 * scale:.1f}" height="{8 * scale:.1f}" rx="1.5" fill="url(#pin)"/>')
    return f"""
    <g filter="url(#softShadow)">
      <path d="M{cx - w / 2 - 10 * scale},{cy - h / 2 - 8 * scale} L{cx + w / 2 + 10 * scale},{cy - h / 2 - 8 * scale}
               L{cx + w / 2 - 2 * scale},{cy + h / 2 + 8 * scale} L{cx - w / 2 + 2 * scale},{cy + h / 2 + 8 * scale} Z" fill="#141518"/>
    </g>
    <path d="M{cx - w / 2},{cy - h / 2} L{cx + w / 2},{cy - h / 2} L{cx + w / 2 - inset},{cy + h / 2} L{cx - w / 2 + inset},{cy + h / 2} Z"
          fill="#060607" stroke="#2a2c31" stroke-width="{2 * scale}"/>
    {''.join(pins)}
    <rect x="{cx - 9 * scale}" y="{cy - h / 2 - 8 * scale}" width="{18 * scale}" height="{6 * scale}" fill="#0a0b0c"/>"""


def adapter(x, y, angle):
    """A cheap ELM327 dongle (translucent blue body, plug on top, green LED), turned by angle (clockwise)."""
    return f"""
    <g transform="translate({x},{y}) rotate({angle:.1f}) scale(.85)" filter="url(#softShadow)">
      <path d="M-50,-58 L50,-58 L42,-30 L-42,-30 Z" fill="#101114"/>
      <rect x="-70" y="-34" width="140" height="92" rx="16" fill="url(#dongle)"/>
      <rect x="-70" y="-34" width="140" height="92" rx="16" fill="none" stroke="#9fd0ff" stroke-opacity=".35" stroke-width="2"/>
      <rect x="-54" y="-20" width="108" height="10" rx="5" fill="#ffffff" opacity=".18"/>
      <circle cx="44" cy="36" r="6" fill="#5dff8a"/>
      <circle cx="44" cy="36" r="14" fill="#5dff8a" opacity=".25" filter="url(#glow)"/>
    </g>"""


def svg(car):
    c = CARS[car]
    sx, sy = c["socket"]
    covered = c.get("covered")
    if sy is None:
        sy = lip_y(sx) + 34
    dash, dash_hi, carpet = c["dash"], c["dash_hi"], c["carpet"]
    parts = []

    # Pedals: footrest on the left, clutch (manual), brake, gas.
    pedals = []
    if c["pedals"] == 3:
        pedals.append(pedal(440, 468, 108, 78, 330))
    pedals.append(pedal(640, 470, 150 if c["pedals"] == 2 else 118, 82, 338))
    pedals.append(pedal(860, 440, 64, 170, 318))

    # Hood release lever on the end of the dash, near the door.
    hood = ""
    if c.get("hood"):
        hx = c["hood"]
        hy = lip_y(hx) + 6
        hood = f"""
        <g filter="url(#softShadow)">
          <rect x="{hx - 48}" y="{hy - 20}" width="96" height="40" rx="12" fill="#111215"/>
          <rect x="{hx - 38}" y="{hy + 4}" width="76" height="30" rx="10" fill="url(#rubber)"/>
        </g>
        <rect x="{hx - 30}" y="{hy + 8}" width="60" height="5" rx="2.5" fill="#fff" opacity=".12"/>
        <g transform="translate({hx - 16},{hy - 14})" fill="none" stroke="#c9ced6" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" opacity=".85">
          <path d="M2,20 L2,15 L8,9 L24,9 L30,15 L30,20 Z"/><path d="M8,9 L14,1"/>
          <circle cx="8" cy="21" r="2.5"/><circle cx="24" cy="21" r="2.5"/>
        </g>"""

    fuse = ""
    if c.get("fuse"):
        fx, fy, fw, fh = c["fuse"]
        fuse = f"""
        <rect x="{fx}" y="{fy}" width="{fw}" height="{fh}" rx="18" fill="{shade(dash, .04)}" stroke="#000" stroke-opacity=".55" stroke-width="3"/>
        <rect x="{fx + 2}" y="{fy + 2}" width="{fw - 4}" height="{fh - 4}" rx="16" fill="none" stroke="#fff" stroke-opacity=".06" stroke-width="2"/>
        <rect x="{fx + fw / 2 - 26}" y="{fy + fh - 22}" width="52" height="10" rx="5" fill="#000" opacity=".45"/>"""

    cover = ""
    if covered:
        cx0, cy0, cw, ch = covered
        # The opening with the socket inside, and the removed cover lying below it.
        cover = f"""
        <rect x="{cx0}" y="{cy0}" width="{cw}" height="{ch}" rx="16" fill="#08090b"/>
        <rect x="{cx0}" y="{cy0}" width="{cw}" height="{ch}" rx="16" fill="url(#cavity)"/>
        <rect x="{cx0}" y="{cy0}" width="{cw}" height="{ch}" rx="16" fill="none" stroke="#000" stroke-width="4"/>"""
        removed = f"""
        <g transform="translate({cx0 + cw + 50},{cy0 + 70}) rotate(16) scale(.8)" filter="url(#softShadow)" opacity=".92">
          <rect x="0" y="0" width="{cw}" height="{ch}" rx="16" fill="{shade(dash, .08)}"/>
          <rect x="2" y="2" width="{cw - 4}" height="{ch - 4}" rx="14" fill="none" stroke="#fff" stroke-opacity=".08" stroke-width="2"/>
          <rect x="{cw / 2 - 26}" y="{ch - 22}" width="52" height="10" rx="5" fill="#000" opacity=".45"/>
        </g>
        <path d="M{cx0 + cw + 8},{cy0 + ch / 2} q24,6 44,34" fill="none" stroke="#FFC857" stroke-width="5" stroke-dasharray="2 12" stroke-linecap="round" opacity=".9"/>"""
    else:
        removed = ""

    # Adapter: at its own offset from the socket, plug turned to it, with a dashed "plug it in" path.
    dx, dy = c["adapter"]
    ax, ay = sx + dx, sy + dy
    angle = math.degrees(math.atan2(-dx, dy))
    ux, uy = -dx / math.hypot(dx, dy), -dy / math.hypot(dx, dy)
    x0, y0 = ax + ux * 62, ay + uy * 62
    r = 86 if covered else 96
    x1, y1 = sx - ux * (r + 8), sy - uy * (r + 8)
    bend = 30 if dx else 0
    arrow = f"""
        <path d="M{x0:.0f},{y0:.0f} Q{(x0 + x1) / 2 + bend:.0f},{(y0 + y1) / 2:.0f} {x1:.0f},{y1:.0f}"
              fill="none" stroke="#FFC857" stroke-width="6" stroke-dasharray="2 14" stroke-linecap="round"/>
        <g transform="translate({x1:.0f},{y1:.0f}) rotate({angle:.1f})"><path d="M0,-4 l-14,22 l28,0 z" fill="#FFC857"/></g>"""

    parts.append(f"""
<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">
  <defs>
    <linearGradient id="well" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#07080a"/><stop offset=".55" stop-color="#101216"/><stop offset="1" stop-color="#15171b"/>
    </linearGradient>
    <linearGradient id="panel" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="{dash_hi}"/><stop offset=".75" stop-color="{dash}"/><stop offset="1" stop-color="{shade(dash, -.35)}"/>
    </linearGradient>
    <linearGradient id="side" x1="0" y1="0" x2="1" y2="0">
      <stop offset="0" stop-color="{shade(dash, -.45)}"/><stop offset="1" stop-color="{shade(dash, -.15)}"/>
    </linearGradient>
    <linearGradient id="column" x1="0" y1="0" x2="1" y2="0">
      <stop offset="0" stop-color="{shade(dash, -.5)}"/><stop offset=".45" stop-color="{shade(dash_hi, .08)}"/><stop offset="1" stop-color="{shade(dash, -.55)}"/>
    </linearGradient>
    <linearGradient id="rim" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#1a1b1f"/><stop offset=".6" stop-color="#2d2f35"/><stop offset="1" stop-color="#101114"/>
    </linearGradient>
    <linearGradient id="metal" x1="0" y1="0" x2="1" y2="0">
      <stop offset="0" stop-color="#2a2c30"/><stop offset=".5" stop-color="#6b7079"/><stop offset="1" stop-color="#26282c"/>
    </linearGradient>
    <linearGradient id="rubber" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#34363b"/><stop offset="1" stop-color="#1b1c1f"/>
    </linearGradient>
    <linearGradient id="pin" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#f3e3b0"/><stop offset=".5" stop-color="#b89a52"/><stop offset="1" stop-color="#5e4c24"/>
    </linearGradient>
    <linearGradient id="dongle" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="#3a8be0" stop-opacity=".95"/><stop offset="1" stop-color="#123a72" stop-opacity=".95"/>
    </linearGradient>
    <linearGradient id="sill" x1="0" y1="0" x2="1" y2="0">
      <stop offset="0" stop-color="#3b3e44"/><stop offset=".6" stop-color="#8a8f98"/><stop offset="1" stop-color="#2b2d31"/>
    </linearGradient>
    <linearGradient id="underDash" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#030304" stop-opacity=".97"/><stop offset=".55" stop-color="#030304" stop-opacity=".6"/>
      <stop offset="1" stop-color="#030304" stop-opacity="0"/>
    </linearGradient>
    <linearGradient id="fadeDown" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#fff" stop-opacity="0"/><stop offset=".7" stop-color="#fff" stop-opacity="1"/>
    </linearGradient>
    <mask id="armFade" maskContentUnits="objectBoundingBox"><rect width="1" height="1" fill="url(#fadeDown)"/></mask>
    <radialGradient id="cavity" cx=".5" cy=".3" r=".8">
      <stop offset="0" stop-color="#000" stop-opacity="0"/><stop offset="1" stop-color="#000" stop-opacity=".8"/>
    </radialGradient>
    <radialGradient id="torch" cx="{sx / W}" cy="{sy / H}" r=".42" gradientUnits="objectBoundingBox">
      <stop offset="0" stop-color="#fff6dc" stop-opacity=".38"/><stop offset=".35" stop-color="#fff6dc" stop-opacity=".15"/>
      <stop offset="1" stop-color="#fff6dc" stop-opacity="0"/>
    </radialGradient>
    <radialGradient id="vignette" cx=".5" cy=".45" r=".75">
      <stop offset=".55" stop-color="#000" stop-opacity="0"/><stop offset="1" stop-color="#000" stop-opacity=".55"/>
    </radialGradient>
    <filter id="softShadow" x="-30%" y="-30%" width="160%" height="170%">
      <feDropShadow dx="0" dy="10" stdDeviation="10" flood-color="#000" flood-opacity=".55"/>
    </filter>
    <filter id="glow" x="-50%" y="-50%" width="200%" height="200%"><feGaussianBlur stdDeviation="8"/></filter>
    <filter id="carpetNoise" x="0" y="0" width="100%" height="100%">
      <feTurbulence type="fractalNoise" baseFrequency="1.4" numOctaves="2" seed="3"/>
      <feColorMatrix values="0 0 0 0 0  0 0 0 0 0  0 0 0 0 0  0 0 0 .55 0"/>
      <feComposite in2="SourceGraphic" operator="in"/>
    </filter>
    <filter id="grain" x="0" y="0" width="100%" height="100%">
      <feTurbulence type="fractalNoise" baseFrequency=".9" numOctaves="1" seed="8"/>
      <feColorMatrix values="0 0 0 0 1  0 0 0 0 1  0 0 0 0 1  0 0 0 .05 0"/>
      <feComposite in2="SourceGraphic" operator="in"/>
    </filter>
  </defs>

  <!-- Footwell, carpet, door sill -->
  <rect width="{W}" height="{H}" fill="url(#well)"/>
  <path d="M0,470 L1080,430 L1080,660 L0,660 Z" fill="{carpet}"/>
  <path d="M0,470 L1080,430 L1080,660 L0,660 Z" fill="#fff" filter="url(#carpetNoise)"/>
  <path d="M0,470 L1080,430 L1080,520 L0,560 Z" fill="#000" opacity=".35"/>
  <path d="M150,560 L330,540 L350,640 L165,660 Z" fill="#1a1b1e" filter="url(#softShadow)"/>
  {''.join(f'<line x1="{165 + i * 30}" y1="{556 - i * 3}" x2="{182 + i * 30}" y2="{650 - i * 3}" stroke="#000" stroke-opacity=".5" stroke-width="5"/>' for i in range(6))}
  <path d="M0,380 L100,392 L78,660 L0,660 Z" fill="url(#sill)"/>
  <path d="M0,380 L100,392 L96,410 L0,400 Z" fill="#000" opacity=".4"/>

  {''.join(pedals)}
  <path d="M100,{P2[1] - 10} Q{P1[0]},{P1[1] - 10} 1080,{P0[1] - 10} L1080,{P0[1] + 150} Q{P1[0]},{P1[1] + 130} 100,{P2[1] + 120} Z" fill="url(#underDash)"/>

  <!-- Dash end cap by the door, knee panel with its lip -->
  <path d="M0,0 L118,0 L104,392 L0,378 Z" fill="url(#side)"/>
  <path d="M100,0 L1080,0 L1080,{P0[1]} Q{P1[0]},{P1[1]} {P2[0]},{P2[1]} Z" fill="url(#panel)"/>
  <path d="M1080,{P0[1]} Q{P1[0]},{P1[1]} {P2[0]},{P2[1]} L{P2[0]},{P2[1] + 16} Q{P1[0]},{P1[1] + 18} 1080,{P0[1] + 14} Z" fill="{shade(dash, -.55)}"/>
  <path d="M1080,{P0[1] - 3} Q{P1[0]},{P1[1] - 3} {P2[0]},{P2[1] - 3}" fill="none" stroke="#fff" stroke-opacity=".10" stroke-width="3"/>
  <path d="M100,0 L1080,0 L1080,{P0[1]} Q{P1[0]},{P1[1]} {P2[0]},{P2[1]} Z" fill="#fff" filter="url(#grain)"/>
  <path d="M140,{lip_y(140) - 40} Q560,{lip_y(560) - 36} 1040,{lip_y(1040) - 40}" fill="none" stroke="#000" stroke-opacity=".35" stroke-width="2"/>
  {fuse}
  {cover}
  {hood}

  <!-- Steering column and the lower part of the wheel -->
  <path d="M{COLUMN - 80},0 L{COLUMN + 80},0 L{COLUMN + 110},215 Q{COLUMN},250 {COLUMN - 110},215 Z" fill="url(#column)" filter="url(#softShadow)"/>
  <path d="M{COLUMN - 96},120 Q{COLUMN},134 {COLUMN + 96},120" fill="none" stroke="#000" stroke-opacity=".4" stroke-width="2"/>
  <path d="M{COLUMN - 40},-10 L{COLUMN + 40},-10 L{COLUMN + 70},118 L{COLUMN - 70},118 Z" fill="#1c1d21" opacity=".9"/>
  <ellipse cx="{COLUMN}" cy="-190" rx="400" ry="330" fill="none" stroke="#000" stroke-opacity=".45" stroke-width="72" filter="url(#glow)"/>
  <ellipse cx="{COLUMN}" cy="-196" rx="400" ry="330" fill="none" stroke="url(#rim)" stroke-width="60"/>
  <ellipse cx="{COLUMN}" cy="-196" rx="400" ry="330" fill="none" stroke="#ffffff" stroke-opacity=".08" stroke-width="3" stroke-dasharray="6 7"/>

  {removed}
  <!-- Torch light on the socket, the socket, highlight ring, adapter and its path -->
  <rect width="{W}" height="{H}" fill="url(#torch)"/>
  {socket(sx, sy, .9 if covered else 1.0)}
  <circle cx="{sx}" cy="{sy}" r="{86 if covered else 96}" fill="none" stroke="#FFC857" stroke-width="14" opacity=".35" filter="url(#glow)"/>
  <circle cx="{sx}" cy="{sy}" r="{86 if covered else 96}" fill="none" stroke="#FFC857" stroke-width="5"/>
  {arrow}
  {adapter(ax, ay, angle)}
  <rect width="{W}" height="{H}" fill="url(#vignette)"/>
</svg>""")
    return "".join(parts)


def render(car):
    os.makedirs(RES, exist_ok=True)
    src = os.path.join(HERE, f"obd_{car}.svg")
    with open(src, "w", encoding="utf-8") as f:
        f.write(svg(car).strip() + "\n")
    html = os.path.join(HERE, f".render_{car}.html")
    with open(html, "w", encoding="utf-8") as f:
        f.write(f'<html><body style="margin:0;background:#000"><img src="obd_{car}.svg" width="{W}" height="{H}"></body></html>')
    png = os.path.join(HERE, f".render_{car}.png")
    subprocess.run([EDGE, "--headless=new", "--disable-gpu", "--hide-scrollbars", f"--window-size={W},{H}",
                    f"--screenshot={png}", "file:///" + html.replace("\\", "/")], check=True, capture_output=True)
    os.remove(html)
    from PIL import Image
    out = os.path.join(RES, f"obd_{car}.webp")
    Image.open(png).convert("RGB").save(out, "WEBP", quality=86, method=6)
    os.remove(png)
    print(out, os.path.getsize(out) // 1024, "KB")


if __name__ == "__main__":
    for name in sys.argv[1:] or CARS:
        render(name)
