"""Generates TuKuai's pixel-art textures: the card atlas and the table block faces.

Run from the repository root: python3 tools/gen_textures.py
Needs Pillow. Output goes into common/src/main/resources/assets/tukuai/textures.
The card layout here must match client/CardAtlas.kt.
"""
import math
import os
import random
from PIL import Image

OUT = "common/src/main/resources/assets/tukuai/textures"
CARD_W, CARD_H = 32, 44
COLS = 14
ATLAS_W, ATLAS_H = 512, 256
EDGE_X, EDGE_Y = 448, 0  # Right of the cards, see CardArt.EDGE_U.
FELT_X, FELT_Y = 464, 0  # The table cloth, tinted when drawn; see CardArt.FELT_U.

RED = (200, 48, 46, 255)
BLACK = (32, 32, 36, 255)
PAPER = (246, 242, 230, 255)
BORDER = (58, 58, 62, 255)
GOLD = (222, 170, 30, 255)
CLEAR = (0, 0, 0, 0)

FONT = {
    "A": ["..#..", ".#.#.", "#...#", "#...#", "#####", "#...#", "#...#"],
    "2": [".###.", "#...#", "....#", "...#.", "..#..", ".#...", "#####"],
    "3": ["####.", "....#", "....#", ".###.", "....#", "....#", "####."],
    "4": ["...#.", "..##.", ".#.#.", "#..#.", "#####", "...#.", "...#."],
    "5": ["#####", "#....", "####.", "....#", "....#", "#...#", ".###."],
    "6": [".###.", "#....", "#....", "####.", "#...#", "#...#", ".###."],
    "7": ["#####", "....#", "...#.", "..#..", ".#...", ".#...", ".#..."],
    "8": [".###.", "#...#", "#...#", ".###.", "#...#", "#...#", ".###."],
    "9": [".###.", "#...#", "#...#", ".####", "....#", "....#", ".###."],
    "0": [".###.", "#...#", "#..##", "#.#.#", "##..#", "#...#", ".###."],
    "1": [".#.", "##.", ".#.", ".#.", ".#.", ".#.", "###"],
    "J": ["..###", "...#.", "...#.", "...#.", "#..#.", "#..#.", ".##.."],
    "Q": [".###.", "#...#", "#...#", "#...#", "#.#.#", "#..#.", ".##.#"],
    "K": ["#...#", "#..#.", "#.#..", "##...", "#.#..", "#..#.", "#...#"],
}
SMALL = {
    "J": ["###", "..#", "..#", "#.#", "##."],
    "O": [".#.", "#.#", "#.#", "#.#", ".#."],
    "K": ["#.#", "#.#", "##.", "#.#", "#.#"],
    "E": ["###", "#..", "##.", "#..", "###"],
    "R": ["##.", "#.#", "##.", "#.#", "#.#"],
}
PIPS = {
    "S": ["...#...", "..###..", ".#####.", "#######", "#######", "...#...", "..###.."],
    "H": [".##.##.", "#######", "#######", ".#####.", "..###..", "...#...", "......."],
    "C": ["..###..", "..###..", "##.#.##", "#######", "##.#.##", "...#...", "..###.."],
    "D": ["...#...", "..###..", ".#####.", "#######", ".#####.", "..###..", "...#..."],
}
SUITS = ["S", "H", "C", "D"]  # Same order as Suit in the engine.
RANKS = ["A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K"]  # Same order as Rank.


def put(img, x, y, color):
    if 0 <= x < img.width and 0 <= y < img.height:
        img.putpixel((x, y), color)


def bitmap(img, rows, x, y, color):
    for dy, row in enumerate(rows):
        for dx, ch in enumerate(row):
            if ch == "#":
                put(img, x + dx, y + dy, color)


def text(img, label, x, y, color):
    for ch in label:
        rows = FONT[ch]
        bitmap(img, rows, x, y, color)
        x += len(rows[0]) + 1


def text_width(label):
    return sum(len(FONT[c][0]) + 1 for c in label) - 1


