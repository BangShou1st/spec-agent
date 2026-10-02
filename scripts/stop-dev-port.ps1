param([int]$Port)
$ErrorActionPreference = 'Stop'
$repo = (Split-Path -Parent $PSScriptRoot).Replace('/', '\')
$listeners = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
foreach ($processId in ($listeners.OwningProcess | Select-Object -Unique)) {
    $owner = Get-CimInstance Win32_Process -Filter "ProcessId=$processId"
    if (!$owner) { continue }
    $parent = Get-CimInstance Win32_Process -Filter "ProcessId=$($owner.ParentProcessId)"
    $ours = ([string]$owner.CommandLine).Replace('/', '\').Contains($repo) -or
        ([string]$owner.ExecutablePath).Replace('/', '\').Contains($repo) -or
        ($parent -and ([string]$parent.ExecutablePath).Replace('/', '\').Contains($repo)) -or
        ($parent -and ([string]$parent.CommandLine).Replace('/', '\').Contains($repo))
    if (!$ours) { Write-Host "Port $Port belongs to another application (PID $processId); kept running"; continue }
    Write-Host "Restarting this checkout on port $Port (PID $processId)"
    Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue
    $until = [DateTime]::UtcNow.AddSeconds(10)
    while ((Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue) -and [DateTime]::UtcNow -lt $until) {
        Start-Sleep -Milliseconds 200
    }
}
