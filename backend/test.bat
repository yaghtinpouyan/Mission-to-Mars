@echo off
cd /d "%~dp0\.."
call backend\build.bat || exit /b 1
java -Xmx1500m -cp backend\out marswalk.TestMain
