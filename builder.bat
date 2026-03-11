@echo off
cd /d %~dp0
echo [Builder] Launching Hollow in BUILDER MODE - no enemies, frozen noon, free fly
echo [Builder] G=mark corner A/B  F10=save struct  H=paste mode
echo.
.\gradlew run --args="--builder"
pause
