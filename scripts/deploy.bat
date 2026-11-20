@echo off
REM ---------------------------------------------------------------------------
REM Build demoEJB-Transaction and hot-deploy it into JBoss EAP 8.0.
REM Requires: EAP running (..\demoEJB_Stateless\scripts\start-server.bat)
REM ---------------------------------------------------------------------------
setlocal enabledelayedexpansion
set "JAVA_HOME=C:\Program Files\Java\jdk-17.0.18"
set "MAVEN_HOME=D:\APPS_EJB_20261119\tools\apache-maven-3.9.16"
set "DEPLOY=D:\Download\jboss-eap-8.0.0\jboss-eap-8.0\standalone\deployments"
set "URL=http://localhost:8080/demoEJB-Transaction/"

cd /d "%~dp0.."

echo === Building (JDK 17 required - machine JAVA_HOME is JDK 8) ===
call "%MAVEN_HOME%\bin\mvn.cmd" -B -ntp clean package
if errorlevel 1 ( echo [ERROR] build failed & exit /b 1 )

echo.
echo === Deploying ===
copy /y "target\demoEJB-Transaction.war" "%DEPLOY%\" >nul
if errorlevel 1 ( echo [ERROR] copy failed - is the server running? & exit /b 1 )

echo Waiting for the deployment scanner ...
ping -n 8 127.0.0.1 >nul

set "CODE="
for /L %%i in (1,1,30) do (
    for /f %%c in ('curl -s -o NUL -w "%%{http_code}" "%URL%"') do set "CODE=%%c"
    if "!CODE!"=="200" goto :ready
    ping -n 2 127.0.0.1 >nul
)
:ready
echo App responded with HTTP %CODE%
echo Open %URL%
