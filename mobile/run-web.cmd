@echo off
rem Serves the app at http://localhost:5005 against the local backend.
rem Uses FLUTTER if set, else C:\src\flutter.
cd /d "%~dp0"
if "%FLUTTER%"=="" set FLUTTER=C:\src\flutter\bin\flutter.bat
call "%FLUTTER%" run -d web-server --web-port 5005 --web-hostname localhost %*
