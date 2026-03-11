@echo off
setlocal

set "STEP=%~1"
if "%STEP%"=="" (
  echo.
  echo Choose step: m1 m2 m3 m4 m5 m6 m7 m8 m9 m10 m11 m12 m13 m14 m15 m16 m17 m18 m19 m20 m21 m22 m23 m24 m25 m26 m27 m28 m29 m30 m31 m32 m33 m34 m35 m36 m37 m38 m39 m40 m41 m42 m43 m44 m45 m46 m47 m48 m49 m50 m51 m52 m53 m54 m55 m56 m57 m58 m59 m60 m61 m62 m63 m64 m65 m66 m67 m68 m69 m70 m71 m72 m73 m74 m75 m76 m77 m78 m79 m80 m81 m82 m83 m84 m85 m86 m87 m88 m89 m90 m91 m92 m93 m94 m95 m95.1 m96 m97 m98 m99 m100 m101 m102 m103 m104 m105 m106 ... m158 m159 m160 m161 latest m162 ... m180 latest
  set /p STEP=Step: 
  if "%STEP%"=="" set "STEP=latest"
)

set "TARGET="
if /I "%STEP%"=="m1" set "TARGET=milestone-1"
if /I "%STEP%"=="m2" set "TARGET=milestone-2"
if /I "%STEP%"=="m3" set "TARGET=milestone-3"
if /I "%STEP%"=="m4" set "TARGET=milestone-4"
if /I "%STEP%"=="m5" set "TARGET=milestone-5"
if /I "%STEP%"=="m6" set "TARGET=milestone-6"
if /I "%STEP%"=="m7" set "TARGET=milestone-7"
if /I "%STEP%"=="m8" set "TARGET=milestone-8"
if /I "%STEP%"=="m9" set "TARGET=milestone-9"
if /I "%STEP%"=="m10" set "TARGET=milestone-10"
if /I "%STEP%"=="m11" set "TARGET=milestone-11"
if /I "%STEP%"=="m12" set "TARGET=milestone-12"
if /I "%STEP%"=="m13" set "TARGET=milestone-13"
if /I "%STEP%"=="m14" set "TARGET=milestone-14"
if /I "%STEP%"=="m15" set "TARGET=milestone-15"
if /I "%STEP%"=="m16" set "TARGET=milestone-16"
if /I "%STEP%"=="m17" set "TARGET=milestone-17"
if /I "%STEP%"=="m18" set "TARGET=milestone-18"
if /I "%STEP%"=="m19" set "TARGET=milestone-19"
if /I "%STEP%"=="m20" set "TARGET=milestone-20"
if /I "%STEP%"=="m21" set "TARGET=milestone-21"
if /I "%STEP%"=="m22" set "TARGET=milestone-22"
if /I "%STEP%"=="m23" set "TARGET=milestone-23"
if /I "%STEP%"=="m24" set "TARGET=milestone-24"
if /I "%STEP%"=="m25" set "TARGET=milestone-25"
if /I "%STEP%"=="m26" set "TARGET=milestone-26"
if /I "%STEP%"=="m27" set "TARGET=milestone-27"
if /I "%STEP%"=="m28" set "TARGET=milestone-28"
if /I "%STEP%"=="m29" set "TARGET=milestone-29"
if /I "%STEP%"=="m30" set "TARGET=milestone-30"
if /I "%STEP%"=="m31" set "TARGET=milestone-31"
if /I "%STEP%"=="m32" set "TARGET=milestone-32"
if /I "%STEP%"=="m33" set "TARGET=milestone-33"
if /I "%STEP%"=="m34" set "TARGET=milestone-34"
if /I "%STEP%"=="m35" set "TARGET=milestone-35"
if /I "%STEP%"=="m36" set "TARGET=milestone-36"
if /I "%STEP%"=="m37" set "TARGET=milestone-37"
if /I "%STEP%"=="m38" set "TARGET=milestone-38"
if /I "%STEP%"=="m39" set "TARGET=milestone-39"
if /I "%STEP%"=="m40" set "TARGET=milestone-40"
if /I "%STEP%"=="m41" set "TARGET=milestone-41"
if /I "%STEP%"=="m42" set "TARGET=milestone-42"
if /I "%STEP%"=="m43" set "TARGET=milestone-43"
if /I "%STEP%"=="m44" set "TARGET=milestone-44"
if /I "%STEP%"=="m45" set "TARGET=milestone-45"
if /I "%STEP%"=="m46" set "TARGET=milestone-46"
if /I "%STEP%"=="m47" set "TARGET=milestone-47"
if /I "%STEP%"=="m48" set "TARGET=milestone-48"
if /I "%STEP%"=="m49" set "TARGET=milestone-49"
if /I "%STEP%"=="m50" set "TARGET=milestone-50"
if /I "%STEP%"=="m51" set "TARGET=milestone-51"
if /I "%STEP%"=="m52" set "TARGET=milestone-52"
if /I "%STEP%"=="m53" set "TARGET=milestone-53"
if /I "%STEP%"=="m54" set "TARGET=milestone-54"
if /I "%STEP%"=="m55" set "TARGET=milestone-55"
if /I "%STEP%"=="m56" set "TARGET=milestone-56"
if /I "%STEP%"=="m57" set "TARGET=milestone-57"
if /I "%STEP%"=="m58" set "TARGET=milestone-58"
if /I "%STEP%"=="m59" set "TARGET=milestone-59"
if /I "%STEP%"=="m60" set "TARGET=milestone-60"
if /I "%STEP%"=="m61" set "TARGET=milestone-61"
if /I "%STEP%"=="m62" set "TARGET=milestone-62"
if /I "%STEP%"=="m63" set "TARGET=milestone-63"
if /I "%STEP%"=="m64" set "TARGET=milestone-64"
if /I "%STEP%"=="m65" set "TARGET=milestone-65"
if /I "%STEP%"=="m66" set "TARGET=milestone-66"
if /I "%STEP%"=="m67" set "TARGET=milestone-67"
if /I "%STEP%"=="m68" set "TARGET=milestone-68"
if /I "%STEP%"=="m69" set "TARGET=milestone-69"
if /I "%STEP%"=="m70" set "TARGET=milestone-70"
if /I "%STEP%"=="m71" set "TARGET=milestone-71"
if /I "%STEP%"=="m72" set "TARGET=milestone-72"
if /I "%STEP%"=="m73" set "TARGET=milestone-73"
if /I "%STEP%"=="m74" set "TARGET=milestone-74"
if /I "%STEP%"=="m75" set "TARGET=milestone-75"
if /I "%STEP%"=="m76" set "TARGET=milestone-76"
if /I "%STEP%"=="m77" set "TARGET=milestone-77"
if /I "%STEP%"=="m78" set "TARGET=milestone-78"
if /I "%STEP%"=="m79" set "TARGET=milestone-79"
if /I "%STEP%"=="m80" set "TARGET=milestone-80"
if /I "%STEP%"=="m81" set "TARGET=milestone-81"
if /I "%STEP%"=="m82" set "TARGET=milestone-82"
if /I "%STEP%"=="m83" set "TARGET=milestone-83"
if /I "%STEP%"=="m84" set "TARGET=milestone-84"
if /I "%STEP%"=="m85" set "TARGET=milestone-85"
if /I "%STEP%"=="m86" set "TARGET=milestone-86"
if /I "%STEP%"=="m87" set "TARGET=milestone-87"
if /I "%STEP%"=="m88" set "TARGET=milestone-88"
if /I "%STEP%"=="m89" set "TARGET=milestone-89"
if /I "%STEP%"=="m90" set "TARGET=milestone-90"
if /I "%STEP%"=="m91" set "TARGET=milestone-91"
if /I "%STEP%"=="m92" set "TARGET=milestone-92"
if /I "%STEP%"=="m93" set "TARGET=milestone-93"
if /I "%STEP%"=="m94" set "TARGET=milestone-94"
if /I "%STEP%"=="m95" set "TARGET=milestone-95"
if /I "%STEP%"=="m95.1" set "TARGET=milestone-95.1"
if /I "%STEP%"=="m96" set "TARGET=milestone-96"

