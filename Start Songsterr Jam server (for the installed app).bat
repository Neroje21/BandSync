@echo off
cd /d "%~dp0"
cd server
call npm run public
echo.
pause
