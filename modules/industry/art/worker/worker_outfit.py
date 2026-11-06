#!/usr/bin/env python3
"""Frontier worker outfit: full construction work clothes over a vanilla villager.

Original pixel art for the 64x64 vanilla villager UV layout (VillagerModel). The game
draws minecraft:textures/entity/villager/villager.png (head, face, nose stay vanilla),
then this overlay; no vanilla texture is copied into the jar. The overlay paints a hard
hat with brim, a navy work shirt with rolled cuffs, leather work gloves on the folded
hands, a hi-vis vest, a tool belt with pouch, tape measure and hammer, charcoal work
trousers with knee pads and dark boots with light soles, plus a little dirt and wear.
The robe skirt of the jacket layer stays clear so the trousers show.

Standard library only and deterministic:
    python3 modules/industry/art/worker/worker_outfit.py          # write the PNG
    python3 modules/industry/art/worker/worker_outfit.py --check  # verify it is current
"""
from pathlib import Path
import struct
import sys
import zlib

MODULE = Path(__file__).resolve().parents[2]
OUTPUT = MODULE / "src/main/resources/assets/frontier_industry/textures/entity/worker_outfit.png"
SIZE = 64
CLEAR = (0, 0, 0, 0)

# Palettes: light, base, shade, edge.
HAT = ((255, 224, 92), (243, 191, 33), (214, 160, 20), (168, 116, 12))
VEST = ((255, 146, 54), (250, 120, 24), (222, 96, 14), (178, 70, 10))
STRIPE = ((252, 253, 246), (222, 226, 220), (178, 184, 182))
SHIRT = ((84, 108, 152), (50, 70, 112), (40, 56, 92), (28, 40, 68))
CUFF = ((112, 136, 178), (92, 116, 160))
GLOVE = ((222, 182, 116), (198, 154, 88), (164, 122, 64), (126, 90, 46))
TROUSERS = ((92, 98, 106), (70, 75, 82), (58, 62, 68), (42, 45, 50))
KNEE_PAD = ((64, 64, 66), (36, 36, 38), (24, 24, 26))
BOOT = ((96, 66, 44), (72, 48, 32), (54, 36, 24), (34, 24, 18))
SOLE = ((176, 156, 120), (148, 128, 96))
DIRT = ((120, 100, 74), (96, 80, 60))
BELT = ((112, 72, 42), (88, 56, 32), (62, 38, 22))
BUCKLE = (204, 178, 98)
POUCH = ((168, 122, 72), (140, 98, 56), (104, 70, 40))
TAPE = ((250, 206, 40), (40, 40, 44))
STEEL = ((176, 182, 186), (128, 134, 140))


def box(u, v, w, h, d):
    """Vanilla box-UV faces as (x, y, width, height) rectangles."""
    return {"top": (u + d, v, w, d), "bottom": (u + d + w, v, w, d),
            "right": (u, v + d, d, h), "front": (u + d, v + d, w, h),
            "left": (u + d + w, v + d, d, h), "back": (u + 2 * d + w, v + d, w, h)}


HAT_BOX = box(32, 0, 8, 10, 8)      # "hat", inflated 0.51 around the 8x10x8 head
RIM_BOX = box(30, 47, 16, 16, 1)    # "hat_rim", a 16x16 plate turned flat four pixels below the crown
BODY_BOX = box(16, 20, 8, 12, 6)    # "body"
JACKET_BOX = box(0, 38, 8, 20, 6)   # "jacket", inflated 0.5 robe over the body
ARM_BOX = box(44, 22, 4, 8, 4)      # both upper arms of "arms" (the left one mirrored)
HANDS_BOX = box(40, 38, 8, 4, 4)    # crossed forearms of "arms", hands meet in the middle
LEG_BOX = box(0, 22, 4, 12, 4)      # both legs (the left one mirrored)
PAINTED = (HAT_BOX, RIM_BOX, BODY_BOX, JACKET_BOX, ARM_BOX, HANDS_BOX, LEG_BOX)
SIDES = ("right", "front", "left", "back")


def noise(x, y, salt=0):
    """Stable pseudo-random 0..96 per pixel."""
    return ((x * 73856093) ^ (y * 19349663) ^ (salt * 83492791)) % 97


def grain(x, y, palette):
    """Two-tone cloth grain like vanilla villager clothes."""
    return palette[2] if (x * 7 + y * 13) % 11 == 0 else palette[1]


