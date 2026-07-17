@echo off
setlocal
cd /d "%~dp0"

set "LAUNCHER_NAME=TinyCraftLauncher"
set "BUILD_DIR=build\launcher"
set "OUT_DIR=%BUILD_DIR%\out"
set "TEST_OUT_DIR=%BUILD_DIR%\test-out"
set "TEST_LOCALAPPDATA=%CD%\%BUILD_DIR%\test-localappdata"
set "INPUT_DIR=%BUILD_DIR%\input"
set "RUNTIME_DIR=%BUILD_DIR%\runtime"
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
mkdir "%TEST_OUT_DIR%"
mkdir "%TEST_LOCALAPPDATA%"
mkdir "%INPUT_DIR%"

echo Building launcher classes...
javac -Xlint:-options -encoding UTF-8 --release 8 -d "%OUT_DIR%" Launcher.java
if errorlevel 1 (
    echo Launcher compilation failed.
    exit /b 1
)

echo Building launcher tests...
javac -Xlint:-options -encoding UTF-8 --release 8 -cp "%OUT_DIR%" -d "%TEST_OUT_DIR%" ^
    tests\LauncherInstallTest.java ^
    tests\LauncherJavaSelectionTest.java ^
    tests\LauncherDownloadSecurityTest.java
if errorlevel 1 (
    echo Launcher test compilation failed.
    exit /b 1
)

echo Running launcher tests...
set "SAVED_LOCALAPPDATA=%LOCALAPPDATA%"
set "LOCALAPPDATA=%TEST_LOCALAPPDATA%"
java -Djava.awt.headless=true -cp "%OUT_DIR%;%TEST_OUT_DIR%" LauncherInstallTest
if errorlevel 1 goto :test_failed
java -Djava.awt.headless=true -cp "%OUT_DIR%;%TEST_OUT_DIR%" LauncherJavaSelectionTest
if errorlevel 1 goto :test_failed
java -Djava.awt.headless=true -cp "%OUT_DIR%;%TEST_OUT_DIR%" LauncherDownloadSecurityTest
if errorlevel 1 goto :test_failed
set "LOCALAPPDATA=%SAVED_LOCALAPPDATA%"

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

echo Building optimized Java runtime...
jlink ^
    --add-modules java.base,java.desktop,jdk.crypto.ec,jdk.unsupported ^
    --strip-debug ^
    --no-header-files ^
    --no-man-pages ^
    --compress=zip-6 ^
    --output "%RUNTIME_DIR%"
if errorlevel 1 (
    echo Optimized Java runtime build failed.
    exit /b 1
)

if not exist "%RUNTIME_DIR%\bin\java.exe" (
    echo Optimized runtime is missing bin\java.exe.
    exit /b 1
)

"%RUNTIME_DIR%\bin\java.exe" -Djava.awt.headless=true -cp "%OUT_DIR%;%TEST_OUT_DIR%" LauncherDownloadSecurityTest
if errorlevel 1 (
    echo Optimized Java runtime smoke test failed.
    exit /b 1
)

echo Building Windows app image...
jpackage --type app-image ^
    --name %LAUNCHER_NAME% ^
    --input "%INPUT_DIR%" ^
    --main-jar %LAUNCHER_NAME%.jar ^
    --main-class Launcher ^
    --runtime-image "%RUNTIME_DIR%" ^
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
exit /b 0

:test_failed
set "LOCALAPPDATA=%SAVED_LOCALAPPDATA%"
echo Launcher tests failed.
exit /b 1

endlocal
