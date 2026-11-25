# Dedicated RLCraft qualification server

The server probe is a separate Forge test mod. It runs console commands that create fixtures and modify inventories. Use a disposable server directory. The product JAR alone belongs in normal client and server installations.

## Inputs

- Use a Java 8 JDK for both Gradle and the server. The tested Gradle wrapper is 5.6.4. The provisioning script uses Python 3.11 or newer and its standard library.
- Obtain the June 27, 2023 server archive, file ID 4612990. The tested ZIP has SHA-256 `a29b3c0c99b41e1c7d404a78f48b7f9698f15b068141a042d5ca1108a8636c55`. [Official RLCraft 2.9.3 server pack](https://www.curseforge.com/minecraft/modpacks/rlcraft/files/4612990)
- Obtain the Forge installer for Minecraft 1.12.2, build 14.23.5.2860. In a fresh directory run `java -jar <absolute-path-to-the-installer.jar> --installServer`. Supply that directory as `--forge-home`. It must contain the Forge launcher, `minecraft_server.1.12.2.jar`, and `libraries/`. The helper checks the launcher and Minecraft JAR against the tested bytes. [Official Forge 1.12.2 downloads](https://files.minecraftforge.net/net/minecraftforge/forge/index_1.12.2.html)
- Build the product and probe from the project root with `.\gradlew.bat build reobfServerProbeJar --console=plain`. On macOS or Linux use `./gradlew`. The artifacts go to `build/libs/` and `build/qa-server/probe/` respectively. Choose the intended files by version and record their SHA-256 hashes.

## Provision a new server

Run these PowerShell commands from the project root after setting the paths for your machine. Existing directories are preserved. The staging helper constructs a server with the pack's own configuration, commands, structures and mods, plus the selected product and probe. It writes all input hashes to `qa-inputs.json`. The example uses offline profiles and a loopback-only listener for private local test actors.

```powershell
$packZip = 'D:/inputs/RLCraft Server Pack 1.12.2 - Release v2.9.3.zip'
$forgeInstall = 'D:/inputs/forge-1.12.2-2860-server'
$product = 'build/libs/Lootr4RLCraft-0.2.0-dev.jar'
$probe = 'build/qa-server/probe/LootrServerProbe-0.2.0-dev.jar'
$serverDir = 'D:/qa/rlcraft-stock'
python tools/stage_server.py --pack $packZip --forge-home $forgeInstall --product $product --probe $probe --destination $serverDir --mode stock
Set-Location -LiteralPath $serverDir
java -Xms512M -Xmx4G -jar forge.jar nogui
```

An initial Minecraft launch may create `eula.txt` and exit. Review the applicable terms and set `eula=true` yourself if accepted, then rerun the Java command in the same directory. The staged server uses seed `81327831`, overworld `CandidateWorld2`, port 25583, and command blocks enabled. Use `--port` to run a separate isolated instance on a different port. [Minecraft EULA](https://www.minecraft.net/eula)

## Stock console sequence

1. Wait for `Done`. Run `lootrqa generate` once in a new test world. It uses the installed Ruins `TowerMedium.tml` generator at 25 controlled sites, captures the seven original chest tiles at each site and emits Forge chunk-watch events for the native Locks pass.
2. Wait for `GENERATED towers=25 chests=175`, and then at least 40 **server ticks**. Use `lootrqa status` until the quiet read-only snapshot reports all 175 managed, or record the outstanding positions and phases. Run `lootrqa sample`. This opens available chests with three server-side actors, checks native slot changes, persists their actual player NBT and writes `CandidateWorld2/data/lootr-server-probe.dat`.
3. Run `save-all flush`, then `stop`. Relaunch the same directory and run `lootrqa verify`. Preserve the `RESTART` counts. `lootrqa sample` has its own stage checks and should not replace the restart verification.
4. For the seed above, `lootrqanatural 50 12` requests fresh terrain starting at **chunk** coordinates. This sample reached the installed Battle Towers generator and found 20 naturally generated chest halves grouped into ten inventories. A reused region may produce no new events. Other seeds require another sample area.
5. `lootrqa compat` exercises native lock/key/lockpick and RLArtifacts interactions. `lootrqa physical` exercises the hopper, Carry On, destruction, piston and paired-Mimic fixtures. Check the actual per-case output. `lootrqa remote` and `lootrqa row` prepare the network and visual fixtures used by the client probe. These commands create blocks in the disposable world and wait for the real Locks pass where needed.

Keep `logs/latest.log` and rollover logs alongside the exact JAR hashes. Three synthetic server actors qualify the server inventory path. Networked-client screenshots and actor sessions have separate evidence. [Dedicated evidence](evidence/dedicated/) · [Connected clients](evidence/network/three-players/)

## Abrupt process trial

Use a separate destination with `--mode crash` and complete the EULA step before running the automation. For a fresh run without creating a world prematurely, place your own previously accepted `eula.txt` in the new directory. Run the helper from the project root.

```powershell
python tools/run_crash_trial.py 'D:/jdk8/bin/java.exe' 'D:/qa/rlcraft-crash'
```

The helper owns the Java processes it starts. Its first server establishes a baseline, then disables ordinary world saves. The probe uses native slot clicks to transfer five named apples and two named emeralds. It saves three different combinations and emits `CRASH_ARMED` after the requested writes finish. The runner terminates its own armed JVM and starts a second JVM against the same world. Compare each `CRASH_STATE` and `CRASH_RESULT` in the before/restart logs.

| Case | Intentionally flushed before the hard stop | Expected state after restart |
| --- | --- | --- |
| A | Player file after withdrawal, registered chunk from before withdrawal | Player recovers the five apples, private chest retains the two emeralds |
| B | Registered chunk after transfer, player file from before first open | Five apples remain in the private chest, two emeralds remain with the player |
| C | Newly enrolled chest, only the later player file explicitly flushed | Managed chest identity survives and both stacks have exactly one owner |

The checked run reached `CRASH_PRIMED` and stopped gracefully while all three targets were still in Locks phase 1. It wrote no baseline and performed no hard stop. The runner now waits and retries the readiness check, which precedes any fixture writes. To resume **that specific primed trial** use `--resume-setup`. Each pre-existing log remains available. All three real-process outcomes are **UNVERIFIED**. Native save-order tests exercised the serialized states separately. [Crash setup evidence](evidence/dedicated/crash-setup.log) · [Native save-order evidence](evidence/dedicated/native-save-order-generation.log)

## Optional Luckified environments

For an independent full-pack test, stage another destination using `--mode luckified` and supply two separate `--addon` arguments pointing at `RLCraftLuckified-1.1.3.3.jar` and `00FermiumBooter-1.6.5.jar`. This mode preserves the stock pack mods. The observed full-pack setup failed during Phosphor's chunk mixin application before the server reached readiness. Stock qualification uses neither optional addition. [Luckified files](https://www.curseforge.com/minecraft/mc-mods/luckified/files/all) · [FermiumBooter files](https://www.curseforge.com/minecraft/mc-mods/fermiumbooter/files/all)

The `--mode minimal-luckified` diagnostic uses Forge, Baubles, RLArtifacts, both optional JARs, the product and the probe in a flat world. This profile and the full optional profile both default to port 25589 and world name `OptionalLuckWorld`; keep separate directories. After `Done`, run `lootrqa luck-prime`, wait for the native tick pass, then run `lootrqa luck`. Preserve the control-versus-managed result for the specific profile. This minimal diagnostic does not establish optional compatibility in the full pack.

## Resuming the original evidence

1. Verify and restore the private handoff ZIP in a new clone using the [transfer instructions](HANDOFF_STATE.md), then open PowerShell in the clone root. Keep these saved worlds as the baseline. Close any server using an intended copy destination with `save-all flush` and `stop`, then wait for its process to exit. Reacquire the pack and Forge inputs from the publishers listed above.
2. Set `$packZip` and `$forgeInstall` as in the provisioning example. Choose a **new, absent runtime directory outside the extracted project**. The staging helper refuses an existing directory. The archive already contains `build/qualification/server` and the other evidence folders. Stage the runtime first, leave its server stopped and then copy the worlds **into** the runtime. Use the following stock commands in order.

```powershell
$ErrorActionPreference = 'Stop'
$pinned = (Resolve-Path 'build/handoff-pinned').Path
$saved = (Resolve-Path 'build/qualification').Path
$runtime = 'D:/qa/restored-stock'
function Get-PinnedJar($role, $hash) {
    $matchesByHash = @(Get-ChildItem -LiteralPath (Join-Path $pinned $role) -Filter '*.jar' -File |
        Where-Object { (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash -eq $hash })
    if ($matchesByHash.Count -ne 1) { throw "Expected exactly one $role JAR with SHA-256 $hash" }
    $matchesByHash[0].FullName
}
function Copy-SavedWorld($source, $runtimeDir) {
    if (!(Test-Path -LiteralPath (Join-Path $source 'level.dat') -PathType Leaf)) { throw "Incomplete source world $source" }
    $target = Join-Path $runtimeDir (Split-Path -Leaf $source)
    if (Test-Path -LiteralPath $target) { throw "World destination already exists: $target" }
    Copy-Item -LiteralPath $source -Destination $target -Recurse -ErrorAction Stop
}
$product = Get-PinnedJar 'product' 'F67C7B9B2ACDDAEF5420FB74FA92FFBB4DBA54BD02EBC2CC6F4C9D845CCB1B0A'
$probe = Get-PinnedJar 'server' 'F6EC2A475DBA366457CA6694F3403FD1B60F2A63B8D90EE1F7F487FDEF390843'
python tools/stage_server.py --pack $packZip --forge-home $forgeInstall --product $product --probe $probe --destination $runtime --mode stock
if ($LASTEXITCODE -ne 0) { throw 'Staging failed' }
foreach ($world in 'CandidateWorld2', 'CandidateWorld', 'World') {
    Copy-SavedWorld (Join-Path $saved "server/$world") $runtime
}
$settings = Join-Path $pinned 'stock-settings'
Rename-Item -LiteralPath (Join-Path $runtime 'server.properties') -NewName 'server.staged.properties'
Copy-Item -LiteralPath (Join-Path $settings 'server.properties') -Destination $runtime
Copy-Item -LiteralPath (Join-Path $settings 'ops.json') -Destination $runtime
Copy-Item -LiteralPath (Join-Path $settings 'lootr4rlcraft.cfg') -Destination (Join-Path $runtime 'config')
```

3. Check `server.properties` in the runtime. The supplied stock settings select `CandidateWorld2` on `127.0.0.1:25583`. Complete the EULA step in this runtime, then launch `java -Xms512M -Xmx4G -jar forge.jar nogui` from it. Use `lootrqa status` to inspect the saved 175-chest sample. Running `generate` belongs to a new trial. To inspect `CandidateWorld` or `World`, stop the copy, select that name in its `level-name` property and restart. `CandidateWorld` holds the earlier timing failure. `CandidateWorld2` holds the verified allocations, region data and associated player files. Normal gameplay and probe commands can change this working copy.
4. For a **crash-trial continuation**, choose another absent runtime, repeat staging using the pinned stock `$product` and `$probe` and `--mode crash`, and check its exit code. While that runtime is stopped, use `Copy-SavedWorld (Join-Path $saved 'crash-server/CrashTrial') $runtime` and `Copy-Item -LiteralPath (Join-Path $saved 'crash-server/crash-before.log') -Destination $runtime`. Complete the EULA step and run `python tools/run_crash_trial.py 'D:/jdk8/bin/java.exe' $runtime --resume-setup` from the extracted project root. The retained log contains the pre-baseline `CRASH_PRIMED` marker required by the runner. The runner saves a new log alongside it.
5. For the **minimal Luckified diagnostic**, stage another absent runtime with `--mode minimal-luckified` and both separately acquired optional JARs. Keep the pinned `$product` and use `$probe = Get-PinnedJar 'server-optional' 'F1ED1B1D2B5378DD7F3313A0DCEB5AF9F2E62183F09C6F9A92F5A9D11DA109F0'` before staging. These probe builds share a filename. While the staged runtime is stopped, use `Copy-SavedWorld (Join-Path $saved 'luckified-minimal/OptionalLuckWorld') $runtime`, complete the EULA step and launch from the runtime. This copy has port 25589; stop any other server using that port first.

The checked source tree contains the probe classes and test loot resource in `src/serverProbe/`. Gradle packages them into the dedicated test JAR. The client probes live in `src/test/` and have a separate runner. `tools/audit_saved_chests.py <world-region-directory> --bounds 142 142 154 154` reads a stopped 1.12.2 save without changing it. Its per-block counts describe stored markers and native Locks phases.
