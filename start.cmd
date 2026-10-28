@echo off
rem on-sil start
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start.ps1" %*
set ONSIL_EXIT=%ERRORLEVEL%
if not defined ONSIL_NOPAUSE pause
exit /b %ONSIL_EXIT%
