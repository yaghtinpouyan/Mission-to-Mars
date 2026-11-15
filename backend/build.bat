@echo off
rem Compile the backend (needs a JDK 17+; check with: javac -version)
cd /d "%~dp0\.."
if errorlevel 1 exit /b 1
if not exist backend\out mkdir backend\out
if errorlevel 1 exit /b 1
setlocal EnableDelayedExpansion
set "SOURCES="
for %%f in (backend\src\main\java\marswalk\*.java backend\src\test\java\marswalk\*.java) do set SOURCES=!SOURCES! "%%f"
if not defined SOURCES (
  echo No Java source files found.
  exit /b 1
)
javac -Xlint:all -d backend\out !SOURCES!
if errorlevel 1 exit /b 1
echo Built into backend\out
