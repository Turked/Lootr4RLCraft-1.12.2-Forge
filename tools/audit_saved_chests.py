"""Read chest markers and Locks phases from a stopped 1.12.2 dimension's region directory."""

import argparse
import collections
import gzip
import io
import struct
import zlib
from pathlib import Path


def read_nbt(data):
    source = io.BytesIO(data)

    def unpack(fmt):
        return struct.unpack(">" + fmt, source.read(struct.calcsize(">" + fmt)))[0]

    def string():
        return source.read(unpack("H")).decode("utf-8", "replace")

    def payload(kind):
        if 1 <= kind <= 6:
            return unpack("bhiqfd"[kind - 1])
        if kind == 8:
            return string()
        if kind == 9:
            element = unpack("B")
            return [payload(element) for _ in range(unpack("i"))]
        if kind == 10:
            compound = {}
            while True:
                child = unpack("B")
                if child == 0:
                    return compound
                name = string()
                compound[name] = payload(child)
        if kind in (7, 11, 12):
            source.seek(unpack("i") * {7: 1, 11: 4, 12: 8}[kind], io.SEEK_CUR)
            return None
        raise ValueError(f"Unexpected NBT type {kind}")

    if unpack("B") != 10:
        raise ValueError("Expected an NBT root compound")
    string()
    return payload(10)


def chunks(path):
    for region in sorted(path.glob("r.*.*.mca")):
        with region.open("rb") as source:
            offsets = source.read(4096)
            for entry in range(0, 4096, 4):
                offset = int.from_bytes(offsets[entry:entry + 3], "big") * 4096
                if offset == 0:
                    continue
                source.seek(offset)
                length, codec = struct.unpack(">IB", source.read(5))
                decompress = {1: gzip.decompress, 2: zlib.decompress}[codec]
                yield read_nbt(decompress(source.read(length - 1)))["Level"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("region", type=Path)
    parser.add_argument("--bounds", type=int, nargs=4, metavar=("MIN_X", "MIN_Z", "MAX_X", "MAX_Z"))
    args = parser.parse_args()
    if not args.region.is_dir():
        parser.error("Select the dimension's existing region directory")
    counts = collections.Counter()
    phases = collections.Counter()
    total = 0
    for chunk in chunks(args.region):
        total += 1
        caps = chunk.get("ForgeCaps", {})
        phase = caps.get("locks:lockable_worldgen_handler", "absent")
        for tile in chunk.get("TileEntities", []):
            kind = tile["id"]
            tags = tile.get("ForgeData", {})
            if not any(word in kind for word in ("chest", "barrel", "cabinet")):
                continue
            if kind == "lootr4rlcraft:structure_chest" or "lootr4rlcraft:container" in tags:
                status = "managed marker"
            elif "lootr4rlcraft:pending_registration" in tags:
                status = "pending marker"
                phases[phase] += 1
            elif tile.get("LootTable") or tile.get("lootTable"):
                status = "retained table"
            elif tile.get("Items"):
                status = "physical Items list"
            else:
                status = "no marker, table or nonempty Items list"
            counts[kind, status] += 1
            if args.bounds:
                x1, z1, x2, z2 = args.bounds
                x, y, z = (tile[key] for key in "xyz")
                if x1 <= x <= x2 and z1 <= z <= z2:
                    print(f"{x} {y} {z}  {kind}  {status}  Locks phase {phase}")
    print(f"{total} saved chunks")
    for (kind, status), count in sorted(counts.items()):
        print(f"{count:6}  {kind}  {status}")
    for phase, count in phases.items():
        print(f"{count:6}  pending tile(s) in Locks phase {phase}")


if __name__ == "__main__":
    main()
