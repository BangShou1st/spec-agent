param([string]$CandidateDirectory)
$ErrorActionPreference = 'Stop'
$taskBrainRoot = Split-Path $PSScriptRoot -Parent
$taskRepoRoot = Split-Path $taskBrainRoot -Parent
if (-not $CandidateDirectory) { $CandidateDirectory = Join-Path $taskRepoRoot 'backend/build/ga-validation-venv' }
$taskCandidate = [IO.Path]::GetFullPath($CandidateDirectory)
$taskOriginal = [IO.Path]::GetFullPath((Join-Path $taskBrainRoot '.venv'))
if ($taskCandidate -eq $taskOriginal -or $taskCandidate.StartsWith($taskOriginal + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'The original .venv must not be replaced.'
}
$taskRuntimeRoot = Join-Path $taskRepoRoot 'backend/build/ga-python-runtime'
$taskBasePython = Join-Path $taskRuntimeRoot 'cpython-3.11.14-windows-x86_64-none/python.exe'
if (-not (Test-Path -LiteralPath $taskBasePython)) {
    $taskUvVersion = (& uv --version)
    if ($taskUvVersion -notmatch '^uv 0\.12\.2(?:\s|$)') { throw 'Use the pinned uv 0.12.2 catalog/build; do not silently substitute another build.' }
    & uv python install 3.11.14 --install-dir $taskRuntimeRoot --no-bin --no-registry
    if ($LASTEXITCODE -ne 0) { throw 'Candidate interpreter installation failed.' }
}
$taskPython = Join-Path $taskCandidate 'Scripts/python.exe'
if (-not (Test-Path -LiteralPath $taskCandidate)) {
    & uv venv $taskCandidate --python $taskBasePython
    if ($LASTEXITCODE -ne 0) { throw 'Candidate venv creation failed.' }
    & uv pip install --python $taskPython -c (Join-Path $taskBrainRoot 'requirements.lock') -e "$taskBrainRoot[dev]"
    if ($LASTEXITCODE -ne 0) { throw 'Candidate dependency installation failed.' }
}
if (-not (Test-Path -LiteralPath $taskPython)) { throw 'Existing candidate directory is not a venv; it was left unchanged.' }
& $taskPython (Join-Path $PSScriptRoot 'runtime_manifest.py') --output (Join-Path $taskRepoRoot 'docs/v2/evidence/GLOBAL_ASSISTANT_CANDIDATE_RUNTIME_MANIFEST.json')
if ($LASTEXITCODE -ne 0) { throw 'Candidate does not match the pinned interpreter/dependency lock; no repair or retry performed.' }
& uv pip check --python $taskPython
if ($LASTEXITCODE -ne 0) { throw 'Candidate dependency check failed.' }
Write-Output "Candidate verified: $taskPython"
