# Art

How art gets into the game. The same layout works for any of our games.

```
art/                                      source files (.aseprite, .kra, .svg, …), never shipped
app/src/main/assets/sprites/
  sprites.json                            manifest: sprite key → PNG file, plus the target size
  tiles/  buildings/  icons/              the PNGs the game loads
tools/sprites/generate_placeholders.py    draws a placeholder for every listed sprite that has no file yet
```

## Keys

Code never names files. It asks for a key, `<category>.<name>`:

| Category     | Example              | Used for                                      |
|--------------|----------------------|-----------------------------------------------|
| `tile.`      | `tile.grass_1`       | Hex tiles; the hex fills the image's height   |
| `building.`  | `building.town_hall` | `building.` + the `HexBuilding` enum name in lower case |
| `icon.`      | `icon.attack`        | `icon.` + the `Stat` enum name in lower case  |

## Format

- PNG with transparency, square, `size` × `size` pixels (currently 128). Bigger is fine; it is scaled.
- Tiles: pointy-top hexagon from the top edge to the bottom edge, centred.
- Buildings: front view, standing near the bottom, with a little room around them. They are drawn
  on top of `tile.foundation`, so leave the foundation out.

## Replacing a sprite

Export over the existing PNG (same path). No code change is needed.

## Adding a sprite

1. Add the key and file to `sprites.json`.
2. Run `python3 tools/sprites/generate_placeholders.py` (needs Pillow). It only writes files that
   don't exist yet; keys without a drawing function get a magenta hex so they stand out in the game.
   `--check` lists missing files, `--force` redraws everything **and overwrites real art**.
3. Look the sprite up in code with `LocalSprites.current["your.key"]`, or `SpriteIcon(key, fallbackEmoji, size)`.
   If a sprite is missing at runtime, the UI shows the fallback emoji and the boards a plain shape.
