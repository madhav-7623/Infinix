@echo off
setlocal
set "DSS_EXE="

if exist "F:\OpenDSS\x64\OpenDSS.exe" set "DSS_EXE=F:\OpenDSS\x64\OpenDSS.exe"
if not defined DSS_EXE if exist "%ProgramFiles%\OpenDSS\x64\OpenDSS.exe" set "DSS_EXE=%ProgramFiles%\OpenDSS\x64\OpenDSS.exe"
if not defined DSS_EXE if exist "C:\OpenDSS\x64\OpenDSS.exe" set "DSS_EXE=C:\OpenDSS\x64\OpenDSS.exe"
if not defined DSS_EXE for /f "delims=" %%P in ('where OpenDSS.exe 2^>nul') do if not defined DSS_EXE set "DSS_EXE=%%P"

if not defined DSS_EXE (
  echo OpenDSS.exe was not found. Edit this file with your OpenDSS install path.
  pause
  exit /b 1
)

pushd "%~dp0"
start "ChargeSync OpenDSS" "%DSS_EXE%" "%~dp0Master.dss"
popd
endlocal
