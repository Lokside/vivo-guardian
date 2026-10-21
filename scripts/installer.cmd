@echo off
rem Installe et configure le Gardien notifs sur un telephone vivo branche en USB (debogage USB active).
rem Usage : installer.cmd                       -> cherche GardienNotifs-*.apk a cote du script
rem         installer.cmd chemin\vers\app.apk   -> installe cet APK
setlocal
set "PKG=com.phili.notifguard"
set "SVC=com.phili.notifguard/com.phili.notifguard.GuardService"
set "ADB=adb"
for /d %%D in ("%LOCALAPPDATA%\Microsoft\WinGet\Packages\Google.PlatformTools_*") do (
  if exist "%%D\platform-tools\adb.exe" set "ADB=%%D\platform-tools\adb.exe"
)

set "APK=%~1"
if not defined APK for %%F in ("%~dp0GardienNotifs*.apk" "%~dp0..\GardienNotifs*.apk" "%~dp0..\NotifGuard.apk") do if exist "%%~F" set "APK=%%~fF"
if not defined APK goto :noapk
if not exist "%APK%" goto :noapk

"%ADB%" get-state >nul 2>&1
if errorlevel 1 (
  echo Aucun telephone detecte. Branchez-le, activez le debogage USB et acceptez l'empreinte du PC.
  goto :end
)

echo [1/7] Installation de %APK%
echo       ACCEPTEZ L'INSTALLATION SUR L'ECRAN DU TELEPHONE (la fenetre vivo expire vite).
"%ADB%" install -r "%APK%"
if errorlevel 1 (
  echo Installation refusee ou expiree : relancez le script.
  goto :end
)
echo [2/7] Autorisation "liste des apps installees" (GET_INSTALLED_APPS)
"%ADB%" shell pm grant %PKG% com.android.permission.GET_INSTALLED_APPS
echo [3/7] Parametres restreints autorises (sinon l'accessibilite peut etre grisee)
"%ADB%" shell cmd appops set %PKG% ACCESS_RESTRICTED_SETTINGS allow
echo [4/7] Autorisations jamais retirees si l'app reste longtemps sans etre ouverte
"%ADB%" shell cmd appops set %PKG% AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore
echo [5/7] Exemption de l'optimisation batterie Android
"%ADB%" shell cmd deviceidle whitelist +%PKG%
echo [6/7] Activation du service d'accessibilite (les autres services actifs sont conserves)
"%ADB%" shell "cur=$(settings get secure enabled_accessibility_services); case ${cur:-null} in *%SVC%*) ;; null) settings put secure enabled_accessibility_services %SVC% ;; *) settings put secure enabled_accessibility_services $cur:%SVC% ;; esac; settings put secure accessibility_enabled 1"
echo [7/7] GmsCore (microG de ReVanced / Morphe) exempte de l'optimisation batterie, s'il est installe
"%ADB%" shell "pm path app.revanced.android.gms >/dev/null 2>&1 && cmd deviceidle whitelist +app.revanced.android.gms || echo       GmsCore absent : rien a faire"

"%ADB%" shell am start -n %PKG%/.MainActivity >nul
echo.
echo Le Gardien est ouvert sur le telephone. A faire UNE FOIS, sur le telephone :
echo   1. Bouton "Verrouiller dans les apps recentes" et suivre les 3 etapes (cadenas vivo).
echo   2. Verifier "Service actif" en haut de l'ecran du Gardien.
echo      Sinon : bouton "Reglages d'accessibilite" - Gardien notifs - activer - bouton Accueil,
echo      puis revenir verifier apres 1 minute.
echo Verification depuis le PC a tout moment : scripts\verifier.cmd
goto :end

:noapk
echo APK introuvable. Telechargez GardienNotifs-*.apk depuis la page Releases et placez-le a cote
echo de ce script, ou indiquez son chemin : installer.cmd C:\chemin\GardienNotifs-v11.apk

:end
pause
