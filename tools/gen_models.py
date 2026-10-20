#!/usr/bin/env python3
"""
Writes the base block models of the card table and the stool, which every wood's models
inherit; gen_wood.py writes those per-wood models. Run it from the repository root.

The table top is 14 pixels high, two lower than a full block, and the stool's cushion 12,
so a seated player looks down onto the cloth over the hand in front of them. Keep these
in step with CardTableBlock.TOP and StoolBlock.SEAT_HEIGHT.

A table is 3x3 blocks: the middle part is plain top, the four edge parts add a raised rim
and an apron under the top on their outer side (north, before turning), and the corner
parts add both of those on two sides and a turned leg.
"""
import json
import os

MODELS = "common/src/main/resources/assets/tukuai/models/block"

TOP = 14  # Height of the table top, in pixels.
BOARD = 2  # Thickness of the top board.
RIM_W = 2  # Width of the raised wooden rim around the cloth; see CardTableRenderer.RIM.
RIM_H = 1
APRON = 3  # Height of the apron board under the top.

TABLE_TEXTURES = {
    "particle": "tukuai:block/card_table_side",
    "top": "minecraft:block/oak_planks",
    "side": "tukuai:block/card_table_side",
    "bottom": "minecraft:block/oak_planks",
    "leg": "minecraft:block/stripped_oak_log",
}


def box(frm, to, faces):
    return {"from": frm, "to": to, "faces": faces}


def face(texture, uv=None, cull=None):
    f = {"texture": texture}
    if uv is not None:
        f["uv"] = uv
    if cull is not None:
        f["cullface"] = cull
    return f


def side_uv(y0, y1, x0=0, x1=16):
    """A strip of a side texture, for a face from y0 to y1 pixels up the block."""
    return [x0, 16 - y1, x1, 16 - y0]


def board(*outer):
    """The top board, across the whole block, with its edge showing on the [outer] sides."""
    faces = {"up": face("#top"), "down": face("#bottom")}
    for d in outer:
        faces[d] = face("#side", side_uv(TOP - BOARD, TOP))
    return box([0, TOP - BOARD, 0], [16, TOP, 16], faces)


def rim_north(x0=0, x1=16):
    """The raised rim along the north edge."""
    return [
        box([x0, TOP, 0], [x1, TOP + RIM_H, RIM_W], {
            "up": face("#leg", [x0, 0, x1, RIM_W]),
            "north": face("#leg", side_uv(TOP, TOP + RIM_H, x0, x1)),
            "south": face("#leg", side_uv(TOP, TOP + RIM_H, x0, x1)),
            "east": face("#leg", side_uv(TOP, TOP + RIM_H, 0, RIM_W)),
            "west": face("#leg", side_uv(TOP, TOP + RIM_H, 0, RIM_W)),
        }),
    ]


def rim_west(z0=0, z1=16):
    return [
        box([0, TOP, z0], [RIM_W, TOP + RIM_H, z1], {
            "up": face("#leg", [0, z0, RIM_W, z1]),
            "west": face("#leg", side_uv(TOP, TOP + RIM_H, z0, z1)),
            "east": face("#leg", side_uv(TOP, TOP + RIM_H, z0, z1)),
            "north": face("#leg", side_uv(TOP, TOP + RIM_H, 0, RIM_W)),
            "south": face("#leg", side_uv(TOP, TOP + RIM_H, 0, RIM_W)),
        }),
    ]


def apron_north(x0=0, x1=16):
    """A board under the top, set in a little from the edge."""
    y1 = TOP - BOARD
    return box([x0, y1 - APRON, 0.5], [x1, y1, 1.5], {
        "north": face("#side", side_uv(y1 - APRON, y1, x0, x1)),
        "south": face("#side", side_uv(y1 - APRON, y1, x0, x1)),
        "down": face("#side", [x0, 0, x1, 1]),
        "east": face("#side", [0, 16 - y1, 1, 16 - y1 + APRON]),
        "west": face("#side", [0, 16 - y1, 1, 16 - y1 + APRON]),
    })


def apron_west(z0=0, z1=16):
    y1 = TOP - BOARD
    return box([0.5, y1 - APRON, z0], [1.5, y1, z1], {
        "west": face("#side", side_uv(y1 - APRON, y1, z0, z1)),
        "east": face("#side", side_uv(y1 - APRON, y1, z0, z1)),
        "down": face("#side", [0, z0, 1, z1]),
        "north": face("#side", [0, 16 - y1, 1, 16 - y1 + APRON]),
        "south": face("#side", [0, 16 - y1, 1, 16 - y1 + APRON]),
    })


def column(x0, z0, x1, z1, y0, y1, tex="#leg", down=False):
    """A square post of the leg texture, its sides cut from the log's bark."""
    w = x1 - x0
    faces = {d: face(tex, [0, 16 - y1, w, 16 - y0]) for d in ("north", "south", "east", "west")}
    faces["up"] = face(tex, [0, 0, w, z1 - z0])
    if down:
        faces["down"] = face(tex, [0, 0, w, z1 - z0], "down")
    else:
        faces["down"] = face(tex, [0, 0, w, z1 - z0])
    return box([x0, y0, z0], [x1, y1, z1], faces)