def big_pip(img, suit, cx, cy, r, color):
    """Draws a suit symbol of radius r around (cx, cy) by testing every pixel."""
    for y in range(cy - r - 1, cy + r + 2):
        for x in range(cx - r - 1, cx + r + 2):
            u, v = (x + 0.5 - cx) / r, (y + 0.5 - cy) / r
            inside = False
            if suit == "D":
                inside = abs(u) * 1.25 + abs(v) <= 1.0
            elif suit == "H":
                inside = (math.hypot(u + 0.45, v + 0.35) < 0.52 or math.hypot(u - 0.45, v + 0.35) < 0.52
                          or (v > -0.35 and abs(u) < 0.95 * (1 - (v + 0.35) / 1.3) and v < 0.95))
            elif suit == "S":
                # A heart turned upside down, on a short stem.
                vv = -(v + 0.25)
                inside = (math.hypot(u + 0.45, vv + 0.35) < 0.52 or math.hypot(u - 0.45, vv + 0.35) < 0.52
                          or (vv > -0.35 and abs(u) < 0.95 * (1 - (vv + 0.35) / 1.3) and vv < 0.95))
                inside = inside or (abs(u) < 0.12 + max(0, v - 0.6) * 1.2 and 0.2 < v < 1.0)
            elif suit == "C":
                inside = (math.hypot(u, v + 0.45) < 0.42 or math.hypot(u + 0.47, v + 0.08) < 0.42
                          or math.hypot(u - 0.47, v + 0.08) < 0.42)
                inside = inside or (abs(u) < 0.12 + max(0, v - 0.55) * 0.9 and -0.1 < v < 1.0)
            if inside:
                put(img, x, y, color)


def star(img, cx, cy, r, color, inner=0.45):
    points = []
    for i in range(10):
        a = -math.pi / 2 + i * math.pi / 5
        rad = r if i % 2 == 0 else r * inner
        points.append((cx + rad * math.cos(a), cy + rad * math.sin(a)))
    for y in range(cy - r - 1, cy + r + 2):
        for x in range(cx - r - 1, cx + r + 2):
            if point_in_polygon(x + 0.5, y + 0.5, points):
                put(img, x, y, color)


def point_in_polygon(x, y, pts):
    inside = False
    j = len(pts) - 1
    for i in range(len(pts)):
        xi, yi = pts[i]
        xj, yj = pts[j]
        if (yi > y) != (yj > y) and x < (xj - xi) * (y - yi) / (yj - yi) + xi:
            inside = not inside
        j = i
    return inside


def blank(fill=PAPER):
    card = Image.new("RGBA", (CARD_W, CARD_H), CLEAR)
    for y in range(CARD_H):
        for x in range(CARD_W):
            corner = (x in (0, CARD_W - 1)) and (y in (0, CARD_H - 1))
            if corner:
                continue
            edge = x in (0, CARD_W - 1) or y in (0, CARD_H - 1)
            card.putpixel((x, y), BORDER if edge else fill)
    for x, y in [(1, 1), (CARD_W - 2, 1), (1, CARD_H - 2), (CARD_W - 2, CARD_H - 2)]:
        card.putpixel((x, y), BORDER)
    return card


