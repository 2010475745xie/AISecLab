@echo off
chcp 65001 >nul 2>&1
title AISecLab Backend
cd /d "%~dp0."
"%~dp0aiseclab-backend\aiseclab-backend.exe"
echo.
echo [AISecLab backend stopped] Press any key to close...
pause >nul