def leg():
    """A leg in the north-west corner: a foot, a slim post, and a collar under the apron."""
    top = TOP - BOARD
    return [
        column(1, 1, 5, 5, 0, 1, down=True),
        column(1.5, 1.5, 4.5, 4.5, 1, top - 2),
        column(1, 1, 5, 5, top - 2, top),
    ]


def table_part(kind):
    if kind == "edge":
        elements = [board("north")] + rim_north() + [apron_north()]
    elif kind == "corner":
        elements = [board("north", "west")] + rim_north() + rim_west(RIM_W, 16) + [apron_north(5, 16), apron_west(5, 16)] + leg()
    else:
        elements = [board()]
    return {"parent": "minecraft:block/block", "textures": TABLE_TEXTURES, "elements": elements}


def turned(element, times):
    """The same element turned 90 degrees clockwise seen from above, [times] times, for the item."""
    for _ in range(times):
        (x0, y0, z0), (x1, y1, z1) = element["from"], element["to"]
        element = dict(element)
        element["from"] = [16 - z1, y0, x0]
        element["to"] = [16 - z0, y1, x1]
        turn = {"north": "east", "east": "south", "south": "west", "west": "north", "up": "up", "down": "down"}
        element["faces"] = {turn[d]: f for d, f in element["faces"].items()}
    return element


def table_item():
    """The table in the inventory: one block with a rim and a leg in every corner."""
    elements = [box([0, TOP - BOARD, 0], [16, TOP, 16], {
        "up": face("#top"),
        "down": face("#bottom"),
        "north": face("#side", side_uv(TOP - BOARD, TOP)),
        "south": face("#side", side_uv(TOP - BOARD, TOP)),
        "east": face("#side", side_uv(TOP - BOARD, TOP)),
        "west": face("#side", side_uv(TOP - BOARD, TOP)),
    })]
    corner = rim_north() + leg()
    for times in range(4):
        elements += [turned(e, times) for e in corner]
    textures = dict(TABLE_TEXTURES)
    textures["top"] = "tukuai:block/card_table_top"
    return {"parent": "minecraft:block/block", "textures": textures, "elements": elements}


SEAT = 12  # Top of the stool's cushion.
CUSHION = 2.5
SEAT_BOARD = 1.5


def stool_frame():
    board_top = SEAT - CUSHION
    board_bottom = board_top - SEAT_BOARD
    legs = []
    for x, z in ((2, 2), (12, 2), (2, 12), (12, 12)):
        legs.append(box([x, 0, z], [x + 2, board_bottom, z + 2], {
            **{d: face("#leg", [0, 16 - board_bottom, 2, 16]) for d in ("north", "south", "east", "west")},
            "down": face("#leg", [0, 0, 2, 2], "down"),
        }))
    rails = [
        box([4, 3, 2.5], [12, 4, 3.5], {d: face("#wood", [4, 12, 12, 13]) for d in ("north", "south", "up", "down")}),
        box([4, 3, 12.5], [12, 4, 13.5], {d: face("#wood", [4, 12, 12, 13]) for d in ("north", "south", "up", "down")}),
        box([2.5, 5, 4], [3.5, 6, 12], {d: face("#wood", [4, 10, 12, 11]) for d in ("east", "west", "up", "down")}),
        box([12.5, 5, 4], [13.5, 6, 12], {d: face("#wood", [4, 10, 12, 11]) for d in ("east", "west", "up", "down")}),
    ]
    seat = box([1, board_bottom, 1], [15, board_top, 15], {
        **{d: face("#wood", [1, 16 - board_top, 15, 16 - board_bottom]) for d in ("north", "south", "east", "west")},
        "down": face("#wood"),
        "up": face("#wood"),
    })
    return {
        "parent": "minecraft:block/block",
        "textures": {"particle": "#wood", "wood": "minecraft:block/oak_planks", "leg": "minecraft:block/stripped_oak_log"},
        "elements": legs + rails + [seat],
    }


def stool_cushion():
    y0 = SEAT - CUSHION
    return {
        "parent": "minecraft:block/block",
        "textures": {"particle": "#cushion", "cushion": "minecraft:block/red_wool"},
        "elements": [
            box([1.5, y0, 1.5], [14.5, SEAT - 0.5, 14.5], {
                **{d: face("#cushion", [1.5, 16 - SEAT + 0.5, 14.5, 16 - y0]) for d in ("north", "south", "east", "west")},
            }),
            # A slightly smaller top layer rounds the cushion's edge.
            box([2, SEAT - 0.5, 2], [14, SEAT, 14], {
                **{d: face("#cushion", [2, 16 - SEAT, 14, 16 - SEAT + 0.5]) for d in ("north", "south", "east", "west")},
                "up": face("#cushion", [2, 2, 14, 14]),
            }),
        ],
    }


def write(name, model):
    with open(f"{MODELS}/{name}.json", "w", encoding="utf-8") as f:
        json.dump(model, f, indent=2)
        f.write("\n")


def main():
    os.makedirs(MODELS, exist_ok=True)
    for kind in ("center", "edge", "corner"):
        write(f"card_table_{kind}", table_part(kind))
    write("card_table", table_item())
    write("stool_frame", stool_frame())
    write("stool_cushion", stool_cushion())


if __name__ == "__main__":
    main()
