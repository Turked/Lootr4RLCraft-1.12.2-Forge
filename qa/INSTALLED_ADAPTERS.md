# Headless tests against installed RLCraft classes

Use the project JDK 8 and your own clean RLCraft 2.9.3 client profile. From the checkout root, run the following with the profile path that contains `mods`.

```powershell
$pack = 'D:\Games\CurseForge\Instances\RLCraft'
.\gradlew.bat -I qa/installed-adapters.gradle installedAdapterTest "-PmodpackDir=$pack" --console=plain
```

The fixture reads Quark r1.6-179, AutoRegLib 1.3-32, Rustic 1.1.7, Charm 1.4.1 and Varied Commodities 31Mar23 from that installation. Gradle resolves the project's pinned Forge platform and MCP mapping, then downloads AutoRenamingTool 2.0.3 from NeoForged Maven and verifies its SHA-256. The optional task writes its development-mapped copies and remapper logs under `build/qa-installed`. Only the test process adds these copies to its classpath. Install the original mods through the pack launcher on each machine. Never put the mapped copies in a game profile or a distribution.

The fixture checks seven installed tile classes, the contents and slot counts of private inventories, native loot-table replay, an open Rustic barrel menu, Quark pairing and the vertical 54-slot Rustic cabinet. A successful run prints the individual assertions before `BUILD SUCCESSFUL`. The portable command passed using a separate checkout directory and an independently populated Gradle home. [Console evidence](evidence/dedicated/portable-installed-adapters.log)

These checks run in a native Minecraft test world with installed mod classes. Full-pack event ordering, three-client synchronization and screen rendering each have their own fixtures. [Dedicated RLCraft server](SERVER_SETUP.md) · [Actual connected clients](evidence/network/README.md)
