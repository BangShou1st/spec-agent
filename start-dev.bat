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
rem  PREVIOUS INSTANCES: only processes that verifiably belong to THIS
rem  repository (their command line references the repo root or the
rem  agent-brain module) are restarted. A port held by any other
rem  application is left alone: the picker moves to the next free port,
rem  or an explicit port request fails with a clear message.
rem
rem  INTERNAL SECRET: the script generates (once) and reuses a per-install
rem  random internal token in data\internal-secret.txt and passes the SAME
rem  value to the backend and the brain. The backend binds 127.0.0.1 and
rem  the brain listens on 127.0.0.1 - the stack is loopback-only.
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

rem Normalized repo root (no trailing backslash) used as process identity.
set "REPO_ROOT=%~dp0"
if "!REPO_ROOT:~-1!"=="\" set "REPO_ROOT=!REPO_ROOT:~0,-1!"

rem ============================================================
rem  Step 0: restart previous Spec Agent instances ONLY.
rem  For each service port, listeners whose command line belongs to
rem  this repo are stopped; foreign processes are reported and kept.
rem ============================================================
if defined SPEC_AGENT_BACKEND_PORT (set "PREF_BACKEND=%SPEC_AGENT_BACKEND_PORT%") else (set "PREF_BACKEND=8080")
if defined SPEC_AGENT_FRONTEND_PORT (set "PREF_FRONTEND=%SPEC_AGENT_FRONTEND_PORT%") else (set "PREF_FRONTEND=5173")
if defined SPEC_AGENT_BRAIN_PORT (set "PREF_BRAIN=%SPEC_AGENT_BRAIN_PORT%") else (set "PREF_BRAIN=8100")

echo [0/3] Checking previous instances on ports !PREF_BACKEND! / !PREF_FRONTEND! / !PREF_BRAIN! ...
call :stopOursOnly !PREF_BACKEND!
call :stopOursOnly !PREF_FRONTEND!
call :stopOursOnly !PREF_BRAIN!

rem ============================================================
rem  Step 1: Pick ports.
rem  Explicit port -> honour it, fail fast if still occupied by a
rem  foreign process (no silent drift, no killing other apps).
rem  Otherwise start at the default and advance past busy ports.
rem ============================================================
if defined SPEC_AGENT_BACKEND_PORT (
    set "BACKEND_PORT=%SPEC_AGENT_BACKEND_PORT%"
    call :portBusy !BACKEND_PORT!
    if "!PORT_BUSY!"=="1" (
        echo [FATAL] SPEC_AGENT_BACKEND_PORT=!BACKEND_PORT! is held by another application
        echo        that does not belong to Spec Agent. Free it, or unset
        echo        SPEC_AGENT_BACKEND_PORT to auto-select a free port.
        echo.
        pause
        exit /b 1
    )
    echo [1/3] Backend port  !BACKEND_PORT! [explicit]
) else (
    call :nextFreePort 8080
    set "BACKEND_PORT=!NFP_RESULT!"
    if not "!BACKEND_PORT!"=="8080" (
        echo [1/3] Backend port  !BACKEND_PORT! [8080 busy - kept the other application running]
    ) else (
        echo [1/3] Backend port  !BACKEND_PORT!
    )
)

