@echo off
setlocal
set "WIZARD=%~dp0mafteach-installer\Start-MafteachInstaller.ps1"

if not exist "%WIZARD%" (
  echo.
  echo ERROR: Mafteach installer wizard was not found:
  echo %WIZARD%
  echo.
  pause
  exit /b 1
)

start "Mafteach Installer" powershell.exe -NoProfile -ExecutionPolicy Bypass -STA -WindowStyle Hidden -File "%WIZARD%" -Port 0
if errorlevel 1 (
  echo.
  echo ERROR: The installer wizard could not be started.
  echo.
  pause
  exit /b 1
)

exit /b 0
