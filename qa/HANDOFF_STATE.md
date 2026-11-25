# Transferring the saved qualification state

**Copy one file privately from the desktop before travel:** `Private-Travel-Handoff/Lootr4RLCraft-private-QA-2026-11-25.zip`, in the directory alongside the source checkout. Keep a copy outside the checkout on the laptop. Gradle's `clean` deletes the checkout's `build` directory, including any extracted evidence there.

The ZIP is **32,665,014 bytes**. Its SHA-256 is `1e3e59a2e9c4af54082ab01d637d8bc0a7d47bccf302d6539dfb4646edfa5b0a`. A test restore into a separate directory on the desktop succeeded. Each of the 644 payload files has a SHA-256 entry in `SHA256.json`. All 644 entries and the ZIP member CRCs passed verification.

## Restore on the laptop

1. Clone the repository and follow the prerequisites in [Development setup](../DEVELOPMENT_SETUP.md). Put the ZIP in a directory you control. Open PowerShell in the new checkout, before restoring or generating any test worlds.
2. Set the path to the actual ZIP and check its full-file checksum. Then use the included Python tool to verify and restore its contents. These commands use the Windows Python launcher with a Python 3.11 or newer installation. If the laptop uses a Python executable directly, substitute `python` for `py -3`.

   ```powershell
   $zip = 'D:/Transfer/Lootr4RLCraft-private-QA-2026-11-25.zip'
   $expected = '1e3e59a2e9c4af54082ab01d637d8bc0a7d47bccf302d6539dfb4646edfa5b0a'
   if ((Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash -ne $expected) {
       throw 'The transfer checksum differs. Copy the archive again.'
   }
   py -3 tools/handoff_state.py verify $zip
   if ($LASTEXITCODE -ne 0) { throw 'Archive verification failed' }
   py -3 tools/handoff_state.py restore $zip
   if ($LASTEXITCODE -ne 0) { throw 'Restore failed' }
   ```

   The tool checks the manifest and every archived file before writing, creates directories under `build/` and refuses to overwrite existing target files. If restoring into a directory containing an earlier attempt, use a fresh clone or the tool's `--destination` option with a new directory. The runbooks below assume you restored in the clone.

3. Obtain the publisher's RLCraft server pack, install the matching Forge server and provision a **separate, previously absent runtime directory**. Follow **Resuming the original evidence** in [Dedicated server setup](SERVER_SETUP.md). Stage that runtime with the pinned JARs, copy the saved worlds and fixture settings into it while stopped, then start it. The restored `build/qualification/server` is a snapshot with world data. The separate staged runtime supplies the Minecraft server, mods, configs, scripts and libraries.
4. Install and launch the matching CurseForge client pack on the laptop to provision its libraries, assets and natives. Use its profile and install paths in the [three-client instructions](evidence/network/README.md). Start the saved trial at the step named in the [development checkpoint](../DEVELOPMENT_CHECKPOINT.md). The user folders, test accounts' authentication, pack assets, launcher installations, and IDE/Gradle caches require no transfer.

## Archive contents

| Restored path under `build/` | Historical state |
| --- | --- |
| `qualification/server/CandidateWorld2/` | Current dedicated fixture, all dimensions, regions, persistent mod data and six synthetic player files, including the three connected players. This is the world for continuing the row test. |
| `qualification/server/CandidateWorld/` and `World/` | Earlier registration-timing failure and stock baseline. Preserve separately for comparisons. |
| `qualification/crash-server/CrashTrial/` and `crash-before.log` | Primed crash fixture at the Locks readiness failure. The hard-stop test has yet to reach its baseline. |
| `qualification/luckified-minimal/OptionalLuckWorld/` | Minimal optional-mod startup fixture. The Luckified loot behavior trial is pending. |
| `qa-client/run-*/` and `qa-client/synchronized-clients/` | Two integrated test worlds, historical frames and available artifact lists, and nine rendezvous markers for the completed three-player diamond session. Launchers create fresh client profiles and a new rendezvous directory for each future phase. |
| `handoff-pinned/{product,server,client,server-optional}/` | Original project-owned historical JARs. The stock product hash begins `f67c7b9b`. Verify all hashes against the checkpoint and the server runbook. |
| `handoff-pinned/{stock-settings,historical,diagnostics,context}/` | Stock server properties, the synthetic operators and project config, historical project/probe binaries, desktop diagnostic logs and a private, path-redacted extract of the prior development transcript. The original requirements and acceptance instructions are in the repository's `qa/requirements/` folder. |

The pack's shipped mods, Minecraft assets, native libraries, credentials, personal Minecraft profiles and personal worlds are outside this archive. Download runtime inputs on the laptop using the setup guides. The retained saves are for the owner's private continuity and include game-generated state. Maintain the immutable archive until the laptop's restored evidence has been checked.
