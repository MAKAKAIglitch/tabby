@echo off
setlocal
cd /d "%~dp0"
for /f "tokens=2 delims==" %%V in ('findstr /b "minecraft_version=" "%~dp0gradle.properties"') do set "TABBY_MC_VERSION=%%V"
if not defined TABBY_MC_VERSION set "TABBY_MC_VERSION=unknown"

echo.
echo Tabby local builder
echo Target: Fabric Loader - Minecraft Java Edition %TABBY_MC_VERSION%
echo This JAR is not for Forge or NeoForge.
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
if not exist "%~dp0dist\Tabby-%TABBY_MC_VERSION%.jar" (
    echo.
    echo BUILD FAILED: expected output JAR is missing.
    echo Checked: "%~dp0dist\Tabby-%TABBY_MC_VERSION%.jar"
    set "TABBY_BUILD_EXIT=1"
    goto finish
)

echo.
echo Loader: Fabric (Minecraft %TABBY_MC_VERSION%)
echo Final mod JAR:
echo "%~dp0dist\Tabby-%TABBY_MC_VERSION%.jar"
if /I not "%CI%"=="true" start "" explorer.exe "%~dp0dist"

:finish
echo.
if /I "%CI%"=="true" goto no_pause
if "%TABBY_NO_PAUSE%"=="1" goto no_pause
pause

:no_pause
exit /b %TABBY_BUILD_EXIT%
