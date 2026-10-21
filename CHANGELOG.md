# Changelog

## v12 — 21/10/2026
- New name: **Guardian** (was "Gardien notifs").
- Interface in English, French translation on phones set to French.
- Documentation and ADB scripts in English (`install.cmd`, `install.sh`, `check.cmd`); French README kept.

## v11 — 21/10/2026 (first public version)
- Built-in screen to put the vivo lock on in recent apps ("Lock in recent apps" button): no more need to build a temporary version with a card.
- ADB install and check scripts.
- APK signed with a dedicated release key.

## v10 — 19/10/2026
- No card in recent apps (`excludeFromRecents` + `finishAndRemoveTask()`), protection against "Clear all" through the vivo lock.

## v6 — 18/10/2026
- Keeps GmsCore (ReVanced / Morphe microG) connected.

## v5 — 18/10/2026
- Cached list of watched apps: frequent checks are almost free.

## v4 — 18/10/2026
- An app that cannot be unblocked is logged only once, then left alone.

## v3 — 18/10/2026
- Watches every app allowed to post notifications; respects a "Force stop" done from Settings.
