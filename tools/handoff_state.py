"""Pack or restore the November 2026 disposable QA state. Python standard library only."""

import argparse
import hashlib
import json
import os
import re
import shutil
import socket
from pathlib import Path, PureWindowsPath
from zipfile import ZIP_DEFLATED, ZipFile


PROJECT = Path(__file__).resolve().parents[1]
MANIFEST = "SHA256.json"
WORLDS = (
    "qualification/server/World",
    "qualification/server/CandidateWorld",
    "qualification/server/CandidateWorld2",
    "qualification/crash-server/CrashTrial",
    "qualification/luckified-minimal/OptionalLuckWorld",
)
PINS = {
    "product": ("qualification/server/mods/Lootr4RLCraft-0.2.0-dev.jar",
                "f67c7b9b2acddaef5420fb74fa92ffbb4dba54bd02ebc2cc6f4c9d845ccb1b0a"),
    "server": ("qualification/server/mods/LootrServerProbe-0.2.0-dev.jar",
               "f6ec2a475dba366457ca6694f3403fd1b60f2a63b8d90ee1f7f487fdef390843"),
    "client": ("qa-client/probe/LootrRenderProbe-0.2.0-dev.jar",
               "5f36bff6a7a0b114823fc30ef7db74bba5aad890c3ccad5aae79f73ebc9c79bc"),
    "server-optional": ("qualification/luckified-minimal/mods/LootrServerProbe-0.2.0-dev.jar",
                        "f1ed1b1d2b5378dd7f3313a0dceb5af9f2e62183f09c6f9a92f5a9d11da109f0"),
}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def save_files(folder):
    if not (folder / "level.dat").is_file():
        raise ValueError(f"Missing test world {folder}")
    for path in folder.rglob("*"):
        if path.is_file() and path.name != "session.lock":
            if path.suffix.lower() not in {".dat", ".dat_old", ".mca", ".json", ".tmp", ".txt", ".nbt"}:
                raise ValueError(f"Inspect unexpected world content before transfer: {path.name}")
            yield path


