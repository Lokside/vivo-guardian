# 🛡️ Guardian — reliable notifications on vivo (OriginOS)

A small **no-root** Android app that stops **vivo** phones from cutting off your apps' notifications.

**Download**: [latest version (APK)](https://github.com/mohsinian/vivo-guardian/releases/latest) · [technical details and evidence](docs/TECHNICAL.md) · [changelog](CHANGELOG.md) · 🇫🇷 [Version française](README.fr.md)

---

> **Fork without an accessibility service** (versions `-noa11y`). This is a fork of [Lokside/vivo-guardian](https://github.com/Lokside/vivo-guardian): the research, the method and the original app are Lokside's. The one difference: banking apps (seen: BRAC Bank Astha) refuse to run while a third-party accessibility service is enabled, so here Guardian is kept alive as a **notification listener** instead, with every notification type disabled in its manifest. It is not on the accessibility list at all and receives no notification content. Nothing is hidden from the banking app: the accessibility service simply no longer exists. If no banking app complains on your phone, use the original. See [changelog](CHANGELOG.md) and [technical details](docs/TECHNICAL.md#4-keep-alive-a-notification-listener-not-an-accessibility-service).

## The problem

On vivo phones (Chinese OriginOS ROM), apps stop receiving notifications for hours, until you open them again. The cause, proven by the system logs:

1. **Launcher pre-start**: as soon as your finger **lands on an icon**, vivo starts the app in advance. If the gesture turns into a swipe (changing home screen page, scrolling the app drawer), vivo **force-stops** it ~50 to 150 ms later.
2. **Resource manager** (`com.vivo.sps:rms`): force-stops apps 1 to 2 s after the screen turns off.
3. **Cleaner** ("Clear all" in recent apps).

A **force-stopped** app is put in the "stopped" state: Google Play Services **refuses to deliver its notifications** (`Failed to broadcast to stopped app`) until it is opened again. An app that is merely killed (memory, card swiped away) is not affected: the next notification restarts it normally.

Apps you open **rarely** are hit the hardest (news, banking, smart home, work messaging…). No vivo setting prevents it: autostart, associated start or "high background power consumption" do not protect against force-stops, and the system switch (`persist.vivo.qos.prestartapp`) is refused without root.

## What Guardian does

It is a **notification listener** (not an accessibility service): Android keeps it alive and tells it as soon as an app is force-stopped. Guardian then sends a signal to one of that app's receivers, which restarts it in the background **without showing anything** and clears the "stopped" state. It does not run in a loop: it waits for Android to tell it.

| When | Action |
|---|---|
| an app is force-stopped | unblocks it (1 to 2 s) |
| 4 s after the screen turns off | checks the watched apps |
| screen on, then every 5 min while the screen is on | rebuilds the app list + reconnects GmsCore if installed |
| phone asleep | nothing (no alarm, no wakelock) |

- **Watched apps**: every installed app allowed to send notifications, except vivo / Android system apps. New apps are picked up automatically.
- A **"Force stop" done from Settings** is respected (the app stays stopped) if Guardian has the optional **usage access**; without it, that app is unblocked like the others.
- An app that cannot be unblocked is logged **once**, then left alone. "unblock failed" means vivo refused the wake-up: allow **autostart** for that app (i Manager → App manager → Autostart, or Settings → Apps → the app → Permissions). Seen on the test phone: every app with autostart off failed, every app with autostart on (Gmail, WhatsApp) was unblocked. From a computer: `adb shell content query --uri content://com.vivo.permissionmanager.provider.permission/bg_start_up_apps` (`currentstate=0` = allowed).
- The app screen shows: service status, apps blocked right now, apps that cannot be unblocked, log.
- Interface in **English**, and in **French** on phones set to French.

**Measured over a full day unplugged** (original accessibility version, 10 h 20, `dumpsys batterystats`): **0.73 mAh out of 1,794 mAh used (0.04 %)**, 7.9 s of CPU, 0 wakelocks, 0 alarms, 0 network.

**Privacy**: no Internet permission, **no accessibility service** (it cannot see or touch the screen), log stored on the phone only. It holds "notification access" only to stay alive: all notification types are disabled in the manifest (`disabled_filter_types`), so Android delivers it no notification, and it contains no code that reads one. Permissions: see the list of installed apps (`QUERY_ALL_PACKAGES` and `GET_INSTALLED_APPS`, mandatory on Chinese ROMs), and the optional usage access (`PACKAGE_USAGE_STATS`), used only to know whether the Settings app is on screen when an app is stopped. The whole code is ~550 lines: [src/](src/com/phili/notifguard).

## Compatibility

| | |
|---|---|
| Tested | **vivo X Fold6**, OriginOS 7 **Chinese ROM**, Android 17, locked bootloader, no root (accessibility version, v12) |
| Tested (this build) | vivo **V2502DA**, OriginOS 7, Android 17, no root |
| Probably | other vivo / iQOO phones on OriginOS (same system code) |
| Not tested | global ROMs (FuntouchOS / international OriginOS), other brands |
| Minimum | Android 8.0 |

---

## Installation

### Recommended method: with a computer (5 minutes)

This method reproduces the tested setup exactly.

**1. On the computer**: install the Android tools (adb).
- Windows: `winget install Google.PlatformTools` (or [download Platform Tools](https://developer.android.com/tools/releases/platform-tools) and unzip).
- macOS: `brew install android-platform-tools` · Linux: `adb` package.

**2. On the phone**: enable USB debugging.
- Settings → **About phone** → **Software version**: tap 7 times.
- Settings → System → **Developer options** → turn on **USB debugging** (and **Install via USB** if present).
- Plug in the cable, accept the computer's fingerprint on the phone.

**3. Download** from the [Releases page](https://github.com/mohsinian/vivo-guardian/releases/latest): `Guardian.apk` and `install.cmd` (Windows) or `install.sh` (macOS/Linux), into the **same folder**.

**4. Run the script** (double-click `install.cmd`, or `bash install.sh`). It runs:

| Step | Command | Why |
|---|---|---|
| 1 | `adb install -r Guardian.apk` | installs the app — **accept on the phone**, the vivo prompt expires quickly |
| 2 | `adb shell pm grant com.phili.notifguard com.android.permission.GET_INSTALLED_APPS` | without it, the app only sees system apps (0 apps watched) |
| 3 | `adb shell cmd appops set com.phili.notifguard ACCESS_RESTRICTED_SETTINGS allow` | Android 13+ greys out notification access for apps installed outside a store; vivo has no ⋮ menu to unlock it |
| 4 | `adb shell cmd appops set com.phili.notifguard AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore` | = "Pause app activity if unused" off: Android will never remove its permissions |
| 5 | `adb shell cmd deviceidle whitelist +com.phili.notifguard` | exempts it from Android battery optimisation |
| 6 | `adb shell cmd notification allow_listener com.phili.notifguard/com.phili.notifguard.GuardService` | turns the service on (notification access) |
| 7 | `adb shell cmd appops set com.phili.notifguard GET_USAGE_STATS allow` | optional usage access: a "Force stop" done from Settings is respected |
| 8 | `adb shell cmd deviceidle whitelist +app.revanced.android.gms` | only if GmsCore (ReVanced / Morphe microG) is installed |

Then it opens the app on the phone.

**5. On the phone: put the lock on (mandatory, once)** → see [below](#the-vivo-lock-mandatory).

### Without a computer

1. Download `Guardian.apk` on the phone and install it (allow installs from the browser / file manager).
2. Open **Guardian** → allow "list of installed apps".
3. **Notification access settings** button → Guardian → turn on.
   - If the switch is **greyed out** ("restricted setting"): you need the command from step 3 above, so a computer.
   - Optional: **Usage access settings** button → Guardian → turn on (a "Force stop" done from Settings is then respected).
4. Settings → Apps → Guardian → turn off "Pause app activity if unused" (if present).
5. Put the lock on (below).

### The vivo lock (mandatory)

Without it, **"Clear all" force-stops Guardian** like any other app. The recent-apps lock is **remembered per app** and survives reboots and updates.

1. In Guardian: **"Lock in recent apps (once)"** button.
2. Open **recent apps** → put the **lock** on the "Guardian" card (drag the card down, or long-press → Lock, depending on the version).
3. Tap the card to come back to Guardian → **"Done: remove the card"**.

⚠️ **Never** close Guardian's card by swiping it up: vivo force-stopped the accessibility version that way (`single-cleaner`); not re-tested with this fork. The rest of the time, Guardian has no card in recent apps.

### Recommended vivo settings (optional)

In **i Manager → App manager** (or Settings → Battery) for *Guardian*: autostart ✅, associated start ✅, high background power consumption ✅. The tested setup had them; they are not enough on their own (the lock is what protects against the cleaner). Names vary between versions.

---

## Check that everything works

- **In the app**: "✅ Service active" at the top, number of watched apps, blocked apps, log.
- **From the computer**: `check.cmd` (version, notification access, accessibility list, process, permissions, recent log).
- **Banking-app check**: `adb shell settings get secure enabled_accessibility_services` must not contain `com.phili.notifguard` (it cannot: the app declares no accessibility service).
- **Unblocking test** (replace with one of your apps):
  ```bash
  adb shell am force-stop com.whatsapp
  ```
  ```bash
  adb shell "sleep 3; dumpsys package com.whatsapp | grep -o 'stopped=[a-z]*'"
  ```
  → `stopped=false`: unblocked. Guardian's log shows "force-stop detected" then "unblocked ✓".
- **Lock test**: note `adb shell pidof com.phili.notifguard`, tap "Clear all" in recent apps, run the command again → **same number** = Guardian survived.
- Live log: `adb logcat -s NotifGuard`.

## Rules to know

1. When in doubt: open Guardian → "✅ Service active" or "❌ Service disabled".
2. If "❌ Service disabled": "Notification access settings" button → Guardian → turn on → come back → "Refresh". No need to put the lock on again.
3. "Refresh" only updates the display.
4. After an **update**: accept the install on the phone; the service usually stays on, otherwise rule 2.
5. The suspension of the accessibility service by vivo's security centre while Settings is open (previous versions) no longer applies: there is no accessibility service.

## What is not covered

- **Apps without an entry point**: some apps have no usable receiver (e.g. F-Droid, Proton Pass, Ente Auth, Le Monde, some banking or insurance apps). Guardian lists them under "Cannot be unblocked": if they go quiet, open them once.
- A notification refused while an app is blocked is **retried by Google** (seen: refused at 15:33, delivered at 15:39 after unblocking); maximum retry delay unknown.
- Per-app settings (e.g. Outlook/Gmail do not notify a mail sent from your own address).

**Without Guardian**, two manual workarounds: put important apps in a **folder** (touching a folder pre-starts nothing); scroll the app drawer from an **empty margin**, not with your finger on the icons.

**"Smart" battery mode**: no problem. vivo freezes the app when you leave it but unfreezes it as soon as a notification arrives (measured: 1.1 s between reception by Google and display). No need to set your apps to "high background power consumption".

## Bonus: patched YouTube (ReVanced / Morphe) with GmsCore

Patched YouTube notifications go through **GmsCore** (`app.revanced.android.gms`), not Google Play Services. vivo kills it like any ordinary app and it **does not reconnect on its own** (seen: 3 h without notifications). If it is installed, Guardian sends it a "soft" signal at every screen-on and every 5 min: reconnection if it dropped, a simple heartbeat otherwise (no needless reconnection). Measured: killed at 19:01:07, reconnected at 19:01:22.

Manual reconnection:
```bash
adb shell am broadcast -n app.revanced.android.gms/org.microg.gms.gcm.TriggerReceiver -a org.microg.gms.gcm.FORCE_TRY_RECONNECT
```

---

## Update, undo, uninstall

- **Update**: run `install.cmd` again with the new APK (or install the APK on top). The lock and settings are kept.
- **Uninstall**:
  ```bash
  adb uninstall com.phili.notifguard
  ```
  (the lock is lost; put it on again after reinstalling).
- **Undo the commands** one by one:

| Undoes step | Command |
|---|---|
| 3 | `adb shell cmd appops set com.phili.notifguard ACCESS_RESTRICTED_SETTINGS default` |
| 4 | `adb shell cmd appops set com.phili.notifguard AUTO_REVOKE_PERMISSIONS_IF_UNUSED default` |
| 5 | `adb shell cmd deviceidle whitelist -com.phili.notifguard` |
| 6 | `adb shell cmd notification disallow_listener com.phili.notifguard/com.phili.notifguard.GuardService` |
| 7 | `adb shell cmd appops set com.phili.notifguard GET_USAGE_STATS default` |
| 8 | `adb shell cmd deviceidle whitelist -app.revanced.android.gms` |

## Build it yourself

No Gradle: JDK 17+ and Android SDK (platform 36 and build-tools 36.0.0, or the newest ones installed; `ANDROID_HOME` set, or SDK in `~/Android/Sdk` or `%LOCALAPPDATA%AndroidSdk`).

```bash
bash build.sh
```

Produces `Guardian.apk` signed with your debug key (`~/.android/debug.keystore`, created on first build: keep it, updates must be signed with the same key). With your own key: `KEYSTORE=… KEYSTORE_PASS=… KEY_ALIAS=… bash build.sh`. An APK signed with another key does not install on top of the published version: uninstall first.

Translations: `res/values/strings.xml` (English) and `res/values-fr/strings.xml` (French). Pull requests for other languages are welcome.

## License

[MIT](LICENSE). Personal project, not affiliated with vivo. Provided as is: use at your own discretion.
