#!/usr/bin/env python3
"""Draws simple placeholder sprites for every key in a sprites.json manifest.

Only missing files are written, so real art dropped into the sprites folder is never touched.
Use --force to redraw everything (this overwrites real art too), --check to only list what's missing.

    python3 tools/sprites/generate_placeholders.py [--force | --check] [manifest]

Needs Pillow (pip install pillow). Coordinates below are in sprite pixels (0..size); drawing happens
at SUPERSAMPLE times the size and is scaled down for smooth edges.
"""
import argparse
import json
import math
import random
import sys
from pathlib import Path

from PIL import Image, ImageDraw

DEFAULT_MANIFEST = Path(__file__).resolve().parents[2] / "app/src/main/assets/sprites/sprites.json"
SUPERSAMPLE = 4
INK = (60, 45, 35, 255)


class Pen:
    """ImageDraw wrapper working in sprite pixels, with a dark outline by default."""

    def __init__(self, size):
        self.size = size
        self.k = SUPERSAMPLE
        self.img = Image.new("RGBA", (size * self.k, size * self.k), (0, 0, 0, 0))
        self.d = ImageDraw.Draw(self.img)

    def _p(self, pts):
        return [(x * self.k, y * self.k) for x, y in pts]

    def _w(self, w):
        return max(1, round(w * self.k))

    def poly(self, pts, fill, outline=INK, width=2.5):
        self.d.polygon(self._p(pts), fill=fill, outline=outline, width=self._w(width) if outline else 0)

    def rect(self, x0, y0, x1, y1, fill, outline=INK, width=2.5, radius=0):
        box = [x0 * self.k, y0 * self.k, x1 * self.k, y1 * self.k]
        self.d.rounded_rectangle(box, radius * self.k, fill=fill, outline=outline, width=self._w(width) if outline else 0)

    def ellipse(self, x0, y0, x1, y1, fill, outline=INK, width=2.5):
        self.d.ellipse([x0 * self.k, y0 * self.k, x1 * self.k, y1 * self.k], fill=fill, outline=outline,
                       width=self._w(width) if outline else 0)

    def line(self, pts, fill=INK, width=2.5):
        self.d.line(self._p(pts), fill=fill, width=self._w(width), joint="curve")

    def pieslice(self, x0, y0, x1, y1, start, end, fill, outline=INK, width=2.5):
        self.d.pieslice([x0 * self.k, y0 * self.k, x1 * self.k, y1 * self.k], start, end, fill=fill,
                        outline=outline, width=self._w(width) if outline else 0)

    def shadow(self, cx, cy, rx, ry):
        self.ellipse(cx - rx, cy - ry, cx + rx, cy + ry, (0, 0, 0, 60), outline=None)

    def result(self):
        return self.img.resize((self.size, self.size), Image.LANCZOS)


def hex_points(s, scale=1.0):
    """Pointy-top hexagon filling the sprite's height, like the board draws its tiles."""
    c, r = s / 2, s / 2 * scale
    return [(c + r * math.cos(math.radians(60 * i - 30)), c + r * math.sin(math.radians(60 * i - 30))) for i in range(6)]


# --- Tiles ------------------------------------------------------------------------------------

def grass(variant):
    base = [(156, 204, 101), (148, 196, 94), (163, 209, 108)][variant - 1]

    def draw(p):
        s = p.size
        p.poly(hex_points(s), base + (255,), outline=None)
        rng = random.Random(variant)
        dark = tuple(max(0, v - 28) for v in base) + (255,)
        for _ in range(14):
            x, y = rng.uniform(0.25, 0.75) * s, rng.uniform(0.15, 0.85) * s
            p.line([(x, y), (x - 1.5, y - 5)], fill=dark, width=1.5)
            p.line([(x + 2.5, y), (x + 3.5, y - 4)], fill=dark, width=1.5)
    return draw


def foundation(p):
    p.poly(hex_points(p.size), (234, 223, 200, 255), outline=(190, 170, 140, 255), width=3)


