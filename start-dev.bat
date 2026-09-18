@echo off
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"

rem ============================================================
rem  Spec Agent - one-click start for FRONTEND + BACKEND.
rem
rem  Usage:
rem    start-dev.bat                 start frontend + backend + brain (local)
rem    start-dev.bat --no-brain      skip the Python agent-brain
rem    start-dev.bat --with-brain    accepted for compatibility (default now)
rem    start-dev.bat --help
rem
rem  Ports (optional overrides):
rem    set SPEC_AGENT_BACKEND_PORT=xxxx    (default 8080, auto-advances if busy)
rem    set SPEC_AGENT_FRONTEND_PORT=xxxx   (default 5173, auto-advances if busy)
rem
rem  BACKEND_PORT is the single runtime authority: it is propagated both to
rem  the Vite proxy target and to Spring SERVER_PORT so they always agree.
rem
rem  NOTE: every string in this file is ASCII-only on purpose.  A .bat file
rem  containing multi-byte characters is decoded using the console code page
rem  and can be corrupted (or misparsed) when it is edited or re-saved.
rem ============================================================

set "WITH_BRAIN=1"
for %%A in (%*) do (
    if /i "%%~A"=="--with-brain" set "WITH_BRAIN=1"
    if /i "%%~A"=="--no-brain" set "WITH_BRAIN=0"
    if /i "%%~A"=="--help" goto :usage
    if /i "%%~A"=="-h" goto :usage
    if /i "%%~A"=="/?" goto :usage
)

if not exist "%~dp0frontend\package.json" (
    echo [ERROR] frontend\package.json not found. Run this from the repo root.
    echo.
    pause
    exit /b 1
)
if not exist "%~dp0backend\gradlew.bat" (
    echo [ERROR] backend\gradlew.bat not found. Run this from the repo root.
    echo.
    pause
    exit /b 1
)

rem ============================================================
rem  Step 1: Pick ports.
rem  Explicit port -> honour it, fail fast if occupied (no silent drift).
rem  Otherwise start at the default and advance to the next free port.
rem  Pure netstat/findstr: no PowerShell startup cost, no quoting traps.
rem ============================================================
if defined SPEC_AGENT_BACKEND_PORT (
    set "BACKEND_PORT=%SPEC_AGENT_BACKEND_PORT%"
    call :portBusy !BACKEND_PORT!
    if "!PORT_BUSY!"=="1" (
        echo [FATAL] SPEC_AGENT_BACKEND_PORT=!BACKEND_PORT! is already in use.
        echo        Free it, or unset SPEC_AGENT_BACKEND_PORT to auto-select.
        echo.
        pause
        exit /b 1
    )
    echo [1/3] Backend port  !BACKEND_PORT! [explicit]
) else (
    call :nextFreePort 8080
    set "BACKEND_PORT=!NFP_RESULT!"
    if not "!BACKEND_PORT!"=="8080" (
        echo [1/3] Backend port  !BACKEND_PORT! [8080 busy - another instance may be running]
    ) else (
        echo [1/3] Backend port  !BACKEND_PORT!
    )
)

if defined SPEC_AGENT_FRONTEND_PORT (
    set "FRONTEND_PORT=%SPEC_AGENT_FRONTEND_PORT%"
    call :portBusy !FRONTEND_PORT!
    if "!PORT_BUSY!"=="1" (
        echo [FATAL] SPEC_AGENT_FRONTEND_PORT=!FRONTEND_PORT! is already in use.
        echo        Free it, or unset SPEC_AGENT_FRONTEND_PORT to auto-select.
        echo.
        pause
        exit /b 1
    )
) else (
    call :nextFreePort 5173
    set "FRONTEND_PORT=!NFP_RESULT!"
)
echo     Frontend port !FRONTEND_PORT!

rem ============================================================
rem  Preflight: PostgreSQL.  The Spring datasource points at 5434 by
rem  default; without it bootRun still starts but every request fails.
rem  Docker Desktop port mappings do not always appear in netstat, so a
rem  missing LISTENING socket is only a warning when docker agrees.
rem ============================================================
if defined SPEC_AGENT_DB_PORT (set "PG_PORT=%SPEC_AGENT_DB_PORT%") else (set "PG_PORT=5434")
set "PG_OK=1"
call :portBusy %PG_PORT%
if "!PORT_BUSY!"=="0" (
    docker ps --filter "name=spec-agent-postgres" --format "{{.Status}}" 2>nul | findstr /I /C:"Up" >nul
    if errorlevel 1 set "PG_OK=0"
)
if "!PG_OK!"=="0" (
    echo.
    echo [WARN] No PostgreSQL on port !PG_PORT! and no running spec-agent-postgres container.
    echo        The backend will start but fail to serve requests.
    echo        Fix with:  docker compose up -d
    echo                   docker start spec-agent-postgres
    echo.
)

rem agent-brain runs LOCALLY (venv + uvicorn) and Step 2 starts it by default;
rem --no-brain skips it. Without a brain, AI runs stay QUEUED.

if not exist "%~dp0frontend\node_modules\." (
    echo [WARN] frontend\node_modules is missing. Run "npm install" in frontend first.
)

