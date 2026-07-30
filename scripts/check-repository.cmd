@echo off
setlocal

for %%I in ("%~dp0..") do set "BILL_REPOSITORY_ROOT=%%~fI"

powershell -NoProfile -ExecutionPolicy Bypass -File "%BILL_REPOSITORY_ROOT%\scripts\check-docs.ps1"
if errorlevel 1 exit /b %ERRORLEVEL%

powershell -NoProfile -ExecutionPolicy Bypass -File "%BILL_REPOSITORY_ROOT%\scripts\check-architecture.ps1"
if errorlevel 1 exit /b %ERRORLEVEL%

powershell -NoProfile -ExecutionPolicy Bypass -File "%BILL_REPOSITORY_ROOT%\scripts\check-sensitive-boundaries.ps1"
if errorlevel 1 exit /b %ERRORLEVEL%

powershell -NoProfile -ExecutionPolicy Bypass -File "%BILL_REPOSITORY_ROOT%\scripts\generate-repository-facts.ps1" -Check
if errorlevel 1 exit /b %ERRORLEVEL%

powershell -NoProfile -ExecutionPolicy Bypass -File "%BILL_REPOSITORY_ROOT%\scripts\test-repository-checks.ps1"
if errorlevel 1 exit /b %ERRORLEVEL%

echo Repository checks passed.
exit /b 0
