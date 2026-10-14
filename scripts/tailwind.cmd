@echo off
rem Builds src\main\tailwind\app.css with the Tailwind CSS standalone CLI (no Node.js).
rem Usage:
rem   scripts\tailwind.cmd                         one-off minified build into target\classes
rem   scripts\tailwind.cmd --watch                 rebuild on template changes
rem   scripts\tailwind.cmd --output <file> [...]   used by the Maven build
setlocal EnableDelayedExpansion

set "TAILWIND_VERSION=4.3.3"
set "ASSET=tailwindcss-windows-x64.exe"
set "SHA256=e0e260ce048014e9268f6237ff18f8ccf02cef521cbd0ae04e82c2cdf7aa3955"

for %%I in ("%~dp0..") do set "ROOT=%%~fI"
set "TOOLS_DIR=%ROOT%\.tools"
set "BIN=%TOOLS_DIR%\tailwindcss-windows-x64-%TAILWIND_VERSION%.exe"

if not exist "%BIN%" (
  if not exist "%TOOLS_DIR%" mkdir "%TOOLS_DIR%"
  echo Downloading Tailwind CSS v%TAILWIND_VERSION% ^(%ASSET%^)...
  powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$ErrorActionPreference='Stop';" ^
    "Invoke-WebRequest -UseBasicParsing -Uri 'https://github.com/tailwindlabs/tailwindcss/releases/download/v%TAILWIND_VERSION%/%ASSET%' -OutFile '%BIN%.tmp';" ^
    "$h=(Get-FileHash -Algorithm SHA256 '%BIN%.tmp').Hash.ToLower();" ^
    "if ($h -ne '%SHA256%') { Remove-Item '%BIN%.tmp'; Write-Error ('Checksum mismatch: ' + $h) };" ^
    "Move-Item '%BIN%.tmp' '%BIN%'"
  if errorlevel 1 exit /b 1
)

set "EXTRA="
echo %* | findstr /C:"--output" >nul || set "EXTRA=--output "%ROOT%\target\classes\static\css\app.css""
echo %* | findstr /C:"--watch" >nul || set "EXTRA=!EXTRA! --minify"

"%BIN%" --input "%ROOT%\src\main\tailwind\app.css" --cwd "%ROOT%" !EXTRA! %*
exit /b %ERRORLEVEL%
