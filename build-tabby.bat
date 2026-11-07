@echo off
setlocal
cd /d "%~dp0"

echo.
echo Tabby local builder
echo Target version is read from gradle.properties.
echo.

powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-tabby.ps1"
set "TABBY_BUILD_EXIT=%ERRORLEVEL%"

if not "%TABBY_BUILD_EXIT%"=="0" (
    echo.
    echo BUILD FAILED. See build-tabby.log for details.
)

if /I "%CI%"=="true" exit /b %TABBY_BUILD_EXIT%
if "%TABBY_NO_PAUSE%"=="1" exit /b %TABBY_BUILD_EXIT%
pause
exit /b %TABBY_BUILD_EXIT%
