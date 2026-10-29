@echo off
setlocal
cd /d "%~dp0"

echo.
echo Tabby local builder
echo This window will remain open when the build finishes.
echo.

powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-tabby.ps1"
set "TABBY_BUILD_EXIT=%ERRORLEVEL%"

if "%TABBY_BUILD_EXIT%"=="0" goto build_success

echo.
echo BUILD FAILED. The error is shown above.
echo Full log: "%~dp0build-tabby.log"
goto finish

:build_success
if not exist "%~dp0dist\Tabby-1.21.4.jar" (
    echo.
    echo BUILD FAILED: expected output JAR is missing.
    echo Checked: "%~dp0dist\Tabby-1.21.4.jar"
    set "TABBY_BUILD_EXIT=1"
    goto finish
)

echo.
echo Final mod JAR:
echo "%~dp0dist\Tabby-1.21.4.jar"
if /I not "%CI%"=="true" start "" explorer.exe "%~dp0dist"

:finish
echo.
if /I "%CI%"=="true" goto no_pause
if "%TABBY_NO_PAUSE%"=="1" goto no_pause
pause

:no_pause
exit /b %TABBY_BUILD_EXIT%
