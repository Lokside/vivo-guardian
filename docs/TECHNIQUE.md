# Détails techniques et preuves

🇬🇧 [English version](TECHNICAL.md)

Tout ce qui suit a été observé sur un **vivo X Fold6** (ROM chinoise OriginOS 7, Android 17, sans root), avec `adb logcat -b events`, `dumpsys activity exit-info`, le journal GCM de Google Play Services et le code de `vivo-services.jar` / `com.vivo.sps` (désassemblé).

> ⚠️ **Version 12.1-noa11y** : Guardian n'est plus un service d'accessibilité (les apps bancaires le refusent) mais un service d'écoute des notifications, tous types de notifications désactivés. Cette page décrit encore la v12 : la documentation à jour est en anglais ([README](../README.md), [détails techniques](TECHNICAL.md)).

## 1. Le pré-démarrage du lanceur

Quand le doigt se pose sur une icône, le lanceur prévient le système (`vboost` → `notifyTouchAppToAms`, actif si le système répond « version ≥ 2 »), puis :

`QoSManager.notifyTouchAppFromLauncher` → `PreStartAppPolicy` (`com.vivo.services.qos.policy.list.appStart`) → `VivoAmsImpl.killPreStartProcess` → **`forceStopPackage`** si l'app n'est pas passée au premier plan.

Test sur Amazon, geste de changement de page commencé sur l'icône :
```
19:11:24.598 am_proc_start [... com.amazon.mShop.android.shopping, preStart_down ...]
19:11:24.764 am_kill [... stop com.amazon.mShop.android.shopping due to stop]
```
Puis, à la notification suivante, dans le journal de Google Play Services :
```
Failed to broadcast to stopped app
```
- Interrupteurs : `persist.vivo.qos.prestartapp` / `persist.vivo.qos.downprestartapp` (refusés par `setprop` sans root) et configuration cloud vivo `pre_start_app_enable` / `down_pre_start_app_enable` (non modifiable). La liste d'exceptions est codée en dur.
- Décocher l'option de préchargement dans les réglages vivo ne change rien (test Amazon ci-dessus fait après).
- Le tiroir d'apps déclenche la même chose quand on le fait défiler avec le doigt posé sur les icônes. Un dossier, non.

## 2. Les autres arrêts forcés

- **Gestionnaire de ressources** `com.vivo.sps:rms`, 1 à 2 s après l'extinction de l'écran :
  ```
  19:04:30     écran éteint
  19:04:31.972 stop com.google.android.gm due to forceStopPackageByUid 1000pid 3604   (3604 = com.vivo.sps:rms)
  ```
- **Nettoyeur** (« Tout effacer ») : `due to cleaner`. Balayage d'une carte : `due to single-cleaner` (simple fermeture pour la plupart des apps, mais arrêt forcé pour un service d'accessibilité tiers).

À l'inverse, une app **tuée** (mémoire, `low memory`, `pruner`) n'est pas « stopped » : la notification suivante la relance normalement.

## 3. Comment Guardian débloque une app

- Il reçoit `ACTION_PACKAGE_RESTARTED` (diffusé par Android à chaque arrêt forcé).
- 300 ms plus tard, il envoie un intent explicite (`com.phili.notifguard.WAKE`, `FLAG_INCLUDE_STOPPED_PACKAGES | FLAG_RECEIVER_FOREGROUND`) à un récepteur **exporté et sans permission** de l'app : de préférence `BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED` ou `MY_PACKAGE_REPLACED`, à défaut n'importe quel récepteur exporté. Livrer un broadcast à une app « stopped » lève cet état. Jusqu'à 3 essais sur des récepteurs différents.
- Une app sans récepteur utilisable (ou qui ignore le signal) est notée une fois puis laissée tranquille jusqu'à ce que son état change.
- Si l'app Paramètres est au premier plan au moment de l'arrêt, c'est un « Forcer l'arrêt » de l'utilisateur : il est respecté.
- Le même test Amazon, Guardian actif :
  ```
  19:11:24.764 am_kill ... due to stop
  19:11:25.285 NotifGuard: arrêt forcé détecté : Boutique Amazon
  19:11:25.617 am_proc_start ... broadcast ... com.phili.notifguard.WAKE
  19:11:27.094 NotifGuard: Boutique Amazon débloqué ✓
  ```
  Gmail (vrai pré-démarrage) : arrêté à 17:55:27.344, débloqué à 17:55:28.209 (0,9 s).

