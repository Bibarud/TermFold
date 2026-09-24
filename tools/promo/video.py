"""Renders the TermFold promo video (1080x1350, 30 fps) frame by frame and muxes the soundtrack.

Everything is drawn here: kinetic type, the logo folding together from its three layers, and
the real app screenshots from docs/screenshots presented as floating cards. Scene cuts sit on
the soundtrack's bar lines (120 BPM, one bar = 2 s); after the drop the frame "bumps" on every
beat.

    py -3 tools/promo/music.py build/promo/music.wav
    py -3 tools/promo/video.py build/promo/music.wav docs/media/termfold-promo.mp4
"""
import math
import os
import subprocess
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
W, H, FPS = 1080, 1350, 30
DURATION = 29.5
BEAT = 0.5
DROP = 4.0

BG = (8, 8, 10)
ORANGE = (255, 122, 46)
DEEP = (208, 86, 23)
TEXT = (244, 244, 246)
DIM = (139, 139, 147)
FAINT = (90, 90, 98)
CARD = (20, 20, 23)

FONT_OUTFIT = os.path.join(ROOT, "app", "src", "main", "res", "font", "outfit.ttf")
FONT_MONO = os.path.join(ROOT, "app", "src", "main", "res", "font", "jetbrains_mono.ttf")
SHOTS = os.path.join(ROOT, "docs", "screenshots")
LOGO_SOURCE = os.path.join(ROOT, "tools", "brand", "logo-source.png")

_fonts = {}


def outfit(size, weight="SemiBold"):
    key = ("o", size, weight)
    if key not in _fonts:
        f = ImageFont.truetype(FONT_OUTFIT, size)
        f.set_variation_by_name(weight)
        _fonts[key] = f
    return _fonts[key]


def mono(size):
    key = ("m", size)
    if key not in _fonts:
        _fonts[key] = ImageFont.truetype(FONT_MONO, size)
    return _fonts[key]


# --- Easing ----------------------------------------------------------------------------------

def clamp01(x):
    return max(0.0, min(1.0, x))


def progress(t, start, duration):
    return clamp01((t - start) / duration)


def ease_out_cubic(x):
    return 1 - (1 - x) ** 3


def ease_in_out(x):
    return 3 * x * x - 2 * x * x * x


def ease_out_back(x, s=1.7):
    x -= 1
    return x * x * ((s + 1) * x + s) + 1


# --- Drawing helpers -------------------------------------------------------------------------

def text_image(text, font, color):
    """Text on a line box of the font's full ascent + descent, so words and lines share a
    baseline whatever letters they contain."""
    ascent, descent = font.getmetrics()
    width = int(math.ceil(font.getlength(text))) + 6
    img = Image.new("RGBA", (width, ascent + descent + 6), (0, 0, 0, 0))
    ImageDraw.Draw(img).text((3, 3), text, font=font, fill=color)
    return img


def paste_alpha(frame, img, xy, alpha=1.0):
    if alpha <= 0:
        return
    if alpha < 1:
        img = img.copy()
        a = img.getchannel("A").point(lambda v: int(v * alpha))
        img.putalpha(a)
    frame.alpha_composite(img, (int(xy[0]), int(xy[1])))


def rise_text(frame, text, font, color, cx, y, t, start, dur=0.45, align="center", lift=46):
    """A line that slides up and fades in, starting at `start`."""
    p = progress(t, start, dur)
    if p <= 0:
        return
    img = text_image(text, font, color)
    x = cx - img.width / 2 if align == "center" else cx
    paste_alpha(frame, img, (x, y + lift * (1 - ease_out_cubic(p))), ease_out_cubic(p))


def words_rise(frame, text, font, color, cx, y, t, start, stagger=0.09):
    """Word by word, each sliding up in turn: the kinetic-type hook."""
    words = text.split(" ")
    imgs = [text_image(w, font, color) for w in words]
    space = font.getlength(" ")
    total = sum(i.width for i in imgs) + space * (len(imgs) - 1)
    x = cx - total / 2
    for i, img in enumerate(imgs):
        p = progress(t, start + i * stagger, 0.42)
        if p > 0:
            e = ease_out_back(p, 1.3)
            paste_alpha(frame, img, (x, y + 60 * (1 - e)), clamp01(p * 1.6))
        x += img.width + space


def rounded_mask(size, radius):
    m = Image.new("L", size, 0)
    ImageDraw.Draw(m).rounded_rectangle([0, 0, size[0] - 1, size[1] - 1], radius=radius, fill=255)
    return m


