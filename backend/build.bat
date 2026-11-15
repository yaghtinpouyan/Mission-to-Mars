@echo off
rem Compile the backend (needs a JDK 17+; check with: javac -version)
cd /d "%~dp0\.."
if not exist backend\out mkdir backend\out
dir /s /b backend\src\main\java\marswalk\*.java backend\src\test\java\marswalk\*.java > backend\out\sources.txt
javac -Xlint:all -d backend\out @backend\out\sources.txt || exit /b 1
echo Built into backend\out