if /I "%STEP%"=="m97" set "TARGET=milestone-97"

if /I "%STEP%"=="m98" set "TARGET=milestone-98"

if /I "%STEP%"=="m99" set "TARGET=milestone-99"

if /I "%STEP%"=="m100" set "TARGET=milestone-100"

if /I "%STEP%"=="m101" set "TARGET=milestone-101"

if /I "%STEP%"=="m102" set "TARGET=milestone-102"

if /I "%STEP%"=="m103" set "TARGET=milestone-103"

if /I "%STEP%"=="m104" set "TARGET=milestone-104"

if /I "%STEP%"=="m105" set "TARGET=milestone-105"

if /I "%STEP%"=="m106" set "TARGET=milestone-106"

if /I "%STEP%"=="m107" set "TARGET=milestone-107"

if /I "%STEP%"=="m108" set "TARGET=milestone-108"

if /I "%STEP%"=="m109" set "TARGET=milestone-109"

if /I "%STEP%"=="m110" set "TARGET=milestone-110"

if /I "%STEP%"=="m111" set "TARGET=milestone-111"

if /I "%STEP%"=="m112" set "TARGET=milestone-112"

if /I "%STEP%"=="m113" set "TARGET=milestone-113"

if /I "%STEP%"=="m114" set "TARGET=milestone-114"

