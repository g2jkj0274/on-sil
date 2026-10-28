@echo off
rem on-sil stop
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\stop.ps1" %*
set ONSIL_EXIT=%ERRORLEVEL%
if not defined ONSIL_NOPAUSE pause
exit /b %ONSIL_EXIT%
