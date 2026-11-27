param(
    [Parameter(Mandatory=$true)][Alias('Profile')][string]$ProfilePath,
    [Parameter(Mandatory=$true)][string]$Install,
    [Parameter(Mandatory=$true)][ValidateSet('Baseline','Families','VerifyFamilyRestart')][string]$Phase,
    [string]$CandidateJar,
    [string]$ProbeJar,
    [string]$JavaPath,
    [string]$NativeDirectory,
    [string]$ExpectedCandidate,
    [string]$ExpectedProbe,
    [ValidateRange(0,300)][int]$LaunchIntervalSeconds = 0
)
$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$entry = Join-Path $PSScriptRoot 'Run-RenderProbe.ps1'
$runId = "$Phase-" + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0,8)
$meeting = (New-Item -ItemType Directory -Force -Path (Join-Path $project "build/qa-client/rendezvous/$runId")).FullName
$shared = @{
    Profile = (Resolve-Path -LiteralPath $ProfilePath).Path
    Install = (Resolve-Path -LiteralPath $Install).Path
    Rendezvous = $meeting
    AfterRestart = $Phase -ne 'Baseline'
    Families = $Phase -ne 'Baseline'
    VerifyFamilyRestart = $Phase -eq 'VerifyFamilyRestart'
}
foreach ($key in @('CandidateJar','ProbeJar','JavaPath','NativeDirectory')) {
    if ($PSBoundParameters.ContainsKey($key)) { $shared[$key] = (Resolve-Path -LiteralPath $PSBoundParameters[$key]).Path }
}
if ($ExpectedCandidate) { $shared.ExpectedCandidate = $ExpectedCandidate }
if ($ExpectedProbe) { $shared.ExpectedProbe = $ExpectedProbe }
Write-Output "Rendezvous $meeting"
$jobs = foreach ($name in @('RenderOne','RenderTwo','RenderThree')) {
    if ($name -ne 'RenderOne' -and $LaunchIntervalSeconds -gt 0) {
        Start-Sleep -Seconds $LaunchIntervalSeconds
    }
    $parameters = $shared.Clone()
    $parameters.Player = $name
    Start-Job -Name "$runId-$name" -ArgumentList $entry,$parameters -ScriptBlock {
        param($script, $options)
        $ErrorActionPreference = 'Stop'
        & $script @options
    }
}
do {
    $active = @($jobs | Where-Object { $_.State -eq 'Running' -or $_.State -eq 'NotStarted' })
    if ($active.Count -gt 0) { Wait-Job -Job $active -Any -Timeout 15 | Out-Null }
    foreach ($job in $jobs) { Receive-Job -Job $job -ErrorAction Continue }
} while ($active.Count -gt 0)
$failedJobs = @($jobs | Where-Object { $_.State -ne 'Completed' })
$failures = @(Get-ChildItem -LiteralPath $meeting -Filter '*-failed.txt' -File)
$missing = @('RenderOne','RenderTwo','RenderThree' | Where-Object { !(Test-Path -LiteralPath (Join-Path $meeting "$_-verified.txt")) })
if ($failedJobs.Count -gt 0 -or $failures.Count -gt 0 -or $missing.Count -gt 0) {
    throw "Incomplete $Phase run. Failed processes: $($failedJobs.Count). Failed checks: $($failures.Count). Missing completion markers: $($missing -join ', '). See $meeting and each client log."
}
Write-Output "All three clients reported completion for $Phase. Review the captured frames and recorded slot counts in build/qa-client."
