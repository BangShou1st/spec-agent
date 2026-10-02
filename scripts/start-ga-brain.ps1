param(
    [int]$Port = 8101,
    [int]$BackendPort = 8080,
    [string]$SecretFile = ''
)
$ErrorActionPreference = 'Stop'
# Provider credentials belong only to Java, never to Python's environment.
Remove-Item Env:SPEC_AGENT_TAVILY_API_KEY -ErrorAction SilentlyContinue
$repo = Split-Path -Parent $PSScriptRoot
if (!$SecretFile) { $SecretFile = Join-Path $repo 'data/internal-secret.txt' }
if (!(Test-Path -LiteralPath $SecretFile)) { throw 'Shared internal secret file is missing' }
$secret = (Get-Content -LiteralPath $SecretFile -Raw).Trim()
if (!$secret) { throw 'Shared internal secret is empty' }
$listener = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
if ($listener) {
    foreach ($socket in $listener) {
        $owner = Get-CimInstance Win32_Process -Filter "ProcessId=$($socket.OwningProcess)"
        $command = ([string]$owner.CommandLine).Replace('/', '\')
        $expected = (Join-Path $repo 'agent-brain/src').Replace('/', '\')
        if (!$command.Contains($expected) -or !$command.Contains('spec_agent_brain.app:app')) {
            throw "GA port $Port is occupied by another process; choose SPEC_AGENT_GA_BRAIN_PORT"
        }
        # Stop only the verified GA interpreter and its venv launcher, owned by this checkout.
        Stop-Process -Id $owner.ProcessId -Force -ErrorAction SilentlyContinue
        $parent = Get-CimInstance Win32_Process -Filter "ProcessId=$($owner.ParentProcessId)"
        if ($parent -and ([string]$parent.CommandLine).Replace('/', '\').Contains($expected) -and
                ([string]$parent.CommandLine).Contains('spec_agent_brain.app:app')) {
            Stop-Process -Id $parent.ProcessId -Force -ErrorAction SilentlyContinue
        }
    }
}
$python = $env:SPEC_AGENT_GA_PYTHON
if (!$python) {
    $python = Join-Path $repo 'agent-brain/.venv-ga/Scripts/python.exe'
    if (!(Test-Path -LiteralPath $python)) {
        $uv = Get-Command uv -ErrorAction SilentlyContinue
        if (!$uv) { throw 'Install uv or set SPEC_AGENT_GA_PYTHON to the qualified Python 3.11.14 environment' }
        & $uv.Source venv --python 3.11.14 (Join-Path $repo 'agent-brain/.venv-ga')
        if ($LASTEXITCODE) { throw 'GA Python environment creation failed' }
        & $uv.Source pip install --python $python --constraint (Join-Path $repo 'agent-brain/requirements.lock') --editable (Join-Path $repo 'agent-brain')
        if ($LASTEXITCODE) { throw 'GA locked dependency installation failed' }
    }
}
if (!(Test-Path -LiteralPath $python)) { throw 'SPEC_AGENT_GA_PYTHON executable does not exist' }
& $python -c 'import sys; from langchain.agents import create_agent; from spec_agent_brain.global_assistant.execution import execution_router; assert sys.version_info[:3] == (3, 11, 14), "GA requires the qualified CPython 3.11.14 environment"'
if ($LASTEXITCODE) { throw 'GA Python preflight failed; no fallback or automatic retry' }
$env:SPEC_AGENT_INTERNAL_BROKER_URL = "http://127.0.0.1:$BackendPort/internal/v1/model-inference"
$env:SPEC_AGENT_BRAIN_INTERNAL_SECRET = $secret
$env:SPEC_AGENT_BRAIN_MODEL_MODE = 'broker'
$env:PYTHONFAULTHANDLER = '1'
$logs = Join-Path $repo 'backend/build'
New-Item -ItemType Directory -Force -Path $logs | Out-Null
$process = Start-Process -FilePath $python -ArgumentList @('-m', 'uvicorn', 'spec_agent_brain.app:app', '--app-dir', ('"' + (Join-Path $repo 'agent-brain/src') + '"'), '--host', '127.0.0.1', '--port', "$Port") -WorkingDirectory (Join-Path $repo 'agent-brain') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logs 'ga-dev-stdout.log') -RedirectStandardError (Join-Path $logs 'ga-dev-stderr.log')
$until = [DateTime]::UtcNow.AddSeconds(60)
while ([DateTime]::UtcNow -lt $until) {
    $process.Refresh()
    if ($process.HasExited) { throw 'GA Python exited; inspect backend/build/ga-dev-stderr.log (no automatic retry)' }
    try {
        $health = Invoke-RestMethod "http://127.0.0.1:$Port/health" -TimeoutSec 1
        if ($health.ready -eq $true) { Write-Host "GA Python ready on 127.0.0.1:$Port (CPython 3.11.14 / create_agent)"; exit 0 }
    } catch { }
    Start-Sleep -Milliseconds 250
}
throw 'GA Python did not become ready; inspect backend/build/ga-dev-stderr.log'
