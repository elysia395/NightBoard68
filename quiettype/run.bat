@echo off
rem QuietType host launcher
cd /d "%~dp0"
where python >nul 2>nul
if %errorlevel%==0 (
  python server\quiettype_server.py %*
) else (
  py -3 server\quiettype_server.py %*
)
pause
