@echo off
setlocal EnableExtensions DisableDelayedExpansion

for %%I in ("%~dp0..") do set "BILL_PROJECT_ROOT=%%~fI"
set "BILL_PROJECT_JAVA=%BILL_PROJECT_ROOT%\.tools\jdk17\bin\java.exe"
set "BILL_SIGNING_ACTION="

if not exist "%BILL_PROJECT_JAVA%" (
    echo Bill internal signing requires the project JDK at "%BILL_PROJECT_JAVA%".
    exit /b 1
)

if "%~1"=="" goto usage
if not "%~2"=="" goto usage
if /i "%~1"=="create" set "BILL_SIGNING_ACTION=create"
if /i "%~1"=="build" set "BILL_SIGNING_ACTION=build"
if /i "%~1"=="status" set "BILL_SIGNING_ACTION=status"
if /i "%~1"=="self-test" set "BILL_SIGNING_ACTION=self-test"
if not defined BILL_SIGNING_ACTION goto usage

pushd "%BILL_PROJECT_ROOT%"
if errorlevel 1 exit /b 1
"%BILL_PROJECT_JAVA%" -Dfile.encoding=UTF-8 "%~dp0InternalSigning.java" %BILL_SIGNING_ACTION%
set "BILL_SIGNING_EXIT=%ERRORLEVEL%"
popd
exit /b %BILL_SIGNING_EXIT%

:usage
echo Usage: scripts\internal-signing.cmd create^|build^|status^|self-test
exit /b 2
