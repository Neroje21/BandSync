@echo off
cd /d "%~dp0"
node scripts\build-apk.mjs
echo.
pause
