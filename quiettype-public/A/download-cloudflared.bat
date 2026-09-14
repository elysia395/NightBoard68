@echo off
rem Download cloudflared.exe manually (if auto-download fails)
cd /d "%~dp0"
where python >nul 2>nul
if %errorlevel%==0 (
  python -c "import run_tunnel; run_tunnel.ensure_cloudflared()"
) else (
  py -3 -c "import run_tunnel; run_tunnel.ensure_cloudflared()"
)
pause
