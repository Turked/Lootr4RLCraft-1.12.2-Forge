"""Build per-fixture contact sheets from Families and VerifyFamilyRestart screenshots (Pillow >= 10.1)."""

import argparse
import csv
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


PLAYERS = ("RenderOne", "RenderTwo", "RenderThree")
FIXTURES = (
    "single", "double", "trapped", "quark-spruce", "quark-birch", "rustic-cabinet", "rustic-barrel",
    "charm-barrel", "variedcommodities-barrel", "minecart", "charm-crate", "charm-bookshelf",
    "locked-single", "double-cabinet",
)
FAMILIES_STAGES = ("unopened", "opened", "chunk-reload", "dimension-return", "reconnect")
# Match the connected launcher's framebuffer so normal captures retain their native pixels and HUD.
FRAME_SIZE = (960, 620)


def screenshot_dirs(profiles):
    return [(path if path.name == "screenshots" else path / "screenshots").resolve() for path in profiles]


def render_sheet(output, fixture, stages, font, index):
    width, height = FRAME_SIZE
    header = 46
    gap = 4
    row_height = header + height + gap
    sheet = Image.new("RGB", (len(PLAYERS) * (width + gap) - gap, len(stages) * row_height - gap), "#202328")
    draw = ImageDraw.Draw(sheet)
    missing = 0
    for row, (stage, dirs) in enumerate(stages):
        for column, (player, directory) in enumerate(zip(PLAYERS, dirs)):
            x, y = column * (width + gap), row * row_height
            draw.text((x + 10, y + 7), f"{fixture}   /   {stage}   /   {player}", font=font, fill="white")
            path = directory / f"{player}-{fixture}-{stage}.png"
            try:
                with Image.open(path) as frame:
                    frame.thumbnail(FRAME_SIZE, Image.Resampling.LANCZOS)
                    sheet.paste(frame.convert("RGB"), (x + (width - frame.width) // 2, y + header + (height - frame.height) // 2))
                status = "present"
            except FileNotFoundError:
                status = "missing"
            except OSError as error:
                status = f"unreadable ({error})"
            index.writerow((fixture, stage, player, status, path))
            if status != "present":
                missing += 1
                draw.text((x + 10, y + header + 10), f"{player} frame {status.split(' (')[0]}", font=font, fill="#ffb099")
    sheet.save(output / f"{fixture}.png", compress_level=2)
    return missing


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for option, phase in (("--families", "Families"), ("--restart", "VerifyFamilyRestart")):
        parser.add_argument(option, nargs=3, type=Path, metavar=PLAYERS,
                            help=f"{phase} client profile directories in player order (or their screenshots directories)")
    parser.add_argument("--output", required=True, type=Path, help="directory for derived PNG sheets and frames.csv")
    args = parser.parse_args()
    if not args.families and not args.restart:
        parser.error("supply --families, --restart, or both")
    for path in (args.families or []) + (args.restart or []):
        if not path.is_dir():
            parser.error(f"client directory does not exist: {path}")
    stages = []
    if args.families:
        dirs = screenshot_dirs(args.families)
        stages.extend((stage, dirs) for stage in FAMILIES_STAGES)
    if args.restart:
        stages.append(("server-restart", screenshot_dirs(args.restart)))
    args.output.mkdir(parents=True, exist_ok=True)
    font = ImageFont.load_default(size=24)
    index_path = args.output / "frames.csv"
    with index_path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(("fixture", "stage", "player", "status", "original_path"))
        missing = sum(render_sheet(args.output, fixture, stages, font, writer) for fixture in FIXTURES)
    expected = len(PLAYERS) * len(FIXTURES) * len(stages)
    print(f"{expected - missing}/{expected} frames readable. {missing} missing or unreadable. Index: {index_path}")
    return int(missing > 0)


if __name__ == "__main__":
    raise SystemExit(main())
