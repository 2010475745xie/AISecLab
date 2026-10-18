@echo off
chcp 65001 >nul 2>&1
title AISecLab Backend - STOP
cd /d "%~dp0"

rem  Double-click to stop the backend started by start-backend.bat
rem  or by  tools\start-backend.py --inline  (PID comes from the pid file).

set "VENV_PY=%USERPROFILE%\.workbuddy\binaries\python\envs\default\Scripts\python.exe"
if not exist "%VENV_PY%" set "VENV_PY=python"

"%VENV_PY%" "%~dp0start-backend.py" --stop
echo.
pause >nul