def fog(p):
    s = p.size
    p.poly(hex_points(s), (84, 96, 112, 255), outline=None)
    for cx, cy, r in [(0.38, 0.42, 0.14), (0.55, 0.38, 0.17), (0.68, 0.5, 0.12), (0.45, 0.62, 0.13), (0.6, 0.64, 0.11)]:
        p.ellipse((cx - r) * s, (cy - r) * s, (cx + r) * s, (cy + r) * s, (120, 132, 148, 255), outline=None)


def hit(p):
    s = p.size
    c = s / 2
    flame = [(c, 18), (c + 22, 58), (c + 30, 88), (c + 14, 108), (c - 14, 108), (c - 30, 88), (c - 22, 58)]
    p.poly(flame, (229, 57, 53, 255))
    p.poly([(c, 50), (c + 14, 82), (c + 8, 100), (c - 8, 100), (c - 14, 82)], (255, 179, 0, 255), outline=None)


def miss(p):
    s = p.size
    c = s / 2
    p.ellipse(c - 30, c - 30, c + 30, c + 30, None, outline=(255, 255, 255, 230), width=5)
    p.ellipse(c - 12, c - 12, c + 12, c + 12, None, outline=(255, 255, 255, 230), width=4)


def unknown(p):
    """Something is built here, but not what."""
    s = p.size
    c = s / 2
    p.shadow(c, 92, 30, 7)
    p.poly([(c - 28, 88), (c - 28, 54), (c, 36), (c + 28, 54), (c + 28, 88)], (66, 66, 66, 230), outline=(30, 30, 30, 255))


def crack(p):
    dark = (40, 30, 25, 255)
    p.line([(40, 30), (54, 52), (46, 66), (62, 84), (58, 102)], fill=dark, width=4)
    p.line([(54, 52), (74, 58), (86, 46)], fill=dark, width=3)
    p.line([(62, 84), (80, 90)], fill=dark, width=3)


# --- Buildings --------------------------------------------------------------------------------
# Drawn front-on inside roughly 16..112, standing on a shadow near the bottom.

def town_hall(p):
    p.shadow(64, 104, 46, 10)
    p.rect(24, 58, 104, 102, (236, 228, 210, 255))
    p.poly([(16, 60), (64, 26), (112, 60)], (120, 144, 156, 255))
    for x in (34, 52, 70, 88):
        p.rect(x, 64, x + 8, 100, (250, 245, 235, 255), width=2)
    p.line([(64, 26), (64, 8)], width=2.5)
    p.poly([(64, 8), (84, 13), (64, 18)], (229, 57, 53, 255), width=2)


def house(p):
    p.shadow(64, 102, 38, 9)
    p.rect(32, 58, 96, 100, (255, 224, 178, 255))
    p.poly([(24, 62), (64, 26), (104, 62)], (198, 40, 40, 255))
    p.rect(56, 74, 72, 100, (121, 85, 72, 255), width=2)
    p.rect(78, 68, 90, 80, (144, 202, 249, 255), width=2)


def farm(p):
    p.shadow(64, 100, 48, 10)
    p.poly([(14, 98), (36, 48), (92, 48), (114, 98)], (141, 110, 99, 255))
    for i in range(5):
        t = (i + 0.5) / 5
        x0, x1 = 36 + (14 - 36) * t, 92 + (114 - 92) * t
        y = 48 + 50 * t
        p.line([(x0 + 4, y), (x1 - 4, y)], fill=(124, 179, 66, 255), width=3 + 2 * t)
    p.rect(88, 30, 108, 52, (198, 40, 40, 255), width=2)
    p.poly([(85, 32), (98, 20), (111, 32)], (121, 85, 72, 255), width=2)


def wall(p):
    p.shadow(64, 100, 46, 9)
    top = 50
    p.rect(16, top, 112, 98, (176, 112, 92, 255))
    for x in range(16, 112, 24):
        p.rect(x, top - 12, x + 14, top, (176, 112, 92, 255))
    for row, y in enumerate(range(top + 12, 98, 12)):
        p.line([(18, y), (110, y)], fill=(120, 70, 55, 255), width=1.5)
        for x in range(28 + (row % 2) * 12, 110, 24):
            p.line([(x, y - 12), (x, y)], fill=(120, 70, 55, 255), width=1.5)


