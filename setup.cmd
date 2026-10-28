@echo off
rem on-sil setup
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\setup.ps1" %*
set ONSIL_EXIT=%ERRORLEVEL%
if not defined ONSIL_NOPAUSE pause
exit /b %ONSIL_EXIT%
