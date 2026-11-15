@echo off
rem Build and start the Marswalk Planner, then open http://localhost:8080
cd /d "%~dp0\.."
call backend\build.bat || exit /b 1
java -cp backend\out marswalk.Main %*