def corner(card, label, suit, color):
    w = text_width(label)
    text(card, label, 3 + (5 - w) // 2 if w <= 5 else 2, 3, color)
    bitmap(card, PIPS[suit], 2, 12, color)


def face(rank, suit):
    card = blank()
    color = RED if suit in ("H", "D") else BLACK
    corner(card, rank, suit, color)
    if rank in ("J", "Q", "K"):
        for x in range(10, 22):
            put(card, x, 4, GOLD)
            put(card, x, 39, GOLD)
        for y in range(4, 40):
            put(card, 10, y, GOLD)
            put(card, 21, y, GOLD)
        text(card, rank, 14, 8, color)
        big_pip(card, suit, 16, 26, 5, color)
    else:
        big_pip(card, suit, 16, 22, 7 if rank == "A" else 6, color)
    paste_turned_corner(card, 11, 20)
    return card


def paste_turned_corner(card, w, h):
    """Copies the top-left corner, turned around, into the bottom-right corner."""
    rotated = card.rotate(180)
    for y in range(CARD_H - h, CARD_H):
        for x in range(CARD_W - w, CARD_W):
            px = rotated.getpixel((x, y))
            if px[3] > 0 and px not in (PAPER, BORDER, GOLD):
                card.putpixel((x, y), px)


def joker(red):
    card = blank()
    color = RED if red else BLACK
    for i, ch in enumerate("JOKER"):
        bitmap(card, SMALL[ch], 3, 3 + i * 6, color)
    if red:
        star(card, 17, 22, 8, GOLD)
        star(card, 17, 22, 4, RED, 0.5)
    else:
        star(card, 17, 22, 8, (90, 90, 96, 255))
        star(card, 17, 22, 4, BLACK, 0.5)
    paste_turned_corner(card, 8, 34)
    return card


def back(style):
    rnd = random.Random(style)
    card = blank((250, 250, 250, 255))
    for y in range(3, CARD_H - 3):
        for x in range(3, CARD_W - 3):
            if style == "red":
                c = (172, 34, 40, 255) if (x + y) % 6 and (x - y) % 6 else (120, 18, 26, 255)
            elif style == "blue":
                c = (40, 78, 170, 255) if (x + y) % 6 and (x - y) % 6 else (24, 44, 112, 255)
            elif style == "dirt":
                if y < 9:
                    c = rnd.choice([(96, 160, 56, 255), (84, 142, 48, 255), (110, 176, 64, 255)])
                    if y == 8 and rnd.random() < 0.5:
                        c = (134, 96, 67, 255)
                else:
                    c = rnd.choice([(134, 96, 67, 255), (121, 85, 58, 255), (150, 108, 74, 255), (104, 74, 50, 255)])
            else:  # green felt
                c = (34, 110, 64, 255) if rnd.random() > 0.15 else (28, 96, 54, 255)
            card.putpixel((x, y), c)
    if style in ("red", "blue", "felt"):
        star(card, 16, 22, 6, GOLD)
    return card


def landlord_icon():
    icon = Image.new("RGBA", (16, 16), CLEAR)
    hat = ["................",
           "................",
           "................",
           "......####......",
           ".....######.....",
           "....########....",
           "....########....",
           "...##########...",
           "...##########...",
           "..############..",
           ".##############.",
           "################",
           "################",
           "................",
           "................",
           "................"]
    bitmap(icon, hat, 0, 0, (196, 150, 70, 255))
    for x in range(3, 13):
        put(icon, x, 10, (150, 40, 30, 255))
    return icon


def farmer_icon():
    icon = Image.new("RGBA", (16, 16), CLEAR)
    hat = ["................",
           "................",
           "................",
           "................",
           ".......##.......",
           "......####......",
           ".....######.....",
           "....########....",
           "...##########...",
           "..############..",
           ".##############.",
           "################",
           "................",
           "................",
           "................",
           "................"]
    bitmap(icon, hat, 0, 0, (226, 196, 110, 255))
    return icon


def atlas():
    sheet = Image.new("RGBA", (ATLAS_W, ATLAS_H), CLEAR)
    cards = [face(r, s) for s in SUITS for r in RANKS] + [joker(False), joker(True)]
    cards += [back(s) for s in ("red", "blue", "dirt", "felt")]
    for i, card in enumerate(cards):
        sheet.paste(card, ((i % COLS) * CARD_W, (i // COLS) * CARD_H))
    # Icons sit below the cards.
    icon_y = 5 * CARD_H
    sheet.paste(landlord_icon(), (0, icon_y))
    sheet.paste(farmer_icon(), (16, icon_y))
    # The paper colour of a card's edges, for cards drawn with thickness on the table.
    for y in range(8):
        for x in range(8):
            sheet.putpixel((EDGE_X + x, EDGE_Y + y), (226, 222, 208, 255))
    sheet.paste(felt(), (FELT_X, FELT_Y))
    return sheet


def felt():
    """Pale cloth with a little noise; the renderer tints it with the dyed colour."""
    tile = Image.new("RGBA", (16, 16))
    rnd = random.Random(3)
    for y in range(16):
        for x in range(16):
            g = 236 + rnd.randint(-14, 8)
            tile.putpixel((x, y), (g, g, g, 255))
    return tile


def table_textures():
    rnd = random.Random(7)
    top = Image.new("RGBA", (16, 16))
    side = Image.new("RGBA", (16, 16))
    planks = [(162, 120, 74), (150, 110, 66), (174, 130, 82), (140, 100, 60)]
    for y in range(16):
        for x in range(16):
            # The top is a wooden rim around a pale area; the felt is drawn over it in the dyed color.
            rim = x < 2 or x > 13 or y < 2 or y > 13
            if rim:
                c = planks[(x + y * 3) % 4] if rnd.random() > 0.2 else planks[0]
            else:
                g = 214 + rnd.randint(-10, 10)
                c = (g, g, g)
            top.putpixel((x, y), c + (255,))
            band = (y // 4) % 2
            c = planks[(x // 4 + band) % 4]
            if y in (0, 15) or x in (0, 15):
                c = (110, 78, 44)
            side.putpixel((x, y), tuple(min(255, max(0, v + rnd.randint(-6, 6))) for v in c) + (255,))
    return top, side


def main():
    os.makedirs(f"{OUT}/gui", exist_ok=True)
    os.makedirs(f"{OUT}/block", exist_ok=True)
    atlas().save(f"{OUT}/gui/cards.png")
    top, side = table_textures()
    top.save(f"{OUT}/block/card_table_top.png")
    side.save(f"{OUT}/block/card_table_side.png")


if __name__ == "__main__":
    main()