if /I "%STEP%"=="m115" set "TARGET=milestone-115"

if /I "%STEP%"=="m116" set "TARGET=milestone-116"

if /I "%STEP%"=="m117" set "TARGET=milestone-117"

if /I "%STEP%"=="m118" set "TARGET=milestone-118"

if /I "%STEP%"=="m119" set "TARGET=milestone-119"

if /I "%STEP%"=="m120" set "TARGET=milestone-120"

if /I "%STEP%"=="m121" set "TARGET=milestone-121"

if /I "%STEP%"=="m122" set "TARGET=milestone-122"

if /I "%STEP%"=="m123" set "TARGET=milestone-123"

if /I "%STEP%"=="m124" set "TARGET=milestone-124"

if /I "%STEP%"=="m125" set "TARGET=milestone-125"

if /I "%STEP%"=="m126" set "TARGET=milestone-126"

if /I "%STEP%"=="m127" set "TARGET=milestone-127"

if /I "%STEP%"=="m128" set "TARGET=milestone-128"

if /I "%STEP%"=="m129" set "TARGET=milestone-129"

if /I "%STEP%"=="m130" set "TARGET=milestone-130"

if /I "%STEP%"=="m131" set "TARGET=milestone-131"

if /I "%STEP%"=="m132" set "TARGET=milestone-132"

if /I "%STEP%"=="m133" set "TARGET=milestone-133"

if /I "%STEP%"=="m134" set "TARGET=milestone-134"

if /I "%STEP%"=="m135" set "TARGET=milestone-135"

if /I "%STEP%"=="m136" set "TARGET=milestone-136"

if /I "%STEP%"=="m137" set "TARGET=milestone-137"

if /I "%STEP%"=="m138" set "TARGET=milestone-138"

if /I "%STEP%"=="m139" set "TARGET=milestone-139"

