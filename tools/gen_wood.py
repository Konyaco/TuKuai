#!/usr/bin/env python3
"""
Writes the JSON for card tables and stools in every kind of wood: block states, block and
item models, and recipes. Run it from the repository root after changing WOODS; the side
textures of the tables come from gen_textures.py.

A table takes its wood from the planks in its recipe, and a stool from its slab. Oak is
the default, so tables and stools saved before they had a wood keep the oak look.

Block states and block models are the same for every Minecraft version and go in common/.
Item models and recipes differ, so each version under versions/ gets its own:

- 26.1.2 picks an item's model by its block state and writes recipe ingredients as plain ids.
- 1.21.1 picks it by number (the `tukuai:wood` and `tukuai:color` item properties, see
  ItemModels.kt), writes ingredients as {"item": ...} or {"tag": ...}, and has no pale oak:
  no recipes for it, and birch textures on its block models so they are not missing any.
"""
import json
import os

COMMON = "common/src/main/resources"
ASSETS = f"{COMMON}/assets/tukuai"
V26 = "versions/26.1.2/src/main/resources"
V1211 = "versions/1.21.1/src/main/resources"

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

# Woods that came after 1.21.1, and the wood 1.21.1 draws instead, so its models have textures.
NEWER_WOODS = {"pale_oak": ("birch", "stripped_birch_log")}

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


def write_block_model(name, wood, model):
    """A block model of [wood]: in common, or for a newer wood, per version with 1.21.1's stand-in."""
    if wood not in NEWER_WOODS:
        write(f"{ASSETS}/models/block/{name}.json", model)
        return
    write(f"{V26}/assets/tukuai/models/block/{name}.json", model)
    old, old_log = NEWER_WOODS[wood]
    text = json.dumps(model).replace(f"minecraft:block/{wood}_planks", f"minecraft:block/{old}_planks")
    text = text.replace(f"minecraft:block/{dict(WOODS)[wood]}", f"minecraft:block/{old_log}")
    write(f"{V1211}/assets/tukuai/models/block/{name}.json", json.loads(text))


def table_textures(wood, log):
    return {
        "particle": side(wood),
        "top": f"minecraft:block/{wood}_planks",
        "side": side(wood),
        "bottom": f"minecraft:block/{wood}_planks",
        "leg": f"minecraft:block/{log}",
    }


def woods_for(version):
    return [(i, wood, log) for i, (wood, log) in enumerate(WOODS) if version != V1211 or wood not in NEWER_WOODS]


def ingredient(version, value):
    """A recipe ingredient: an item id, or a tag as #namespace:path."""
    if version == V26:
        return value
    return {"tag": value[1:]} if value.startswith("#") else {"item": value}


def recipe(version, group, key, pattern, result, state):
    return {
        "type": "minecraft:crafting_shaped",
        "category": "misc",
        "group": group,
        "key": {k: ingredient(version, v) for k, v in key.items()},
        "pattern": pattern,
        "result": {"count": 1, "id": result, "components": {"minecraft:block_state": state}},
    }


def by_number(base, overrides):
    """A 1.21.1 item model: [base], or the last of [overrides], (predicate, model), whose numbers the item reaches."""
    return {"parent": base, "overrides": [{"predicate": p, "model": m} for p, m in overrides]}


