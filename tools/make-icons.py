"""Builds the launcher icon layers from the source logo (tools/brand/logo-source.png).

The source is the orange folder mark on a near-black square. This cuts the mark out onto a
transparent background, keeping the anti-aliased edges, and writes:

  - mipmap-*/ic_launcher_foreground.png: the mark, centred in the adaptive-icon safe zone
  - mipmap-*/ic_launcher_monochrome.png: the same silhouette in white, for themed icons
  - tools/brand/logo-mark.png: the transparent mark at full resolution (README, video)

The background layer is a solid colour (@color/icon_bg), so every launcher mask is filled edge
to edge with no frame.

    py -3 tools/make-icons.py
"""
import os

import numpy as np
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOURCE = os.path.join(ROOT, "tools", "brand", "logo-source.png")
RES = os.path.join(ROOT, "app", "src", "main", "res")

# Adaptive icon: a 108dp canvas, of which a 66dp circle is guaranteed visible under any mask.
DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}
CANVAS_DP = 108
MARK_DP = 54  # the mark's longest side; leaves breathing room inside the 66dp safe circle


def cut_out(path):
    """Separates the orange mark from the dark background as straight (un-premultiplied) RGBA."""
    rgb = np.asarray(Image.open(path).convert("RGB")).astype(np.float32)
    # The background colour, sampled from the corners.
    corners = np.concatenate([rgb[:20, :20].reshape(-1, 3), rgb[-20:, -20:].reshape(-1, 3)])
    bg = corners.mean(axis=0)
    # Both oranges are far from the background in red-minus-blue; the darker orange defines 1.0.
    signal = (rgb[..., 0] - rgb[..., 2]) - (bg[0] - bg[2])
    alpha = np.clip(signal / 178.0, 0.0, 1.0)
    alpha[alpha < 0.04] = 0.0
    # Undo the blend with the background so edge pixels keep the mark's colour, not a dark rim.
    safe = np.maximum(alpha, 1e-3)[..., None]
    color = np.clip((rgb - (1 - alpha[..., None]) * bg) / safe, 0, 255)
    rgba = np.dstack([color, alpha * 255]).astype(np.uint8)
    image = Image.fromarray(rgba, "RGBA")
    return image.crop(image.getbbox())


def place(mark, canvas_px, mark_px):
    scale = mark_px / max(mark.size)
    resized = mark.resize((round(mark.width * scale), round(mark.height * scale)), Image.LANCZOS)
    canvas = Image.new("RGBA", (canvas_px, canvas_px), (0, 0, 0, 0))
    canvas.alpha_composite(resized, ((canvas_px - resized.width) // 2, (canvas_px - resized.height) // 2))
    return canvas


def main():
    mark = cut_out(SOURCE)
    mark.save(os.path.join(ROOT, "tools", "brand", "logo-mark.png"))
    white = Image.new("RGBA", mark.size, (255, 255, 255, 0))
    white.putalpha(mark.getchannel("A"))

    for density, factor in DENSITIES.items():
        folder = os.path.join(RES, f"mipmap-{density}")
        os.makedirs(folder, exist_ok=True)
        canvas = round(CANVAS_DP * factor)
        size = round(MARK_DP * factor)
        place(mark, canvas, size).save(os.path.join(folder, "ic_launcher_foreground.png"), optimize=True)
        place(white, canvas, size).save(os.path.join(folder, "ic_launcher_monochrome.png"), optimize=True)
        print(f"  mipmap-{density}: {canvas}px")


if __name__ == "__main__":
    main()
