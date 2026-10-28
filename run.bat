@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"

echo ================================================================
echo    SwiftTransit - Bus Reservation System (SQLite Database)
echo ================================================================
echo.

:: 1. Download self-contained SQLite driver if no sqlite-jdbc jar exists
if not exist "sqlite-jdbc*.jar" (
    echo [*] Downloading SQLite JDBC driver (v3.36.0.3)...
    powershell -NoProfile -ExecutionPolicy Bypass -Command "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; (New-Object Net.WebClient).DownloadFile('https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.36.0.3/sqlite-jdbc-3.36.0.3.jar', 'sqlite-jdbc-3.36.0.3.jar')"
)

:: 2. Download SLF4J companion jars (in case any other sqlite driver is used)
if not exist "slf4j-api.jar" (
    echo [*] Downloading slf4j-api.jar...
    powershell -NoProfile -ExecutionPolicy Bypass -Command "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; (New-Object Net.WebClient).DownloadFile('https://repo1.maven.org/maven2/org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.jar', 'slf4j-api.jar')"
)
if not exist "slf4j-nop.jar" (
    echo [*] Downloading slf4j-nop.jar...
    powershell -NoProfile -ExecutionPolicy Bypass -Command "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; (New-Object Net.WebClient).DownloadFile('https://repo1.maven.org/maven2/org/slf4j/slf4j-nop/1.7.36/slf4j-nop-1.7.36.jar', 'slf4j-nop.jar')"
)

echo.
echo [*] Compiling BusReservationGUI.java...
javac -cp ".;*" BusReservationGUI.java
if errorlevel 1 (
    echo [!] Compilation failed. Please ensure JDK (javac) is installed and in your PATH.
    pause
    exit /b 1
)

echo [*] Starting application with SQLite database...
java -cp ".;*" BusReservationGUI

if errorlevel 1 (
    pause
)