def glow_background(t):
    """The app's own backdrop: near-black with a slow warm glow drifting in the lower corner."""
    img = Image.new("RGB", (W, H), BG)
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    cx = W * (0.85 + 0.05 * math.sin(t * 0.4))
    cy = H * (1.02 + 0.03 * math.cos(t * 0.3))
    d = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2) / (W * 0.95)
    g = np.clip(1 - d, 0, 1) ** 2.2
    arr = np.asarray(img).astype(np.float32)
    arr[..., 0] += g * 60
    arr[..., 1] += g * 22
    arr[..., 2] += g * 4
    return Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8)).convert("RGBA")


# --- Assets ----------------------------------------------------------------------------------

def logo_layers():
    """The mark split into its three layers (back chevron, middle chevron, folder)."""
    src = np.asarray(Image.open(LOGO_SOURCE).convert("RGB")).astype(np.float32)
    bg = src[:20, :20].reshape(-1, 3).mean(0)
    signal = (src[..., 0] - src[..., 2]) - (bg[0] - bg[2])
    alpha = np.clip(signal / 178.0, 0, 1)
    light = (src[..., 1] > 104) & (alpha > 0.5)          # the bright top folder
    dark = (alpha > 0.5) & ~light

    # Split the two dark chevrons: flood-fill from a point inside the back one.
    lab = Image.fromarray((dark * 255).astype(np.uint8))
    ImageDraw.floodfill(lab, (265, 520), 128)
    back = np.asarray(lab) == 128
    middle = dark & ~back

    color = np.clip((src - (1 - alpha[..., None]) * bg) / np.maximum(alpha, 1e-3)[..., None], 0, 255)

    def layer(mask):
        grown = np.asarray(Image.fromarray((mask * 255).astype(np.uint8)).filter(ImageFilter.MaxFilter(5))) > 0
        a = np.where(grown, alpha, 0)
        rgba = np.dstack([color, a * 255]).astype(np.uint8)
        return Image.fromarray(rgba, "RGBA")

    layers = [layer(back), layer(middle), layer(light)]
    full = Image.new("RGBA", layers[0].size)
    for l in layers:
        full.alpha_composite(l)
    box = full.getbbox()
    return [l.crop(box) for l in layers]


def card(path, region, size, radius=26):
    """A screenshot region presented as a floating card with a soft shadow."""
    shot = Image.open(path).convert("RGB")
    img = shot.crop(region).resize(size, Image.LANCZOS).convert("RGBA")
    img.putalpha(rounded_mask(size, radius))
    pad = 60
    out = Image.new("RGBA", (size[0] + 2 * pad, size[1] + 2 * pad), (0, 0, 0, 0))
    shadow = Image.new("RGBA", out.size, (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle([pad, pad + 18, pad + size[0], pad + size[1] + 18], radius, fill=(0, 0, 0, 190))
    out.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(26)))
    out.alpha_composite(img, (pad, pad))
    ImageDraw.Draw(out).rounded_rectangle([pad, pad, pad + size[0] - 1, pad + size[1] - 1], radius, outline=(46, 46, 52, 255), width=2)
    return out


