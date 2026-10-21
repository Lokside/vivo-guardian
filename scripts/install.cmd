@echo off
rem Installs and sets up Guardian on a vivo phone plugged in over USB (USB debugging on).
rem Usage: install.cmd                      -> looks for Guardian-*.apk next to the script
rem        install.cmd path\to\app.apk      -> installs this APK
setlocal
set "PKG=com.phili.notifguard"
set "SVC=com.phili.notifguard/com.phili.notifguard.GuardService"
set "ADB=adb"
for /d %%D in ("%LOCALAPPDATA%\Microsoft\WinGet\Packages\Google.PlatformTools_*") do (
  if exist "%%D\platform-tools\adb.exe" set "ADB=%%D\platform-tools\adb.exe"
)

set "APK=%~1"
if not defined APK for %%F in ("%~dp0Guardian*.apk" "%~dp0..\Guardian*.apk") do if exist "%%~F" set "APK=%%~fF"
if not defined APK goto :noapk
if not exist "%APK%" goto :noapk

"%ADB%" get-state >nul 2>&1
if errorlevel 1 (
  echo No phone detected. Plug it in, turn on USB debugging and accept the computer's fingerprint.
  goto :end
)

echo [1/7] Installing %APK%
echo       ACCEPT THE INSTALL ON THE PHONE SCREEN (the vivo prompt expires quickly).
"%ADB%" install -r "%APK%"
if errorlevel 1 (
  echo Install refused or expired: run the script again.
  goto :end
)
echo [2/7] Permission "list of installed apps" (GET_INSTALLED_APPS)
"%ADB%" shell pm grant %PKG% com.android.permission.GET_INSTALLED_APPS
echo [3/7] Restricted settings allowed (otherwise accessibility may be greyed out)
"%ADB%" shell cmd appops set %PKG% ACCESS_RESTRICTED_SETTINGS allow
echo [4/7] Permissions never removed if the app is not opened for a long time
"%ADB%" shell cmd appops set %PKG% AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore
echo [5/7] Exemption from Android battery optimisation
"%ADB%" shell cmd deviceidle whitelist +%PKG%
echo [6/7] Turning on the accessibility service (other active services are kept)
"%ADB%" shell "cur=$(settings get secure enabled_accessibility_services); case ${cur:-null} in *%SVC%*) ;; null) settings put secure enabled_accessibility_services %SVC% ;; *) settings put secure enabled_accessibility_services $cur:%SVC% ;; esac; settings put secure accessibility_enabled 1"
echo [7/7] GmsCore (ReVanced / Morphe microG) exempted from battery optimisation, if installed
"%ADB%" shell "pm path app.revanced.android.gms >/dev/null 2>&1 && cmd deviceidle whitelist +app.revanced.android.gms || echo       GmsCore not installed: nothing to do"

"%ADB%" shell am start -n %PKG%/.MainActivity >nul
echo.
echo Guardian is open on the phone. To do ONCE, on the phone:
echo   1. "Lock in recent apps" button, then follow the 3 steps (vivo lock).
echo   2. Check "Service active" at the top of Guardian's screen.
echo      Otherwise: "Accessibility settings" button - Guardian - turn on - Home button,
echo      then come back and check after 1 minute.
echo Check from the computer at any time: check.cmd
goto :end

:noapk
echo APK not found. Download Guardian-*.apk from the Releases page and put it next to
echo this script, or give its path: install.cmd C:\path\Guardian-v12.apk

:end
pause