if /I "%STEP%"=="m140" set "TARGET=milestone-140"
if /I "%STEP%"=="m141" set "TARGET=milestone-141"
if /I "%STEP%"=="m142" set "TARGET=milestone-142"
if /I "%STEP%"=="m143" set "TARGET=milestone-143"
if /I "%STEP%"=="m144" set "TARGET=milestone-144"
if /I "%STEP%"=="m145" set "TARGET=milestone-145"
if /I "%STEP%"=="m146" set "TARGET=milestone-146"
if /I "%STEP%"=="m147" set "TARGET=milestone-147"
if /I "%STEP%"=="m148" set "TARGET=milestone-148"
if /I "%STEP%"=="m149" set "TARGET=milestone-149"
if /I "%STEP%"=="m150" set "TARGET=milestone-150"
if /I "%STEP%"=="m151" set "TARGET=milestone-151"
if /I "%STEP%"=="m152" set "TARGET=m152"
if /I "%STEP%"=="m153" set "TARGET=m153"
if /I "%STEP%"=="m154" set "TARGET=m154"
if /I "%STEP%"=="m155" set "TARGET=m155"
if /I "%STEP%"=="m156" set "TARGET=m156"
if /I "%STEP%"=="m157" set "TARGET=m157"
if /I "%STEP%"=="m158" set "TARGET=m158"
if /I "%STEP%"=="m159" set "TARGET=m159"
if /I "%STEP%"=="m160" set "TARGET=m160"
if /I "%STEP%"=="m161" set "TARGET=m161"
if /I "%STEP%"=="m162" set "TARGET=m162"
if /I "%STEP%"=="m163" set "TARGET=m163"
if /I "%STEP%"=="m164" set "TARGET=m164"
if /I "%STEP%"=="m165" set "TARGET=m165"
if /I "%STEP%"=="m166" set "TARGET=m166"
if /I "%STEP%"=="m167" set "TARGET=m167"
if /I "%STEP%"=="m168" set "TARGET=m168"
if /I "%STEP%"=="m169" set "TARGET=m169"
if /I "%STEP%"=="m170" set "TARGET=m170"
if /I "%STEP%"=="m171" set "TARGET=m171"
if /I "%STEP%"=="m172" set "TARGET=m172"
if /I "%STEP%"=="m173" set "TARGET=m173"
if /I "%STEP%"=="m174" set "TARGET=m174"
if /I "%STEP%"=="m175" set "TARGET=m175"
if /I "%STEP%"=="m176" set "TARGET=m176"
if /I "%STEP%"=="m177" set "TARGET=m177"
if /I "%STEP%"=="m178" set "TARGET=m178"
if /I "%STEP%"=="m179" set "TARGET=m179"
if /I "%STEP%"=="m180" set "TARGET=m180"
if /I "%STEP%"=="m181" set "TARGET=m181"
if /I "%STEP%"=="m182" set "TARGET=m182"
if /I "%STEP%"=="m183" set "TARGET=m183"
if /I "%STEP%"=="m184" set "TARGET=m184"
if /I "%STEP%"=="m185" set "TARGET=m185"
if /I "%STEP%"=="m186" set "TARGET=m186"
if /I "%STEP%"=="m187" set "TARGET=m187"
if /I "%STEP%"=="m188" set "TARGET=m188"
if /I "%STEP%"=="m189" set "TARGET=m189"
if /I "%STEP%"=="m190" set "TARGET=m190"
if /I "%STEP%"=="m191" set "TARGET=m191"
if /I "%STEP%"=="m192" set "TARGET=m192"
if /I "%STEP%"=="m193" set "TARGET=m193"
if /I "%STEP%"=="m194" set "TARGET=m194"
if /I "%STEP%"=="m195" set "TARGET=m195"
if /I "%STEP%"=="m196" set "TARGET=m196"
if /I "%STEP%"=="m197" set "TARGET=m197"
if /I "%STEP%"=="m198" set "TARGET=m198"
if /I "%STEP%"=="m199" set "TARGET=m199"
if /I "%STEP%"=="m200" set "TARGET=m200"
if /I "%STEP%"=="m201" set "TARGET=m201"
if /I "%STEP%"=="m202" set "TARGET=m202"
if /I "%STEP%"=="m203" set "TARGET=m203"
if /I "%STEP%"=="m204" set "TARGET=m204"
if /I "%STEP%"=="m205" set "TARGET=m205"
if /I "%STEP%"=="m206" set "TARGET=m206"
if /I "%STEP%"=="m207" set "TARGET=m207"
if /I "%STEP%"=="m208" set "TARGET=m208"
if /I "%STEP%"=="m209" set "TARGET=m209"
if /I "%STEP%"=="m210" set "TARGET=m210"
if /I "%STEP%"=="m211" set "TARGET=m211"
if /I "%STEP%"=="m212" set "TARGET=m212"
if /I "%STEP%"=="m213" set "TARGET=m213"
if /I "%STEP%"=="m214" set "TARGET=m214"
if /I "%STEP%"=="m215" set "TARGET=m215"
if /I "%STEP%"=="m216" set "TARGET=milestone-216"
if /I "%STEP%"=="m217" set "TARGET=milestone-217"
if /I "%STEP%"=="m218" set "TARGET=milestone-218"
if /I "%STEP%"=="m219" set "TARGET=milestone-219"
if /I "%STEP%"=="m220" set "TARGET=milestone-220"
if /I "%STEP%"=="m221" set "TARGET=milestone-221"
if /I "%STEP%"=="m222" set "TARGET=milestone-222"
if /I "%STEP%"=="m223" set "TARGET=milestone-223"
if /I "%STEP%"=="m224" set "TARGET=milestone-224"
if /I "%STEP%"=="m225" set "TARGET=milestone-225"
if /I "%STEP%"=="m226" set "TARGET=milestone-226"
if /I "%STEP%"=="m227" set "TARGET=milestone-227"
if /I "%STEP%"=="m228" set "TARGET=milestone-228"
if /I "%STEP%"=="m229" set "TARGET=milestone-229"

if /I "%STEP%"=="latest" set "TARGET="

if "%TARGET%"=="" if /I not "%STEP%"=="latest" (
  echo [run_step] Unknown step '%STEP%'. Use: m1 .. m232 latest
  pause
  exit /b 1
)

for /f "delims=" %%b in ('git rev-parse --abbrev-ref HEAD') do set "ORIGINAL_BRANCH=%%b"
for /f "delims=" %%r in ('git rev-parse --short HEAD') do set "ORIGINAL_SHA=%%r"
for /f "delims=" %%r in ('git rev-parse HEAD') do set "ORIGINAL_REF=%%r"
set "DID_STASH=0"
set "STASH_NAME=run_step_autostash_%DATE%_%TIME%"
set "LOGFILE=run_step.log"

echo [run_step] writing log to %LOGFILE%
echo ===== run_step start %DATE% %TIME% ===== > %LOGFILE%
echo STEP=%STEP% TARGET=%TARGET% >> %LOGFILE%

