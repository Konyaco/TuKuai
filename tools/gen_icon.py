"""Generates TuKuai's mod icon and logo: a grass-topped dirt block with a hand of cards.

Run from the repository root: python3 tools/gen_icon.py
Needs Pillow and the WenQuanYi Zen Hei font for the Chinese name on the logo.
The cards come from gen_textures.py, so they look the same as in game.
"""
import os
import random
import sys

from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gen_textures import face, joker  # noqa: E402

ICON_OUT = "common/src/main/resources/assets/tukuai/icon.png"
LOGO_OUT = "neoforge/src/main/resources/tukuai_logo.png"
README_OUT = "docs/logo.png"
CJK_FONT = "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc"

OUTLINE = (28, 20, 16, 255)
CLEAR = (0, 0, 0, 0)
GRASS = [(96, 160, 56), (84, 142, 48), (110, 176, 64), (102, 168, 60)]
DIRT = [(134, 96, 67), (121, 85, 58), (150, 108, 74), (104, 74, 50)]


def grass_top(n, rnd):
    tex = Image.new("RGB", (n, n))
    for y in range(n):
        for x in range(n):
            tex.putpixel((x, y), rnd.choice(GRASS))
    return tex


def grass_side(n, rnd):
    """Dirt with a band of grass along the top that drips down unevenly."""
    tex = Image.new("RGB", (n, n))
    depth = [max(2, n // 5) + rnd.choice([0, 0, 1, 1, 2]) for _ in range(n)]
    for y in range(n):
        for x in range(n):
            tex.putpixel((x, y), rnd.choice(GRASS if y < depth[x] else DIRT))
    return tex


def shade(color, k):
    return tuple(int(v * k) for v in color[:3]) + (255,)


def block(n, seed=5):
    """An isometric block with an n-texel edge, drawn as pixel art 2n wide and 2n tall."""
    rnd = random.Random(seed)
    top, left, right = grass_top(n, rnd), grass_side(n, rnd), grass_side(n, rnd)
    img = Image.new("RGBA", (2 * n, 2 * n), CLEAR)
    cx = n
    for y in range(2 * n):
        for x in range(2 * n):
            px, py = x + 0.5 - cx, y + 0.5
            # Top face: P = (u - v, (u + v) / 2).
            u, v = py + px / 2, py - px / 2
            if 0 <= u < n and 0 <= v < n:
                img.putpixel((x, y), shade(top.getpixel((int(u), int(v))), 1.0))
                continue
            if px < 0:
                # Left face: P = L + a * (1, 1/2) + h * (0, 1), L = (-n, n / 2).
                a = px + n
                h = py - n / 2 - a / 2
                if 0 <= a < n and 0 <= h < n:
                    img.putpixel((x, y), shade(left.getpixel((int(a), int(h))), 0.82))
            else:
                # Right face: P = F + a * (1, -1/2) + h * (0, 1), F = (0, n).
                a = px
                h = py - n + a / 2
                if 0 <= a < n and 0 <= h < n:
                    img.putpixel((x, y), shade(right.getpixel((int(a), int(h))), 0.64))
    return img


def outline(img, color=OUTLINE):
    """Adds a one-pixel outline around everything that is not transparent."""
    out = Image.new("RGBA", (img.width + 2, img.height + 2), CLEAR)
    alpha = img.getchannel("A")
    for y in range(img.height):
        for x in range(img.width):
            if alpha.getpixel((x, y)):
                for dx in (0, 1, 2):
                    for dy in (0, 1, 2):
                        out.putpixel((x + dx, y + dy), color)
    out.alpha_composite(img, (1, 1))
    return out


def icon():
    """64 x 64: three cards (A of spades and both jokers) held up behind a grass block."""
    canvas = Image.new("RGBA", (62, 62), CLEAR)
    hand = [face("A", "S"), joker(False), joker(True)]
    for card, pos in zip(hand, [(6, 1), (16, 3), (27, 5)]):
        canvas.alpha_composite(outline(card), pos)
    canvas.alpha_composite(outline(block(17)), (0, 26))
    framed = Image.new("RGBA", (64, 64), CLEAR)
    framed.alpha_composite(canvas, (1, 1))
    return framed


LETTERS = {
    "T": ["#####", "..#..", "..#..", "..#..", "..#..", "..#..", "..#.."],
    "U": ["#...#", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."],
    "K": ["#...#", "#..#.", "#.#..", "##...", "#.#..", "#..#.", "#...#"],
    "A": [".###.", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"],
    "I": ["###", ".#.", ".#.", ".#.", ".#.", ".#.", "###"],
}


def title(word, scale, seed=11):
    """Block letters made of grass and dirt, with a dark side so they look like they stick out."""
    rnd = random.Random(seed)
    width = sum(len(LETTERS[ch][0]) for ch in word) * scale + (len(word) - 1) * scale
    height = 7 * scale
    mask = set()
    x0 = 0
    for ch in word:
        rows = LETTERS[ch]
        for gy, row in enumerate(rows):
            for gx, cell in enumerate(row):
                if cell == "#":
                    for dy in range(scale):
                        for dx in range(scale):
                            mask.add((x0 + gx * scale + dx, gy * scale + dy))
        x0 += (len(rows[0]) + 1) * scale
    depth = max(2, scale * 3 // 4)
    img = Image.new("RGBA", (width + depth, height + depth), CLEAR)
    for d in range(depth, 0, -1):
        for x, y in mask:
            img.putpixel((x + d, y + d), shade(rnd.choice(DIRT), 0.5))
    for x, y in mask:
        # The top of every stroke is grass, a few pixels deep with the odd drip.
        run = 0
        while (x, y - run - 1) in mask:
            run += 1
        grassy = run < scale // 2 + (1 if rnd.random() < 0.3 else 0)
        img.putpixel((x, y), shade(rnd.choice(GRASS if grassy else DIRT), 1.0))
    return outline(img)


def pixel_text(text, size, color):
    """Chinese text from the bitmap strike of WenQuanYi Zen Hei Sharp, so it stays crisp."""
    font = ImageFont.truetype(CJK_FONT, size, index=1)
    left, top, right, bottom = font.getbbox(text)
    img = Image.new("RGBA", (right - left, bottom - top), CLEAR)
    draw = ImageDraw.Draw(img)
    draw.fontmode = "1"
    draw.text((-left, -top), text, font=font, fill=color)
    return outline(img)


def logo():
    """A wide banner: the icon, the name in block letters and the Chinese name under it."""
    mark = icon()
    word = title("TUKUAI", 4)
    sub = pixel_text("土块棋牌室", 13, (246, 242, 230, 255))
    text_w = max(word.width, sub.width)
    w = mark.width + 6 + text_w + 2
    canvas = Image.new("RGBA", (w, 64), CLEAR)
    canvas.alpha_composite(mark, (0, 0))
    tx = mark.width + 6
    top = (64 - word.height - 4 - sub.height) // 2
    canvas.alpha_composite(word, (tx + (text_w - word.width) // 2, top))
    canvas.alpha_composite(sub, (tx + (text_w - sub.width) // 2, top + word.height + 4))
    return canvas


def main():
    os.makedirs(os.path.dirname(ICON_OUT), exist_ok=True)
    icon().resize((256, 256), Image.NEAREST).save(ICON_OUT)
    banner = logo()
    for path in (LOGO_OUT, README_OUT):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        banner.resize((banner.width * 4, banner.height * 4), Image.NEAREST).save(path)


if __name__ == "__main__":
    main()
