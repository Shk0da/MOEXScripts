@echo off
cd /d "%~dp0"
kotlin fundamentals-scanner.kts
:confirm
echo.
set /p choice="Выйти? (Д/Н): "
if /i "%choice%"=="Д" exit /b
if /i "%choice%"=="д" exit /b
goto :confirm
