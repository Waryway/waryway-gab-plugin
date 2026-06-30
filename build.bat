@echo off
setlocal EnableExtensions EnableDelayedExpansion

cd /d "%~dp0"

echo === Waryway Gab Plugin Build ===
echo.

REM --- JAVA_HOME: respect existing value, else auto-detect a JDK 17-21 runtime ---
if not defined JAVA_HOME (
    echo JAVA_HOME not set. Searching for a compatible JDK 17-21...
    for /f "usebackq delims=" %%J in (`powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\find-jdk.ps1"`) do set "JAVA_HOME=%%J"
)

if not defined JAVA_HOME (
    echo ERROR: JAVA_HOME is not set and no compatible JDK 17-21 was found.
    echo Kotlin 1.9.x cannot run on Java 22 or newer. Let Gradle download JDK 21 first:
    echo   gradlew.bat --version
    exit /b 1
)

if not exist "%JAVA_HOME%\bin\java.exe" (
    echo ERROR: java.exe not found under JAVA_HOME=%JAVA_HOME%
    exit /b 1
)

echo Using JAVA_HOME=%JAVA_HOME%
"%JAVA_HOME%\bin\java.exe" -version
echo.

REM --- Bump patch version in gradle.properties ---
set "NEW_VERSION="
for /f "usebackq delims=" %%V in (`powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\bump-version.ps1"`) do set "NEW_VERSION=%%V"

if errorlevel 1 (
    echo ERROR: Failed to bump pluginVersion in gradle.properties.
    exit /b 1
)

if not defined NEW_VERSION (
    echo ERROR: Version bump did not return a new version.
    exit /b 1
)

echo Bumped pluginVersion to %NEW_VERSION%
echo.

REM --- Build plugin distribution ---
call gradlew.bat buildPlugin --no-daemon --no-configuration-cache
set "BUILD_EXIT=%ERRORLEVEL%"

echo.
if "%BUILD_EXIT%"=="0" (
    echo Build succeeded: build\distributions\waryway-gab-plugin-%NEW_VERSION%.zip
) else (
    echo Build failed with exit code %BUILD_EXIT%.
)

exit /b %BUILD_EXIT%