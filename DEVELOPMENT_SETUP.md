# Windows development setup

Start in a new checkout of the `main` branch. A source build needs Git, a 64-bit Java 8 JDK and an internet connection for the first Gradle run. The real-pack tests also need a licensed Minecraft Java Edition installation, the matching RLCraft client and server pack, and the test probes. The saved multiplayer session travels in the owner's private handoff ZIP. [Development checkpoint](DEVELOPMENT_CHECKPOINT.md)

## Software

| Component | Version used and where to obtain it |
| --- | --- |
| Java | **Eclipse Temurin JDK 8u504-b01, Windows x64, HotSpot**, reporting `1.8.0_504-b01`. Install the JDK, which contains both `java.exe` and `javac.exe`. [Temurin release](https://github.com/adoptium/temurin8-binaries/releases/tag/jdk8u504-b01) |
| IntelliJ IDEA | The desktop has **2026.1.1**, build **261.23567.138**. Use that version to reproduce its IDE setup. The Gradle build works from a terminal independently of the IDE. [JetBrains version archive](https://www.jetbrains.com/idea/download/other.html) |
| Git | The desktop uses **Git for Windows 2.54.0.windows.1**. This is the reproduction version. No older minimum was established. [Git for Windows releases](https://github.com/git-for-windows/git/releases) |
| Gradle, ForgeGradle and mappings | The checked-in wrapper downloads **Gradle 5.6.4** and verifies the distribution checksum. The project resolves **ForgeGradle 3.0.197**, **Forge 1.12.2-14.23.5.2860** and MCP snapshot **20171003-1.12**. A separate Gradle installation is unnecessary. [Build configuration](build.gradle) · [Wrapper configuration](gradle/wrapper/gradle-wrapper.properties) |
| Minecraft and RLCraft | Install **Minecraft Java Edition 1.12.2**, **RLCraft 2.9.3** with the pack's Forge **14.23.5.2860**. The Windows CurseForge client on the desktop reports **1.327.0.43047**. The QA launchers read the CurseForge profile metadata and installed runtime. [Client pack file 4612979](https://www.curseforge.com/minecraft/modpacks/rlcraft/files/4612979) · [Server pack file 4612990](https://www.curseforge.com/minecraft/modpacks/rlcraft/files/4612990) |
| QA scripting | **Python 3.11+**, standard library only, for server staging, restore, crash runner and saved-world reader. The desktop interpreter is **3.12.14**. Use **64-bit PowerShell** for the Windows client scripts. The inspected shell is **PowerShell 7.6.9**. Neither is a runtime dependency of the mod. [Python for Windows](https://www.python.org/downloads/windows/) · [PowerShell for Windows](https://learn.microsoft.com/powershell/scripting/install/installing-powershell-on-windows) |

## Clone and set Java

From a writable development directory in PowerShell, run the following. Select the installed JDK's root directory when prompted.

```powershell
git clone --branch main https://github.com/Turked/Lootr4RLCraft-1.12.2-Forge.git
Set-Location 'Lootr4RLCraft-1.12.2-Forge'
$env:JAVA_HOME = (Resolve-Path -LiteralPath (Read-Host 'Path to the Temurin 8 JDK')).Path
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
git --version
java -version
javac -version
.\gradlew.bat --version
```

The expected Gradle/JVM pair is 5.6.4 and 1.8.0_504. The commands set Java for the current terminal. To keep it across sessions, set the Windows user environment variable `JAVA_HOME` to that same JDK root and add `%JAVA_HOME%\bin` to the user `Path`. The default Gradle home is `%USERPROFILE%\.gradle`. An optional `GRADLE_USER_HOME` can point to any writable cache directory. The first build downloads the wrapper distribution, Forge, MCP, Minecraft development artifacts and Java libraries. Start online and leave Gradle offline mode disabled.

## Import into IntelliJ and build

1. Use **File → Open**, select the cloned folder containing `settings.gradle`, and open it as a Gradle project. Under **File → Project Structure → Project**, add the installed Temurin JDK as the project SDK and set language level to 8.
2. Under **Settings → Build, Execution, Deployment → Build Tools → Gradle**, choose the **Gradle wrapper** distribution and set **Gradle JVM** to that same JDK 8. Leave IntelliJ itself on its bundled runtime. Reload the project in the Gradle tool window.
3. Run the commands below at the project root. The `build` task invokes both storage and native chest integration checks. The two additional tasks create the reobfuscated fixtures for installed Forge. Product and probes have distinct output directories.

```powershell
.\gradlew.bat --console=plain build reobfClientProbeJar reobfServerProbeJar
if ($LASTEXITCODE -ne 0) { throw 'Build or tests failed; inspect the first failing Gradle task' }
Get-FileHash build/libs/Lootr4RLCraft-0.2.0-dev.jar
Get-FileHash build/qa-client/probe/LootrRenderProbe-0.2.0-dev.jar
Get-FileHash build/qa-server/probe/LootrServerProbe-0.2.0-dev.jar
```

For a specific regression, run `.\gradlew.bat lootStorageTest` or `.\gradlew.bat chestIntegrationTest`. For the Forge development sandbox, the Gradle tasks are `runClient` and `runServer`. Run `.\gradlew.bat genIntellijRuns` to generate IDE configurations and download the Minecraft assets. A cold setup encountered a transient download error on `icons/icon_32x32.png`. Retrying that same task completed. Development runs use `run/client` and `run/server` and exercise a different environment from the full RLCraft tests. Preserve the private ZIP outside the checkout. Gradle's `clean` removes the entire `build` directory, including any restored QA worlds and captures there.

## Configure a laptop RLCraft installation

To rerun the separate seven-class native adapter checks, use the optional Gradle init script with an installed 2.9.3 profile. It prepares its own mapped test copies from the pack's published JARs. [Installed-class procedure and evidence](qa/INSTALLED_ADAPTERS.md)

In CurseForge, install the specified 2.9.3 profile, launch it once through the normal Minecraft launcher with your own account, reach the menu and exit. Use **Open Folder** on that profile to obtain the folder containing `mods`, `manifest.json` and `minecraftinstance.json`. In CurseForge's Minecraft settings find the Minecraft modding folder. Its shared **Install** directory contains `versions`, `libraries` and `assets`. The connected-client scripts take these two directories separately and create their disposable profiles under `build/qa-client`.

```powershell
$instance = (Resolve-Path -LiteralPath (Read-Host 'RLCraft 2.9.3 profile folder')).Path
$install = (Resolve-Path -LiteralPath (Read-Host 'CurseForge Minecraft Install folder')).Path
Test-Path (Join-Path $instance 'mods')
Test-Path (Join-Path $install 'versions/1.12.2/1.12.2.json')
```

To deploy a newly built product, close the chosen destination game/server. Use a disposable destination during development. The Gradle property takes the directory **containing** `mods`.

```powershell
.\gradlew.bat push-to-modpack "-PmodpackDir=$instance"
if ($LASTEXITCODE -ne 0) { throw 'Deployment failed' }
Get-FileHash (Join-Path $instance 'mods/Lootr4RLCraft-0.2.0-dev.jar')
```

For a persistent per-checkout setting, put a line such as `modpackDir=D:/Games/CurseForge/Instances/RLCraft QA` in an untracked `gradle.properties` in the checkout root. Use forward slashes and omit quotes around the value. Then run `.\gradlew.bat push-to-modpack`, or create an IntelliJ **Gradle** run configuration against this project with the task `push-to-modpack`. Repoint it whenever the target profile changes. The task verifies matching old mod metadata and backs up replaced project JARs to the target profile's `lootr4rlcraft-backups` directory. Deploy only the product to normal client/server profiles. Both QA JARs are development fixtures.

## Restore the private state and resume the stopped test

1. On the laptop, keep the private ZIP outside the clone. From the project root, compare its SHA-256 with the published handoff checksum, verify each entry, then restore into the fresh clone. The restore helper rejects existing destination files. If Python is installed as `py -3`, substitute that for `python` throughout these guides. [Private archive name, checksum and contents](qa/HANDOFF_STATE.md)

   ```powershell
   $state = (Resolve-Path -LiteralPath (Read-Host 'Private QA handoff ZIP')).Path
   Get-FileHash -LiteralPath $state
   python tools/handoff_state.py verify $state
   if ($LASTEXITCODE -ne 0) { throw 'Private transfer failed verification' }
   python tools/handoff_state.py restore $state
   if ($LASTEXITCODE -ne 0) { throw 'Private transfer restore failed' }
   ```

2. Recreate the dedicated runtime using the official 2.9.3 server ZIP and a freshly installed Forge 2860 server. The server guide gives the exact hashes, installer command, Python staging command, EULA handling and copy procedure for the **stopped, saved** `CandidateWorld2`. Stage to a *new* directory and copy the saved world there before its first normal launch. Keep the ZIP's other worlds as historical evidence. [Dedicated server setup and restore](qa/SERVER_SETUP.md)
3. For the interrupted run, select the preserved **0.2.0-dev** product in `build/handoff-pinned/product` and both pinned QA JARs. Its tested product SHA-256 is `F67C7B9B2ACDDAEF5420FB74FA92FFBB4DBA54BD02EBC2CC6F4C9D845CCB1B0A`. Use the hashes in the checkpoint to identify the build. The latest source includes a subsequent starter-chest provenance change. Test a new source build under its own checksum. Keep the restored original chest at (72, 160, 72), its saved player files, and the prior diamond allocation.
4. Start the restored server on `127.0.0.1:25583`. After `Done`, run `lootrqa row`. Wait for `RENDER ROW READY 14 native fixtures`, record any failures, and launch the **Families** phase with the pinned candidate and expanded probe. The network guide gives the parameters and the separate **VerifyFamilyRestart** phase. It explains how to review the three real clients' screenshots, logs and rendezvous markers. The stock physical interaction check is also pending. [Connected-client procedure](qa/evidence/network/README.md) · [Current checkpoint](DEVELOPMENT_CHECKPOINT.md)

A new world can reproduce the original tests if the archive is unavailable. Follow the stock console sequence for Ruins and the **Baseline** network phase before preparing the row. A new world cannot recover the prior players' exact state. The separate server guide also covers the crash and optional Luckified environments. [Server acceptance commands](qa/SERVER_SETUP.md) · [Integrated visual checks](qa/evidence/client/README.md) · [Verification matrix](VERIFICATION.md)
