@echo off
setlocal
set GRADLE_VERSION=8.9
set GRADLE_HOME_BASE=%USERPROFILE%\.gradle\mira-bootstrap
set GRADLE_HOME=%GRADLE_HOME_BASE%\gradle-%GRADLE_VERSION%
set DIST_ZIP=%GRADLE_HOME_BASE%\gradle-%GRADLE_VERSION%-bin.zip

if not exist "%GRADLE_HOME%\bin\gradle.bat" (
  if not exist "%GRADLE_HOME_BASE%" mkdir "%GRADLE_HOME_BASE%"
  if not exist "%DIST_ZIP%" (
    powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile '%DIST_ZIP%'"
    if errorlevel 1 exit /b 1
  )
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -Path '%DIST_ZIP%' -DestinationPath '%GRADLE_HOME_BASE%' -Force"
  if errorlevel 1 exit /b 1
)
call "%GRADLE_HOME%\bin\gradle.bat" %*
exit /b %ERRORLEVEL%
