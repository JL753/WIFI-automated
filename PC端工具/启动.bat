@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"
set "HERE=%~dp0"
if "%HERE:~-1%"=="\" set "HERE=%HERE:~0,-1%"

set "PY="
python -c "import sys" >nul 2>&1 && set "PY=python"
if not defined PY ( py -3 -c "import sys" >nul 2>&1 && set "PY=py -3" )
if not defined PY ( if exist "%HERE%\python\python.exe" set "PY=%HERE%\python\python.exe" )

if not defined PY goto :NOPY
if not exist "%HERE%\main.py" goto :NOFILES
if not exist "%HERE%\vendor\PySide6\__init__.py" goto :NOQT

set "PYTHONIOENCODING=utf-8"
set "PYTHONUTF8=1"
set "PYTHONDONTWRITEBYTECODE=1"
set "PYTHONPATH=%HERE%;%HERE%\vendor"

%PY% "%HERE%\main.py" %*
set "RC=%ERRORLEVEL%"
goto :DONE

:NOFILES
echo [ERROR] main.py is missing next to this launcher.
set "RC=2"
goto :DONE

:NOQT
echo [ERROR] PySide6 is missing in the vendor folder.
echo         Expected: vendor\PySide6\__init__.py
echo         Fix: copy the whole PC tool folder again, do not delete vendor.
set "RC=2"
goto :DONE

:NOPY
echo [ERROR] Python 3.8+ was not found on this computer.
echo.
echo   Reason : this GUI needs Python plus the bundled PySide6 in vendor.
echo   Fix    : install Python 3.8 or newer from https://www.python.org/downloads/
echo            and tick "Add python.exe to PATH", then double-click this file again.
echo            Or put a portable python folder here: %HERE%\python\python.exe
set "RC=1"
goto :DONE

:DONE
echo %CMDCMDLINE% | find /i "%~nx0" >nul
if not errorlevel 1 pause
endlocal & exit /b %RC%