if defined SPEC_AGENT_FRONTEND_PORT (
    set "FRONTEND_PORT=%SPEC_AGENT_FRONTEND_PORT%"
    call :portBusy !FRONTEND_PORT!
    if "!PORT_BUSY!"=="1" (
        echo [FATAL] SPEC_AGENT_FRONTEND_PORT=!FRONTEND_PORT! is held by another application
        echo        that does not belong to Spec Agent. Free it, or unset
        echo        SPEC_AGENT_FRONTEND_PORT to auto-select a free port.
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

rem ============================================================
rem  Internal secret: per-install random token shared by backend and
rem  brain. Generated once, persisted in data\internal-secret.txt.
rem ============================================================
set "SECRET_FILE=%~dp0data\internal-secret.txt"
if not exist "!SECRET_FILE!" (
    echo [0/3] Generating per-install internal secret ...
    call :ensureDir "%~dp0data"
    set "GEN_SECRET="
    for /l %%I in (1,1,64) do call :appendHexDigit
    <nul set /p="!GEN_SECRET!" > "!SECRET_FILE!"
)
set "INTERNAL_SECRET="
set /p INTERNAL_SECRET=<"!SECRET_FILE!"
if "!INTERNAL_SECRET!"=="" (
    echo [FATAL] Internal secret file !SECRET_FILE! is empty or unreadable.
    echo        Delete the file to regenerate it.
    echo.
    pause
    exit /b 1
)

rem agent-brain runs LOCALLY (venv + uvicorn) and Step 2 starts it by default;
rem --no-brain skips it. Without a brain, AI runs stay QUEUED.

if not exist "%~dp0frontend\node_modules\." (
    echo [WARN] frontend\node_modules is missing. Run "npm install" in frontend first.
)

rem ============================================================
rem  Step 2 (default): Python agent-brain on the HOST (no Docker),
rem  bound to 127.0.0.1 (loopback only). First run creates
rem  agent-brain\.venv and installs the package editable; later starts
rem  reuse it. The brain reaches the backend broker over plain
rem  localhost, so the broker URL must carry the SAME backend port
rem  picked in Step 1, and the internal secret must match the backend.
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
            echo [2/3] Starting agent-brain locally [broker -^> backend !BACKEND_PORT!, loopback only] ...
            start "Spec Agent Brain" /d "%~dp0agent-brain" cmd /k "set "SPEC_AGENT_INTERNAL_BROKER_URL=http://localhost:!BACKEND_PORT!/internal/v1/model-inference" && set "SPEC_AGENT_BRAIN_INTERNAL_SECRET=!INTERNAL_SECRET!" && set "SPEC_AGENT_BRAIN_MODEL_MODE=broker" && .venv\Scripts\python.exe -m uvicorn spec_agent_brain.app:app --host 127.0.0.1 --port !BRAIN_PORT!"
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
rem  The backend binds 127.0.0.1 by default (server.address) and receives the
rem  SAME per-install internal secret as the brain.
start "Spec Agent Backend" /d "%~dp0backend" cmd /k "set SERVER_PORT=!BACKEND_PORT! && set SERVER__PORT= && set SPEC_AGENT_BRAIN_INTERNAL_SECRET=!INTERNAL_SECRET! && call gradlew.bat bootRun"
start "Spec Agent Frontend" /d "%~dp0frontend" cmd /k "set VITE_API_PROXY_TARGET=http://localhost:!BACKEND_PORT! && npm run dev -- --port !FRONTEND_PORT!"

rem ============================================================
rem  Summary
rem ============================================================
>"%~dp0start-dev.log" echo %DATE% %TIME% backend=!BACKEND_PORT! frontend=!FRONTEND_PORT! brain=!BRAIN_STATUS!

echo.
echo ------------------------------------------------------------
echo   Frontend      http://localhost:!FRONTEND_PORT!
echo   Backend       http://localhost:!BACKEND_PORT!   [loopback only]
echo   Health        http://localhost:!BACKEND_PORT!/actuator/health
echo   Brain         !BRAIN_STATUS!   [http://127.0.0.1:!BRAIN_PORT!/health]
echo   Internal secret  data\internal-secret.txt [per-install, keep it private]
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

:stopOursOnly
rem %1 = port -> stop every LISTENING process on it that verifiably
rem belongs to this repository (command line contains the repo root or
rem the agent-brain module marker). Foreign listeners are reported and
rem left running.
for /f "tokens=5" %%P in ('netstat -ano -p tcp ^| findstr /R /C:":%1 " ^| findstr /C:"LISTENING"') do (
    call :identityKill %%P %1
)
exit /b 0

:identityKill
rem %1 = PID, %2 = port
set "KILL_PID=%~1"
set "KILL_PORT=%~2"
set "CMDLINE="
for /f "usebackq delims=" %%L in (`powershell -NoProfile -Command "(Get-CimInstance Win32_Process -Filter 'ProcessId = %KILL_PID%').CommandLine" 2^>nul`) do set "CMDLINE=%%L"
rem 归一化分隔符后再匹配,避免 / 与 \ 形态差异漏判
set "CHECKLINE=!CMDLINE:/=\!"
set "IS_OURS=0"
if not "!CHECKLINE!"=="" (
    echo !CHECKLINE! | findstr /I /C:"!REPO_ROOT!" >nul 2>&1
    if not errorlevel 1 set "IS_OURS=1"
    echo !CHECKLINE! | findstr /I /C:"spec_agent_brain" >nul 2>&1
    if not errorlevel 1 set "IS_OURS=1"
)
if "!IS_OURS!"=="1" (
    echo        Restarting previous Spec Agent instance: PID !KILL_PID! on port !KILL_PORT!
    taskkill /F /T /PID !KILL_PID! >nul 2>&1
    call :waitForPortFree !KILL_PORT!
) else (
    echo        Port !KILL_PORT! is held by another application [PID !KILL_PID!] - leaving it alone.
    echo        Command line: !CMDLINE!
)
exit /b 0

:waitForPortFree
rem %1 = port -> block until nothing LISTENING on it (max ~10s), then give
rem up with a warning. Prevents the port picker from drifting after a kill.
set /a WFP_COUNT=0
:waitForPortFreeLoop
call :portBusy %1
if "!PORT_BUSY!"=="0" exit /b 0
set /a WFP_COUNT+=1
if !WFP_COUNT! geq 10 (
    echo [WARN] Port %1 still busy after 10s - continuing anyway.
    exit /b 0
)
ping -n 2 127.0.0.1 >nul
goto waitForPortFreeLoop

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

:ensureDir
if not exist "%~1" mkdir "%~1"
exit /b 0

:appendHexDigit
rem Appends one random hex char to GEN_SECRET (64 chars total).
set /a "RND=%RANDOM% %% 16"
set "HEXDIGIT=0123456789abcdef"
set "CHAR=!HEXDIGIT:~%RND%,1!"
set "GEN_SECRET=!GEN_SECRET!!CHAR!"
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
