@echo off
setlocal EnableExtensions EnableDelayedExpansion
set "ROOT=%~dp0"
cd /d "%ROOT%"
set "BUILD_DRIVE="
for %%D in (Z: Y: X: W: V: U:) do (
  if not defined BUILD_DRIVE if not exist "%%D\" (
    subst %%D "%ROOT:~0,-1%" >nul 2>&1
    if not errorlevel 1 set "BUILD_DRIVE=%%D"
  )
)
if defined BUILD_DRIVE (
  echo Using temporary build drive !BUILD_DRIVE!
  cd /d "!BUILD_DRIVE!\"
) else (
  echo [WARN] No free SUBST drive letter. Building from the original path.
  cd /d "%ROOT%"
)
if not defined ANDROID_HOME if exist "%LOCALAPPDATA%\Android\Sdk" set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
if not defined ANDROID_HOME goto no_sdk
>local.properties echo sdk.dir=%ANDROID_HOME:\=/%
if not defined JAVA_HOME goto no_java
if not exist "%JAVA_HOME%\bin\java.exe" goto no_java

echo === Building signed NovelRegEx release ===
if "%KEYSTORE_PATH%"=="" goto no_signing
if "%KEY_ALIAS%"=="" goto no_signing
if "%KEYSTORE_PASSWORD%"=="" goto no_signing
set "PATH=%JAVA_HOME%\bin;%PATH%"
call gradlew.bat --stop >nul 2>&1
call gradlew.bat :app:buildReleaseApk
if errorlevel 1 goto fail

echo.
echo BUILD SUCCESSFUL
for /f "tokens=1,* delims==" %%A in (gradle.properties) do if "%%A"=="app.versionName" set "APP_VERSION=%%B"
echo APK: %ROOT%apk\NovelRegEx-v%APP_VERSION%.apk
call :cleanup_drive
pause
exit /b 0

:no_sdk
echo [ERROR] Android SDK not found.
goto fail
:no_java
echo [ERROR] Java 17 not found. Set JAVA_HOME to a JDK 17 installation.
goto fail
:no_signing
echo [ERROR] Release signing variables are missing.
echo Required: KEYSTORE_PATH, KEY_ALIAS, KEYSTORE_PASSWORD
call :cleanup_drive
pause
exit /b 1
:fail
echo.
echo [ERROR] Release build failed.
call :cleanup_drive
pause
exit /b 1

:cleanup_drive
cd /d "%ROOT%" >nul 2>&1
if defined BUILD_DRIVE subst %BUILD_DRIVE% /d >nul 2>&1
exit /b 0
