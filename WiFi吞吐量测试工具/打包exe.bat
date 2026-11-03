@echo off
rem Build a standalone onedir exe of this tool.
rem Usage: double-click, or: <this file> [output folder]
rem   default output: ..\WiFiTput
setlocal
cd /d "%~dp0"

set "HERE=%~dp0"
set "DIST=%~1"
if "%DIST%"=="" set "DIST=%HERE%..\WiFiTput"
set "PILIB=%HERE%tools\_buildtools\pyinstaller"

rem ---- 1. locate python ----
set "PY="
python -c "import sys" >nul 2>&1
if not errorlevel 1 set "PY=python"
if defined PY goto :HAVEPY
py -3 -c "import sys" >nul 2>&1
if not errorlevel 1 set "PY=py -3"
if defined PY goto :HAVEPY
if exist "%HERE%python\python.exe" set "PY=%HERE%python\python.exe"
if defined PY goto :HAVEPY
echo [ERROR] Python not found.
echo         Install Python 3.8+ or put a portable copy in python\ next to this file.
exit /b 1
:HAVEPY

rem ---- 2. locate PyInstaller ----
set "PYTHONPATH=%PILIB%"
set "PYTHONUTF8=1"
set "PYTHONDONTWRITEBYTECODE=1"
if not exist "%HERE%_build\tmp" mkdir "%HERE%_build\tmp"
set "TEMP=%HERE%_build\tmp"
set "TMP=%HERE%_build\tmp"
%PY% -m PyInstaller --version >nul 2>&1
if errorlevel 1 (
  echo [ERROR] PyInstaller not available.
  echo         Put the PyInstaller package tree in tools\_buildtools\pyinstaller
  echo         or run: %PY% -m pip install pyinstaller
  exit /b 1
)

rem ---- 3. build ----
echo Building...
%PY% -m PyInstaller --onedir --noconfirm --console --name WiFiTput --paths "tools" --distpath "%HERE%_build\dist" --workpath "%HERE%_build\work" --specpath "%HERE%_build" "tools\main.py"
if errorlevel 1 (
  echo [ERROR] PyInstaller build failed. See the log above.
  exit /b 1
)

rem ---- 4. copy bin beside the exe ----
rem xcopy is replaced by Copy-Item: under some antivirus / sandbox setups xcopy
rem stalls on this tree for minutes and then returns nothing.
if exist "%DIST%" rmdir /s /q "%DIST%"
if not exist "%DIST%" mkdir "%DIST%"
powershell -NoProfile -ExecutionPolicy Bypass -Command "Copy-Item -Path '%HERE%_build\dist\WiFiTput\*' -Destination '%DIST%' -Recurse -Force -ErrorAction Stop"
if errorlevel 1 (
  echo [ERROR] Cannot copy build output to %DIST%
  exit /b 1
)
if not exist "%DIST%\bin" mkdir "%DIST%\bin"
powershell -NoProfile -ExecutionPolicy Bypass -Command "Copy-Item -Path '%HERE%bin\*' -Destination '%DIST%\bin' -Recurse -Force -ErrorAction Stop"
if errorlevel 1 (
  echo [ERROR] Cannot copy bin\ to %DIST%\bin
  exit /b 1
)

rem ---- 5. clean ----
rmdir /s /q "%HERE%_build" >nul 2>&1

echo.
echo Done: %DIST%
echo Run  %DIST%\WiFiTput.exe  or  %DIST%\WiFiTput.exe list
endlocal
exit /b 0