def tables():
    parts = []
    for wood, log in WOODS:
        textures = table_textures(wood, log)
        for shape in ("corner", "edge", "center"):
            write_block_model(f"card_table_{shape}_{wood}", wood, {"parent": f"tukuai:block/card_table_{shape}", "textures": textures})
        # The item shows the middle part with the cloth's pale top.
        write_block_model(
            f"card_table_{wood}", wood,
            {"parent": "tukuai:block/card_table", "textures": dict(textures, top="tukuai:block/card_table_top")},
        )
        for part, (shape, turn) in enumerate(PARTS):
            model = {"model": f"tukuai:block/card_table_{shape}_{wood}"}
            if turn:
                model["y"] = turn
            parts.append({"when": {"part": str(part), "wood": wood}, "apply": model})
    write(f"{ASSETS}/blockstates/card_table.json", {"multipart": parts})

    # The big table is built from the same parts, five by five.
    big = []
    for wood, _ in WOODS:
        for part, (shape, turn) in enumerate(table_parts(2)):
            model = {"model": f"tukuai:block/card_table_{shape}_{wood}"}
            if turn:
                model["y"] = turn
            big.append({"when": {"part": str(part), "wood": wood}, "apply": model})
    write(f"{ASSETS}/blockstates/big_card_table.json", {"multipart": big})

    for name in ("card_table", "big_card_table"):
        write(f"{V26}/assets/tukuai/items/{name}.json", {"model": select("wood", [(w, f"tukuai:block/card_table_{w}") for w, _ in WOODS], "tukuai:block/card_table_oak")})
        write(f"{V1211}/assets/tukuai/models/item/{name}.json", by_number(
            "tukuai:block/card_table_oak",
            [({"tukuai:wood": i / 16}, f"tukuai:block/card_table_{wood}") for i, wood, _ in woods_for(V1211) if i > 0],
        ))

    for version in (V26, V1211):
        for _, wood, _ in woods_for(version):
            write(f"{version}/data/tukuai/recipe/card_table/{wood}.json", recipe(
                version, "tukuai:card_table",
                {"W": "#minecraft:wool", "P": f"minecraft:{wood}_planks", "L": "#minecraft:logs"},
                ["WWW", "PPP", "L L"], "tukuai:card_table", {"wood": wood},
            ))
            # A big table is a card table of any wood with more cloth and planks around it.
            write(f"{version}/data/tukuai/recipe/big_card_table/{wood}.json", recipe(
                version, "tukuai:big_card_table",
                {"W": "#minecraft:wool", "P": f"minecraft:{wood}_planks", "T": "tukuai:card_table", "L": "#minecraft:logs"},
                ["WWW", "PTP", "L L"], "tukuai:big_card_table", {"wood": wood},
            ))


def stools():
    parts = []
    for wood, log in WOODS:
        write_block_model(f"stool_frame_{wood}", wood, {
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
    write(f"{V26}/assets/tukuai/items/stool.json", {"model": {
        "type": "minecraft:composite",
        "models": [
            select("wood", [(w, f"tukuai:block/stool_frame_{w}") for w, _ in WOODS], "tukuai:block/stool_frame_oak"),
            select("color", [(c, f"tukuai:block/stool_cushion_{c}") for c in COLORS], "tukuai:block/stool_cushion_red"),
        ],
    }})

    # 1.21.1 has no composite item models, so it gets the frame and the cushion in one model
    # for every wood and colour, chosen by number.
    with open(f"{ASSETS}/models/block/stool_frame.json", encoding="utf-8") as f:
        frame = json.load(f)
    with open(f"{ASSETS}/models/block/stool_cushion.json", encoding="utf-8") as f:
        cushion = json.load(f)
    item = f"{V1211}/assets/tukuai/models/item"
    write(f"{item}/stool_whole.json", {
        "parent": frame["parent"],
        "textures": {"particle": "#wood"},
        "elements": frame["elements"] + cushion["elements"],
    })
    overrides = []
    for i, wood, log in woods_for(V1211):
        for c, color in enumerate(COLORS):
            write(f"{item}/stool_{wood}_{color}.json", {
                "parent": "tukuai:item/stool_whole",
                "textures": {"wood": f"minecraft:block/{wood}_planks", "leg": f"minecraft:block/{log}", "cushion": f"minecraft:block/{color}_wool"},
            })
            overrides.append(({"tukuai:wood": i / 16, "tukuai:color": c / 16}, f"tukuai:item/stool_{wood}_{color}"))
    write(f"{item}/stool.json", by_number("tukuai:item/stool_oak_red", overrides))

    for version in (V26, V1211):
        for _, wood, _ in woods_for(version):
            for color in COLORS:
                write(f"{version}/data/tukuai/recipe/stool/{wood}_{color}.json", recipe(
                    version, "tukuai:stool",
                    {"W": f"minecraft:{color}_wool", "S": f"minecraft:{wood}_slab", "T": "minecraft:stick"},
                    ["W", "S", "T"], "tukuai:stool", {"color": color, "wood": wood},
                ))


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
