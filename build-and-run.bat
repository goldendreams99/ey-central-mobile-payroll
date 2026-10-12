@echo off
setlocal enabledelayedexpansion

echo.
echo ========================================
echo   Employ Central - Build and Run
echo ========================================
echo.

cd /d "%~dp0"

:: 1. Build web app
echo [1/6] Building web app...
call npm run build
if %errorlevel% neq 0 (
    echo ERROR: npm run build failed
    pause
    exit /b 1
)
echo      OK
echo.

:: 2. Sync Capacitor
echo [2/6] Syncing with Capacitor...
call npx cap sync android
if %errorlevel% neq 0 (
    echo ERROR: cap sync failed
    pause
    exit /b 1
)
echo      OK
echo.

:: 3. Clean Android project
echo [3/6] Cleaning Android project...
cd android
call gradlew.bat clean
if %errorlevel% neq 0 (
    echo ERROR: gradlew clean failed
    pause
    exit /b 1
)
echo      OK
echo.

:: 4. Build Debug APK
echo [4/6] Building Debug APK...
call gradlew.bat assembleDebug
if %errorlevel% neq 0 (
    echo ERROR: gradlew assembleDebug failed
    pause
    exit /b 1
)
echo      OK
echo.

:: 5. Install on device
echo [5/6] Installing on device...
adb install -r app\build\outputs\apk\debug\app-debug.apk
if %errorlevel% neq 0 (
    echo ERROR: adb install failed
    pause
    exit /b 1
)
echo      OK
echo.

:: 6. Launch app
echo [6/6] Launching app...
adb shell am start -n com.employ.app/.MainActivity
if %errorlevel% neq 0 (
    echo WARNING: Could not launch app automatically
)
echo      OK
echo.

echo ========================================
echo   BUILD COMPLETE - App running!
echo ========================================
echo.

cd ..
pause
