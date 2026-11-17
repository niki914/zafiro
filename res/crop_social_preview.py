#!/usr/bin/env python3
"""
Crop and resize horizontal images to 2:1 aspect ratio (recommended 1280x640 for GitHub social preview).

Logic:
1. Confirmed horizontal image (w >= h).
2. Uses the shorter side (height h) to deduce target width (target_w = 2 * h):
   - If w > 2 * h: image is wider than 2:1 -> crop excess width (left/right symmetrically).
   - If w < 2 * h: image is taller than 2:1 -> crop excess height (top/bottom symmetrically).
   - If w == 2 * h: already 2:1 -> no cropping needed.
3. If target size exceeds 1280x640 (or if --downscale is requested), downscales
   to 1280x640 (or custom target) to save bandwidth and meet GitHub's recommended display spec.
"""

import argparse
import sys
from pathlib import Path
from PIL import Image


def process_image(
    input_path: Path,
    output_path: Path = None,
    max_w: int = 1280,
    max_h: int = 640,
    downscale_first: bool = True,
    quality: int = 90,
) -> Path:
    if not input_path.exists():
        raise FileNotFoundError(f"Input file not found: {input_path}")

    if output_path is None:
        output_path = input_path.with_name(f"{input_path.stem}_github.jpeg")

    with Image.open(input_path) as im:
        # Convert RGBA / P to RGB if saving as JPEG
        if im.mode in ("RGBA", "LA", "P"):
            rgb_im = Image.new("RGB", im.size, (255, 255, 255))
            if im.mode == "P":
                im = im.convert("RGBA")
            rgb_im.paste(im, mask=im.split()[-1] if "A" in im.mode else None)
            im = rgb_im
        elif im.mode != "RGB":
            im = im.convert("RGB")

        w, h = im.size
        if w < h:
            print(f"Warning: Image is vertical ({w}x{h}), treating width as the short side.")

        print(f"Original size: {w}x{h}")

        # If user recommends downscaling before cropping:
        # We can scale down proportionally first so that after 2:1 crop it matches exactly max_w x max_h.
        if downscale_first and max_w and max_h:
            # We want the cropped result to be max_w x max_h (aspect ratio 2:1).
            # Determine whether width or height should be matched to cover (max_w, max_h)
            # scale = max(max_w / w, max_h / h)
            # If scaling up, we only do it if the image is smaller than target
            scale = min(max_w / min(w, 2 * h), max_h / h)
            if scale < 1.0:
                # Downscale first while maintaining aspect ratio
                new_w = int(round(w * scale))
                new_h = int(round(h * scale))
                # Ensure it at least covers max_w x max_h
                if new_w < max_w:
                    new_w = max_w
                    new_h = int(round(h * (max_w / w)))
                if new_h < max_h:
                    new_h = max_h
                    new_w = int(round(w * (max_h / h)))

                print(f"Downscaled first to: {new_w}x{new_h}")
                im = im.resize((new_w, new_h), Image.Resampling.LANCZOS)
                w, h = im.size

        # Deduce target width based on short side (height h)
        ideal_w = 2 * h

        if w > ideal_w:
            # Too wide: crop horizontal sides symmetrically
            crop_w = ideal_w
            crop_h = h
            left = (w - crop_w) // 2
            top = 0
            right = left + crop_w
            bottom = h
            print(f"Image is wider than 2:1 ({w} > {ideal_w}). Cropping width: [{left}, {top}, {right}, {bottom}]")
            im = im.crop((left, top, right, bottom))
        elif w < ideal_w:
            # Too tall: crop vertical sides symmetrically (target height = w // 2)
            crop_w = w
            crop_h = w // 2
            left = 0
            top = (h - crop_h) // 2
            right = w
            bottom = top + crop_h
            print(f"Image is taller than 2:1 ({w} < {ideal_w}). Cropping height: [{left}, {top}, {right}, {bottom}]")
            im = im.crop((left, top, right, bottom))
        else:
            print("Image is already 2:1 aspect ratio. No crop needed.")

        # If not downscaled first or if cropped size is larger than max_w x max_h, scale to max_w x max_h
        if max_w and max_h and (im.width > max_w or im.height > max_h):
            print(f"Scaling final output to {max_w}x{max_h}")
            im = im.resize((max_w, max_h), Image.Resampling.LANCZOS)

        output_path.parent.mkdir(parents=True, exist_ok=True)
        im.save(output_path, format="JPEG", quality=quality, optimize=True)
        print(f"Processed successfully! Saved to: {output_path} ({im.size[0]}x{im.size[1]})")

    return output_path


def main():
    parser = argparse.ArgumentParser(
        description="Crop/resize horizontal image to 2:1 for GitHub social preview."
    )
    parser.add_argument(
        "input",
        nargs="?",
        default="./brand/zafiro_social_preview.jpeg",
        help="Path to the input image (default: ./brand/zafiro_social_preview.jpeg)",
    )
    parser.add_argument(
        "-o",
        "--output",
        help="Path to output file (default: <input_stem>_github.jpeg in same directory)",
    )
    parser.add_argument(
        "--width",
        type=int,
        default=1280,
        help="Target width (default: 1280)",
    )
    parser.add_argument(
        "--height",
        type=int,
        default=640,
        help="Target height (default: 640)",
    )
    parser.add_argument(
        "--quality",
        type=int,
        default=90,
        help="JPEG quality (default: 90)",
    )
    parser.add_argument(
        "--no-downscale-first",
        action="store_true",
        help="Crop first at full resolution, then downscale",
    )

    args = parser.parse_args()

    input_path = Path(args.input)
    if not input_path.is_absolute():
        # resolve relative to current working dir
        input_path = Path.cwd() / input_path

    out_path = Path(args.output) if args.output else None
    if out_path and not out_path.is_absolute():
        out_path = Path.cwd() / out_path

    try:
        process_image(
            input_path=input_path,
            output_path=out_path,
            max_w=args.width,
            max_h=args.height,
            downscale_first=not args.no_downscale_first,
            quality=args.quality,
        )
    except Exception as e:
        print(f"Error: {e}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