REM Detect any local changes (tracked + untracked).
git status --porcelain > .run_step_status.tmp
for %%A in (.run_step_status.tmp) do set SIZE=%%~zA
del .run_step_status.tmp >nul 2>nul

if not "%SIZE%"=="0" (
  echo [run_step] Local changes detected. Auto-stashing...
  echo [run_step] Auto-stashing local changes... >> %LOGFILE%
  git stash push -u -m "%STASH_NAME%" >> %LOGFILE% 2>&1
  if errorlevel 1 (
    echo [run_step] Failed to stash changes. Resolve manually and retry.
    echo [run_step] ERROR: stash failed >> %LOGFILE%
    pause
    exit /b 1
  )
  set "DID_STASH=1"
)

if not "%TARGET%"=="" goto checkoutTarget
echo [run_step] Running latest from %ORIGINAL_BRANCH% %ORIGINAL_SHA%
echo [run_step] Running latest from %ORIGINAL_BRANCH% %ORIGINAL_SHA% >> %LOGFILE%
goto buildRun

:checkoutTarget
echo [run_step] Checkout %TARGET%
echo [run_step] Checkout %TARGET% >> %LOGFILE%
git checkout %TARGET% >> %LOGFILE% 2>&1
if errorlevel 1 goto restore

:buildRun
echo [run_step] Building...
echo [run_step] gradlew compileJava >> %LOGFILE%
call .\gradlew.bat compileJava >> %LOGFILE% 2>&1
if errorlevel 1 goto buildFailed

echo [run_step] Launching app (close window to return)...
echo [run_step] gradlew run --stacktrace >> %LOGFILE%
call .\gradlew.bat run --stacktrace >> %LOGFILE% 2>&1
if errorlevel 1 goto runFailed
goto restore

:buildFailed
echo [run_step] BUILD FAILED. See %LOGFILE%
echo [run_step] BUILD FAILED >> %LOGFILE%
goto restoreWithPause

:runFailed
echo [run_step] RUN FAILED/CRASHED. See %LOGFILE%
echo [run_step] RUN FAILED/CRASHED >> %LOGFILE%
goto restoreWithPause

:restore
if "%TARGET%"=="" goto maybeUnstash
echo [run_step] Restoring original checkout...
echo [run_step] Restoring original checkout... >> %LOGFILE%
if /I "%ORIGINAL_BRANCH%"=="HEAD" (
  git checkout %ORIGINAL_REF% >> %LOGFILE% 2>&1
) else (
  git checkout %ORIGINAL_BRANCH% >> %LOGFILE% 2>&1
)

:maybeUnstash
if "%DID_STASH%"=="1" (
  echo [run_step] Restoring stashed local changes...
  echo [run_step] Restoring stashed local changes... >> %LOGFILE%
  git stash pop >> %LOGFILE% 2>&1
)

echo ===== run_step end %DATE% %TIME% ===== >> %LOGFILE%
echo [run_step] Done.
endlocal
exit /b 0

:restoreWithPause
if not "%TARGET%"=="" (
  echo [run_step] Restoring original checkout...
  echo [run_step] Restoring original checkout... >> %LOGFILE%
  if /I "%ORIGINAL_BRANCH%"=="HEAD" (
    git checkout %ORIGINAL_REF% >> %LOGFILE% 2>&1
  ) else (
    git checkout %ORIGINAL_BRANCH% >> %LOGFILE% 2>&1
  )
)
if "%DID_STASH%"=="1" (
  echo [run_step] Restoring stashed local changes...
  echo [run_step] Restoring stashed local changes... >> %LOGFILE%
  git stash pop >> %LOGFILE% 2>&1
)
echo ===== run_step end (error) %DATE% %TIME% ===== >> %LOGFILE%
echo [run_step] Press any key to close. Log: %LOGFILE%
pause
endlocal
exit /b 1

if /I "%STEP%"=="m230" set "TARGET=milestone-230"

if /I "%STEP%"=="m231" set "TARGET=milestone-231"
if /I "%STEP%"=="m232" set "TARGET=milestone-232"
if /I "%STEP%"=="m233" set "TARGET=milestone-233"
if /I "%STEP%"=="m234" set "TARGET=milestone-234"
if /I "%STEP%"=="m235" set "TARGET=milestone-235"
if /I "%STEP%"=="m236" set "TARGET=milestone-236"
if /I "%STEP%"=="m237" set "TARGET=milestone-237"
if /I "%STEP%"=="m238" set "TARGET=milestone-238"
if /I "%STEP%"=="m239" set "TARGET=milestone-239"
if /I "%STEP%"=="m240" set "TARGET=milestone-240"
if /I "%STEP%"=="m241" set "TARGET=milestone-241"
