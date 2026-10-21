#!/usr/bin/env python3
"""
Writes the JSON for card tables and stools in every kind of wood: block states, block and
item models, and recipes. Run it from the repository root after changing WOODS; the side
textures of the tables come from gen_textures.py.

A table takes its wood from the planks in its recipe, and a stool from its slab. Oak is
the default, so tables and stools saved before they had a wood keep the oak look.
"""
import json
import os

RES = "common/src/main/resources"
ASSETS = f"{RES}/assets/tukuai"
DATA = f"{RES}/data/tukuai"

# In the order of Wood in TuKuaiContent: (name, stripped log texture).
WOODS = [
    ("oak", "stripped_oak_log"),
    ("spruce", "stripped_spruce_log"),
    ("birch", "stripped_birch_log"),
    ("jungle", "stripped_jungle_log"),
    ("acacia", "stripped_acacia_log"),
    ("dark_oak", "stripped_dark_oak_log"),
    ("mangrove", "stripped_mangrove_log"),
    ("cherry", "stripped_cherry_log"),
    ("pale_oak", "stripped_pale_oak_log"),
    ("bamboo", "stripped_bamboo_block"),
    ("crimson", "stripped_crimson_stem"),
    ("warped", "stripped_warped_stem"),
]

COLORS = [
    "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
    "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black",
]

