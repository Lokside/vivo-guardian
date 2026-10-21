# Historique

## v11 — 21/10/2026 (première version publique)
- Écran intégré pour poser le cadenas vivo dans les apps récentes (bouton « Verrouiller dans les apps récentes ») : plus besoin de compiler une version temporaire avec carte.
- Scripts d'installation ADB (`scripts/installer.cmd`, `scripts/installer.sh`) et de vérification.
- APK signé avec une clé de publication dédiée.

## v10 — 19/10/2026
- Aucune carte dans les apps récentes (`excludeFromRecents` + `finishAndRemoveTask()`), protection contre « Tout effacer » par le cadenas vivo.

## v6 — 18/10/2026
- Maintien de la connexion de GmsCore (microG de ReVanced / Morphe).

## v5 — 18/10/2026
- Liste des apps surveillées mémorisée : contrôles fréquents quasi gratuits.

## v4 — 18/10/2026
- Une app impossible à débloquer n'est notée qu'une fois puis laissée tranquille.

## v3 — 18/10/2026
- Surveille toutes les apps autorisées à notifier ; respecte un « Forcer l'arrêt » fait depuis les Paramètres.
