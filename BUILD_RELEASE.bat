@echo off
setlocal EnableExtensions EnableDelayedExpansion
set "ROOT=%~dp0"
set "BUILD_DRIVE=Z:"
cd /d "%ROOT%"
subst %BUILD_DRIVE% >nul 2>&1
if not errorlevel 1 subst %BUILD_DRIVE% /d >nul 2>&1
subst %BUILD_DRIVE% "%ROOT%"
if errorlevel 1 goto fail
cd /d "%BUILD_DRIVE%\"
if not defined ANDROID_HOME if exist "%LOCALAPPDATA%\Android\Sdk" set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
if not defined ANDROID_HOME goto no_sdk
>local.properties echo sdk.dir=%ANDROID_HOME:\=/%
if not defined JAVA_HOME goto no_java
if not exist "%JAVA_HOME%\bin\java.exe" goto no_java

echo === Building signed NovelFlow release ===
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
echo APK: %ROOT%apk\NovelFlow-v%APP_VERSION%.apk
subst %BUILD_DRIVE% /d >nul 2>&1
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
subst %BUILD_DRIVE% /d >nul 2>&1
pause
exit /b 1
:fail
echo.
echo [ERROR] Release build failed.
subst %BUILD_DRIVE% /d >nul 2>&1
pause
exit /b 1
