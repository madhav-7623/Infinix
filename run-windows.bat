@echo off
setlocal
cd /d "%~dp0"

where java >nul 2>nul
if errorlevel 1 (
  echo Java was not found. Install a JDK 17 or newer, then reopen this window.
  echo Download: https://adoptium.net/temurin/releases/
  pause
  exit /b 1
)

where mvnd >nul 2>nul
if not errorlevel 1 goto use_mvnd

where mvn >nul 2>nul
if errorlevel 1 (
  echo Maven was not found. Install Apache Maven 3.8+ or Maven Daemon 1.0.6 and add its bin folder to PATH.
  echo Maven: https://maven.apache.org/download.cgi
  echo Maven Daemon: https://maven.apache.org/mvnd.html
  pause
  exit /b 1
)

echo Starting EV ChargeSync. Keep this window open while using the dashboard.
mvn spring-boot:run
goto finish

:use_mvnd
echo Starting EV ChargeSync with Maven Daemon. Keep this window open while using the dashboard.
mvnd spring-boot:run

:finish
if errorlevel 1 (
  echo.
  echo The application did not start. Check the messages above.
  pause
)
endlocal
