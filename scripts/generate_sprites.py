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


def parse_codepoints(filepath):
    """Read a codepoints file — one codepoint per line, decimal or 0x hex."""
    codepoints = []
    with open(filepath, "r", encoding="utf-8") as f:
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
    Returns a PIL Image cropped to the glyph bounding box, or None if the
    character has no visible pixels (e.g. control characters).
    """
    char = chr(codepoint)

    # Render onto a generous canvas first
    canvas_size = size * 4
    img = Image.new("RGBA", (canvas_size, canvas_size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    # Draw in white
    draw.text((size, size), char, font=font, fill=(255, 255, 255, 255))

    # Crop to bounding box of non-transparent pixels
    bbox = img.getbbox()
    if bbox is None:
        return None  # nothing visible

    cropped = img.crop(bbox)
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
        help="Path to a file listing Unicode codepoints (one per line, decimal or 0xHex)"
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
