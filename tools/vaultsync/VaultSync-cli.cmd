@echo off
cd /d "%~dp0"
set "PATH=%~dp0runtime;%~dp0runtime\git\cmd;%PATH%"
if /I "%~1"=="setup" goto setup
if /I "%~1"=="configure" goto configure
if /I "%~1"=="start" goto start
node src\manage.js %*
exit /b %errorlevel%
:setup
node src\setup.js
exit /b %errorlevel%
:configure
node src\setup.js --reconfigure
exit /b %errorlevel%
:start
node src\index.js
exit /b %errorlevel%
