# Rendered client verification

These captures came from two disposable RLCraft 2.9.3 integrated-server sessions. Each used Minecraft 1.12.2, Forge 14.23.5.2860, the 187 entries in the pack manifest checked against the installed CurseForge metadata, the installed pack configuration, scripts and resources, the development candidate and the separate test probe. The fixture used a fresh superflat world, native container blocks and entities, and individual operator enrollment. This exercise covers appearance, targeting and opening. Natural structure discovery has its own acceptance tests.

[Before and after comparison](render-comparison.png)

Both integrated runs used the manifest-listed mods and omitted Antique Cities, a separately supplied pack override. The later three-client dedicated run and the current launcher include that override. [Dedicated client evidence](../network/)

| Candidate SHA-256 | Cases | Evidence |
| --- | --- | --- |
| `029AEBCDD1C835E8490E2FF6C1A22CBC492B164D7F384FA70F0C4E4424DA8EC9` | Vanilla single, double, trapped, Rustic cabinet, Charm barrel and Varied Commodities barrel showed gold before opening, a native chest menu, gray afterward and a viewer-specific HUD label. The double and Varied Commodities barrel menus each had six rows. | [Full frames and log](block-inventories/) |
| `417258CCB1A409F1F0E3AC2547352C0AB447D119247B8CAAADEB15219D5D443B` | Quark spruce chest, chest minecart and a vanilla chest with an attached Locks wooden lock showed their native models, gold and gray indicators, menus and HUD states. | [Full frames and log](spruce-cart-lock/) |

The first run exposed a fixture error. It placed the Quark chest block with empty wood-type metadata. Quark reported a missing texture for that empty name and rendered a magenta surface. The second fixture populated the native spruce tile field used by Quark. The second set of frames shows the spruce texture through enrollment and opening.

The locked fixture attached a native lock before enrollment. A survival player clicked with empty hands, received the native locked notification and retained the unopened indicator. The matching native key unlocked the chest. A second empty-hand click opened the menu, and the lock remained visible with the gray indicator after closing. [Denied survival interaction](spruce-cart-lock/probe-locked-locked-denied.png) · [After using the key](spruce-cart-lock/probe-locked-after.png)

To reproduce on Windows, install RLCraft 2.9.3 through CurseForge and launch it once to populate the Forge 2860 game libraries, native DLLs and assets. Build with Java 8. Supply the actual instance and CurseForge Minecraft Install locations on this machine. The script reads from those locations and creates a disposable profile under `build/qa-client`. Each frame is saved in the new profile's `screenshots` directory.

```powershell
.\gradlew.bat build clientProbeJar reobfClientProbeJar
$instance = Read-Host 'Full path of the RLCraft 2.9.3 profile containing manifest.json and mods'
$runtime = Read-Host 'Full path of the CurseForge Minecraft Install folder containing versions and libraries'
.\qa\Run-RenderProbe.ps1 -Profile $instance -Install $runtime
```

The targeted label uses Forge's text-overlay event and the same per-recipient state as the world geometry. The tested manifest contains no Hwyla or The One Probe overlay. Dynamic Surroundings HUDs' installed configuration exposes its potion, clock, compass and light-level displays. [Forge 1.12.x HUD implementation](https://github.com/MinecraftForge/MinecraftForge/blob/1.12.x/src/main/java/net/minecraftforge/client/GuiIngameForge.java)

The original screenshots and the short observation excerpts are the retained evidence. Their test avatar is synthetic. The mod's client and world classes were compiled and then reobfuscated for the versions shown in the evidence table. A rebuild can produce a different hash. Supply exact transferred development JARs with `-CandidateJar` and `-ProbeJar` when repeating an exact-candidate check. If the Java executable or the folder of extracted 64-bit LWJGL DLLs is elsewhere, specify `-JavaPath` and `-NativeDirectory`.
