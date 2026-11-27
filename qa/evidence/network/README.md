# Dedicated multiplayer qualification

The dedicated server and three separate RLCraft 2.9.3 clients use a disposable world on loopback port 25583. RenderOne, RenderTwo and RenderThree are synthetic local test accounts. Each has its own game directory, network connection, inventory, camera and framebuffer. The candidate SHA-256 is `F67C7B9B2ACDDAEF5420FB74FA92FFBB4DBA54BD02EBC2CC6F4C9D845CCB1B0A`.

## Completed first session

The clients held menus on the same chest simultaneously. All three initially had three diamonds in slot zero. RenderOne took one, RenderTwo took two and RenderThree took three. The recorded remaining counts were two, one and zero, respectively. RenderTwo and RenderThree still saw gold and the unopened label after RenderOne opened the chest. Each client saw gray after opening it. The players disconnected after traveling away. The original travel assertion used a vanilla client API that treats empty placeholder chunks as loaded. The expanded probe now checks the client chunk provider's actual loaded-chunk map.

[RenderOne frames and log](three-players/RenderOne/) · [RenderTwo frames and log](three-players/RenderTwo/) · [RenderThree frames and log](three-players/RenderThree/)

RenderTwo's first frame after teleport shows the gold overlay before the native chest body and nearby terrain are visible. A later frame from that same client shows the underlying chest with its lid raised, its gold indicator and the unopened HUD while RenderOne holds the menu. The first frame leaves scene rendering immediately after teleport unresolved. [First arrival](three-players/RenderTwo/RenderTwo-unopened.png) · [Other player holding the menu](three-players/RenderTwo/RenderTwo-other-player-opened.png)

## Handoff state

The expanded client probe was compiled and staged. The fourteen-family connected-client sweep was never launched. Per-family inventory balances after restart, appearance after chunk return, cross-dimension synchronization and reconnect captures remain **UNVERIFIED** for that sweep. The retained first-session captures document the original diamond fixture. No test client remains connected.

## Starting on another Windows desktop

Install RLCraft 2.9.3 in CurseForge and launch it once to populate the Forge 14.23.5.2860 runtime. Use the Java 8 toolchain specified in the project README. Run the commands below in PowerShell from the checkout. The runner makes a new set of three client directories and a fresh shared rendezvous for each phase. It validates the artifacts copied into each profile, records their SHA-256 hashes in `artifacts.txt`, and uses three separate synthetic sessions. The dedicated server must already be listening on `127.0.0.1:25583`.

The qualified production hash is `F67C7B9B2ACDDAEF5420FB74FA92FFBB4DBA54BD02EBC2CC6F4C9D845CCB1B0A`. The first three-client probe had hash `00981D40364F0B2C14D50DDCB4BB8BDF8C54E9DB52F8409EC32DEFD0077279B0`. The expanded probe containing the corrected client chunk check has hash `5F36BFF6A7A0B114823FC30EF7DB74BBA5AAD890C3CCAD5AAE79F73EBC9C79BC`. Use the expanded probe for remaining work.

```powershell
.\gradlew.bat build reobfClientProbeJar reobfServerProbeJar
$instance = Read-Host 'Full path of the RLCraft 2.9.3 CurseForge profile'
$runtime = Read-Host 'Full path of the CurseForge Minecraft Install folder containing versions and libraries'
$common = @{ Profile = $instance; Install = $runtime }
```

Extract the private transfer ZIP at the project root before resuming the exact candidate. It restores the pinned product and client probe at the paths below. Its dedicated server probe belongs under `build/handoff-pinned/server/LootrServerProbe-0.2.0-dev.jar`. The saved world and old rendezvous files return to their previous `build/` locations. Newly staged client profiles can use the pinned artifacts without rebuilding them. For a fresh rebuild, record the newly built hashes and identify them as a different build.

For the current desktop continuation, use the original pre-row world and the pinned product with freshly built client and server probes. Point `ProbeJar` at `build/qa-client/probe/LootrRenderProbe-0.2.0-dev.jar`, set `ExpectedProbe` to its actual SHA-256, and stage the matching newly built server probe. Confirm both halves of the Rustic cabinet share one managed identity and open a 54-slot private menu before proceeding. [Current checkpoint](../../../DEVELOPMENT_CHECKPOINT.md)

