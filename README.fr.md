# 🛡️ Guardian — notifications fiables sur vivo (OriginOS)

Petite app Android **sans root** qui empêche les téléphones **vivo** de couper les notifications de vos apps.

**Télécharger** : [dernière version (APK)](https://github.com/Lokside/vivo-guardian/releases/latest) · [détails techniques et preuves](docs/TECHNIQUE.md) · [historique](CHANGELOG.md) · 🇬🇧 [English version](README.md)

---

> ⚠️ **Version sans service d'accessibilité** : Guardian n'est plus un service d'accessibilité (les apps bancaires le refusent) mais un service d'écoute des notifications, tous types de notifications désactivés. Cette page décrit encore la v12 : la documentation à jour est en anglais ([README](README.md), [détails techniques](docs/TECHNICAL.md)).

## Le problème

Sur vivo (ROM chinoise OriginOS), vos apps arrêtent de recevoir leurs notifications pendant des heures, jusqu'à ce que vous les rouvriez. La cause, prouvée par les journaux système :

1. **Pré-démarrage du lanceur** : dès que le doigt se **pose sur une icône**, vivo lance l'app à l'avance. Si le geste devient un balayage (changer de page, faire défiler le tiroir d'apps), vivo l'**arrête de force** ~50 à 150 ms plus tard.
2. **Gestionnaire de ressources** (`com.vivo.sps:rms`) : arrête de force des apps 1 à 2 s après l'extinction de l'écran.
3. **Nettoyeur** (« Tout effacer » dans les apps récentes).

Une app **arrêtée de force** passe à l'état « stopped » : Google Play Services **refuse de lui livrer ses notifications** (`Failed to broadcast to stopped app`) tant qu'on ne l'a pas rouverte. Une app simplement fermée (mémoire, carte balayée) n'est pas concernée : la notification la relance normalement.

Les apps que vous ouvrez **rarement** sont les plus touchées (journal, banque, domotique, messagerie pro…). Aucun réglage vivo ne l'empêche : le démarrage auto, le démarrage associé ou la « consommation élevée en arrière-plan » ne protègent pas de l'arrêt forcé, et l'interrupteur système (`persist.vivo.qos.prestartapp`) est refusé sans root.

## Ce que fait Guardian

C'est un **service d'accessibilité** : Android le garde en vie et le prévient dès qu'une app est arrêtée de force. Il envoie alors un signal à l'un des récepteurs de cette app, ce qui la relance en arrière-plan **sans rien afficher** et lève l'état « stopped ». Il ne tourne pas en boucle : il attend qu'Android le prévienne.

| Quand | Action |
|---|---|
| une app est arrêtée de force | la débloque (1 à 2 s) |
| changement d'app (au plus toutes les 20 s) | vérifie les apps surveillées |
| allumage d'écran, puis toutes les 5 min écran allumé | refait la liste des apps + reconnecte GmsCore s'il est installé |
| téléphone en veille | rien (aucune alarme, aucun maintien d'éveil) |

- **Apps surveillées** : toutes les apps installées autorisées à envoyer des notifications, hors apps système vivo / Android. Une nouvelle app est prise en compte toute seule.
- Un **« Forcer l'arrêt » fait depuis les Paramètres** est respecté (l'app reste arrêtée).
- Une app impossible à débloquer est notée **une fois** dans le journal, puis laissée tranquille.
- L'écran de l'app montre : état du service, apps bloquées en ce moment, apps impossibles à débloquer, journal.
- Interface en **français** sur un téléphone réglé en français, en **anglais** sinon.

**Mesuré sur une journée entière débranchée** (10 h 20, `dumpsys batterystats`) : **0,73 mAh sur 1 794 mAh consommés (0,04 %)**, 7,9 s de processeur, 0 maintien d'éveil, 0 alarme, 0 réseau.

**Vie privée** : pas d'autorisation Internet, ne lit **aucun** contenu de l'écran (`canRetrieveWindowContent=false`), journal stocké uniquement sur le téléphone. Seules autorisations : voir la liste des apps installées (`QUERY_ALL_PACKAGES` et `GET_INSTALLED_APPS`, obligatoire sur les ROM chinoises). Le code complet fait ~550 lignes : [src/](src/com/phili/notifguard).

## Compatibilité

| | |
|---|---|
| Testé | **vivo X Fold6**, OriginOS 7 **ROM chinoise**, Android 17, bootloader verrouillé, sans root |
| Probablement | autres vivo / iQOO sous OriginOS (même code système) — retours bienvenus dans les *Issues* |
| Non testé | ROM globales FuntouchOS / OriginOS international, autres marques |
| Minimum | Android 8.0 |

---

## Installation

### Méthode recommandée : avec un PC (5 minutes)

C'est la méthode qui reproduit exactement la configuration testée.

**1. Sur le PC** : installer les outils Android (adb).
- Windows : `winget install Google.PlatformTools` (ou [télécharger Platform Tools](https://developer.android.com/tools/releases/platform-tools) et dézipper).
- macOS : `brew install android-platform-tools` · Linux : paquet `adb`.

**2. Sur le téléphone** : activer le débogage USB.
- Paramètres → **À propos du téléphone** → **Version du logiciel** : toucher 7 fois.
- Paramètres → Système → **Options pour les développeurs** → activer **Débogage USB** (et **Installation via USB** s'il existe).
- Brancher le câble, accepter l'empreinte du PC sur le téléphone.

**3. Télécharger** depuis la [page Releases](https://github.com/Lokside/vivo-guardian/releases/latest) : `Guardian.apk` et `install.cmd` (Windows) ou `install.sh` (macOS/Linux), dans le **même dossier**.

**4. Lancer le script** (double-clic sur `install.cmd`, ou `bash install.sh`). Il :

| Étape | Commande | Pourquoi |
|---|---|---|
| 1 | `adb install -r Guardian-v12.apk` | installe l'app — **acceptez sur le téléphone**, la fenêtre vivo expire vite |
| 2 | `adb shell pm grant com.phili.notifguard com.android.permission.GET_INSTALLED_APPS` | sans elle, l'app ne voit que les apps système (0 app surveillée) |
| 3 | `adb shell cmd appops set com.phili.notifguard ACCESS_RESTRICTED_SETTINGS allow` | Android 13+ grise l'accessibilité des apps installées hors magasin ; vivo n'a pas le menu ⋮ pour la débloquer |
| 4 | `adb shell cmd appops set com.phili.notifguard AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore` | = « Interrompre l'activité si non utilisée » désactivé : Android ne retirera jamais ses autorisations |
| 5 | `adb shell cmd deviceidle whitelist +com.phili.notifguard` | exemption de l'optimisation batterie Android |
| 6 | ajoute `com.phili.notifguard/com.phili.notifguard.GuardService` à `settings secure enabled_accessibility_services` | active le service, **sans désactiver** vos autres services d'accessibilité |
| 7 | `adb shell cmd deviceidle whitelist +app.revanced.android.gms` | seulement si GmsCore (microG de ReVanced / Morphe) est installé |

Puis il ouvre l'app sur le téléphone.

**5. Sur le téléphone : poser le cadenas (obligatoire, une seule fois)** → voir [plus bas](#le-cadenas-vivo-obligatoire).

### Sans PC

1. Télécharger `Guardian-v12.apk` depuis le téléphone et l'installer (autoriser l'installation depuis le navigateur / gestionnaire de fichiers).
2. Ouvrir **Guardian** → accepter « liste des apps installées ».
3. Bouton **Réglages d'accessibilité** → Guardian → activer → **bouton Accueil**.
   - Si l'interrupteur est **grisé** (« paramètre restreint ») : il faut la commande de l'étape 3 ci-dessus, donc un PC.
4. Paramètres → Applications → Guardian → désactiver « Interrompre l'activité de l'appli si elle n'est pas utilisée » (si présent).
5. Poser le cadenas (ci-dessous).

### Le cadenas vivo (obligatoire)

Sans lui, **« Tout effacer » arrête Guardian de force** (le nettoyeur vivo arrête tous les services d'accessibilité tiers qui ne sont pas dans sa liste interne) et Android désactive alors son service. Le cadenas des apps récentes est **mémorisé par app** et conservé après redémarrage et mise à jour.

1. Dans Guardian : bouton **« Verrouiller dans les apps récentes (une fois) »**.
2. Ouvrir les **apps récentes** → poser le **cadenas** sur la carte « Guardian » (glisser la carte vers le bas, ou appui long → Verrouiller, selon la version).
3. Toucher la carte pour revenir dans Guardian → **« Terminé : retirer la carte »**.

⚠️ Ne **jamais** fermer la carte de Guardian en la balayant vers le haut : vivo l'arrête de force (`single-cleaner`). Le reste du temps, Guardian n'a aucune carte dans les apps récentes.

### Réglages vivo conseillés (facultatif)

Dans **i Manager → Gestion des applications** (ou Paramètres → Batterie) pour *Guardian* : démarrage auto ✅, démarrage associé ✅, consommation élevée en arrière-plan ✅. La configuration testée les avait ; ils ne suffisent pas seuls (c'est le cadenas qui protège du nettoyeur).

---

## Vérifier que tout marche

- **Dans l'app** : « ✅ Service actif » en haut, nombre d'apps surveillées, apps bloquées, journal.
- **Depuis le PC** : `check.cmd` (version, service, processus, autorisations, journal récent).
- **Test d'un déblocage** (remplacer par une de vos apps) :
  ```bash
  adb shell am force-stop com.whatsapp
  ```
  ```bash
  adb shell "sleep 3; dumpsys package com.whatsapp | grep -o 'stopped=[a-z]*'"
  ```
  → `stopped=false` : débloquée. Le journal de Guardian affiche « arrêt forcé détecté » puis « débloqué ✓ ».
- **Test du cadenas** : noter `adb shell pidof com.phili.notifguard`, faire « Tout effacer » dans les apps récentes, relancer la commande → **même numéro** = Guardian a survécu.
- Journal en direct : `adb logcat -s NotifGuard`.

## Règles à connaître

1. **Paramètres** : tant que l'app Paramètres est ouverte (n'importe quelle page), le centre de sécurité vivo **suspend** Guardian ; il revient **tout seul ~1 min** après la sortie. C'est normal.
2. **Ne pas redémarrer dans la minute** qui suit un passage dans les Paramètres (sinon le service reste désactivé).
3. En cas de doute : ouvrir Guardian → « ✅ Service actif » ou « ❌ Service désactivé ».
4. Si « ❌ Service désactivé » : bouton « Réglages d'accessibilité » → Guardian → activer → **bouton Accueil** → revérifier après ~1 min. Inutile de reposer le cadenas.
5. « Rafraîchir » ne fait que mettre l'affichage à jour.
6. Après une **mise à jour** : accepter l'installation sur le téléphone ; le service reste en général activé, sinon règle 4.

## Ce qui n'est pas couvert

- **Apps sans point d'entrée** : certaines apps n'ont aucun récepteur utilisable (ex. F-Droid, Proton Pass, Ente Auth, Le Monde, quelques apps de banque ou de mutuelle). Guardian les liste dans « Impossibles à débloquer » : si elles se taisent, les ouvrir une fois.
- Une notification refusée pendant un blocage est **réessayée par Google** (vu : refus à 15:33, livraison à 15:39 après déblocage) ; délai maximal inconnu.
- Réglages propres à chaque app (ex. Outlook/Gmail ne notifient pas un mail envoyé depuis votre propre adresse).

**Sans Guardian**, deux parades manuelles : ranger les apps importantes dans un **dossier** (toucher un dossier ne pré-démarre rien) ; faire défiler le tiroir d'apps depuis une **marge vide**, pas avec le doigt sur les icônes.

**Mode batterie « intelligent »** : aucun souci. vivo gèle l'app quand vous en sortez mais la dégèle dès qu'une notification arrive (mesuré : 1,1 s entre la réception par Google et l'affichage). Inutile de passer vos apps en « consommation élevée ».

## Bonus : YouTube patché (ReVanced / Morphe) avec GmsCore

Les notifications de YouTube patché passent par **GmsCore** (`app.revanced.android.gms`), pas par Google Play Services. vivo le tue comme une app ordinaire et il **ne se reconnecte pas tout seul** (vu : 3 h sans notifications). S'il est installé, Guardian lui envoie à chaque allumage d'écran et toutes les 5 min un signal « doux » : reconnexion s'il est tombé, simple ping sinon (aucune reconnexion inutile). Mesuré : tué à 19:01:07, reconnecté à 19:01:22.

Reconnexion manuelle :
```bash
adb shell am broadcast -n app.revanced.android.gms/org.microg.gms.gcm.TriggerReceiver -a org.microg.gms.gcm.FORCE_TRY_RECONNECT
```

---

## Mettre à jour, annuler, désinstaller

- **Mettre à jour** : relancer `install.cmd` avec le nouvel APK (ou installer l'APK par-dessus). Le cadenas et les réglages sont conservés.
- **Désinstaller** :
  ```bash
  adb uninstall com.phili.notifguard
  ```
  (le cadenas est perdu ; à reposer après une réinstallation).
- **Annuler les commandes** une par une :

| Annule l'étape | Commande |
|---|---|
| 3 | `adb shell cmd appops set com.phili.notifguard ACCESS_RESTRICTED_SETTINGS default` |
| 4 | `adb shell cmd appops set com.phili.notifguard AUTO_REVOKE_PERMISSIONS_IF_UNUSED default` |
| 5 | `adb shell cmd deviceidle whitelist -com.phili.notifguard` |
| 6 | Paramètres → Accessibilité → Guardian → désactiver |
| 7 | `adb shell cmd deviceidle whitelist -app.revanced.android.gms` |

## Compiler soi-même

Sans Gradle : JDK 17+ et Android SDK (platform 36, build-tools 36.0.0, `ANDROID_HOME` défini ou SDK dans `~/Android/Sdk`).

```bash
bash build.sh
```

Produit `Guardian.apk` signé avec votre clé de debug. Avec votre propre clé : `KEYSTORE=… KEYSTORE_PASS=… KEY_ALIAS=… bash build.sh`. Un APK signé avec une autre clé ne s'installe pas par-dessus la version publiée : désinstaller d'abord.

Traductions : `res/values/strings.xml` (anglais) et `res/values-fr/strings.xml` (français).

## Licence

[MIT](LICENSE). Projet personnel, sans lien avec vivo. Fourni tel quel : utilisez-le en connaissance de cause.
