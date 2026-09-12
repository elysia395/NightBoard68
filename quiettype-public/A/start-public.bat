@echo off
rem QuietType PUBLIC tunnel (Windows)
rem Finds a real Python (avoids the Microsoft Store alias), then starts the tunnel.
cd /d "%~dp0"
set PY=

rem 1) look for real python.exe at common install locations first
if exist "D:\Python\python.exe" set PY=D:\Python\python.exe
if not defined PY if exist "C:\Python\python.exe" set PY=C:\Python\python.exe
if not defined PY if exist "%LOCALAPPDATA%\Programs\Python\Python313\python.exe" set PY=%LOCALAPPDATA%\Programs\Python\Python313\python.exe
if not defined PY if exist "%LOCALAPPDATA%\Programs\Python\Python312\python.exe" set PY=%LOCALAPPDATA%\Programs\Python\Python312\python.exe
if not defined PY if exist "%LOCALAPPDATA%\Programs\Python\Python311\python.exe" set PY=%LOCALAPPDATA%\Programs\Python\Python311\python.exe

rem 2) fall back to PATH lookup
if not defined PY (
  where python >nul 2>nul && set PY=python
)
if not defined PY (
  where py >nul 2>nul && set PY=py
)

if not defined PY (
  echo [ERROR] Python 3 not found.
  echo Install from https://www.python.org/downloads/ and tick "Add python.exe to PATH".
  pause
  exit /b 1
)

echo ============================================================
echo  QuietType PUBLIC tunnel  (using: %PY%)
echo  Step 1: find / download cloudflared (first run, big file)...
echo ============================================================
"%PY%" run_tunnel.py %*
echo.
echo (window closed = service stopped)
pause