def tower(p):
    p.shadow(64, 108, 26, 7)
    p.rect(42, 34, 86, 106, (189, 189, 189, 255))
    for x in (38, 56, 74):
        p.rect(x, 20, x + 16, 36, (189, 189, 189, 255))
    p.rect(56, 54, 72, 74, (66, 66, 66, 255), radius=6)


def barracks(p):
    p.shadow(64, 102, 46, 9)
    p.rect(18, 60, 110, 100, (161, 136, 127, 255))
    p.poly([(12, 64), (30, 38), (98, 38), (116, 64)], (85, 139, 47, 255))
    for x in (30, 52, 74, 92):
        p.rect(x, 70, x + 8, 82, (66, 66, 66, 255), width=1.5)
    p.line([(64, 38), (64, 12)], width=2.5)
    p.poly([(64, 12), (86, 18), (64, 24)], (85, 139, 47, 255), width=2)


def market(p):
    p.shadow(64, 102, 44, 9)
    p.rect(26, 62, 102, 100, (255, 236, 179, 255))
    stripes = [(229, 57, 53, 255), (250, 250, 250, 255)]
    w = 76 / 6
    for i in range(6):
        x = 26 + i * w
        p.poly([(x, 40), (x + w, 40), (x + w + 2, 64), (x - 2, 64)], stripes[i % 2], width=1.5)
    for x, c in ((40, (255, 152, 0)), (60, (139, 195, 74)), (82, (229, 57, 53))):
        p.ellipse(x - 7, 70, x + 7, 84, c + (255,), width=2)


def artillery(p):
    p.shadow(64, 104, 44, 8)
    p.poly([(36, 72), (98, 36), (106, 50), (46, 86)], (84, 84, 84, 255))
    p.ellipse(28, 66, 64, 102, (141, 110, 99, 255), width=3)
    p.ellipse(42, 80, 50, 88, INK, outline=None)


def gate(p):
    red = (211, 47, 47, 255)
    p.shadow(64, 104, 46, 8)
    p.rect(30, 34, 40, 104, red)
    p.rect(88, 34, 98, 104, red)
    p.poly([(12, 22), (116, 22), (110, 34), (18, 34)], (66, 66, 66, 255))
    p.rect(24, 46, 104, 54, red)


def hangar(p):
    p.shadow(64, 86, 52, 8)
    p.pieslice(14, 34, 114, 134, 180, 360, (144, 164, 174, 255))
    p.line([(14, 84), (114, 84)], width=2.5)
    p.rect(44, 54, 84, 84, (69, 90, 100, 255), width=2)
    for x in (34, 94):
        p.line([(x, 50), (x, 84)], fill=(96, 125, 139, 255), width=1.5)


def temple(p):
    p.shadow(64, 104, 46, 9)
    gold = (255, 193, 7, 255)
    p.rect(18, 84, 110, 102, (239, 235, 233, 255))
    p.rect(30, 66, 98, 84, (239, 235, 233, 255))
    p.rect(44, 48, 84, 66, (239, 235, 233, 255))
    p.poly([(52, 48), (64, 16), (76, 48)], gold)
    p.rect(58, 88, 70, 102, (121, 85, 72, 255), width=2)


def monument(p):
    p.shadow(64, 106, 30, 8)
    p.rect(36, 92, 92, 106, (158, 158, 158, 255))
    p.poly([(52, 92), (56, 26), (64, 14), (72, 26), (76, 92)], (189, 189, 189, 255))
    p.line([(64, 30), (64, 86)], fill=(140, 140, 140, 255), width=2)


def quarry(p):
    p.shadow(64, 102, 46, 9)
    rock, light = (141, 141, 141, 255), (189, 189, 189, 255)
    p.poly([(14, 100), (26, 64), (52, 50), (70, 66), (64, 100)], rock)
    p.poly([(56, 100), (70, 66), (96, 56), (114, 80), (112, 100)], light)
    p.poly([(36, 64), (50, 56), (56, 70), (42, 76)], light, width=2)
    p.line([(78, 22), (98, 62)], fill=(121, 85, 72, 255), width=5)
    p.poly([(62, 30), (78, 18), (96, 20), (80, 30)], (120, 144, 156, 255), width=2)


