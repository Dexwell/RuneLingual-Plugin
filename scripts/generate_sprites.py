#!/usr/bin/env python3
"""
Generate white character sprite PNGs for RuneLingual's multi-font system.

Each sprite is a single Unicode character rendered in white on a transparent
background. The Java plugin tints these to all needed colors at runtime.

Usage:
    python generate_sprites.py \
        --font-file /path/to/PixelFont.ttf \
        --font plain12 \
        --size 12 \
        --codepoints codepoints_ja.txt \
        --output ./char_ja/plain12/

    The codepoints file should contain one codepoint per line (decimal or hex):
        3042
        0x3043
        12354

    You can also generate for all fonts at once:
    python generate_sprites.py \
        --font-file /path/to/PixelFont.ttf \
        --font all \
        --size 12 \
        --codepoints codepoints_ja.txt \
        --output ./char_ja/
    This creates subdirectories: plain12/, bold12/, quill8/, etc.
"""

import argparse
import os
import sys

from PIL import Image, ImageDraw, ImageFont

# Known OSRS font names and their typical pixel sizes
KNOWN_FONTS = {
    "plain11": 11,
    "plain12": 12,
    "bold12": 12,
    "quill": 12,
    "quill8": 8,
}


def parse_codepoint_value(token):
    """Parse a single codepoint token (decimal or 0x hex) into an int."""
    token = token.strip()
    if token.startswith("0x") or token.startswith("0X"):
        return int(token, 16)
    return int(token)


def parse_codepoints(spec):
    """Parse codepoints from either an inline spec or a file.

    Inline spec supports ranges and comma-separated values:
        0x3040-0x309F,0x30A0-0x30FF
        12354,12355,12356
        0x3042

    A file should list one codepoint per line (decimal or 0x hex).
    """
    codepoints = []

    # If it looks like an inline range/list (contains 0x or - or , without
    # being a valid file path), parse it directly.
    if not os.path.isfile(spec):
        for part in spec.split(","):
            part = part.strip()
            if not part:
                continue
            if "-" in part:
                # Handle range like 0x3040-0x309F
                halves = part.split("-", 1)
                start = parse_codepoint_value(halves[0])
                end = parse_codepoint_value(halves[1])
                codepoints.extend(range(start, end + 1))
            else:
                codepoints.append(parse_codepoint_value(part))
        return sorted(set(codepoints))

    # Otherwise read from file
    with open(spec, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            if line.startswith("0x") or line.startswith("0X"):
                codepoints.append(int(line, 16))
            else:
                try:
                    codepoints.append(int(line))
                except ValueError:
                    # Treat as literal character(s)
                    for ch in line:
                        codepoints.append(ord(ch))
    return sorted(set(codepoints))


def render_sprite(codepoint, font, size):
    """
    Render a single character as white on transparent background.
    Returns a PIL Image cropped horizontally to the glyph width, but with
    fixed height so vertical positioning (baseline, middle marks) is preserved.
    Returns None if the character has no visible pixels.
    """
    char = chr(codepoint)

    # Render onto a generous canvas — render at 1:1 with no antialiasing
    # for crisp pixel fonts.
    canvas_size = size * 4
    img = Image.new("1", (canvas_size, canvas_size), 0)  # 1-bit mode: no antialiasing
    draw = ImageDraw.Draw(img)

    # Draw at a fixed Y origin so all characters share the same vertical frame
    x_origin = size
    y_origin = size
    draw.text((x_origin, y_origin), char, font=font, fill=1)

    # Convert to RGBA with white pixels
    img = img.convert("RGBA")
    pixels = img.load()
    for y in range(img.height):
        for x in range(img.width):
            r, g, b, a = pixels[x, y]
            if r == 255 and g == 255 and b == 255:
                pixels[x, y] = (255, 255, 255, 255)
            else:
                pixels[x, y] = (0, 0, 0, 0)

    # Get bounding box of non-transparent pixels
    bbox = img.getbbox()
    if bbox is None:
        return None  # nothing visible

    # Use fixed dimensions based on font metrics so all characters have
    # the same size and alignment is preserved (e.g. ー stays centered)
    ref_bbox = font.getbbox("国")  # full-height reference character
    font_left = x_origin + ref_bbox[0]
    font_top = y_origin + ref_bbox[1]
    font_right = x_origin + ref_bbox[2]
    font_bottom = y_origin + ref_bbox[3]
    cell_width = font_right - font_left
    cell_height = font_bottom - font_top

    # All sprites get the same cell size: fixed width and height
    cropped = img.crop((font_left, font_top, font_left + cell_width, font_top + cell_height))
    return cropped


def generate_sprites(font_file, font_name, pixel_size, codepoints, output_dir):
    """Generate white sprites for all codepoints and save to output_dir."""
    os.makedirs(output_dir, exist_ok=True)

    try:
        font = ImageFont.truetype(font_file, pixel_size)
    except Exception as e:
        print(f"Error loading font '{font_file}' at size {pixel_size}: {e}", file=sys.stderr)
        sys.exit(1)

    generated = 0
    skipped = 0

    for cp in codepoints:
        sprite = render_sprite(cp, font, pixel_size)
        if sprite is None:
            skipped += 1
            continue

        filename = f"{cp}.png"
        sprite.save(os.path.join(output_dir, filename))
        generated += 1

    print(f"[{font_name}] Generated {generated} sprites, skipped {skipped} "
          f"(empty/invisible) → {output_dir}")


def main():
    parser = argparse.ArgumentParser(
        description="Generate white character sprite PNGs for RuneLingual multi-font system."
    )
    parser.add_argument(
        "--font-file", required=True,
        help="Path to the .ttf or .otf font file to use for rendering"
    )
    parser.add_argument(
        "--font", required=True,
        help="Font name (plain12, bold12, quill8, etc.) or 'all' to generate all known fonts"
    )
    parser.add_argument(
        "--size", type=int, default=None,
        help="Pixel size for rendering. If not specified, uses the default for the font name."
    )
    parser.add_argument(
        "--codepoints", required=True,
        help="Inline ranges (e.g. 0x3040-0x309F,0x30A0-0x30FF) or path to a file "
             "listing Unicode codepoints (one per line, decimal or 0xHex)"
    )
    parser.add_argument(
        "--output", required=True,
        help="Output directory. For single font: sprites go here directly. "
             "For 'all': subdirectories are created per font."
    )

    args = parser.parse_args()
    codepoints = parse_codepoints(args.codepoints)

    if not codepoints:
        print("No codepoints found in the codepoints file.", file=sys.stderr)
        sys.exit(1)

    print(f"Loaded {len(codepoints)} unique codepoints.")

    if args.font == "all":
        for font_name, default_size in KNOWN_FONTS.items():
            size = args.size if args.size else default_size
            out_dir = os.path.join(args.output, font_name)
            generate_sprites(args.font_file, font_name, size, codepoints, out_dir)
    else:
        font_name = args.font
        if args.size:
            size = args.size
        elif font_name in KNOWN_FONTS:
            size = KNOWN_FONTS[font_name]
        else:
            print(f"Unknown font '{font_name}' and no --size specified. "
                  f"Known fonts: {', '.join(KNOWN_FONTS.keys())}", file=sys.stderr)
            sys.exit(1)

        generate_sprites(args.font_file, font_name, size, codepoints, args.output)

    print("Done!")


if __name__ == "__main__":
    main()
