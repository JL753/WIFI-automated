@echo off
setlocal
rem Build the WiFi traffic-test APK.
rem Requires the bundled toolchain under ..\_android_sdk
rem Usage: pack-apk.bat [output-dir]

set "HERE=%~dp0"
set "HERE=%HERE:~0,-1%"
set "SDK=%HERE%\..\_android_sdk"
set "DIST=%~1"
if "%DIST%"=="" set "DIST=%HERE%..\APK"

if not exist "%SDK%\jdk\bin\java.exe" (
  echo [ERROR] Java not found: %SDK%\jdk\bin\java.exe
  echo         Put the JDK under _android_sdk\jdk, or edit SDK in this script.
  exit /b 1
)
if not exist "%SDK%\gradle\gradle-8.7\bin\gradle.bat" (
  echo [ERROR] Gradle not found: %SDK%\gradle\gradle-8.7\bin\gradle.bat
  exit /b 1
)

set "JAVA_HOME=%SDK%\jdk"
set "ANDROID_HOME=%SDK%"
set "ANDROID_SDK_ROOT=%SDK%"
set "GRADLE_USER_HOME=%SDK%\gradle_home"
set "ANDROID_USER_HOME=%SDK%\andhome"
if not exist "%GRADLE_USER_HOME%" mkdir "%GRADLE_USER_HOME%"
if not exist "%ANDROID_USER_HOME%" mkdir "%ANDROID_USER_HOME%"

echo [1/3] Building debug + release APK ...
pushd "%HERE%"
call "%SDK%\gradle\gradle-8.7\bin\gradle.bat" assembleDebug assembleRelease --no-daemon --console=plain
set "RC=%ERRORLEVEL%"
popd
if not "%RC%"=="0" (
  echo [ERROR] Gradle failed with exit code %RC%
  exit /b %RC%
)

echo [2/3] Copying APK ...
if not exist "%DIST%" mkdir "%DIST%"
copy /y "%HERE%\app\build\outputs\apk\release\app-release.apk" "%DIST%\WiFiTput.apk" >nul
copy /y "%HERE%\app\build\outputs\apk\debug\app-debug.apk" "%DIST%\WiFiTput-debug.apk" >nul

echo [3/3] Done.
for %%F in ("%DIST%\WiFiTput.apk" "%DIST%\WiFiTput-debug.apk") do echo        %%~fF  %%~zF bytes
echo.
echo Install:  adb install -r "%DIST%\WiFiTput.apk"
exit /b 0
