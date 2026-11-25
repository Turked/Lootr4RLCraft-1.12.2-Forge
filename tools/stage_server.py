"""Prepare a fresh, loopback-only qualification server from independently obtained local inputs."""

import argparse
import hashlib
import json
import shutil
import tempfile
import zipfile
from pathlib import Path, PurePosixPath


PACK_SHA = "a29b3c0c99b41e1c7d404a78f48b7f9698f15b068141a042d5ca1108a8636c55"
FORGE_SHA = "cd3fbf85d7ca744507fd6a37a41b90122d43e616a4f8962332b1b658655e8a64"
MC_SHA = "fe1f9274e6dad9191bf6e6e8e36ee6ebc737f373603df0946aafcded0d53167e"
LAYOUTS = {"stock": ("CandidateWorld2", 25583), "crash": ("CrashTrial", 25619),
           "luckified": ("OptionalLuckWorld", 25589), "minimal-luckified": ("OptionalLuckWorld", 25589)}
MINIMAL_FILES = {"server.properties", "mods/Baubles-1.12-1.5.2.jar", "mods/RLArtifacts-1.1.2.jar"}


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def extract_pack(archive, stage, minimal):
    with zipfile.ZipFile(archive) as source:
        if minimal and not MINIMAL_FILES.issubset(source.namelist()):
            raise ValueError("Minimal diagnostic inputs missing from the server pack")
        for entry in source.infolist():
            if entry.is_dir() or (minimal and entry.filename not in MINIMAL_FILES):
                continue
            member = PurePosixPath(entry.filename)
            if member.is_absolute() or ".." in member.parts or "\\" in entry.filename or ":" in entry.filename:
                raise ValueError(f"Unexpected archive path {entry.filename}")
            destination = stage.joinpath(*member.parts)
            destination.parent.mkdir(parents=True, exist_ok=True)
            with source.open(entry) as original, destination.open("xb") as output:
                shutil.copyfileobj(original, output)


def configure(stage, mode, port):
    world = LAYOUTS[mode][0]
    settings = {"level-name": world, "server-ip": "127.0.0.1", "server-port": str(port),
                "level-seed": "81327831", "online-mode": "false", "enable-rcon": "false", "enable-query": "false",
                "max-tick-time": "-1", "enable-command-block": "true"}
    if mode == "crash":
        settings["view-distance"] = "3"
    if mode == "minimal-luckified":
        settings.update({"level-type": "FLAT", "view-distance": "4"})
    filename = stage / "server.properties"
    lines = filename.read_text(encoding="latin-1").splitlines()
    retained = [line for line in lines if line.split("=", 1)[0] not in settings]
    filename.write_text("\n".join(retained + [f"{key}={val}" for key, val in settings.items()]) + "\n",
                        encoding="latin-1")
    return world


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("pack", "forge-home", "product", "probe", "destination"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--mode", choices=tuple(LAYOUTS), default="stock")
    parser.add_argument("--port", type=int, help="Override loopback server port")
    parser.add_argument("--addon", type=Path, action="append", default=[],
                        help="Optional isolated Luckified run only; supply its two independently obtained JARs")
    args = parser.parse_args()
    port = args.port if args.port is not None else LAYOUTS[args.mode][1]
    if not 1 <= port <= 65535:
        parser.error("Port must be between 1 and 65535")
    target = args.destination.resolve()
    if target.exists():
        parser.error("Choose a new destination; existing trials carry their own worlds and evidence")
    if digest(args.pack) != PACK_SHA:
        parser.error("Expected RLCraft 2.9.3 server pack file 4612990 (see qa/SERVER_SETUP.md)")
    # Normalize relative inputs before descending into Forge's deeply nested Maven layout on Windows.
    forge_home = args.forge_home.resolve(strict=True)
    forge = next((path for path in forge_home.glob("*.jar")
                 if path.stat().st_size == 4466148 and digest(path) == FORGE_SHA), None)
    minecraft = forge_home / "minecraft_server.1.12.2.jar"
    libraries = forge_home / "libraries"
    if not forge or not libraries.is_dir() or not minecraft.is_file() or digest(minecraft) != MC_SHA:
        parser.error("Choose a completed Forge 14.23.5.2860 server installation with Minecraft 1.12.2 and libraries")
    additions = [args.product, args.probe] + args.addon
    if len({p.name.lower() for p in additions}) < len(additions) or any(not p.is_file() for p in additions):
        parser.error("Provide existing JARs with distinct filenames")
    if args.mode.endswith("luckified"):
        if {p.name.lower() for p in args.addon} != {"rlcraftluckified-1.1.3.3.jar", "00fermiumbooter-1.6.5.jar"}:
            parser.error("The optional diagnostic targets RLCraftLuckified-1.1.3.3 and 00FermiumBooter-1.6.5")
    elif args.addon:
        parser.error("Additional mods belong in a separate optional layout")
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=target.parent, prefix=".stage-qa-") as temp:
        stage = Path(temp) / "server"
        stage.mkdir()
        extract_pack(args.pack, stage, args.mode == "minimal-luckified")
        shutil.copy2(forge, stage / "forge.jar")
        shutil.copy2(minecraft, stage / minecraft.name)
        shutil.copytree(libraries, stage / "libraries")
        for jar in additions:
            destination = stage / "mods" / jar.name
            if destination.exists():
                parser.error(f"Server pack already contains {jar.name}")
            shutil.copy2(jar, destination)
        world = configure(stage, args.mode, port)
        manifest = {"mode": args.mode, "world": world, "port": port, "pack_sha256": PACK_SHA,
                    "forge_sha256": FORGE_SHA, "minecraft_sha256": MC_SHA,
                    "added_sha256": {jar.name: digest(jar) for jar in additions}}
        (stage / "qa-inputs.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        if target.exists():
            raise FileExistsError(target)
        stage.rename(target)
    print(f"Staged {args.mode} at {target}; world {world}, loopback port {port}")


if __name__ == "__main__":
    main()
