@echo off
REM Module B - shared compute node (phase 1).
REM Options are passed straight through, e.g.:
REM   run.bat --port 8100 --tier 3
REM   run.bat --port 8100 --tier 3 --backend llama --llama-url http://127.0.0.1:8080/v1
setlocal
cd /d "%~dp0"
python -m mb.cli %*
endlocal
