@echo off
setlocal
cd /d "%~dp0"

set "LAUNCHER_NAME=TinyCraftLauncher"
set "BUILD_DIR=build\launcher"
set "OUT_DIR=%BUILD_DIR%\out"
set "INPUT_DIR=%BUILD_DIR%\input"
set "IMAGE_DIR=%BUILD_DIR%\image\%LAUNCHER_NAME%"
set "APP_DIR=app\%LAUNCHER_NAME%"
set "RELEASE_DIR=release"
set "RELEASE_ZIP=%RELEASE_DIR%\%LAUNCHER_NAME%-windows.zip"
set "ICON=%CD%\TinyCraftLauncher.ico"

if not exist "%RELEASE_DIR%" mkdir "%RELEASE_DIR%"
if not exist app mkdir app
if exist "%BUILD_DIR%" rmdir /s /q "%BUILD_DIR%"
if exist "%APP_DIR%" rmdir /s /q "%APP_DIR%"
if exist "%RELEASE_ZIP%" del /q "%RELEASE_ZIP%"

mkdir "%OUT_DIR%"
mkdir "%INPUT_DIR%"

echo Building launcher classes...
javac -encoding UTF-8 --release 8 -d "%OUT_DIR%" Launcher.java
if errorlevel 1 (
    echo Launcher compilation failed.
    exit /b 1
)

echo Packaging TinyCraftLauncher.jar...
jar --create --file "%INPUT_DIR%\%LAUNCHER_NAME%.jar" --main-class Launcher -C "%OUT_DIR%" .
if errorlevel 1 (
    echo Launcher jar packaging failed.
    exit /b 1
)

jar tf "%INPUT_DIR%\%LAUNCHER_NAME%.jar" | findstr /L /C:"Launcher$InstallAndRunWorker$2.class" >nul
if errorlevel 1 (
    echo Missing Launcher$InstallAndRunWorker$2.class in %INPUT_DIR%\%LAUNCHER_NAME%.jar
    exit /b 1
)

jar tf "%INPUT_DIR%\%LAUNCHER_NAME%.jar" | findstr /L /C:"Launcher$InstallAndRunWorker.class" >nul
if errorlevel 1 (
    echo Missing Launcher$InstallAndRunWorker.class in %INPUT_DIR%\%LAUNCHER_NAME%.jar
    exit /b 1
)

echo Building Windows app image...
jpackage --type app-image ^
    --name %LAUNCHER_NAME% ^
    --input "%INPUT_DIR%" ^
    --main-jar %LAUNCHER_NAME%.jar ^
    --main-class Launcher ^
    --dest "%BUILD_DIR%\image" ^
    --icon "%ICON%"
if errorlevel 1 (
    echo Launcher app image build failed.
    exit /b 1
)

if not exist "%IMAGE_DIR%" (
    echo Launcher image directory not found: %IMAGE_DIR%
    exit /b 1
)

copy /Y ..\ChunkShader.vsh "%IMAGE_DIR%\ChunkShader.vsh" >nul
copy /Y ..\ChunkShader.fsh "%IMAGE_DIR%\ChunkShader.fsh" >nul
copy /Y ..\terrain.png "%IMAGE_DIR%\terrain.png" >nul
if exist launcher-update.txt copy /Y launcher-update.txt "%IMAGE_DIR%\launcher-update.txt" >nul
if errorlevel 1 (
    echo Failed to copy bundled launcher assets.
    exit /b 1
)

xcopy /E /I /Y "%IMAGE_DIR%" "%APP_DIR%" >nul
if errorlevel 1 (
    echo Failed to publish the launcher app image.
    exit /b 1
)

if not exist "%IMAGE_DIR%\app\%LAUNCHER_NAME%.jar" (
    echo Packaged launcher jar is missing from app image.
    exit /b 1
)

jar tf "%IMAGE_DIR%\app\%LAUNCHER_NAME%.jar" | findstr /L /C:"Launcher$InstallAndRunWorker$2.class" >nul
if errorlevel 1 (
    echo Packaged launcher jar is missing Launcher$InstallAndRunWorker$2.class
    exit /b 1
)

powershell -NoProfile -ExecutionPolicy Bypass -Command "Compress-Archive -Path '%APP_DIR%\*' -DestinationPath '%RELEASE_ZIP%' -Force"
if errorlevel 1 (
    echo Zip creation failed.
    exit /b 1
)

echo Done: %RELEASE_ZIP%
endlocal
