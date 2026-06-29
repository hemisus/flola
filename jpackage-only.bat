@echo off
REM ==========================================================
REM  FLOLA - jpackage build (ASCII only, safe on Korean Windows)
REM  Run AFTER Eclipse "clean package". Put next to pom.xml.
REM ==========================================================
setlocal

if not exist "target\flola.jar" (
  echo [ERROR] target\flola.jar not found.
  echo Run Eclipse: Run As - Maven build... - Goals: clean package
  pause
  exit /b 1
)

if exist jpackage-input rmdir /s /q jpackage-input
mkdir jpackage-input
copy /y "target\flola.jar" "jpackage-input\" >nul

if exist "dist\FLOLA" rmdir /s /q "dist\FLOLA"

REM Use flola.ico if present in project root (optional)
set ICON=
if exist flola.ico set ICON=--icon flola.ico

jpackage --type app-image --name FLOLA --input jpackage-input --main-jar flola.jar --main-class com.hemisus.flola.Launcher --app-version 0.1.1 %ICON% --dest dist

if errorlevel 1 (
  echo [ERROR] jpackage failed
  pause
  exit /b 1
)

echo.
echo [OK] Done. Output: dist\FLOLA\FLOLA.exe
pause
endlocal