```powershell
$common.CandidateJar = 'build/handoff-pinned/product/Lootr4RLCraft-0.2.0-dev.jar'
$common.ProbeJar = 'build/handoff-pinned/client/LootrRenderProbe-0.2.0-dev.jar'
$common.ExpectedCandidate = 'F67C7B9B2ACDDAEF5420FB74FA92FFBB4DBA54BD02EBC2CC6F4C9D845CCB1B0A'
$common.ExpectedProbe = '5F36BFF6A7A0B114823FC30EF7DB74BBA5AAD890C3CCAD5AAE79F73EBC9C79BC'
```

If a desktop uses a different Java 8 executable or another native DLL directory, supply `$common.JavaPath` and `$common.NativeDirectory` with their absolute paths. The launcher expects the installed Minecraft 1.12.2 and Forge version JSONs, CurseForge instance metadata and the pack manifest. The JVM and natives alone do not contain the required game, mod and library files.

### First diamond allocation on a fresh disposable world

Use the server probe’s designated disposable world `CandidateWorld2`. While the server is running, execute `lootrqa remote` in its console to prepare the fixed chest at **72, 160, 72**. Wait for the fixture-ready log and grant operator permission to RenderOne, RenderTwo and RenderThree inside that test world. The client scripts use those accounts to position the cameras. Avoid recreating the chest when verifying prior allocations.

```powershell
.\qa\Run-MultiplayerProbe.ps1 @common -Phase Baseline
```

A fresh run should leave two diamonds in RenderOne's private chest, one in RenderTwo's and zero in RenderThree's. Their player inventories should hold one, two and three respectively. The transferred checkpoint already contains the prior allocation session. Skip fresh diamond allocation when resuming that world.

### Fourteen-family transitions

The server probe console command `lootrqa row` creates the row in a fresh test area. For a transferred world, check its log and saved state before provisioning. Use the existing row if it has been prepared. Wait for `RENDER ROW READY 14 native fixtures`. Each new row inventory starts with three books. The camera approaches the north side at z 69.2. All fixtures are at y 160, z 72.

| x | Fixture | x | Fixture |
| --- | --- | --- | --- |
| 88 | Vanilla single | 116 | Charm barrel |
| 92 | Vanilla double | 120 | Varied Commodities barrel |
| 96 | Vanilla trapped | 124 | Chest minecart |
| 100 | Quark spruce | 128 | Charm crate |
| 104 | Quark birch | 132 | Charm bookshelf chest |
| 108 | Rustic cabinet | 136 | Vanilla chest with visible unlocked padlock |
| 112 | Rustic barrel | 140 | Double Rustic cabinet |

```powershell
.\qa\Run-MultiplayerProbe.ps1 @common -Phase Families
```

Set `-LaunchIntervalSeconds` when staggering client startups. Its default is zero. The November 26 laptop retry used approximately 95 seconds between launches and connected all three clients.

This phase first reopens the original diamond chest to check its persisted allocation, then covers the row. RenderTwo and RenderThree wait for RenderOne’s personal open on each container. Each checks its own unopened HUD, takes its assigned share of books, and observes its opened HUD. The probe revisits the row after a client chunk unload, a Nether round trip and reconnect. The reconnect pass checks private book counts through the actual menus.

After recording the results, stop the dedicated test server cleanly using its console. Restart that same server with the same world and mod JAR. Keep its playerdata and private loot records together. Once it is ready, run the final verification.

```powershell
.\qa\Run-MultiplayerProbe.ps1 @common -Phase VerifyFamilyRestart
```

The restart pass checks the previously opened state and private remainder in every row inventory. Every phase prints its private rendezvous directory under `build/qa-client/rendezvous`. Review each client's screenshots, logs and `*-failed.txt` evidence as well as its final `*-verified.txt`. The launcher's completion check covers all three client processes, completion files and failure markers. Full-frame inspection remains necessary to evaluate geometry and colors. Each of the three clients has a 2.7 GiB heap limit. Reserve memory for all three processes and the dedicated server.

With Pillow 10.1 or newer, `tools/review_family_frames.py --families <RenderOne-profile> <RenderTwo-profile> <RenderThree-profile> --output <review-directory>` creates a contact sheet for each fixture and a CSV index. Pass `--restart` with the three restart profiles to include that phase. Partial runs still produce the sheets and index, with missing or unreadable frames marked and a nonzero exit status.

## Transfer contents

Carry the project source, this evidence directory, the original QA screenshots, and the documented test-only JARs with their hashes. To resume the exact saved state, privately transfer the stopped disposable server world and its synthetic playerdata according to the server handoff. The Windows client profiles, launcher account files, private CurseForge metadata, loaded libraries, runtime DLLs, full logs, resource-pack caches and third-party mod JARs can be recreated from the receiving desktop's own installation. None are needed in source control.