def worn(x, y, color, chance, salt=0):
    """Occasional dirt speck."""
    n = noise(x, y, salt)
    return DIRT[n % 2] if n < chance else color


def paint():
    pixels = [[CLEAR] * SIZE for _ in range(SIZE)]

    def put(x, y, color):
        pixels[y][x] = color if len(color) == 4 else (*color, 255)

    def fill(rect, color_at):
        rx, ry, rw, rh = rect
        for y in range(rh):
            for x in range(rw):
                color = color_at(x, y, rx + x, ry + y)
                if color is not None:
                    put(rx + x, ry + y, color)

    # Hard hat: the crown plus the top four rows of every side of the hat layer.
    fill(HAT_BOX["top"], lambda x, y, *_: HAT[0] if x in (3, 4)
         else HAT[3] if x in (0, 7) or y in (0, 7) else grain(x, y, HAT))
    for side in SIDES:
        fill(HAT_BOX[side], lambda x, y, *_, side=side: None if y > 3 else HAT[3] if y == 3
             else HAT[0] if side in ("front", "back") and x in (3, 4) else grain(x, y, HAT))
    fx, fy, _, _ = HAT_BOX["front"]
    put(fx + 3, fy + 1, VEST[1]); put(fx + 4, fy + 1, VEST[1])  # small orange badge

    # Brim on the flat rim plate: rows run back (top) to face (bottom), the head covers
    # columns 4..11 and rows 4..11. A short peak at the front, a one-pixel lip elsewhere.
    for face, palette in (("front", (HAT[1], HAT[2])), ("back", (HAT[2], HAT[3]))):
        rx, ry, _, _ = RIM_BOX[face]
        cells = [(c, 3) for c in range(4, 12)] + [(c, r) for r in range(4, 12) for c in (3, 12)]
        cells += [(c, 12) for c in range(3, 13)] + [(c, 13) for c in range(4, 12)]
        for c, r in cells:
            put(rx + c, ry + r, palette[1] if r == 13 or c in (3, 12) else palette[0])

    # Work shirt on the body, visible at the collar; the waistband is trouser cloth.
    for side, rect in BODY_BOX.items():
        def shirt(x, y, px, py, side=side):
            if side in SIDES and y >= 10:
                return TROUSERS[2]
            if side == "front" and y == 0 and x in (2, 5):
                return SHIRT[0]  # collar points
            return grain(px, py, SHIRT)
        fill(rect, shirt)

    # Upper arms: shirt sleeves, rolled cuffs at the elbow end.
    for side, rect in ARM_BOX.items():
        fill(rect, lambda x, y, px, py, side=side: CUFF[0] if side in SIDES and y == 6
             else CUFF[1] if side in SIDES and y == 7 else grain(px, py, SHIRT))

    # Crossed forearms: sleeve, rolled cuff, glove gauntlet, gloved hands in the middle.
    def forearm(column, px, py):
        if column in (0, 7):
            return grain(px, py, SHIRT)
        if column in (1, 6):
            return CUFF[0]
        if column in (2, 5):
            return GLOVE[3]
        return worn(px, py, GLOVE[0] if (px + py) % 3 == 0 else GLOVE[1], 12, 3)
    for side in ("top", "bottom", "front", "back"):
        fill(HANDS_BOX[side], lambda x, y, px, py, side=side: forearm(x if side != "back" else 7 - x, px, py))
    for side in ("right", "left"):
        fill(HANDS_BOX[side], lambda x, y, px, py: grain(px, py, SHIRT))

    # Legs: charcoal work trousers, knee pads, dark boots with light soles, dirt near the ground.
    for side, rect in LEG_BOX.items():
        def leg(x, y, px, py, side=side):
            if side == "top":
                return TROUSERS[2]
            if side == "bottom":
                return SOLE[1] if (x + y) % 3 else SOLE[0]
            if y == 11:
                return SOLE[0]
            if y == 8:
                return BOOT[3] if x % 2 else BOOT[0]  # boot collar and laces
            if y >= 9:
                return worn(px, py, BOOT[1] if (x + y) % 4 else BOOT[2], 18, 5)
            if y in (4, 5) and side in ("front", "right", "left"):
                strap = side != "front"
                return KNEE_PAD[2] if strap else KNEE_PAD[0] if y == 4 else KNEE_PAD[1]
            if y == 7:
                return worn(px, py, TROUSERS[3], 30, 7)  # hem, a bit muddy
            return worn(px, py, grain(px, py, TROUSERS), 6 if y < 4 else 14, 9)
        fill(rect, leg)

    # Hi-vis vest on the jacket: rows 0..10 cover the torso, row 11 is the tool belt and the
    # robe skirt below stays transparent except for the tools hanging off the belt.
    vest_rows, belt_row = 11, 11
    fill(JACKET_BOX["top"], lambda x, y, px, py: STRIPE[1] if x in (1, 6) else grain(px, py, VEST))
    for side in SIDES:
        sx, sy, sw, _ = JACKET_BOX[side]
        for y in range(vest_rows):
            for x in range(sw):
                color = worn(sx + x, y, grain(x + sx, y, VEST), 4, 11)
                if y in (7, 8):
                    color = STRIPE[0] if y == 7 else STRIPE[1]  # band around the waist
                elif side in ("front", "back") and x in (1, 6) and y < 7:
                    color = STRIPE[1] if y % 3 else STRIPE[0]  # shoulder straps
                elif y == 9:
                    color = VEST[2]
                if side == "front" and x in (3, 4) and y < 2:
                    color = CLEAR  # V-neck shows the shirt collar
                elif side == "front" and x == 4 and 2 <= y < vest_rows and y not in (7, 8):
                    color = VEST[3]  # zip
                put(sx + x, sy + y, color)
        for x in range(sw):
            put(sx + x, sy + belt_row, BELT[2] if x in (0, sw - 1) else BELT[(x + 1) % 2])
    bx, by, _, _ = JACKET_BOX["front"]
    put(bx + 3, by + belt_row, BUCKLE); put(bx + 4, by + belt_row, BUCKLE)

    # Right hip: leather pouch and a yellow tape measure.
    px, py, _, _ = JACKET_BOX["right"]
    for y in range(belt_row + 1, belt_row + 4):
        for x in range(0, 3):
            edge = x in (0, 2) or y == belt_row + 3
            put(px + x, py + y, POUCH[2] if edge else POUCH[0] if y == belt_row + 1 else POUCH[1])
    for y in (belt_row + 1, belt_row + 2):
        for x in (4, 5):
            put(px + x, py + y, TAPE[1] if (x, y) == (4, belt_row + 2) else TAPE[0])
    # Left hip: hammer hanging from a loop.
    hx, hy, _, _ = JACKET_BOX["left"]
    for x in range(1, 5):
        put(hx + x, hy + belt_row + 1, STEEL[0] if x < 4 else STEEL[1])
    for y in range(belt_row + 2, belt_row + 6):
        put(hx + 2, hy + y, BELT[0] if y % 2 else BELT[1])
    return pixels


