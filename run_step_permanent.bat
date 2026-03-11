@echo off
setlocal ENABLEDELAYEDEXPANSION

set "STEP=%~1"
if "%STEP%"=="" (
  echo.
  echo Usage: run_step_permanent.bat m70 ^| m71 ^| ... ^| latest
  set /p STEP=Step: 
  if "%STEP%"=="" set "STEP=latest"
)

set "ROOT=%~dp0"
pushd "%ROOT%" >nul

for /f "delims=" %%b in ('git rev-parse --abbrev-ref HEAD 2^>nul') do set "ORIG_BRANCH=%%b"
if not defined ORIG_BRANCH (
  echo [permanent] Not a git repo or git unavailable.
  popd >nul
  exit /b 1
)
for /f "delims=" %%r in ('git rev-parse HEAD 2^>nul') do set "ORIG_REF=%%r"

set "DID_STASH=0"
set "STASH_NAME=run_step_permanent_autostash_%DATE%_%TIME%"

git status --porcelain > .run_step_permanent_status.tmp
for %%A in (.run_step_permanent_status.tmp) do set SIZE=%%~zA
del .run_step_permanent_status.tmp >nul 2>nul
if not "%SIZE%"=="0" (
  echo [permanent] Local changes detected. Auto-stashing...
  git stash push -u -m "%STASH_NAME%" >nul 2>nul
  if errorlevel 1 (
    echo [permanent] Failed to stash local changes.
    popd >nul
    exit /b 1
  )
  set "DID_STASH=1"
)

echo [permanent] Switching to milestone-29 launcher base...
git checkout milestone-29 >nul 2>nul
if errorlevel 1 goto restoreAndFail

if not exist "%ROOT%run_step.bat" (
  echo [permanent] run_step.bat missing on milestone-29.
  goto restoreAndFail
)

echo [permanent] Running step %STEP%...
call "%ROOT%run_step.bat" %STEP%
set "RUN_EC=%ERRORLEVEL%"

:restore
if /I "%ORIG_BRANCH%"=="HEAD" (
  git checkout %ORIG_REF% >nul 2>nul
) else (
  git checkout %ORIG_BRANCH% >nul 2>nul
)

if "%DID_STASH%"=="1" (
  echo [permanent] Restoring stashed local changes...
  git stash pop >nul 2>nul
)

popd >nul
endlocal & exit /b %RUN_EC%

:restoreAndFail
set "RUN_EC=1"
goto restore
