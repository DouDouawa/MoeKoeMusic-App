#!/usr/bin/env python3
"""Generate mobile icons from images/hero.png without touching desktop artwork.

Requires Python 3 and Pillow. Run from any directory:
    python scripts/generate-mobile-icons.py
    python scripts/generate-mobile-icons.py --check

The alpha silhouette, not a stretched square, determines the scale. Adaptive
foregrounds stay inside the central 66dp circle of a 108dp layer. Maskable PWA
icons stay inside the central circle with a radius of 40% of the image width.
"""

import argparse
from io import BytesIO
from math import hypot
from pathlib import Path

try:
    from PIL import Image, ImageDraw, ImageOps
except ImportError as error:
    raise SystemExit("Pillow is required: python -m pip install Pillow") from error


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "images/hero.png"
ANDROID = ROOT / "android/app/src/main/res"
WEB = ROOT / "public/assets/mobile-icons"
BACKGROUND = "#FFF4F8"
DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
ADAPTIVE_SAFE_RADIUS = 33 / 108
MASKABLE_SAFE_RADIUS = 0.4
REGULAR_SAFE_RADIUS = 0.44
# 启动屏沿用 Capacitor 脚手架的同一批文件名与尺寸，主题里的 @drawable/splash 不必改。
# 主体占短边这个比例：太小会显得空，太大则贴近系统启动图的安全区边缘。
SPLASH_SUBJECT_RATIO = 0.38
SPLASH_SIZES = {
    "drawable": (480, 320),
    "drawable-land-mdpi": (480, 320),
    "drawable-land-hdpi": (800, 480),
    "drawable-land-xhdpi": (1280, 720),
    "drawable-land-xxhdpi": (1600, 960),
    "drawable-land-xxxhdpi": (1920, 1280),
    "drawable-port-mdpi": (320, 480),
    "drawable-port-hdpi": (480, 800),
    "drawable-port-xhdpi": (720, 1280),
    "drawable-port-xxhdpi": (960, 1600),
    "drawable-port-xxxhdpi": (1280, 1920),
}


def alpha_radius(image):
    """Maximum distance from center to any nontransparent pixel's corners."""
    alpha = image.getchannel("A")
    center_x, center_y = image.width / 2, image.height / 2
    radius = 0.0
    for y in range(image.height):
        bounds = alpha.crop((0, y, image.width, y + 1)).getbbox()
        if bounds:
            dx = max(abs(bounds[0] - center_x), abs(bounds[2] - center_x))
            dy = max(abs(y - center_y), abs(y + 1 - center_y))
            radius = max(radius, hypot(dx, dy))
    return radius