def pill(text, font, fill, fg, pad_x=26, pad_y=14):
    """A rounded chip whose height depends only on the font, not on the letters in it."""
    ascent, descent = font.getmetrics()
    cap = font.getbbox("Hg")
    w = int(math.ceil(font.getlength(text))) + 2 * pad_x
    h = (cap[3] - cap[1]) + 2 * pad_y
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([0, 0, w - 1, h - 1], radius=h // 2, fill=fill, outline=(52, 52, 58, 255) if fill[:3] == CARD else None, width=2)
    d.text((pad_x, pad_y - cap[1]), text, font=font, fill=fg)
    return img


def fit(img, max_width):
    if img.width <= max_width:
        return img
    s = max_width / img.width
    return img.resize((max_width, max(1, int(img.height * s))), Image.LANCZOS)


# --- Animated recreations of the app's screens ------------------------------------------------
#
# Drawn with the app's own fonts and palette, and showing only what the app really produced on
# a tablet: the same commands and output, the same Codex exchange. Screenshots at full size are
# in the README; at video size their text would be unreadable.

TERM_BG = (8, 8, 10)
PROMPT = "root@localhost:/Demo# "
TERM_SCRIPT = [
    ("cat /etc/os-release | head -1", ['PRETTY_NAME="Ubuntu 24.04.5 LTS"']),
    ("uname -m", ["aarch64"]),
    ("python3 --version", ["Python 3.12.3"]),
    ("node --version", ["v22.23.3"]),
    ("git --version", ["git version 2.43.0"]),
]


def frame_card(size, radius=28):
    """An empty app window: dark surface, border, soft shadow; returns (image, content offset)."""
    pad = 60
    out = Image.new("RGBA", (size[0] + 2 * pad, size[1] + 2 * pad), (0, 0, 0, 0))
    shadow = Image.new("RGBA", out.size, (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle([pad, pad + 20, pad + size[0], pad + size[1] + 20], radius, fill=(0, 0, 0, 200))
    out.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(28)))
    d = ImageDraw.Draw(out)
    d.rounded_rectangle([pad, pad, pad + size[0] - 1, pad + size[1] - 1], radius, fill=TERM_BG + (255,), outline=(46, 46, 52, 255), width=2)
    return out, pad


def terminal_card(t, start):
    """The terminal typing its way through the real commands, one after another."""
    size = (960, 700)
    out, pad = frame_card(size)
    d = ImageDraw.Draw(out)
    title = outfit(34, "SemiBold")
    d.text((pad + 36, pad + 26), "root@localhost: /Demo", font=title, fill=TEXT)
    d.line([pad + 2, pad + 88, pad + size[0] - 2, pad + 88], fill=(30, 30, 34), width=2)

    font = mono(27)
    line_h = 42
    y = pad + 112
    x0 = pad + 36
    elapsed = t - start
    clock = 0.0
    cursor = None
    for command, output in TERM_SCRIPT:
        if elapsed < clock:
            break
        typed = int((elapsed - clock) * 38)  # characters per second
        shown = command[:typed]
        d.text((x0, y), PROMPT, font=font, fill=(125, 211, 146))
        px = x0 + font.getlength(PROMPT)
        d.text((px, y), shown, font=font, fill=TEXT)
        cursor = (px + font.getlength(shown), y)
        clock += len(command) / 38 + 0.12
        if typed < len(command) or elapsed < clock:
            break
        y += line_h
        for o in output:
            d.text((x0, y), o, font=font, fill=(216, 216, 220))
            y += line_h
        cursor = None
        clock += 0.08
    else:
        d.text((x0, y), PROMPT, font=font, fill=(125, 211, 146))
        cursor = (x0 + font.getlength(PROMPT), y)
    if cursor and int(t * 2.5) % 2 == 0:
        d.rectangle([cursor[0] + 2, cursor[1] + 30, cursor[0] + 20, cursor[1] + 36], fill=ORANGE)

    # The tablet key row along the bottom of the window.
    keys = ["Ctrl", "Alt", "Esc", "Tab", "^C", "Paste", "Enter"]
    kx, ky, gap = pad + 20, pad + size[1] - 86, 10
    kw = (size[0] - 40 - gap * (len(keys) - 1)) / len(keys)
    kf = mono(24)
    for i, k in enumerate(keys):
        primary = k == "Enter"
        d.rounded_rectangle([kx, ky, kx + kw, ky + 62], 12, fill=ORANGE if primary else (22, 22, 26))
        tw = kf.getlength(k)
        d.text((kx + (kw - tw) / 2, ky + 16), k, font=kf, fill=(12, 12, 14) if primary else TEXT)
        kx += kw + gap
    return out


CHAT_PROMPT = "Write a Python function that checks if a string is a palindrome, ignoring case and punctuation."
CHAT_CODE = [
    "def is_palindrome(s: str) -> bool:",
    "    chars = [c.casefold() for c in s if c.isalnum()]",
    "    return chars == chars[::-1]",
]


def wrap(text, font, width):
    words, lines, line = text.split(), [], ""
    for w in words:
        trial = (line + " " + w).strip()
        if font.getlength(trial) <= width:
            line = trial
        else:
            lines.append(line)
            line = w
    lines.append(line)
    return lines


def chat_card(t, start):
    """The native chat: prompt bubble, thinking ticker, then the answer streaming in."""
    size = (960, 760)
    out, pad = frame_card(size)
    d = ImageDraw.Draw(out)
    e = t - start
    title = outfit(34, "SemiBold")
    d.rounded_rectangle([pad + 28, pad + 22, pad + 78, pad + 72], 12, fill=(22, 22, 26))
    d.text((pad + 42, pad + 27), "C", font=outfit(32, "Bold"), fill=TEXT)
    d.text((pad + 96, pad + 20), "Codex", font=title, fill=TEXT)
    d.text((pad + 96, pad + 60), "Demo · " + ("Working" if 0.6 < e < 2.4 else "Ready"), font=outfit(22, "Medium"), fill=FAINT)
    d.line([pad + 2, pad + 98, pad + size[0] - 2, pad + 98], fill=(30, 30, 34), width=2)

    # The user's message.
    body = outfit(29, "Regular")
    if e > 0.1:
        lines = wrap(CHAT_PROMPT, body, 560)
        bw = max(body.getlength(l) for l in lines) + 44
        bh = len(lines) * 40 + 28
        bx = pad + size[0] - 36 - bw
        a = int(255 * ease_out_cubic(clamp01((e - 0.1) / 0.3)))
        d.rounded_rectangle([bx, pad + 124, bx + bw, pad + 124 + bh], 22, fill=(26, 26, 31, a))
        for i, l in enumerate(lines):
            d.text((bx + 22, pad + 138 + i * 40), l, font=body, fill=TEXT + (a,))
        y = pad + 124 + bh + 30
    else:
        y = pad + 250

    # Thinking ticker, then the code block streaming line by line.
    thought = ["Reading the request", "Normalising case and punctuation", "Writing alphanumeric filter"]
    small = outfit(24, "Medium")
    if e > 0.6:
        idx = min(int((e - 0.6) / 0.55), len(thought) - 1)
        label = "Thinking" if e < 2.3 else "Thought"
        d.text((pad + 40, y), label, font=small, fill=ORANGE if e < 2.3 else FAINT)
        d.text((pad + 40 + small.getlength(label) + 18, y), thought[idx], font=outfit(24, "Regular"), fill=FAINT)
        y += 60
    if e > 2.3:
        code = mono(26)
        block_h = 70 + len(CHAT_CODE) * 40
        d.rounded_rectangle([pad + 36, y, pad + size[0] - 36, y + block_h], 16, fill=(20, 20, 23), outline=(29, 29, 34), width=2)
        d.text((pad + 58, y + 16), "python", font=mono(20), fill=FAINT)
        chars = int((e - 2.3) * 90)
        for i, line in enumerate(CHAT_CODE):
            shown = line[:max(0, chars)]
            chars -= len(line)
            d.text((pad + 58, y + 56 + i * 40), shown, font=code, fill=(216, 216, 220))

    # The composer with its model control, as in the app.
    cy = pad + size[1] - 130
    d.rounded_rectangle([pad + 36, cy, pad + size[0] - 36, cy + 100], 24, fill=(20, 20, 23), outline=(35, 35, 40), width=2)
    d.text((pad + 64, cy + 16), "Message Codex", font=outfit(26, "Regular"), fill=FAINT)
    chip = "6 Luna · Xhigh"
    cf = outfit(22, "Medium")
    d.text((pad + size[0] - 130 - cf.getlength(chip), cy + 60), chip, font=cf, fill=DIM)
    d.ellipse([pad + size[0] - 104, cy + 50, pad + size[0] - 60, cy + 94], fill=(28, 28, 33))
    return out


# --- Scenes ----------------------------------------------------------------------------------

class Promo:
    def __init__(self):
        self.layers = logo_layers()
        term = os.path.join(SHOTS, "terminal.png")
        chat = os.path.join(SHOTS, "chat.png")
        settings = os.path.join(SHOTS, "settings.png")
        # The settings popup, cropped close so its text stays readable at video size.
        self.settings_card = card(settings, (1995, 560, 2770, 1640), (640, 892))
        self.agents = ["Claude Code", "Codex", "Gemini CLI", "OpenCode", "Cursor", "Pi", "Devin", "Qwen", "Antigravity", "omp"]

    def logo(self, frame, cx, cy, height, t, start, fold=True):
        """The mark: three layers folding in from the lower left, one after another."""
        scale = height / self.layers[0].height
        size = (int(self.layers[0].width * scale), int(self.layers[0].height * scale))
        x0, y0 = cx - size[0] / 2, cy - size[1] / 2
        for i, layer in enumerate(self.layers):
            p = progress(t, start + i * 0.12, 0.55) if fold else 1.0
            if p <= 0:
                continue
            e = ease_out_back(p, 1.5)
            img = layer.resize(size, Image.LANCZOS)
            dx, dy = -120 * (1 - e), 140 * (1 - e)
            paste_alpha(frame, img, (x0 + dx, y0 + dy), clamp01(p * 2))

    def render(self, t):
        f = glow_background(t)

        if t < 2.0:  # hook
            words_rise(f, "Your Android tablet", outfit(92, "Bold"), TEXT, W / 2, 520, t, 0.15)
            words_rise(f, "is a dev machine now.", outfit(92, "Bold"), ORANGE, W / 2, 640, t, 0.75)

        elif t < DROP:  # tension, one line per beat, then the riser pulls them back
            out = progress(t, 3.55, 0.45)
            for i, line in enumerate(["No laptop.", "No root.", "No cloud IDE."]):
                p = progress(t, 2.0 + i * 0.5, 0.35)
                if p <= 0:
                    continue
                img = text_image(line, outfit(110, "Bold"), TEXT if i < 2 else ORANGE)
                s = 1 - 0.25 * ease_in_out(out)
                img = img.resize((max(1, int(img.width * s)), max(1, int(img.height * s))), Image.LANCZOS)
                y = 440 + i * 170 + 40 * (1 - ease_out_cubic(p))
                paste_alpha(f, img, (W / 2 - img.width / 2, y), ease_out_cubic(p) * (1 - out))

        elif t < 6.0:  # the drop: flash, logo folds together, name
            flash = 1 - progress(t, DROP, 0.35)
            if flash > 0:
                f.alpha_composite(Image.new("RGBA", (W, H), (255, 140, 70, int(120 * flash))))
            self.logo(f, W / 2, 520, 330, t, DROP + 0.02)
            rise_text(f, "TermFold", outfit(128, "Bold"), TEXT, W / 2, 760, t, DROP + 0.55)
            rise_text(f, "Linux + AI coding agents, on Android", outfit(46, "Medium"), DIM, W / 2, 930, t, DROP + 0.85)

        elif t < 10.0:  # a real Ubuntu terminal
            rise_text(f, "A real Ubuntu terminal", outfit(76, "Bold"), TEXT, W / 2, 150, t, 6.0)
            rise_text(f, "in any folder on your device", outfit(44, "Medium"), DIM, W / 2, 255, t, 6.2)
            p = ease_out_cubic(progress(t, 6.1, 0.6))
            c = terminal_card(t, 6.5)
            paste_alpha(f, c, (W / 2 - c.width / 2, 330 + 180 * (1 - p)), p)
            chips = ["apt", "git", "python", "node", "ssh / scp"]
            x = 90
            for i, chip in enumerate(chips):
                img = pill(chip, mono(34), CARD, TEXT)
                q = progress(t, 8.0 + i * 0.25, 0.35)
                if q > 0:
                    paste_alpha(f, img, (x, 1150 + 30 * (1 - ease_out_back(q))), q)
                x += img.width + 16

        elif t < 14.0:  # the agents
            rise_text(f, "Run the agents", outfit(84, "Bold"), TEXT, W / 2, 210, t, 10.0)
            rise_text(f, "you already use", outfit(84, "Bold"), ORANGE, W / 2, 315, t, 10.15)
            font = outfit(46, "SemiBold")
            rows, row, width = [], [], 0
            for name in self.agents:
                img = pill(name, font, CARD, TEXT, pad_x=30, pad_y=18)
                if width + img.width > W - 140 and row:
                    rows.append(row)
                    row, width = [], 0
                row.append(img)
                width += img.width + 18
            rows.append(row)
            y, k = 520, 0
            for r in rows:
                total = sum(i.width for i in r) + 18 * (len(r) - 1)
                x = W / 2 - total / 2
                for img in r:
                    q = progress(t, 10.6 + k * 0.25, 0.4)
                    if q > 0:
                        e = ease_out_back(q, 2.2)
                        s = 0.6 + 0.4 * e
                        im = img.resize((max(1, int(img.width * s)), max(1, int(img.height * s))), Image.LANCZOS)
                        paste_alpha(f, im, (x + (img.width - im.width) / 2, y + (img.height - im.height) / 2), clamp01(q * 2))
                    x += img.width + 18
                    k += 1
                y += 112
            rise_text(f, "in the terminal, or as a native chat", outfit(40, "Medium"), DIM, W / 2, 1150, t, 12.6)

        elif t < 18.0:  # native chat
            rise_text(f, "Or chat with them", outfit(80, "Bold"), TEXT, W / 2, 150, t, 14.0)
            rise_text(f, "natively. Markdown, code, thinking.", outfit(44, "Medium"), DIM, W / 2, 255, t, 14.2)
            p = ease_out_cubic(progress(t, 14.1, 0.6))
            c = chat_card(t, 14.4)
            paste_alpha(f, c, (W / 2 - c.width / 2, 330 + 160 * (1 - p)), p)
            rise_text(f, "Paste images  ·  long text as files  ·  /commands", outfit(36, "Medium"), FAINT, W / 2, 1210, t, 15.4)

        elif t < 21.0:  # models & reasoning
            rise_text(f, "Switch models", outfit(84, "Bold"), TEXT, W / 2, 160, t, 18.0)
            rise_text(f, "and reasoning, in a tap", outfit(84, "Bold"), ORANGE, W / 2, 265, t, 18.15)
            p = ease_out_back(progress(t, 18.3, 0.6), 1.2)
            c = self.settings_card
            paste_alpha(f, c, (W / 2 - c.width / 2, 360 + 200 * (1 - p)), clamp01(p))

        elif t < 24.0:  # rapid-fire features, one per beat
            features = [
                "Tablet-sized key row",
                "Physical keyboard ready",
                "SSH · SCP · git",
                "One-tap first-run setup",
                "No root required",
                "Your folders, your files",
            ]
            i = min(int((t - 21.0) / BEAT), len(features) - 1)
            local = (t - 21.0) - i * BEAT
            p = ease_out_back(clamp01(local / 0.22), 1.8)
            img = fit(text_image(features[i], outfit(96, "Bold"), ORANGE if i % 2 else TEXT), W - 140)
            s = 0.85 + 0.15 * p
            img = img.resize((int(img.width * s), int(img.height * s)), Image.LANCZOS)
            paste_alpha(f, img, (W / 2 - img.width / 2, H / 2 - img.height / 2), clamp01(p))
            # A small beat counter along the bottom.
            for j in range(len(features)):
                on = j <= i
                ImageDraw.Draw(f).rounded_rectangle(
                    [W / 2 - 150 + j * 52, 1100, W / 2 - 150 + j * 52 + 38, 1108], 4,
                    fill=ORANGE if on else (40, 40, 46),
                )

        else:  # call to action
            self.logo(f, W / 2, 400, 250, t, 24.0)
            rise_text(f, "TermFold", outfit(120, "Bold"), TEXT, W / 2, 580, t, 24.35)
            rise_text(f, "Free & open source", outfit(58, "SemiBold"), ORANGE, W / 2, 740, t, 24.6)
            rise_text(f, "github.com/Bibarud/TermFold", mono(44), TEXT, W / 2, 870, t, 24.9)
            rise_text(f, "Android 8+ · arm64 · APK on GitHub Releases", outfit(38, "Medium"), DIM, W / 2, 960, t, 25.2)

        # After the drop the whole frame bumps a little on every beat.
        if DROP <= t < 24.0:
            phase = (t - DROP) % BEAT
            bump = 1 + 0.012 * math.exp(-phase * 14)
            if bump > 1.0005:
                bw, bh = int(W * bump), int(H * bump)
                f = f.resize((bw, bh), Image.BILINEAR).crop(((bw - W) // 2, (bh - H) // 2, (bw - W) // 2 + W, (bh - H) // 2 + H))

        # Fade in from black, fade out at the end.
        fade = min(progress(t, 0, 0.25), 1 - progress(t, DURATION - 0.6, 0.6))
        if fade < 1:
            f.alpha_composite(Image.new("RGBA", (W, H), (0, 0, 0, int(255 * (1 - fade)))))
        return f.convert("RGB")


def main(music, out):
    os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
    promo = Promo()
    frames = int(DURATION * FPS)
    cmd = [
        "ffmpeg", "-y", "-loglevel", "error",
        "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS), "-i", "-",
        "-i", music,
        "-c:v", "libx264", "-preset", "slow", "-crf", "18", "-pix_fmt", "yuv420p",
        "-c:a", "aac", "-b:a", "192k", "-shortest", "-movflags", "+faststart",
        out,
    ]
    proc = subprocess.Popen(cmd, stdin=subprocess.PIPE)
    for i in range(frames):
        proc.stdin.write(promo.render(i / FPS).tobytes())
        if i % 90 == 0:
            print(f"  frame {i}/{frames}", flush=True)
    proc.stdin.close()
    proc.wait()
    print(f"wrote {out}")


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--stills":
        promo = Promo()
        for t in [float(x) for x in sys.argv[3:]]:
            promo.render(t).save(os.path.join(sys.argv[2], f"still_{t:05.2f}.png"))
    else:
        main(sys.argv[1], sys.argv[2])
