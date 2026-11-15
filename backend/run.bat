@echo off
setlocal

set "FRONTEND_DIR=%~dp0..\frontend"
set "SOURCE_FILE=%~dp0StaticFileServer.java"
set "BUILD_DIR=%TEMP%\marswalk-%RANDOM%-%RANDOM%"

if not exist "%FRONTEND_DIR%\index.html" (
  echo Could not find the frontend at "%FRONTEND_DIR%".
  exit /b 1
)
if not exist "%SOURCE_FILE%" (
  echo Could not find the server source at "%SOURCE_FILE%".
  exit /b 1
)

mkdir "%BUILD_DIR%" >nul 2>nul
if errorlevel 1 (
  echo Could not create the temporary build directory "%BUILD_DIR%".
  exit /b 1
)

javac -encoding UTF-8 -d "%BUILD_DIR%" "%SOURCE_FILE%"
if errorlevel 1 goto compile_failed

java -cp "%BUILD_DIR%" StaticFileServer "%FRONTEND_DIR%" 8080
set "SERVER_EXIT_CODE=%ERRORLEVEL%"
rmdir /s /q "%BUILD_DIR%"
exit /b %SERVER_EXIT_CODE%

:compile_failed
rmdir /s /q "%BUILD_DIR%"
exit /b 1
