#!/usr/bin/env python3
"""
Writes the base block models of the card table and the chair, which every wood's models
inherit; gen_wood.py writes those per-wood models. Run it from the repository root.

The table top is 14 pixels high, two lower than a full block, and the chair's cushion 12,
so a seated player looks down onto the cloth over the hand in front of them. Keep these
in step with CardTableBlock.TOP and ChairBlock.SEAT_HEIGHT.

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


SEAT = 12  # Top of the chair's cushion.
SEAT_BOARD = (8, 10)  # Bottom and top of the seat board.
BACK = 22  # Top of the back posts; the crest of the top rail rises one more.

# The chair faces north (whoever sits on it looks north) and the block state turns it.
# Every part is cut to whole or half pixels, touches its neighbours face to face and leaves
# out the faces that touch, so there are no gaps or flickering faces anywhere.
CHAIR_DISPLAY = {
    # The chair is taller than a block: draw it smaller and lower in the inventory.
    "gui": {"rotation": [30, 225, 0], "translation": [0, -1.75, 0], "scale": [0.56, 0.56, 0.56]},
    "fixed": {"rotation": [0, 0, 0], "translation": [0, -1.5, 0], "scale": [0.4, 0.4, 0.4]},
    "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.25, 0.25, 0.25]},
}

SIDES = ("north", "south", "east", "west")
AXES = {"north": ("x", "y"), "south": ("x", "y"), "east": ("z", "y"), "west": ("z", "y"), "up": ("x", "z"), "down": ("x", "z")}
NORMAL = {"north": "z", "south": "z", "east": "x", "west": "x", "up": "y", "down": "y"}


def span(a, b):
    """A range of texture pixels for coordinates a..b, wrapped into the 16 of one texture."""
    shift = 16 * (a // 16)
    return a - shift, b - shift


def grain_box(frm, to, tex, grain, skip=(), cull=None):
    """
    A piece of wood whose grain (the texture's up and down) runs along [grain], with every
    texture pixel the size of a model pixel. Faces in [skip] touch another piece and are left out.
    """
    lo = dict(zip("xyz", frm))
    hi = dict(zip("xyz", to))
    faces = {}
    for d in SIDES + ("up", "down"):
        if d in skip:
            continue
        u_axis, v_axis = AXES[d]
        if NORMAL[d] == grain or v_axis == grain:
            # End grain, or grain already along the texture's up and down.
            u0, u1 = span(lo[u_axis], hi[u_axis])
            v0, v1 = span(lo[v_axis], hi[v_axis])
            f = face(tex, [u0, 16 - v1, u1, 16 - v0] if v_axis == "y" else [u0, v0, u1, v1])
        else:
            # Grain across the face: turn the texture a quarter.
            u0, u1 = span(lo[v_axis], hi[v_axis])
            v0, v1 = span(lo[u_axis], hi[u_axis])
            f = face(tex, [u0, v0, u1, v1])
            f["rotation"] = 90
        if cull and d in cull:
            f["cullface"] = d
        faces[d] = f
    return box(frm, to, faces)


def chair_frame():
    b0, b1 = SEAT_BOARD
    legs = []
    for x in (2, 12):
        # Front legs stop under the seat board.
        legs.append(grain_box([x, 0, 2], [x + 2, b0, 4], "#leg", "y", skip=("up",), cull=("down",)))
        # Back legs run on up through the board as the posts of the back, in two pieces
        # because one texture is only 16 pixels tall.
        legs.append(grain_box([x, 0, 12], [x + 2, 16, 14], "#leg", "y", skip=("up",), cull=("down",)))
        legs.append(grain_box([x, 16, 12], [x + 2, BACK, 14], "#leg", "y", skip=("down",)))
    # Aprons under the board, flush with the outside of the legs.
    aprons = [
        grain_box([4, 6, 2], [12, b0, 3], "#leg", "x", skip=("up", "east", "west")),
        grain_box([4, 6, 13], [12, b0, 14], "#leg", "x", skip=("up", "east", "west")),
        grain_box([2, 6, 4], [3, b0, 12], "#leg", "z", skip=("up", "north", "south")),
        grain_box([13, 6, 4], [14, b0, 12], "#leg", "z", skip=("up", "north", "south")),
        # Low side stretchers.
        grain_box([2.5, 2, 4], [3.5, 3, 12], "#leg", "z", skip=("north", "south")),
        grain_box([12.5, 2, 4], [13.5, 3, 12], "#leg", "z", skip=("north", "south")),
    ]
    edge = [4, 6]  # A light band of the planks, away from the dark joins.
    seat = box([1, b0, 1], [15, b1, 15], {
        "north": face("#wood", [1, edge[0], 15, edge[1]]),
        "south": face("#wood", [1, edge[0], 15, edge[1]]),
        "east": face("#wood", [1, edge[0], 15, edge[1]]),
        "west": face("#wood", [1, edge[0], 15, edge[1]]),
        "up": face("#wood", [1, 1, 15, 15]),
        "down": face("#wood", [1, 1, 15, 15]),
    })
    # The back: rails and a centre splat between the posts, flush with their front, so the
    # cushion meets them without a gap.
    back = [
        grain_box([4, b1, 12], [12, 14, 13], "#leg", "x", skip=("down", "east", "west")),
        grain_box([7, 14, 12], [9, 19, 13], "#leg", "y", skip=("up", "down")),
        grain_box([4, 19, 12], [12, BACK, 13], "#leg", "x", skip=("east", "west")),
        grain_box([5, BACK, 12], [11, BACK + 1, 13], "#leg", "x", skip=("down",)),
    ]
    return {
        "parent": "minecraft:block/block",
        "textures": {"particle": "#wood", "wood": "minecraft:block/oak_planks", "leg": "minecraft:block/stripped_oak_log"},
        "display": CHAIR_DISPLAY,
        "elements": legs + aprons + [seat] + back,
    }


def chair_cushion():
    b1 = SEAT_BOARD[1]
    return {
        "parent": "minecraft:block/block",
        "textures": {"particle": "#cushion", "cushion": "minecraft:block/red_wool"},
        "display": CHAIR_DISPLAY,
        "elements": [
            # From the front of the seat back to the rails of the back.
            box([2, b1, 2], [14, SEAT - 0.5, 12], {
                d: face("#cushion", [2, 16 - SEAT + 0.5, 14, 16 - b1] if d in ("north", "south") else [2, 16 - SEAT + 0.5, 12, 16 - b1])
                for d in ("north", "east", "west")
            }),
            # A slightly smaller top layer rounds the cushion's edge.
            box([2.5, SEAT - 0.5, 2.5], [13.5, SEAT, 12], {
                **{d: face("#cushion", [2.5, 16 - SEAT, 13.5, 16 - SEAT + 0.5]) for d in ("north", "east", "west")},
                "up": face("#cushion", [2.5, 2.5, 13.5, 12]),
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
    write("chair_frame", chair_frame())
    write("chair_cushion", chair_cushion())


if __name__ == "__main__":
    main()