def create(archive, history):
    build = PROJECT / "build"
    for port in (25583, 25589, 25619):
        with socket.socket() as connection:
            connection.settimeout(1)
            if connection.connect_ex(("127.0.0.1", port)) == 0:
                raise ValueError(f"Save and stop the disposable server on {port} first")
    files = {}
    for world in WORLDS:
        for path in save_files(build / world):
            files[path.relative_to(PROJECT).as_posix()] = path
    for client in (build / "qa-client").glob("run-*"):
        for world in (client / "saves").glob("render-probe-*"):
            for path in save_files(world):
                files[path.relative_to(PROJECT).as_posix()] = path
        for path in (client / "screenshots").glob("*.png"):
            files[path.relative_to(PROJECT).as_posix()] = path
        for path in client.glob("artifacts.txt"):
            files[path.relative_to(PROJECT).as_posix()] = path
        for name in ("Lootr4RLCraft-0.2.0-dev.jar", "LootrRenderProbe-0.2.0-dev.jar"):
            path = client / "mods" / name
            if path.is_file():
                files[f"build/handoff-pinned/historical/{client.name}/{name}"] = path
    for path in (build / "qa-client/synchronized-clients").glob("Render*-*.txt"):
        files[path.relative_to(PROJECT).as_posix()] = path
    for role, (source, expected) in PINS.items():
        path = build / source
        if digest(path.read_bytes()) != expected:
            raise ValueError(f"The historical {role} JAR does not match its evidence")
        files[f"build/handoff-pinned/{role}/{path.name}"] = path
    stock = build / "qualification/server"
    settings = stock / "server.properties"
    properties = settings.read_text().splitlines()
    identities = json.loads((stock / "ops.json").read_text())
    if ("server-ip=127.0.0.1" not in properties or "enable-rcon=false" not in properties
            or any("password=" in line.lower() and line.split("=", 1)[1] for line in properties)
            or {p["name"] for p in identities} != {"RenderOne", "RenderTwo", "RenderThree"}):
        raise ValueError("This snapshot requires the isolated loopback test fixture")
    for path in (settings, stock / "ops.json", stock / "config/lootr4rlcraft.cfg"):
        files[f"build/handoff-pinned/stock-settings/{path.name}"] = path
    for path in (build / "qualification").glob("*.log"):
        files[f"build/handoff-pinned/diagnostics/{path.name}"] = path
    for profile in ("server", "luckified-server", "luckified-minimal", "crash-server"):
        path = build / "qualification" / profile / "logs/latest.log"
        if path.is_file():
            files[f"build/handoff-pinned/diagnostics/{profile}-latest.log"] = path
    crash_log = build / "qualification/crash-server/crash-before.log"
    files[crash_log.relative_to(PROJECT).as_posix()] = crash_log
    for client in (build / "qa-client").glob("run-*"):
        log = client / "logs/latest.log"
        if log.is_file():
            files[f"build/handoff-pinned/diagnostics/{client.name}-latest.log"] = log
    if history:
        files["build/handoff-pinned/context/previous-development-log.txt"] = history
    hashes = {}
    with ZipFile(archive, "x", compression=ZIP_DEFLATED, compresslevel=6) as target:
        for name, source in sorted(files.items()):
            if source.is_symlink():
                raise ValueError(f"Unexpected symbolic link: {source}")
            data = source.read_bytes()
            if name.endswith((".log", "previous-development-log.txt")):
                lines = data.decode("utf-8-sig", errors="replace").splitlines()
                home = str(Path.home())
                # Only synthetic QA identities are used. Omit launcher argument/credential lines.
                lines = [line for line in lines if not re.search(
                    r"access.?token|client.?token|session.?id|bearer |password|api[_-]?key|--userProperties",
                    line, re.IGNORECASE)]
                data = ("\n".join(line.replace(home, "%USERPROFILE%").replace(home.replace(os.sep, "/"),
                                  "%USERPROFILE%") for line in lines) + "\n").encode("utf-8")
            if name.endswith(".jar"):
                # Only original project binaries, including test mods, belong in this transfer.
                with ZipFile(source) as jar:
                    for entry in jar.namelist():
                        if entry.endswith(".class") and not entry.startswith("com/mechgaming/lootr4rlcraft/"):
                            raise ValueError(f"Unexpected third-party class in {source.name}")
            target.writestr(name, data)
            hashes[name] = digest(data)
        target.writestr(MANIFEST, json.dumps(hashes, indent=2, sort_keys=True))
    return len(hashes)


def verify_or_restore(archive, destination):
    with ZipFile(archive) as source:
        hashes = json.loads(source.read(MANIFEST))
        names = source.namelist()
        if len(names) != len(set(n.casefold() for n in names)) or set(names) != set(hashes) | {MANIFEST}:
            raise ValueError("Archive manifest differs from its members")
        root = destination.resolve() if destination else None
        for name, expected in hashes.items():
            path = PureWindowsPath(name)
            if (not name.startswith("build/") or path.is_absolute() or "\\" in name or ":" in name
                    or any(part in ("", ".", "..") or part.endswith((" ", ".")) or PureWindowsPath(part).is_reserved()
                           for part in name.split("/"))):
                raise ValueError(f"Invalid transfer path: {name}")
            if digest(source.read(name)) != expected:
                raise ValueError(f"SHA-256 verification failed: {name}")
            if root:
                output = (root / name).resolve()
                if root not in output.parents or output.exists():
                    raise ValueError(f"Choose a new restore directory; target already exists or escapes it: {output}")
        if root:
            for name in hashes:
                output = root / name
                output.parent.mkdir(parents=True, exist_ok=True)
                with source.open(name) as incoming, output.open("xb") as outgoing:
                    shutil.copyfileobj(incoming, outgoing)
        return len(hashes)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["create", "verify", "restore"])
    parser.add_argument("archive", type=Path)
    parser.add_argument("--destination", type=Path, help="New clone root; defaults to the checkout containing this tool")
    parser.add_argument("--prior-log", type=Path, help="Optional owner's historical notes, for private transfer only")
    args = parser.parse_args()
    if args.action == "create":
        count = create(args.archive, args.prior_log)
    else:
        dest = args.destination or PROJECT
        count = verify_or_restore(args.archive, dest if args.action == "restore" else None)
    print(f"{args.action}: {count} files; ZIP SHA-256 {digest(args.archive.read_bytes())}")


if __name__ == "__main__":
    main()
