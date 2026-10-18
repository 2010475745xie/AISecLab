@echo off
chcp 65001 >nul 2>&1
title AISecLab Backend (source mode)
cd /d "%~dp0"

rem ---------------------------------------------------------------------------
rem  Double-click this file to run the AISecLab backend from SOURCE.
rem  Keep this window OPEN while testing; close it or press Ctrl+C to stop.
rem
rem  Why not backend\run.bat ?  That one launches aiseclab-backend.exe, which is
rem  an OLD FROZEN BUILD. This script runs tools\start-backend.py instead, which
rem  handles the three things that are easy to get wrong:
rem    1) uses the venv interpreter  [base python has no uvicorn/fastapi]
rem    2) strips proxy env vars    [otherwise the judge call is eaten by a 502]
rem    3) sets cwd to backend\dist [otherwise .env is not found]
rem ---------------------------------------------------------------------------

set "VENV_PY=%USERPROFILE%\.workbuddy\binaries\python\envs\default\Scripts\python.exe"
set "STARTER=%~dp0tools\start-backend.py"

if not exist "%VENV_PY%" (
    echo [ERROR] venv interpreter not found:
    echo         %VENV_PY%
    echo.
    echo Fix: run  backend\deploy.bat  once to create the venv.
    echo.
    pause >nul
    exit /b 1
)

if not exist "%STARTER%" (
    echo [ERROR] starter script not found:
    echo         %STARTER%
    echo.
    pause >nul
    exit /b 1
)

rem --status exits 0 when /health is already reachable
"%VENV_PY%" "%STARTER%" --status >nul 2>&1
if not errorlevel 1 (
    echo Backend is ALREADY running. Nothing to do.
    echo.
    echo   local   : http://127.0.0.1:8000
    echo   on phone: http://THIS-PC-LAN-IP:8000
    echo.
    echo Stop it with:  tools\stop-backend.bat   or Ctrl+C in the window that started it.
    echo.
    pause >nul
    exit /b 0
)

echo Starting AISecLab backend from source ...
echo   Keep this window OPEN while testing. Close it or press Ctrl+C to stop.
echo.

"%VENV_PY%" "%STARTER%" --inline

echo.
echo [AISecLab backend stopped] Press any key to close ...
pause >nul
