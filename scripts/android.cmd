@echo off
setlocal

for %%I in ("%~dp0..") do set "BILL_PROJECT_ROOT=%%~fI"
set "BILL_PROJECT_JDK=%BILL_PROJECT_ROOT%\.tools\jdk17"

if not exist "%BILL_PROJECT_JDK%\bin\java.exe" (
    echo Bill Android build requires JDK 17 at "%BILL_PROJECT_JDK%".
    echo Install the project-local toolchain or set up an equivalent JDK 17 before retrying.
    exit /b 1
)

if not exist "%BILL_PROJECT_ROOT%\local.properties" (
    echo Missing "%BILL_PROJECT_ROOT%\local.properties" with an Android SDK path.
    exit /b 1
)

set "JAVA_HOME=%BILL_PROJECT_JDK%"
call "%BILL_PROJECT_ROOT%\gradlew.bat" %*
exit /b %ERRORLEVEL%