def table_parts(radius):
    """Which model each part of a table uses, and how far it turns: the 3x3 table, or the 5x5 one with radius 2."""
    parts = []
    for dz in range(-radius, radius + 1):
        for dx in range(-radius, radius + 1):
            if abs(dx) == radius and abs(dz) == radius:
                parts.append(("corner", {(-1, -1): 0, (1, -1): 90, (1, 1): 180, (-1, 1): 270}[(dx // radius, dz // radius)]))
            elif dz == -radius:
                parts.append(("edge", 0))
            elif dx == radius:
                parts.append(("edge", 90))
            elif dz == radius:
                parts.append(("edge", 180))
            elif dx == -radius:
                parts.append(("edge", 270))
            else:
                parts.append(("center", 0))
    return parts


# Which model each of the nine table parts uses, and how far it turns.
PARTS = table_parts(1)


def write(path, value):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(value, f, indent=2)
        f.write("\n")


def side(wood):
    # Oak keeps the side texture tables had before they came in other woods.
    return "tukuai:block/card_table_side" if wood == "oak" else f"tukuai:block/card_table_side_{wood}"


def table_textures(wood, log):
    return {
        "particle": side(wood),
        "top": f"minecraft:block/{wood}_planks",
        "side": side(wood),
        "bottom": f"minecraft:block/{wood}_planks",
        "leg": f"minecraft:block/{log}",
    }


def tables():
    parts = []
    for wood, log in WOODS:
        textures = table_textures(wood, log)
        for shape in ("corner", "edge", "center"):
            write(f"{ASSETS}/models/block/card_table_{shape}_{wood}.json", {"parent": f"tukuai:block/card_table_{shape}", "textures": textures})
        # The item shows the middle part with the cloth's pale top.
        write(
            f"{ASSETS}/models/block/card_table_{wood}.json",
            {"parent": "tukuai:block/card_table", "textures": dict(textures, top="tukuai:block/card_table_top")},
        )
        for part, (shape, turn) in enumerate(PARTS):
            model = {"model": f"tukuai:block/card_table_{shape}_{wood}"}
            if turn:
                model["y"] = turn
            parts.append({"when": {"part": str(part), "wood": wood}, "apply": model})
    write(f"{ASSETS}/blockstates/card_table.json", {"multipart": parts})
    write(f"{ASSETS}/items/card_table.json", {"model": select("wood", [(w, f"tukuai:block/card_table_{w}") for w, _ in WOODS], "tukuai:block/card_table_oak")})

    # The big table is built from the same parts, five by five.
    big = []
    for wood, _ in WOODS:
        for part, (shape, turn) in enumerate(table_parts(2)):
            model = {"model": f"tukuai:block/card_table_{shape}_{wood}"}
            if turn:
                model["y"] = turn
            big.append({"when": {"part": str(part), "wood": wood}, "apply": model})
    write(f"{ASSETS}/blockstates/big_card_table.json", {"multipart": big})
    write(f"{ASSETS}/items/big_card_table.json", {"model": select("wood", [(w, f"tukuai:block/card_table_{w}") for w, _ in WOODS], "tukuai:block/card_table_oak")})

    for wood, _ in WOODS:
        write(f"{DATA}/recipe/card_table/{wood}.json", {
            "type": "minecraft:crafting_shaped",
            "category": "misc",
            "group": "tukuai:card_table",
            "key": {"W": "#minecraft:wool", "P": f"minecraft:{wood}_planks", "L": "#minecraft:logs"},
            "pattern": ["WWW", "PPP", "L L"],
            "result": {"count": 1, "id": "tukuai:card_table", "components": {"minecraft:block_state": {"wood": wood}}},
        })
        # A big table is a card table of any wood with more cloth and planks around it.
        write(f"{DATA}/recipe/big_card_table/{wood}.json", {
            "type": "minecraft:crafting_shaped",
            "category": "misc",
            "group": "tukuai:big_card_table",
            "key": {"W": "#minecraft:wool", "P": f"minecraft:{wood}_planks", "T": "tukuai:card_table", "L": "#minecraft:logs"},
            "pattern": ["WWW", "PTP", "L L"],
            "result": {"count": 1, "id": "tukuai:big_card_table", "components": {"minecraft:block_state": {"wood": wood}}},
        })


def stools():
    parts = []
    for wood, log in WOODS:
        write(f"{ASSETS}/models/block/stool_frame_{wood}.json", {
            "parent": "tukuai:block/stool_frame",
            "textures": {"wood": f"minecraft:block/{wood}_planks", "leg": f"minecraft:block/{log}"},
        })
        parts.append({"when": {"wood": wood}, "apply": {"model": f"tukuai:block/stool_frame_{wood}"}})
    for color in COLORS:
        write(f"{ASSETS}/models/block/stool_cushion_{color}.json", {
            "parent": "tukuai:block/stool_cushion",
            "textures": {"cushion": f"minecraft:block/{color}_wool"},
        })
        parts.append({"when": {"color": color}, "apply": {"model": f"tukuai:block/stool_cushion_{color}"}})
    write(f"{ASSETS}/blockstates/stool.json", {"multipart": parts})
    write(f"{ASSETS}/items/stool.json", {"model": {
        "type": "minecraft:composite",
        "models": [
            select("wood", [(w, f"tukuai:block/stool_frame_{w}") for w, _ in WOODS], "tukuai:block/stool_frame_oak"),
            select("color", [(c, f"tukuai:block/stool_cushion_{c}") for c in COLORS], "tukuai:block/stool_cushion_red"),
        ],
    }})

    for wood, _ in WOODS:
        for color in COLORS:
            write(f"{DATA}/recipe/stool/{wood}_{color}.json", {
                "type": "minecraft:crafting_shaped",
                "category": "misc",
                "group": "tukuai:stool",
                "key": {"W": f"minecraft:{color}_wool", "S": f"minecraft:{wood}_slab", "T": "minecraft:stick"},
                "pattern": ["W", "S", "T"],
                "result": {"count": 1, "id": "tukuai:stool", "components": {"minecraft:block_state": {"color": color, "wood": wood}}},
            })


def select(prop, cases, fallback):
    return {
        "type": "minecraft:select",
        "property": "minecraft:block_state",
        "block_state_property": prop,
        "cases": [{"when": value, "model": {"type": "minecraft:model", "model": model}} for value, model in cases],
        "fallback": {"type": "minecraft:model", "model": fallback},
    }


def main():
    tables()
    stools()


if __name__ == "__main__":
    main()
