@echo off
rem Affiche l'etat du Gardien notifs sur le telephone branche (version, service, processus, autorisations).
rem La liste des apps bloquees et le journal complet sont dans l'app elle-meme.
setlocal
set "PKG=com.phili.notifguard"
set "ADB=adb"
for /d %%D in ("%LOCALAPPDATA%\Microsoft\WinGet\Packages\Google.PlatformTools_*") do (
  if exist "%%D\platform-tools\adb.exe" set "ADB=%%D\platform-tools\adb.exe"
)

echo == Version installee
"%ADB%" shell "dumpsys package %PKG% | grep -m1 versionName"
echo == Services d'accessibilite actifs (doit contenir %PKG%)
"%ADB%" shell settings get secure enabled_accessibility_services
echo == Processus (un numero = en marche ; il doit rester le meme apres "Tout effacer")
"%ADB%" shell pidof %PKG%
echo == Autorisation "liste des apps installees" (granted=true attendu)
"%ADB%" shell "dumpsys package %PKG% | grep -m1 'GET_INSTALLED_APPS: granted'"
echo == Exemption de l'optimisation batterie
"%ADB%" shell "cmd deviceidle whitelist | grep %PKG%"
echo == Journal recent (logcat ne garde que quelques minutes ; vide = rien a signaler)
"%ADB%" logcat -d -s NotifGuard
pause
