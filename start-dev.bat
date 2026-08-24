@echo off
setlocal
cd /d "%~dp0"

rem ============================================================
rem  Spec Agent - one-click start for frontend + backend
rem  Starts Vite and Spring Boot in new windows. If a preferred port is
rem  already occupied, the next available port is selected; existing
rem  processes are never stopped by this script.
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

echo [1/3] Selecting available ports (backend 8080, frontend 5173) ...
for /f "usebackq tokens=*" %%P in (`powershell -NoProfile -Command "$p=8080; while (Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue) { $p++ }; $p"`) do set "BACKEND_PORT=%%P"
for /f "usebackq tokens=*" %%P in (`powershell -NoProfile -Command "$p=5173; while (Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue) { $p++ }; $p"`) do set "FRONTEND_PORT=%%P"

echo [2/3] Starting frontend (Vite on %FRONTEND_PORT%, proxying backend %BACKEND_PORT%) ...
start "Spec Agent Frontend" cmd /k "cd /d %~dp0frontend && set VITE_API_PROXY_TARGET=http://localhost:%BACKEND_PORT% && npm run dev -- --port %FRONTEND_PORT%"

echo [3/3] Starting backend (Spring Boot on %BACKEND_PORT%) ...
start "Spec Agent Backend" cmd /k "cd /d %~dp0backend && set SERVER_PORT=%BACKEND_PORT% && call gradlew.bat bootRun"

echo.
echo Done!
echo   Frontend : http://localhost:%FRONTEND_PORT%
echo   Backend  : http://localhost:%BACKEND_PORT%
echo   Health   : http://localhost:%BACKEND_PORT%/actuator/health
echo Note: PostgreSQL must be running on localhost:5434.
echo       If missing: docker start spec-agent-postgres   (or: docker compose up -d)
echo Tip : rerun this script to start another pair without stopping existing servers.
timeout /t 3 /nobreak >nul
exit /b 0
