@echo off
rem Run the GUI (no arguments) or the CLI (pass flags).  Builds the jar first if missing.
setlocal
cd /d "%~dp0"
if not exist SendMPCVST.jar call build.bat
java -jar SendMPCVST.jar %*
