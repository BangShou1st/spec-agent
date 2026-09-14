@echo off
setlocal
cd /d "%~dp0"

rem ============================================================
rem  Spec Agent - one-click start for frontend + backend + brain
rem  BACKEND_PORT is the single runtime authority: it is propagated
rem  to the frontend proxy, Spring SERVER_PORT, and the brain's
rem  broker URL so they always agree.
rem
rem  One active Spec Agent dev runtime is supported.  If one is
rem  already running, start-dev exits without modifying it.
rem ============================================================

if not exist "%~dp0frontend\package.json" (
    echo [ERROR] frontend\package.json not found. Run this from the repo root.
    pause
    exit /b 1
)
if not exist "%~dp0backend\gradlew.bat" (
    echo [ERROR] backend\gradlew.bat not found. Run this from the repo root.
    pause
    exit /b 1
)

rem ============================================================
rem  RERUN DETECTION
rem  Detect existing Spec Agent processes by their command line
rem  containing the repo path (cwd of this script).  Only Java
rem  (gradlew bootRun) and Node (vite/npm dev) are checked.
rem  We do NOT kill or stop anything — just fail-fast.
rem ============================================================
set "REPO_PATH=%~dp0"
set "EXISTING_RUNTIME=0"

for /f "usebackq tokens=*" %%R in (`powershell -NoProfile -Command "Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -and $_.CommandLine -like '*%REPO_PATH%*' -and ($_.Name -eq 'java.exe' -or $_.Name -eq 'node.exe') } | ForEach-Object { $_.Name + ' (PID ' + $_.ProcessId + ')' }"`) do (
    set "EXISTING_RUNTIME=1"
    set "EXISTING_PROCESS=%%R"
)

if "%EXISTING_RUNTIME%"=="1" (
    echo.
    echo [ERROR] A Spec Agent development runtime is already active.
    echo.
    echo   Detected process: %EXISTING_PROCESS%
    echo.
    echo   Stop the existing Spec Agent frontend/backend before starting another one.
    echo   Do NOT stop PostgreSQL or the agent-brain Docker container manually.
    echo.
    pause
    exit /b 1
)

rem ============================================================
rem  Step 1: Select available ports
rem  BACKEND_PORT is the single authority for all runtime wiring.
rem  If an explicit SPEC_AGENT_BACKEND_PORT is set, honour it
rem  (fail-fast if occupied).  Otherwise auto-select from 8080.
rem ============================================================
if defined SPEC_AGENT_BACKEND_PORT (
    set "BACKEND_PORT=%SPEC_AGENT_BACKEND_PORT%"
    powershell -NoProfile -Command "if (Get-NetTCPConnection -LocalPort %SPEC_AGENT_BACKEND_PORT% -State Listen -ErrorAction SilentlyContinue) { Write-Host '[FATAL] Port %SPEC_AGENT_BACKEND_PORT% is occupied. Another process is using it.'; exit 1 }"
    if errorlevel 1 (
        echo [FATAL] Explicit SPEC_AGENT_BACKEND_PORT=%SPEC_AGENT_BACKEND_PORT% is occupied.
        pause
        exit /b 1
    )
    echo [1/4] Using explicit backend port %BACKEND_PORT% ...
) else (
    echo [1/4] Selecting available ports (backend from 8080, frontend from 5173) ...
    for /f "usebackq tokens=*" %%P in (`powershell -NoProfile -Command "$p=8080; while (Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue) { $p++ }; $p"`) do set "BACKEND_PORT=%%P"
)
for /f "usebackq tokens=*" %%P in (`powershell -NoProfile -Command "$p=5173; while (Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue) { $p++ }; $p"`) do set "FRONTEND_PORT=%%P"

rem ============================================================
rem  Step 2: Start/recreate the Python agent-brain container
rem  The brain's broker URL is derived from BACKEND_PORT so it
rem  always points to the correct Spring backend.  docker compose
rem  --build refreshes the image if source changed; --force-recreate
rem  ensures the container picks up the new BROKER_URL env.
rem  PostgreSQL is left untouched.
rem ============================================================
set "BRAIN_BROKER_URL=http://host.docker.internal:%BACKEND_PORT%/internal/v1/model-inference"

echo [2/4] Starting agent-brain (broker -> %BACKEND_PORT%) ...
docker compose up -d --build --force-recreate agent-brain
if errorlevel 1 (
    echo.
    echo [WARN] agent-brain failed to start.  Project Agent will be unavailable.
    echo        Check: docker compose logs agent-brain
    set "BRAIN_STATUS=FAILED"
) else (
    set "BRAIN_STATUS=OK"
)

rem ============================================================
rem  Step 3: Start frontend (Vite)
rem ============================================================
echo [3/4] Starting frontend (Vite on %FRONTEND_PORT%, proxying backend %BACKEND_PORT%) ...
start "Spec Agent Frontend" cmd /k "cd /d %~dp0frontend && set VITE_API_PROXY_TARGET=http://localhost:%BACKEND_PORT% && npm run dev -- --port %FRONTEND_PORT%"

rem ============================================================
rem  Step 4: Start backend (Spring Boot)
rem ============================================================
echo [4/4] Starting backend (Spring Boot on %BACKEND_PORT%) ...
start "Spec Agent Backend" cmd /k "cd /d %~dp0backend && set SERVER_PORT=%BACKEND_PORT% && call gradlew.bat bootRun"

echo.
if "%BRAIN_STATUS%"=="OK" (
    echo Done!  All services started.
) else (
    echo Done!  Frontend + backend started.  Brain is DOWN (see warnings above).
)
echo.
echo   Frontend     : http://localhost:%FRONTEND_PORT%
echo   Backend      : http://localhost:%BACKEND_PORT%
echo   Health       : http://localhost:%BACKEND_PORT%/actuator/health
echo   Brain        : http://localhost:8100/health
echo   Brain broker : %BRAIN_BROKER_URL%
echo.
echo Note: PostgreSQL must be running on localhost:5434.
echo       If missing: docker start spec-agent-postgres   (or: docker compose up -d)
echo.
echo One active Spec Agent dev runtime is supported.
echo If one is already running, start-dev exits without modifying it.
timeout /t 3 /nobreak >nul
exit /b 0
