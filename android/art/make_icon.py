"""Слои адаптивной иконки из готовой картинки 1254x1254.

Календарь вырезается на передний слой, синий фон «плитки» заменяет градиент
из drawable/ic_launcher_background.xml. Запуск из каталога android/:

    uv run --no-project --with pillow --with numpy --with scipy \
        python art/make_icon.py art/icon-source.webp app/src/main/res

В текущем каталоге появляются preview.png и safezone.png для проверки на глаз.
"""

import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter
from scipy import ndimage

SRC, OUT = sys.argv[1], sys.argv[2]
im = Image.open(SRC).convert("RGB")
a = np.asarray(im).astype(int)
r, g, b = a[..., 0], a[..., 1], a[..., 2]

# Синий фон «плитки» (и тень под календарём): всё остальное — календарь.
bg = (r < 40) & (g < 115) & (b > 170)
labels, _ = ndimage.label(~bg)
seed = (985, 627)  # белый низ календаря под кругом логотипа
assert a[seed].min() > 240, a[seed]
body = labels == labels[seed]
mask = ndimage.binary_fill_holes(body)  # синий круг логотипа внутри белого
mask = ndimage.binary_dilation(mask, iterations=2)  # захватить сглаженный край

# Монохромный слой (темы Android 13+): белое и шапка календаря,
# без ячеек и круга логотипа.
mono = mask & ((r > 215) | ((r < 90) & (g > 115) & (b > 235)))

ys, xs = np.nonzero(mask)
x0, x1, y0, y1 = xs.min(), xs.max() + 1, ys.min(), ys.max() + 1
cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
rmax = np.sqrt(((xs - cx) ** 2 + (ys - cy) ** 2).max())
print("bbox", x0, x1, y0, y1, "rmax", round(rmax))

PX_PER_DP = 4  # xxxhdpi: 108dp -> 432px
CANVAS = 108 * PX_PER_DP
TARGET_R_DP = 32.5  # безопасная зона — круг 66dp (r=33), видимая — 72dp (r=36)
scale = TARGET_R_DP * PX_PER_DP / rmax


def place(rgba: Image.Image) -> Image.Image:
    crop = rgba.crop((x0, y0, x1, y1))
    w, h = round(crop.width * scale), round(crop.height * scale)
    crop = crop.resize((w, h), Image.LANCZOS)
    canvas = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    canvas.alpha_composite(crop, (round(CANVAS / 2 - w / 2), round(CANVAS / 2 - h / 2)))
    return canvas


alpha = Image.fromarray((mask * 255).astype(np.uint8))
fg_src = im.convert("RGBA")
fg_src.putalpha(alpha)
fg = place(fg_src)

# Мягкая тень, как у исходной иконки.
shadow_alpha = (
    fg.getchannel("A")
    .filter(ImageFilter.GaussianBlur(2.5 * PX_PER_DP))
    .point(lambda v: int(v * 0.30))
)
shadow = Image.new("RGBA", fg.size, (0, 20, 80, 0))
shadow.putalpha(shadow_alpha)
shifted = Image.new("RGBA", fg.size, (0, 0, 0, 0))
shifted.alpha_composite(shadow, (0, int(1.5 * PX_PER_DP)))
foreground = Image.alpha_composite(shifted, fg)

mono_alpha = Image.fromarray((mono * 255).astype(np.uint8)).filter(
    ImageFilter.GaussianBlur(1.2)
)
mono_src = Image.new("RGBA", im.size, (255, 255, 255, 0))
mono_src.putalpha(mono_alpha)
monochrome = place(mono_src)

for name, layer in (("foreground", foreground), ("monochrome", monochrome)):
    for density, px in (
        ("mdpi", 108),
        ("hdpi", 162),
        ("xhdpi", 216),
        ("xxhdpi", 324),
        ("xxxhdpi", 432),
    ):
        layer.resize((px, px), Image.LANCZOS).save(
            f"{OUT}/mipmap-{density}/ic_launcher_{name}.png", optimize=True
        )


# Превью: фон-градиент + передний слой под разными масками, и тематическая иконка.
def gradient(size, c0=(0x10, 0x5D, 0xEB), c1=(0x03, 0x42, 0xCC)):
    t = (np.add.outer(np.arange(size), np.arange(size)) / (2 * (size - 1)))[..., None]
    arr = (np.array(c0) * (1 - t) + np.array(c1) * t).astype(np.uint8)
    return Image.fromarray(arr, "RGB").convert("RGBA")


full = Image.alpha_composite(gradient(CANVAS), foreground)
themed_bg = Image.new("RGBA", (CANVAS, CANVAS), (0x1E, 0x2B, 0x45, 255))
tint = Image.new("RGBA", (CANVAS, CANVAS), (0xC9, 0xD9, 0xFF, 255))
tint.putalpha(monochrome.getchannel("A"))
themed = Image.alpha_composite(themed_bg, tint)
view = 72 * PX_PER_DP
off = (CANVAS - view) // 2


def masked(src, shape):
    tile = src.crop((off, off, off + view, off + view))
    m = Image.new("L", (view, view), 0)
    d = ImageDraw.Draw(m)
    if shape == "circle":
        d.ellipse((0, 0, view - 1, view - 1), fill=255)
    elif shape == "squircle":
        d.rounded_rectangle(
            (0, 0, view - 1, view - 1), radius=int(view * 0.32), fill=255
        )
    else:
        d.rounded_rectangle(
            (0, 0, view - 1, view - 1), radius=int(view * 0.12), fill=255
        )
    out = Image.new("RGBA", (view, view), (0, 0, 0, 0))
    out.paste(tile, (0, 0), m)
    return out


tiles = [
    im.convert("RGBA").resize((view, view), Image.LANCZOS),
    masked(full, "circle"),
    masked(full, "squircle"),
    masked(full, "square"),
    masked(themed, "circle"),
]
pad = 24
sheet = Image.new(
    "RGBA", (pad + len(tiles) * (view + pad), view + 2 * pad), (40, 40, 40, 255)
)
for i, t in enumerate(tiles):
    sheet.alpha_composite(t, (pad + i * (view + pad), pad))
sheet.save("preview.png")
# Безопасная зона поверх слоя для проверки.
check = full.copy()
d = ImageDraw.Draw(check)
c = CANVAS / 2
for rad, col in ((33, (255, 0, 0, 255)), (36, (255, 255, 0, 255))):
    d.ellipse(
        (
            c - rad * PX_PER_DP,
            c - rad * PX_PER_DP,
            c + rad * PX_PER_DP,
            c + rad * PX_PER_DP,
        ),
        outline=col,
        width=2,
    )
check.save("safezone.png")