# --- Icons ------------------------------------------------------------------------------------

def attack(p):
    steel, hilt = (207, 216, 220, 255), (121, 85, 72, 255)
    p.poly([(96, 16), (112, 16), (112, 32), (52, 92), (36, 76)], steel)
    p.poly([(26, 70), (34, 62), (66, 94), (58, 102)], (255, 193, 7, 255))
    p.poly([(36, 92), (44, 100), (28, 116), (20, 108)], hilt)


def defense(p):
    p.poly([(64, 12), (108, 28), (104, 72), (64, 116), (24, 72), (20, 28)], (30, 136, 229, 255), width=3)
    p.poly([(64, 26), (94, 37), (91, 69), (64, 100)], (100, 181, 246, 255), outline=None)


def recon(p):
    p.poly([(10, 64), (36, 36), (64, 28), (92, 36), (118, 64), (92, 92), (64, 100), (36, 92)],
           (250, 250, 250, 255), width=3)
    p.ellipse(42, 42, 86, 86, (0, 137, 123, 255), width=3)
    p.ellipse(54, 54, 74, 74, INK, outline=None)
    p.ellipse(66, 52, 74, 60, (255, 255, 255, 255), outline=None)


def materials(p):
    wood, stone = (161, 110, 70, 255), (158, 158, 158, 255)
    p.rect(14, 76, 84, 96, wood, radius=8)
    p.rect(24, 54, 94, 74, wood, radius=8)
    for y in (66, 86):
        p.ellipse(74 if y == 86 else 84, y - 10, 94 if y == 86 else 104, y + 10, (215, 168, 120, 255), width=2)
    p.poly([(70, 104), (78, 84), (100, 78), (116, 92), (110, 110)], stone)


def food(p):
    p.ellipse(16, 40, 112, 104, (230, 160, 70, 255), width=3)
    for x in (44, 64, 84):
        p.line([(x - 6, 58), (x + 6, 70)], fill=(176, 110, 40, 255), width=3)


DRAWERS = {
    "tile.grass_1": grass(1), "tile.grass_2": grass(2), "tile.grass_3": grass(3),
    "tile.foundation": foundation, "tile.fog": fog, "tile.hit": hit, "tile.miss": miss,
    "tile.unknown": unknown, "tile.crack": crack, "building.quarry": quarry,
    "building.town_hall": town_hall, "building.house": house, "building.farm": farm, "building.wall": wall,
    "building.tower": tower, "building.barracks": barracks, "building.market": market,
    "building.artillery": artillery, "building.gate": gate, "building.hangar": hangar,
    "building.temple": temple, "building.monument": monument,
    "icon.attack": attack, "icon.defense": defense, "icon.recon": recon, "icon.materials": materials, "icon.food": food,
}


def missing(p):
    """Magenta hex for keys without a drawer, so missing art is obvious in the game."""
    p.poly(hex_points(p.size, 0.8), (233, 30, 99, 255))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("manifest", nargs="?", type=Path, default=DEFAULT_MANIFEST)
    mode = ap.add_mutually_exclusive_group()
    mode.add_argument("--force", action="store_true", help="redraw every sprite, overwriting existing files")
    mode.add_argument("--check", action="store_true", help="only list missing files; exit 1 if any")
    args = ap.parse_args()

    manifest = json.loads(args.manifest.read_text())
    size = manifest["size"]
    root = args.manifest.parent
    missing = []
    for key, file in manifest["sprites"].items():
        path = root / file
        if path.exists() and not args.force:
            continue
        missing.append(key)
        if args.check:
            print(f"missing  {key}  ({file})")
            continue
        pen = Pen(size)
        DRAWERS.get(key, missing)(pen)
        path.parent.mkdir(parents=True, exist_ok=True)
        pen.result().save(path, optimize=True)
        print(f"drew     {key}  -> {file}" + ("" if key in DRAWERS else "  (no drawer: magenta placeholder)"))
    if args.check and missing:
        sys.exit(1)
    if not missing:
        print("all sprites present")


if __name__ == "__main__":
    main()
