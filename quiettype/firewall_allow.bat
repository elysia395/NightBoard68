@echo off
rem Run this file AS ADMINISTRATOR once to allow inbound TCP 8567
rem (needed when the phone cannot reach the PC on the LAN)
net session >nul 2>&1
if errorlevel 1 (
  echo Please right-click this file and choose "Run as administrator".
  pause
  exit /b 1
)
netsh advfirewall firewall delete rule name="QuietType 8567" >nul 2>nul
netsh advfirewall firewall add rule name="QuietType 8567" dir=in action=allow protocol=TCP localport=8567
echo OK: TCP port 8567 is now allowed inbound.
pause
