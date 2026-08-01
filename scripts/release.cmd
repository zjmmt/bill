@echo off
setlocal EnableExtensions DisableDelayedExpansion

for %%I in ("%~dp0..") do set "BILL_PROJECT_ROOT=%%~fI"
set "BILL_RELEASE_OUTPUT=%BILL_PROJECT_ROOT%\app\build\outputs\apk\release"
set "BILL_ARM64_APK=%BILL_RELEASE_OUTPUT%\app-arm64-v8a-release.apk"
set "BILL_X86_64_APK=%BILL_RELEASE_OUTPUT%\app-x86_64-release.apk"

if not defined BILL_VERSION_CODE (
    echo Missing required environment variable BILL_VERSION_CODE.
    exit /b 2
)
if not defined BILL_VERSION_NAME (
    echo Missing required environment variable BILL_VERSION_NAME.
    exit /b 2
)
if not defined BILL_RELEASE_STORE_FILE (
    echo Missing required environment variable BILL_RELEASE_STORE_FILE.
    exit /b 2
)
if not defined BILL_RELEASE_STORE_PASSWORD (
    echo Missing required environment variable BILL_RELEASE_STORE_PASSWORD.
    exit /b 2
)
if not defined BILL_RELEASE_KEY_ALIAS (
    echo Missing required environment variable BILL_RELEASE_KEY_ALIAS.
    exit /b 2
)
if not defined BILL_RELEASE_KEY_PASSWORD (
    echo Missing required environment variable BILL_RELEASE_KEY_PASSWORD.
    exit /b 2
)

for %%I in ("%BILL_RELEASE_STORE_FILE%") do set "BILL_RELEASE_STORE_FILE=%%~fI"
if not exist "%BILL_RELEASE_STORE_FILE%" (
    echo BILL_RELEASE_STORE_FILE does not point to an existing file.
    exit /b 2
)

set "BILL_REQUIRE_SIGNED_RELEASE=true"
call "%~dp0android.cmd" :app:assembleRelease --no-daemon --console=plain
if errorlevel 1 exit /b %ERRORLEVEL%
set "BILL_RELEASE_STORE_PASSWORD="
set "BILL_RELEASE_KEY_PASSWORD="

if not exist "%BILL_ARM64_APK%" (
    echo Signed arm64-v8a APK was not produced.
    exit /b 4
)
if not exist "%BILL_X86_64_APK%" (
    echo Signed x86_64 APK was not produced.
    exit /b 4
)

if defined BILL_APKSIGNER goto apksigner_ready
if defined ANDROID_SDK_ROOT set "BILL_ANDROID_SDK=%ANDROID_SDK_ROOT%"
if not defined BILL_ANDROID_SDK if defined ANDROID_HOME set "BILL_ANDROID_SDK=%ANDROID_HOME%"
if not defined BILL_ANDROID_SDK (
    for /f "tokens=1,* delims==" %%A in ('findstr /b /c:"sdk.dir=" "%BILL_PROJECT_ROOT%\local.properties"') do set "BILL_ANDROID_SDK=%%B"
)
if defined BILL_ANDROID_SDK set "BILL_ANDROID_SDK=%BILL_ANDROID_SDK:\:=:%"
if defined BILL_ANDROID_SDK set "BILL_ANDROID_SDK=%BILL_ANDROID_SDK:/=\%"
if defined BILL_ANDROID_SDK (
    for /f "delims=" %%D in ('dir /b /ad /o-n "%BILL_ANDROID_SDK%\build-tools" 2^>nul') do if not defined BILL_BUILD_TOOLS set "BILL_BUILD_TOOLS=%%D"
)
if defined BILL_BUILD_TOOLS set "BILL_APKSIGNER=%BILL_ANDROID_SDK%\build-tools\%BILL_BUILD_TOOLS%\apksigner.bat"

:apksigner_ready

if not defined BILL_APKSIGNER (
    echo Could not locate apksigner. Set BILL_APKSIGNER to apksigner.bat and retry.
    exit /b 5
)
if not exist "%BILL_APKSIGNER%" (
    echo BILL_APKSIGNER does not point to an existing file.
    exit /b 5
)

set "JAVA_HOME=%BILL_PROJECT_ROOT%\.tools\jdk17"
set "PATH=%JAVA_HOME%\bin;%PATH%"
call "%BILL_APKSIGNER%" verify --verbose "%BILL_ARM64_APK%" >nul
if errorlevel 1 (
    echo arm64-v8a APK signature verification failed.
    exit /b 6
)
call "%BILL_APKSIGNER%" verify --verbose "%BILL_X86_64_APK%" >nul
if errorlevel 1 (
    echo x86_64 APK signature verification failed.
    exit /b 6
)

"%JAVA_HOME%\bin\java.exe" -Dfile.encoding=UTF-8 "%BILL_PROJECT_ROOT%\scripts\InternalSigning.java" verify-release
if errorlevel 1 (
    echo Signed APK identity verification failed.
    exit /b 6
)

echo Signed release verified in:
echo %BILL_RELEASE_OUTPUT%
exit /b 0
