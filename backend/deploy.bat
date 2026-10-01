@echo off
cd /d "%~dp0"
where python >nul 2>nul && python deploy.py || (where py >nul 2>nul && py deploy.py) || (echo 未找到 Python，请先安装 Python 3.11+ 并加入 PATH & pause & exit /b 1)
pause
