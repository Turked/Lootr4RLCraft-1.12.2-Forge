param(
    [Parameter(Mandatory=$true)][Alias('Profile')][string]$ProfilePath,
    [Parameter(Mandatory=$true)][string]$Install,
    [string]$CandidateJar,
    [string]$ProbeJar,
    [string]$JavaPath,
    [string]$NativeDirectory,
    [ValidateSet('RenderProbe','RenderOne','RenderTwo','RenderThree')][string]$Player = 'RenderProbe',
    [string]$Rendezvous,
    [string]$ExpectedCandidate,
    [string]$ExpectedProbe,
    [string]$PreparedGame,
    [switch]$AfterRestart,
    [switch]$Families,
    [switch]$VerifyFamilyRestart,
    [string]$Cases = 'chest,double,trapped_chest,spruce,cabinet,charm_barrel,vc_barrel,cart,locked'
)
$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$stage = Join-Path $project 'build/qa-client'
$ProfilePath = (Resolve-Path -LiteralPath $ProfilePath).Path
$Install = (Resolve-Path -LiteralPath $Install).Path
function Get-ProbeArtifact($explicit, $directory, $pattern, $hash) {
    if ($explicit) { return Get-Item -LiteralPath $explicit }
    $files = @(Get-ChildItem -LiteralPath $directory -Filter $pattern -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch '-(sources|javadoc|deobf)\.jar$' })
    if ($hash) {
        $files = @($files | Where-Object { (Get-FileHash -LiteralPath $_.FullName).Hash -eq $hash })
    }
    if ($files.Count -ne 1) {
        throw "Expected one $pattern in $directory. Build the reobfuscated artifacts or specify their exact paths with -CandidateJar and -ProbeJar."
    }
    return $files[0]
}
$candidate = Get-ProbeArtifact $CandidateJar (Join-Path $project 'build/libs') 'Lootr4RLCraft-*.jar' $ExpectedCandidate
$probe = Get-ProbeArtifact $ProbeJar (Join-Path $stage 'probe') 'LootrRenderProbe-*.jar' $ExpectedProbe
$profileMetadata = Get-Content -LiteralPath (Join-Path $ProfilePath 'minecraftinstance.json') -Raw | ConvertFrom-Json
$manifestPath = Join-Path $ProfilePath 'manifest.json'
$manifest = if (Test-Path -LiteralPath $manifestPath -PathType Leaf) {
    Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
} else {
    $profileMetadata.manifest
}
if ($manifest.version -ne '2.9.3' -or !$manifest.files -or $profileMetadata.baseModLoader.name -ne 'forge-14.23.5.2860') {
    throw 'This probe targets the RLCraft 2.9.3 CurseForge profile on Forge 14.23.5.2860.'
}
if ($PreparedGame) {
    $game = (Resolve-Path -LiteralPath $PreparedGame).Path
    if ((Split-Path -Parent $game) -ne (Resolve-Path -LiteralPath $stage).Path -or
            !(Test-Path -LiteralPath (Join-Path $game 'artifacts.txt'))) { throw 'Use a staged QA profile from build/qa-client.' }
    $mods = Get-Item -LiteralPath (Join-Path $game 'mods')
} else {
    $game = Join-Path $stage ('run-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '-' + $Player)
    $mods = New-Item -ItemType Directory -Path (Join-Path $game 'mods') -Force
    foreach ($dir in @('config','scripts','structures','resources','resourcepacks','datapacks','bendspacks','llibrary')) {
        $dirPath = Join-Path $ProfilePath $dir
        if (Test-Path -LiteralPath $dirPath) { Copy-Item -LiteralPath $dirPath -Destination $game -Recurse }
    }
    foreach ($file in $manifest.files) {
        $addon = $profileMetadata.installedAddons | Where-Object { $_.addonID -eq $file.projectID }
        if (!$addon -or $addon.installedFile.id -ne $file.fileID) { throw "Missing manifest entry $($file.projectID)/$($file.fileID)" }
        if ($addon.fileNameOnDisk -like '*.jar') {
            Copy-Item -LiteralPath (Join-Path (Join-Path $ProfilePath 'mods') $addon.fileNameOnDisk) -Destination $mods.FullName
        }
    }
    $embedded = Join-Path $ProfilePath 'mods/memory_repo'
    if (Test-Path -LiteralPath $embedded) { Copy-Item -LiteralPath $embedded -Destination $mods.FullName -Recurse }
}
# Antique Cities ships as an override in the dedicated pack, outside the CurseForge manifest IDs.
$atlasOverride = Join-Path $ProfilePath 'mods/antiquecities-1.2.1.jar'
if (Test-Path -LiteralPath $atlasOverride) {
    Copy-Item -LiteralPath $atlasOverride -Destination $mods.FullName -Force
}
$candidatePath = Join-Path $mods.FullName $candidate.Name
$probePath = Join-Path $mods.FullName $probe.Name
if (!$PreparedGame) {
    Copy-Item -LiteralPath $candidate.FullName -Destination $candidatePath
    Copy-Item -LiteralPath $probe.FullName -Destination $probePath
}
$candidateHash = (Get-FileHash -LiteralPath $candidatePath).Hash
$probeHash = (Get-FileHash -LiteralPath $probePath).Hash
if ($candidateHash -ne (Get-FileHash -LiteralPath $candidate.FullName).Hash -or
        $probeHash -ne (Get-FileHash -LiteralPath $probe.FullName).Hash) {
    throw 'The staged mod and probe differ from the selected input artifacts. Use a fresh staging directory.'
}
if ($ExpectedCandidate -and $candidateHash -ne $ExpectedCandidate) { throw "Staged candidate hash mismatch: $candidateHash" }
if ($ExpectedProbe -and $probeHash -ne $ExpectedProbe) { throw "Staged probe hash mismatch: $probeHash" }
[IO.File]::WriteAllLines((Join-Path $game 'artifacts.txt'), @("$candidateHash  $($candidate.Name)", "$probeHash  $($probe.Name)"))
$options = @('version:1343','renderDistance:5','guiScale:2','fullscreen:false','pauseOnLostFocus:false','enableVsync:false','maxFps:40','fancyGraphics:false','clouds:false','resourcePacks:[]','tutorialStep:none')
[IO.File]::WriteAllLines((Join-Path $game 'options.txt'), $options)
$version = $profileMetadata.baseModLoader.name
$forge = Get-Content -LiteralPath (Join-Path $Install "versions/$version/$version.json") -Raw | ConvertFrom-Json
$vanilla = Get-Content -LiteralPath (Join-Path $Install 'versions/1.12.2/1.12.2.json') -Raw | ConvertFrom-Json
$libs = [Collections.Generic.List[string]]::new()
$seen = @{}
foreach ($library in @($forge.libraries) + @($vanilla.libraries)) {
    $allowed = !$library.rules
    foreach ($rule in $library.rules) {
        if (!$rule.os -or $rule.os.name -eq 'windows') { $allowed = $rule.action -eq 'allow' }
    }
    $mavenId = ($library.name -split ':')[0..1] -join ':'
    if (!$allowed -or $seen.ContainsKey($mavenId) -or !$library.downloads.artifact.path) { continue }
    $libraryPath = Join-Path (Join-Path $Install 'libraries') $library.downloads.artifact.path
    if (!(Test-Path -LiteralPath $libraryPath -PathType Leaf)) { throw "Missing installed library $libraryPath" }
    $libs.Add($libraryPath)
    $seen[$mavenId] = $true
}
$libs.Add((Join-Path $Install "versions/$version/$version.jar"))
$java = Join-Path $Install 'runtime/jre-legacy/windows-x64/jre-legacy/bin/java.exe'
$native = Join-Path $Install "natives/$version"
if ($JavaPath) { $java = (Resolve-Path -LiteralPath $JavaPath).Path }
if ($NativeDirectory) { $native = (Resolve-Path -LiteralPath $NativeDirectory).Path }
if (!(Test-Path -LiteralPath $java -PathType Leaf) -or !(Test-Path -LiteralPath (Join-Path $native 'lwjgl64.dll'))) {
    throw 'Install the 64-bit Java 8 and Forge 2860 Windows client runtime, or pass -JavaPath and -NativeDirectory from that runtime.'
}
$fixtureArgs = @('-Xmx4g','-Dlootr.renderProbe=true',"-Dlootr.probeCases=$Cases")
if ($Player -ne 'RenderProbe') {
    if (!$Rendezvous -or !(Test-Path -LiteralPath $Rendezvous -PathType Container)) {
        throw 'Pass the same newly created rendezvous directory for the three local clients.'
    }
    $fixtureArgs = @('-Xmx2700m',"-Dlootr.multiplayerProbe=$((Resolve-Path -LiteralPath $Rendezvous).Path)",("-Dlootr.afterRestart=" + $AfterRestart.IsPresent.ToString().ToLowerInvariant()))
    if ($Families) { $fixtureArgs += '-Dlootr.familySweep=true' }
    if ($VerifyFamilyRestart) { $fixtureArgs += '-Dlootr.verifyFamilyRestart=true' }
}
if ($Player -eq 'RenderProbe') { $testUUID = 'b034578c84e84250abc27ebf7f80081e' }
else { $testUUID = 'b034578c84e84250abc27ebf7f80081' + @{'RenderOne'='1';'RenderTwo'='2';'RenderThree'='3'}[$Player] }
Write-Output "Profile $game. Staged candidate SHA-256 $candidateHash, probe $probeHash"
Push-Location -LiteralPath $game
try {
    & $java '-Xms512m' @fixtureArgs "-Djava.library.path=$native" '-Dfml.disableVersionCheck=true' '-Dlog4j2.formatMsgNoLookups=true' '-cp' ($libs -join ';') $forge.mainClass '--username' $Player '--version' $version '--gameDir' $game '--assetsDir' (Join-Path $Install 'assets') '--assetIndex' '1.12' '--uuid' $testUUID '--accessToken' '0' '--userType' 'legacy' '--tweakClass' 'net.minecraftforge.fml.common.launcher.FMLTweaker' '--versionType' 'Forge' '--width' '960' '--height' '620' *> (Join-Path $game 'process.log')
    if ($LASTEXITCODE -ne 0) { throw "Client exit $LASTEXITCODE. See $game/process.log" }
} finally {
    Pop-Location
}
