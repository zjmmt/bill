@echo off
setlocal

if "%~1"=="" (
    echo Usage: scripts\export-notification-template-samples.cmd ^<new-output.ndjson^>
    exit /b 2
)

for %%I in ("%~dp0..") do set "BILL_PROJECT_ROOT=%%~fI"
for %%I in ("%~1") do set "BILL_SAMPLE_EXPORT=%%~fI"
for %%I in ("%~1") do set "BILL_SAMPLE_EXPORT_PARENT=%%~dpI"
set "BILL_ADB=%BILL_PROJECT_ROOT%\.android-sdk\platform-tools\adb.exe"

if /i not "%BILL_SAMPLE_EXPORT_PARENT%"=="C:\tmp\" (
    echo Refusing raw export outside the direct C:\tmp directory.
    exit /b 8
)

if not exist "%BILL_ADB%" (
    echo Missing project-local adb at "%BILL_ADB%".
    exit /b 3
)

if exist "%BILL_SAMPLE_EXPORT%" (
    echo Refusing to overwrite existing file "%BILL_SAMPLE_EXPORT%".
    exit /b 4
)

"%BILL_ADB%" get-state >nul 2>&1
if errorlevel 1 (
    echo No authorized Android device is connected.
    exit /b 5
)

"%BILL_ADB%" exec-out run-as dev.bill.app cat no_backup/notification-template-samples.ndjson > "%BILL_SAMPLE_EXPORT%"
if errorlevel 1 (
    if exist "%BILL_SAMPLE_EXPORT%" del /q "%BILL_SAMPLE_EXPORT%"
    echo Unable to export the Debug app-private sample file.
    exit /b 6
)

for %%I in ("%BILL_SAMPLE_EXPORT%") do (
    if %%~zI EQU 0 (
        del /q "%BILL_SAMPLE_EXPORT%"
        echo The sample file was empty; no export was retained.
        exit /b 7
    )
)

echo Exported raw private samples without printing content:
echo "%BILL_SAMPLE_EXPORT%"
echo Keep this file outside Git, sanitize it, then clear the device copy from the Debug sampler.
exit /b 0
