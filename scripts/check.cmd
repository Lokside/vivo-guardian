@echo off
rem Shows Guardian's state on the plugged-in phone (version, service, process, permissions).
rem The list of blocked apps and the full log are in the app itself.
setlocal
set "PKG=com.phili.notifguard"
set "ADB=adb"
if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
for /d %%D in ("%LOCALAPPDATA%\Microsoft\WinGet\Packages\Google.PlatformTools_*") do (
  if exist "%%D\platform-tools\adb.exe" set "ADB=%%D\platform-tools\adb.exe"
)

echo == Installed version
"%ADB%" shell "dumpsys package %PKG% | grep -m1 versionName"
echo == Apps with notification access (must contain %PKG%)
"%ADB%" shell settings get secure enabled_notification_listeners
echo == Active accessibility services (Guardian must NOT be listed: banking apps check this list)
"%ADB%" shell settings get secure enabled_accessibility_services
echo == Usage access (optional; "allow" = a Force stop done from Settings is respected)
"%ADB%" shell cmd appops get %PKG% GET_USAGE_STATS
echo == Process (a number = running; it must stay the same after "Clear all")
"%ADB%" shell pidof %PKG%
echo == Permission "list of installed apps" (granted=true expected)
"%ADB%" shell "dumpsys package %PKG% | grep -m1 'GET_INSTALLED_APPS: granted'"
echo == Battery optimisation exemption
"%ADB%" shell "cmd deviceidle whitelist | grep %PKG%"
echo == Recent log (logcat only keeps a few minutes; empty = nothing to report)
"%ADB%" logcat -d -s NotifGuard
pause
