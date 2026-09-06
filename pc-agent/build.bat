@echo off
rem NightBoardAgent build script: uses the .NET Framework compiler shipped with Windows
cd /d "%~dp0"
setlocal
set CSC=%WINDIR%\Microsoft.NET\Framework64\v4.0.30319\csc.exe
if not exist "%CSC%" set CSC=%WINDIR%\Microsoft.NET\Framework\v4.0.30319\csc.exe
if not exist "%CSC%" (
    echo [ERROR] csc.exe not found
    exit /b 1
)
"%CSC%" /nologo /optimize+ /platform:anycpu /out:NightBoardAgent.exe NightBoardAgent.cs
if errorlevel 1 (
    echo [ERROR] build failed
    exit /b 1
)
echo Build OK: NightBoardAgent.exe (portable, no install needed)
endlocal