def png(pixels):
    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data))
    rows = b"".join(b"\0" + bytes(channel for color in row for channel in color) for row in pixels)
    header = struct.pack(">IIBBBBB", SIZE, SIZE, 8, 6, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(rows, 9)) + chunk(b"IEND", b"")


def inside_painted_regions(x, y):
    return any(rx <= x < rx + rw and ry <= y < ry + rh for faces in PAINTED for rx, ry, rw, rh in faces.values())


def main():
    pixels = paint()
    stray = [(x, y) for y in range(SIZE) for x in range(SIZE) if pixels[y][x][3] and not inside_painted_regions(x, y)]
    assert not stray, f"outfit pixels outside the villager UV boxes: {stray[:5]}"
    # Body, arms and legs must be fully covered so no vanilla shirt or skin shows through.
    for faces in (BODY_BOX, ARM_BOX, HANDS_BOX, LEG_BOX):
        for rx, ry, rw, rh in faces.values():
            assert all(pixels[y][x][3] == 255 for y in range(ry, ry + rh) for x in range(rx, rx + rw)), (rx, ry)
    data = png(pixels)
    if "--check" in sys.argv[1:]:
        if not OUTPUT.is_file() or OUTPUT.read_bytes() != data:
            sys.exit(f"FAIL: {OUTPUT} differs from worker_outfit.py; rerun the generator")
        print("PASS: worker_outfit.png matches its generator")
        return
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_bytes(data)
    print(f"Wrote {OUTPUT.relative_to(MODULE)} ({len(data)} bytes)")


if __name__ == "__main__":
    main()
