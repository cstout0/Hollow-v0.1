@echo off
REM ============================================================
REM  Hollow — Playtest Package Builder
REM  Run from the project root: .\package.bat
REM  Output: build\dist\Hollow\  +  desktop Hollow-playtest-v1.zip
REM ============================================================
setlocal

set "NAME=Hollow"
set "VERSION=1.0"
set "MAIN=noctfield.Main"
set "DIST=%~dp0build\dist\%NAME%"
set "ZIP=%USERPROFILE%\Desktop\%NAME%-playtest-v%VERSION%.zip"

echo [1/5] Building fat JAR...
call ".\gradlew.bat" shadowJar --quiet
if errorlevel 1 ( echo ERROR: shadowJar failed & pause & exit /b 1 )

echo [2/5] Running jpackage...
rmdir /s /q "%DIST%" 2>nul
jpackage ^
  --type app-image ^
  --input "%~dp0build\libs" ^
  --main-jar hollow.jar ^
  --main-class %MAIN% ^
  --name %NAME% ^
  --app-version %VERSION% ^
  --dest "%~dp0build\dist" ^
  --java-options "-Xmx512m"
if errorlevel 1 ( echo ERROR: jpackage failed & pause & exit /b 1 )

echo [3/5] Copying game assets...
REM Audio: OGG only — WAV originals are 4+ GB uncompressed, OGGs are the same content at 10% the size
robocopy "%~dp0audio"    "%DIST%\audio"    *.ogg /nfl /ndl /njh /njs /nc /ns /np >nul
REM M236: sounds/ folder contains voice lines + event audio (was missing from old package script)
robocopy "%~dp0sounds"   "%DIST%\sounds"   *.wav *.ogg /nfl /ndl /njh /njs /nc /ns /np >nul
xcopy /e /i /q "%~dp0textures" "%DIST%\textures\" /nfl /ndl >nul
xcopy /e /i /q "%~dp0models"   "%DIST%\models\"   /nfl /ndl >nul
mkdir "%DIST%\worlds" 2>nul
copy "%~dp0README_playtest.txt" "%DIST%\" >nul

echo [4/5] Zipping...
del "%ZIP%" 2>nul
powershell -NoProfile -Command ^
  "Add-Type -Assembly System.IO.Compression.FileSystem; [System.IO.Compression.ZipFile]::CreateFromDirectory('%DIST%','%ZIP%',[System.IO.Compression.CompressionLevel]::Optimal,$true)"

echo [5/5] Done!
echo   Folder : %DIST%
echo   Zip    : %ZIP%
echo.
pause
