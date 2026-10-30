@echo off
rem ==============================================================
rem  WiFi / Hotspot Throughput Test Tool - launcher
rem  ASCII only on purpose: no chcp, no set /p, no non-ASCII text.
rem  (chcp breaks redirected stdin; GBK bytes break cmd parsing.)
rem ==============================================================
setlocal EnableExtensions
cd /d "%~dp0"
set "HERE=%~dp0"
set "TOOLS=%HERE%tools"
set "PY="

if not exist "%TOOLS%\main.py" goto :NOFILES

rem ---- 1) python on PATH ----
python -c "import sys" >nul 2>&1
if not errorlevel 1 set "PY=python"

rem ---- 2) the py launcher ----
if not defined PY py -3 -c "import sys" >nul 2>&1
if not defined PY if not errorlevel 1 set "PY=py -3"

rem ---- 3) a portable python shipped next to this file ----
if not defined PY if exist "%HERE%python\python.exe" set PY="%HERE%python\python.exe"

if defined PY goto :RUNPY

rem ---- 4) no python -> powershell fallback ----
if not exist "%TOOLS%\fallback\wb_fallback.ps1" goto :NOPY
echo.
echo  [INFO] Python not found. Switching to the PowerShell fallback engine.
echo         The fallback covers WiFi / hotspot throughput only.
echo.
powershell -NoProfile -ExecutionPolicy Bypass -File "%TOOLS%\fallback\wb_fallback.ps1" %*
set "RC=%ERRORLEVEL%"
goto :END

:RUNPY
set "PYTHONPATH=%TOOLS%"
set "PYTHONIOENCODING=utf-8"
set "PYTHONUTF8=1"
set "PYTHONDONTWRITEBYTECODE=1"
%PY% "%TOOLS%\main.py" %*
set "RC=%ERRORLEVEL%"
if "%RC%"=="0" goto :END
echo.
echo  [INFO] exit code %RC%
goto :END

:NOFILES
echo.
echo  [ERROR] tools\main.py is missing.
echo  Reason : the tool folder was not copied completely.
echo  Fix    : copy the whole folder, not just this one file.
set "RC=1"
goto :END

:NOPY
echo.
echo  [ERROR] No Python found and no fallback engine available.
echo  Reason : neither python on PATH nor tools\fallback\wb_fallback.ps1.
echo  Fix    : install Python 3.8+ from python.org, tick "Add python.exe to PATH",
echo           then run this file again.
set "RC=1"
goto :END

:END
echo %CMDCMDLINE% | find /i "%~nx0" >nul
if not errorlevel 1 pause
endlocal & exit /b %RC%
