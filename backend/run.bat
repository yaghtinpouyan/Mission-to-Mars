@echo off
setlocal
rem Build and start the Marswalk Planner, then open http://localhost:8080
cd /d "%~dp0.."
if errorlevel 1 exit /b 1
call backend\build.bat
if errorlevel 1 exit /b 1
java -cp backend\out marswalk.Main %*
