# Changelog

## v12.3-noa11y — 10/11/2026
- Back from a settings screen opened by a Guardian button, and Back or "Done" on the lock screen, return to Guardian's main screen (they used to land on the home screen, because the main screen closes itself as soon as it is left).
- Still no card in recent apps for the main screen: this is deliberate (a card can be swiped away).

## v12.2-noa11y — 10/11/2026
- The first button was hidden under the title bar (content drawn behind the bars since Android 15): no title bar any more, the screen keeps clear of the system bars.
- The settings buttons open in their own task (they used to close along with Guardian's screen).
- If notification access is granted but Android has not bound the service, opening Guardian binds it.
- New button "Guardian app settings (autostart)".

## v12.1-noa11y — 10/11/2026
- **No accessibility service any more**: banking apps (BRAC Bank Astha) refuse to run next to a third-party accessibility service. Guardian is now kept alive as a notification listener, with every notification type disabled in the manifest (it receives no notification).
- Check of the watched apps 4 s after the screen turns off (replaces the check on app switches).
- A "Force stop" done from Settings is respected only with the optional usage access.
- The status screen shows whether the listener is connected; buttons for notification access and usage access.
- `install` scripts: `cmd notification allow_listener` instead of the accessibility setting; `check.cmd` shows both lists.
- `build.sh`: finds the SDK in `%LOCALAPPDATA%AndroidSdk` and uses the newest build-tools / platform if 36 is not installed.

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