## 4. Pourquoi un service d'accessibilité

C'est le seul composant qu'une app ordinaire peut garder en vie sur vivo sans root : Android le maintient (`procState=5`), vivo ne le congèle pas. Il ne demande que `typeWindowStateChanged` (sert de rappel pour vérifier les apps) et `canRetrieveWindowContent=false`.

Contraintes découvertes :
- **Nettoyeur** : `com.vivo.sps` (classes `NRMS-AccessibilityServices`) arrête de force les services d'accessibilité tiers à « Tout effacer », sauf ceux de sa liste. Pistes écartées :
  - `settings put secure third_srceen_read_packages …` : lue une seule fois au démarrage de `sps`, puis remise à la valeur d'usine à chaque redémarrage ;
  - listes « notclean » (`content://com.iqoo.secure.provider.secureprovider/speedupwhitelist`, etc.) : écriture refusée depuis adb.
  - **Solution** : le cadenas des apps récentes (`UserLockedAppFilter` / `LockedAppManager`) est mémorisé **par app**. Testé : « Tout effacer » a fermé 96 processus mais pas Guardian (même pid, accessibilité toujours active), aussi après redémarrage. C'est pourquoi l'app a un écran dédié, visible dans les récents le temps de poser le cadenas, alors que son écran principal en est exclu (`excludeFromRecents`, `finishAndRemoveTask()`).
- **Paramètres** : le centre de sécurité vivo (`com.vivo.safecenter`) retire le service de `enabled_accessibility_services` tant que l'app Paramètres est ouverte et le remet ~1 min après (vu : coupé à 18:22:05, remis à 18:23:02). Un redémarrage dans cette minute le laisse désactivé. Visible avec `adb shell dumpsys settings | grep name:enabled_accessibility_services`.
- **`GET_INSTALLED_APPS`** : permission de la norme chinoise ; sans elle, `getInstalledApplications` ne renvoie que les apps système.

## 5. Coût

Journée du 19/10/2026, 08:39 → 19:00, débranché, un redémarrage (`dumpsys batterystats --charged`) :
- Guardian : **0,73 mAh** sur 1 794 mAh (0,04 %), dont 0,42 mAh d'écran pendant ses 13 s d'affichage ; 7,9 s de processeur au total ; aucun wakelock, aucune alarme, aucun réseau.
- Processus jamais tué de la journée ; 14 coupures du service, toutes pendant un passage dans les Paramètres, reprises seules.
- Liste des apps mémorisée : le parcours complet (~450 apps installées) n'a lieu qu'à l'allumage de l'écran, toutes les 5 min et à l'installation / mise à jour d'une app. Contrôles fréquents : < 10 ms de processeur sur 70 s avec 6 changements d'app.

## 6. GmsCore (microG de ReVanced / Morphe)

- Le service push `org.microg.gms.gcm.McsService` tourne dans `app.revanced.android.gms:persistent`, tué par vivo (`low memory`, `pruner`) et pas relancé (vu : déconnecté ~3 h).
- Code de `TriggerReceiver.onReceive` : si `McsService` n'est pas connecté → « Not connected to GCM but should be, asking the service to start up » ; s'il l'est → « service is running. heartbeat instead ». Vérifié sur la connexion TCP vers le port 5228 : même port local avant/après le signal, donc aucune reconnexion inutile.
- Test : `:persistent` tué (`am kill`) → écran éteint/rallumé → reconnecté en < 3 s.

## 7. Le gel « fast_freezer » n'est pas en cause

En mode batterie « intelligent », vivo gèle une app ~5 s après chaque notification (`am_app_frozen … from fast_freezer`) et la dégèle à la suivante. Elle affiche sa notification avant d'être regelée : mail reçu par Google à 19:24:29.739, Gmail relancé, notification affichée à 19:24:30.835, regel à 19:24:36. « Consommation élevée en arrière-plan » supprime ce gel (`add to non-freezable`) mais coûte cher (39 mAh/jour pour Gmail contre 10 pour Outlook en mode intelligent) et ne protège pas de l'arrêt forcé.

## Diagnostic utile

```bash
adb shell dumpsys package <package> | grep -o "stopped=[a-z]*"
```
```bash
adb shell dumpsys activity exit-info <package>
```
(chercher `FORCE STOP … due to stop`, `cleaner`, `forceStopPackageByUid`)