def foreground(source, source_radius, size, safe_ratio):
    # Leave room for the LANCZOS filter halo and integer pixel alignment.
    safe_radius = size * safe_ratio
    scale = (safe_radius - 3) / source_radius
    edge = max(1, int(max(source.size) * scale))
    subject = ImageOps.contain(source, (edge, edge), Image.Resampling.LANCZOS)
    layer = Image.new("RGBA", (size, size))
    layer.alpha_composite(subject, ((size - subject.width) // 2, (size - subject.height) // 2))
    radius = alpha_radius(layer)
    if radius > safe_radius:
        raise ValueError(f"{size}px artwork exceeds safe circle: {radius:.2f} > {safe_radius:.2f}")
    return layer


def with_background(layer, round_icon=False):
    background = Image.new("RGBA", layer.size, BACKGROUND)
    if round_icon:
        # Antialias only the background edge; the subject is never clipped.
        size = layer.width
        mask = Image.new("L", (size * 4, size * 4))
        ImageDraw.Draw(mask).ellipse((0, 0, size * 4 - 1, size * 4 - 1), fill=255)
        background.putalpha(mask.resize(layer.size, Image.Resampling.LANCZOS))
    result = Image.alpha_composite(background, layer)
    return result if round_icon else result.convert("RGB")


def png_bytes(image):
    data = BytesIO()
    image.save(data, format="PNG", optimize=True, compress_level=9)
    return data.getvalue()


def splash(source, size):
    """不透明底 + 严格居中主体。

    居中用画布中心减半宽后四舍五入，宽高差为奇数时也不会系统性偏半个像素。
    尺寸沿用脚手架原图，避免任何密度缺图。
    """
    width, height = size
    canvas = Image.new("RGB", (width, height), BACKGROUND)
    edge = max(1, int(min(width, height) * SPLASH_SUBJECT_RATIO))
    subject = ImageOps.contain(source, (edge, edge), Image.Resampling.LANCZOS)
    left = round((width - subject.width) / 2)
    top = round((height - subject.height) / 2)
    canvas.paste(subject, (left, top), subject)
    return canvas


def build_assets():
    with Image.open(SOURCE) as original:
        source = original.convert("RGBA")
    bounds = source.getchannel("A").getbbox()
    if not bounds:
        raise ValueError("Source artwork has no visible pixels")
    source = source.crop(bounds)  # Trim transparent pixels only; preserve all artwork.
    source_radius = alpha_radius(source)
    assets = {}

    for density, factor in DENSITIES.items():
        directory = ANDROID / f"mipmap-{density}"
        size = int(48 * factor)
        layer = foreground(source, source_radius, size, REGULAR_SAFE_RADIUS)
        assets[directory / "ic_launcher.png"] = png_bytes(with_background(layer))
        assets[directory / "ic_launcher_round.png"] = png_bytes(with_background(layer, round_icon=True))
        adaptive = foreground(source, source_radius, int(108 * factor), ADAPTIVE_SAFE_RADIUS)
        assets[directory / "ic_launcher_foreground.png"] = png_bytes(adaptive)

    for size in (192, 512):
        regular = foreground(source, source_radius, size, REGULAR_SAFE_RADIUS)
        maskable = foreground(source, source_radius, size, MASKABLE_SAFE_RADIUS)
        assets[WEB / f"pwa-{size}.png"] = png_bytes(with_background(regular))
        assets[WEB / f"maskable-{size}.png"] = png_bytes(with_background(maskable))
    for name, size in (("apple-touch-icon.png", 180), ("favicon-32.png", 32)):
        layer = foreground(source, source_radius, size, REGULAR_SAFE_RADIUS)
        assets[WEB / name] = png_bytes(with_background(layer))

    # 默认的启动图是 Capacitor 脚手架自带的蓝 X，换成本应用的标志。
    for directory, size in SPLASH_SIZES.items():
        assets[ANDROID / directory / "splash.png"] = png_bytes(splash(source, size))

    adaptive_xml = '''<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by scripts/generate-mobile-icons.py. -->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background"/>
    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>
</adaptive-icon>
'''
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        assets[ANDROID / "mipmap-anydpi-v26" / name] = adaptive_xml.encode("utf-8")
    assets[ANDROID / "values/ic_launcher_background.xml"] = f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by scripts/generate-mobile-icons.py. -->
<resources>
    <color name="ic_launcher_background">{BACKGROUND}</color>
    <color name="splash_background">{BACKGROUND}</color>
</resources>
'''.encode("utf-8")
    assets[ANDROID / "drawable/ic_launcher_background.xml"] = b'''<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by scripts/generate-mobile-icons.py. -->
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/ic_launcher_background"/>
</shape>
'''
    return assets


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Verify generated files without writing anything")
    options = parser.parse_args()
    assets = build_assets()
    for path, content in assets.items():
        if options.check:
            if not path.is_file() or path.read_bytes() != content:
                raise SystemExit(f"Missing or outdated icon: {path.relative_to(ROOT)}")
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
    action = "Verified" if options.check else "Generated"
    print(f"{action} {len(assets)} assets: 15 Android icons, 11 splash images, 6 web PNGs, 4 Android XMLs.")
    print("Alpha silhouettes fit their safe circles; source and desktop icons were not modified.")


if __name__ == "__main__":
    main()
