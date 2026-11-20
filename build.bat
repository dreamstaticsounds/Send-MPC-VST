@echo off
rem Build SendMPCVST.jar from src\main\java.  Needs a JDK (17 or newer) on PATH.
setlocal
cd /d "%~dp0"
if not exist build\classes mkdir build\classes
dir /s /b src\main\java\*.java > build\sources.txt
javac --release 17 -encoding UTF-8 -d build\classes @build\sources.txt
if errorlevel 1 exit /b 1
jar cfm SendMPCVST.jar MANIFEST.MF -C build\classes .
if errorlevel 1 exit /b 1
echo built SendMPCVST.jar