rem ============================================================
rem  Step 2 (default): Python agent-brain on the HOST (no Docker).
rem  First run creates agent-brain\.venv and installs the package
rem  editable; later starts reuse it. The brain reaches the backend
rem  broker over plain localhost, so the broker URL must carry the
rem  SAME backend port picked in Step 1.
rem ============================================================
set "BRAIN_PORT=8100"
set "BRAIN_STATUS=skipped"
if "!WITH_BRAIN!"=="1" (
    if defined SPEC_AGENT_BRAIN_PORT (set "BRAIN_PORT=!SPEC_AGENT_BRAIN_PORT!") else (set "BRAIN_PORT=8100")
    call :portBusy !BRAIN_PORT!
    if "!PORT_BUSY!"=="1" (
        echo [2/3] Brain port !BRAIN_PORT! already serving - leaving it alone.
        set "BRAIN_STATUS=already-up"
    ) else (
        where python >nul 2>&1
        if errorlevel 1 (
            echo [WARN] python not found on PATH - cannot start agent-brain.
            echo        AI runs will stay QUEUED until a brain serves port !BRAIN_PORT!.
            set "BRAIN_STATUS=no-python"
        ) else (
            if not exist "%~dp0agent-brain\.venv\Scripts\python.exe" (
                echo [2/3] Creating agent-brain venv [first run only, a minute or two] ...
                python -m venv "%~dp0agent-brain\.venv"
                "%~dp0agent-brain\.venv\Scripts\python.exe" -m pip install --quiet -e "%~dp0agent-brain"
            )
            echo [2/3] Starting agent-brain locally [broker -^> backend !BACKEND_PORT!] ...
            start "Spec Agent Brain" /d "%~dp0agent-brain" cmd /k "set SPEC_AGENT_INTERNAL_BROKER_URL=http://localhost:!BACKEND_PORT!/internal/v1/model-inference && set SPEC_AGENT_BRAIN_INTERNAL_SECRET=dev-internal-secret && set SPEC_AGENT_BRAIN_MODEL_MODE=broker && .venv\Scripts\python.exe -m uvicorn spec_agent_brain.app:app --host 0.0.0.0 --port !BRAIN_PORT!"
            set "BRAIN_STATUS=starting"
        )
    )
) else (
    echo [2/3] agent-brain skipped [--no-brain]
)

rem ============================================================
rem  Step 3: Backend then frontend, each in its own console.
rem  start /d sets the working directory, so no nested quotes are needed
rem  inside the command string (the old script's quoting trap).
rem ============================================================
echo [3/3] Launching backend and frontend ...

rem  SERVER__PORT (double underscore) is also bound to server.port by Spring's
rem  relaxed binding and is injected by some IDE/agent shells; clear it so the
rem  port chosen here always wins.
start "Spec Agent Backend" /d "%~dp0backend" cmd /k "set SERVER_PORT=!BACKEND_PORT! && set SERVER__PORT= && call gradlew.bat bootRun"
start "Spec Agent Frontend" /d "%~dp0frontend" cmd /k "set VITE_API_PROXY_TARGET=http://localhost:!BACKEND_PORT! && npm run dev -- --port !FRONTEND_PORT!"

rem ============================================================
rem  Summary
rem ============================================================
>"%~dp0start-dev.log" echo %DATE% %TIME% backend=!BACKEND_PORT! frontend=!FRONTEND_PORT! brain=!BRAIN_STATUS!

echo.
echo ------------------------------------------------------------
echo   Frontend      http://localhost:!FRONTEND_PORT!
echo   Backend       http://localhost:!BACKEND_PORT!
echo   Health        http://localhost:!BACKEND_PORT!/actuator/health
echo   Brain         !BRAIN_STATUS!   [http://localhost:!BRAIN_PORT!/health]
echo ------------------------------------------------------------
echo.
echo  Three new consoles were opened.  The backend compiles on first
echo  start and takes a while - wait for "Started SpecAgentApplication".
echo  Without the brain, AI runs stay QUEUED.
echo.
echo  Press any key to close this window.
pause >nul
exit /b 0

rem ============================================================
rem  Subroutines
rem ============================================================

:portBusy
rem %1 = port -> PORT_BUSY=1 when something is LISTENING on it
set "PORT_BUSY=0"
netstat -ano -p tcp | findstr /R /C:":%1 " | findstr /C:"LISTENING" >nul 2>&1
if not errorlevel 1 set "PORT_BUSY=1"
exit /b 0

:nextFreePort
rem %1 = preferred port -> NFP_RESULT = first free port at or above it
set /a "NFP_P=%1"
:nextFreePortLoop
call :portBusy !NFP_P!
if "!PORT_BUSY!"=="1" (
    set /a "NFP_P+=1"
    goto :nextFreePortLoop
)
set "NFP_RESULT=!NFP_P!"
exit /b 0

:usage
echo.
echo Spec Agent - start frontend + backend
echo.
echo   start-dev.bat                 start frontend + backend + brain (local)
echo   start-dev.bat --no-brain      skip the Python agent-brain
echo.
echo Environment overrides:
echo   SPEC_AGENT_BACKEND_PORT    backend port   [default 8080]
echo   SPEC_AGENT_FRONTEND_PORT   frontend port  [default 5173]
echo   SPEC_AGENT_DB_PORT         postgres port  [default 5434]
echo   SPEC_AGENT_BRAIN_PORT      brain port     [default 8100]
echo.
exit /b 0
