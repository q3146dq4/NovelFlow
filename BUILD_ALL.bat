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
if not defined ANDROID_HOME (
  echo [ERROR] Android SDK not found.
  call :cleanup_drive
  pause
  exit /b 1
)
>local.properties echo sdk.dir=%ANDROID_HOME:\=/%
set "JAVA17_HOME="
if exist "%ROOT%.tools\jdk17\bin\java.exe" set "JAVA17_HOME=%ROOT%.tools\jdk17"
if not defined JAVA17_HOME if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA17_HOME=%JAVA_HOME%"
if not defined JAVA17_HOME (
  echo [INFO] Java 17 not found. Downloading Temurin 17...
  if not exist "%ROOT%.tools" mkdir "%ROOT%.tools"
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -UseBasicParsing -Uri 'https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse' -OutFile '%ROOT%.tools\temurin17.zip'"
  if errorlevel 1 goto fail
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$zip='%ROOT%.tools\temurin17.zip'; $tmp='%ROOT%.tools\jdk17.tmp'; $dst='%ROOT%.tools\jdk17'; if(Test-Path $tmp){Remove-Item $tmp -Recurse -Force}; if(Test-Path $dst){Remove-Item $dst -Recurse -Force}; Expand-Archive -Path $zip -DestinationPath $tmp -Force; $d=Get-ChildItem $tmp -Directory | Select-Object -First 1; New-Item -ItemType Directory -Force -Path $dst | Out-Null; Copy-Item ($d.FullName+'\\*') $dst -Recurse -Force"
  if errorlevel 1 goto fail
  rmdir /s /q "%ROOT%.tools\jdk17.tmp" >nul 2>&1
  del /q "%ROOT%.tools\temurin17.zip" >nul 2>&1
  set "JAVA17_HOME=%ROOT%.tools\jdk17"
)
set "JAVA_HOME=%JAVA17_HOME%"
set "PATH=%JAVA_HOME%\bin;%PATH%"
echo === Building NovelRegEx v0.1 ===
call gradlew.bat --stop >nul 2>&1
call gradlew.bat :app:assembleDebug
if errorlevel 1 goto fail
copy /y "%ROOT%app\build\outputs\apk\debug\app-debug.apk" "%ROOT%NovelRegEx-v0.1-debug.apk" >nul
if errorlevel 1 goto fail
echo.
echo BUILD SUCCESSFUL
echo APK: %ROOT%NovelRegEx-v0.1-debug.apk
call :cleanup_drive
pause
exit /b 0
:fail
echo.
echo [ERROR] Build failed.
call :cleanup_drive
pause
exit /b 1

:cleanup_drive
cd /d "%ROOT%" >nul 2>&1
if defined BUILD_DRIVE subst %BUILD_DRIVE% /d >nul 2>&1
exit /b 0
