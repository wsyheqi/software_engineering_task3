@echo off
setlocal
set "APP=%~dp0release\CampusHospitality.exe"
if not exist "%APP%" (
    echo Desktop package is missing. Run package-desktop.ps1 from this folder first.
    pause
    exit /b 1
)
start "" "%APP%"
