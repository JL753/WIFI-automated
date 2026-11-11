@echo off
setlocal
cd /d "%~dp0"
set "HERE=%~dp0"
if "%HERE:~-1%"=="\" set "HERE=%HERE:~0,-1%"

rem ---- parameters ----------------------------------------------------
set "ARGS=%*"
set "CONSOLE=0"
echo %ARGS% | find /i "--selftest" >nul && set "CONSOLE=1"
echo %ARGS% | find /i "-selftest" >nul && set "CONSOLE=1"

rem ---- files ----------------------------------------------------------
if not exist "%HERE%\main.py" goto :NOFILES
if not exist "%HERE%\pcapp\paths.py" goto :NOFILES

rem ---- find a Python whose ABI matches the bundled PySide6 -------------
set "LIST=%TEMP%\wifitput_py_%RANDOM%.txt"
( echo python
  echo py -3
  echo py -3.15
  echo py -3.14
  echo py -3.13
  echo py -3.12
  echo py -3.11
  echo py -3.10
  echo "%HERE%\python\python.exe"
) > "%LIST%"

set "PYCMD="
for /f "usebackq delims=" %%C in ("%LIST%") do call :TRY "%%~C"
del "%LIST%" >nul 2>&1
if not defined PYCMD goto :NOQT
goto :READY

:TRY
rem %1 = command line that starts a Python interpreter
set "CAND=%~1"
if defined PYCMD exit /b 0
rem Skip the portable interpreter when it is absent; PATH names are probed directly.
echo %CAND% | find /i "python\python.exe" >nul && if not exist %CAND% exit /b 0
set "PYPATH="
for /f "delims=" %%P in ('%CAND% -c "import sys;print(sys.executable)" 2^>nul') do set "PYPATH=%%P"
if not defined PYPATH exit /b 0
set "PIPOK="
for /f "delims=" %%P in ('%CAND% -c "import importlib.util;print(chr(49) if importlib.util.find_spec(chr(80)+chr(121)+chr(83)+chr(105)+chr(100)+chr(101)+chr(54)) else chr(48))" 2^>nul') do set "PIPOK=%%P"
if "%PIPOK%"=="1" ( set "PYCMD=%CAND%" & exit /b 0 )
if exist "%HERE%\vendor\PySide6\__init__.py" (
  set "PYTHONPATH=%HERE%;%HERE%\vendor"
  %CAND% -c "import PySide6.QtWidgets" >nul 2>&1
  if not errorlevel 1 ( set "PYCMD=%CAND%" & exit /b 0 )
)
exit /b 0

:READY
rem ---- install the missing GUI dependency into the ignored vendor dir --
if not exist "%HERE%\vendor\PySide6\__init__.py" (
  echo [INFO] Installing PySide6 6.12.0 into vendor\ ...
  echo         This needs internet access once and takes a few minutes.
  %PYCMD% -m pip install --disable-pip-version-check --target "%HERE%\vendor" PySide6==6.12.0
  if errorlevel 1 goto :INSTALLFAIL
)
if not exist "%HERE%\vendor\PySide6\__init__.py" goto :NOQT

set "PYTHONIOENCODING=utf-8"
set "PYTHONUTF8=1"
set "PYTHONDONTWRITEBYTECODE=1"
set "PYTHONPATH=%HERE%;%HERE%\vendor"

rem ---- console mode: selftest keeps its output visible -----------------
if "%CONSOLE%"=="1" (
  %PYCMD% "%HERE%\main.py" %ARGS%
  set "RC=%ERRORLEVEL%"
  goto :DONE
)

rem ---- GUI mode: no console window at all -------------------------------
set "PYW=%PYPATH%"
if /i "%PYW:~-4%"==".exe" set "PYW=%PYW:~0,-4%w.exe"
if not exist "%PYW%" set "PYW=%PYPATH%"
start "" /B "%PYW%" "%HERE%\main.py" %ARGS%
set "RC=0"
goto :DONE

:NOFILES
echo [ERROR] main.py or pcapp\paths.py is missing next to this launcher.
echo         Fix: copy the whole PC tool folder again.
set "RC=2"
goto :DONE

:INSTALLFAIL
echo [ERROR] Failed to install PySide6 into vendor\.
echo         Fix: run  python -m pip install --target vendor PySide6==6.12.0
echo              from this folder, then double-click 启动.bat again.
set "RC=2"
goto :DONE

:NOQT
echo [ERROR] No Python 3.8+ was found, or no PySide6 6.12.0 matches it.
echo         Fix: install Python 3.8+ from https://www.python.org/downloads/
echo              and tick "Add python.exe to PATH", then double-click 启动.bat again.
set "RC=1"
goto :DONE

:DONE
if "%CONSOLE%"=="1" (
  echo %CMDCMDLINE% | find /i "%~nx0" >nul
  if not errorlevel 1 pause
)
endlocal & exit /b %RC%
