# 🛡️ Guardian — reliable notifications on vivo (OriginOS)

A small **no-root** Android app that stops **vivo** phones from cutting off your apps' notifications.

**Download**: [latest version (APK)](https://github.com/Lokside/vivo-guardian/releases/latest) · [technical details and evidence](docs/TECHNICAL.md) · [changelog](CHANGELOG.md) · 🇫🇷 [Version française](README.fr.md)

---

## The problem

On vivo phones (Chinese OriginOS ROM), apps stop receiving notifications for hours, until you open them again. The cause, proven by the system logs:

1. **Launcher pre-start**: as soon as your finger **lands on an icon**, vivo starts the app in advance. If the gesture turns into a swipe (changing home screen page, scrolling the app drawer), vivo **force-stops** it ~50 to 150 ms later.
2. **Resource manager** (`com.vivo.sps:rms`): force-stops apps 1 to 2 s after the screen turns off.
3. **Cleaner** ("Clear all" in recent apps).

A **force-stopped** app is put in the "stopped" state: Google Play Services **refuses to deliver its notifications** (`Failed to broadcast to stopped app`) until it is opened again. An app that is merely killed (memory, card swiped away) is not affected: the next notification restarts it normally.

Apps you open **rarely** are hit the hardest (news, banking, smart home, work messaging…). No vivo setting prevents it: autostart, associated start or "high background power consumption" do not protect against force-stops, and the system switch (`persist.vivo.qos.prestartapp`) is refused without root.

## What Guardian does

It is an **accessibility service**: Android keeps it alive and tells it as soon as an app is force-stopped. Guardian then sends a signal to one of that app's receivers, which restarts it in the background **without showing anything** and clears the "stopped" state. It does not run in a loop: it waits for Android to tell it.

| When | Action |
|---|---|
| an app is force-stopped | unblocks it (1 to 2 s) |
| app switch (at most every 20 s) | checks the watched apps |
| screen on, then every 5 min while the screen is on | rebuilds the app list + reconnects GmsCore if installed |
| phone asleep | nothing (no alarm, no wakelock) |

- **Watched apps**: every installed app allowed to send notifications, except vivo / Android system apps. New apps are picked up automatically.
- A **"Force stop" done from Settings** is respected (the app stays stopped).
- An app that cannot be unblocked is logged **once**, then left alone.
- The app screen shows: service status, apps blocked right now, apps that cannot be unblocked, log.
- Interface in **English**, and in **French** on phones set to French.

**Measured over a full day unplugged** (10 h 20, `dumpsys batterystats`): **0.73 mAh out of 1,794 mAh used (0.04 %)**, 7.9 s of CPU, 0 wakelocks, 0 alarms, 0 network.

**Privacy**: no Internet permission, reads **no** screen content (`canRetrieveWindowContent=false`), log stored on the phone only. Only permissions: see the list of installed apps (`QUERY_ALL_PACKAGES` and `GET_INSTALLED_APPS`, mandatory on Chinese ROMs). The whole code is ~550 lines: [src/](src/com/phili/notifguard).

## Compatibility

| | |
|---|---|
| Tested | **vivo X Fold6**, OriginOS 7 **Chinese ROM**, Android 17, locked bootloader, no root |
| Probably | other vivo / iQOO phones on OriginOS (same system code) — feedback welcome in *Issues* |
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

**3. Download** from the [Releases page](https://github.com/Lokside/vivo-guardian/releases/latest): `Guardian-v12.apk` and `install.cmd` (Windows) or `install.sh` (macOS/Linux), into the **same folder**.

**4. Run the script** (double-click `install.cmd`, or `bash install.sh`). It runs:

| Step | Command | Why |
|---|---|---|
| 1 | `adb install -r Guardian-v12.apk` | installs the app — **accept on the phone**, the vivo prompt expires quickly |
| 2 | `adb shell pm grant com.phili.notifguard com.android.permission.GET_INSTALLED_APPS` | without it, the app only sees system apps (0 apps watched) |
| 3 | `adb shell cmd appops set com.phili.notifguard ACCESS_RESTRICTED_SETTINGS allow` | Android 13+ greys out accessibility for apps installed outside a store; vivo has no ⋮ menu to unlock it |
| 4 | `adb shell cmd appops set com.phili.notifguard AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore` | = "Pause app activity if unused" off: Android will never remove its permissions |
| 5 | `adb shell cmd deviceidle whitelist +com.phili.notifguard` | exempts it from Android battery optimisation |
| 6 | adds `com.phili.notifguard/com.phili.notifguard.GuardService` to `settings secure enabled_accessibility_services` | turns the service on, **without turning off** your other accessibility services |
| 7 | `adb shell cmd deviceidle whitelist +app.revanced.android.gms` | only if GmsCore (ReVanced / Morphe microG) is installed |

Then it opens the app on the phone.

**5. On the phone: put the lock on (mandatory, once)** → see [below](#the-vivo-lock-mandatory).

### Without a computer

1. Download `Guardian-v12.apk` on the phone and install it (allow installs from the browser / file manager).
2. Open **Guardian** → allow "list of installed apps".
3. **Accessibility settings** button → Guardian → turn on → **Home button**.
   - If the switch is **greyed out** ("restricted setting"): you need the command from step 3 above, so a computer.
4. Settings → Apps → Guardian → turn off "Pause app activity if unused" (if present).
5. Put the lock on (below).

### The vivo lock (mandatory)

Without it, **"Clear all" force-stops Guardian** (vivo's cleaner force-stops every third-party accessibility service that is not on its internal list) and Android then turns its service off. The recent-apps lock is **remembered per app** and survives reboots and updates.

1. In Guardian: **"Lock in recent apps (once)"** button.
2. Open **recent apps** → put the **lock** on the "Guardian" card (drag the card down, or long-press → Lock, depending on the version).
3. Tap the card to come back to Guardian → **"Done: remove the card"**.

⚠️ **Never** close Guardian's card by swiping it up: vivo force-stops it (`single-cleaner`). The rest of the time, Guardian has no card in recent apps.

### Recommended vivo settings (optional)

In **i Manager → App manager** (or Settings → Battery) for *Guardian*: autostart ✅, associated start ✅, high background power consumption ✅. The tested setup had them; they are not enough on their own (the lock is what protects against the cleaner). Names vary between versions.

---

## Check that everything works

- **In the app**: "✅ Service active" at the top, number of watched apps, blocked apps, log.
- **From the computer**: `check.cmd` (version, service, process, permissions, recent log).
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

1. **Settings**: while the Settings app is open (any page), vivo's security centre **suspends** Guardian; it comes back **on its own ~1 min** after you leave. This is normal.
2. **Do not reboot within a minute** of visiting Settings (otherwise the service stays off).
3. When in doubt: open Guardian → "✅ Service active" or "❌ Service disabled".
4. If "❌ Service disabled": "Accessibility settings" button → Guardian → turn on → **Home button** → check again after ~1 min. No need to put the lock on again.
5. "Refresh" only updates the display.
6. After an **update**: accept the install on the phone; the service usually stays on, otherwise rule 4.

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
| 6 | Settings → Accessibility → Guardian → turn off |
| 7 | `adb shell cmd deviceidle whitelist -app.revanced.android.gms` |

## Build it yourself

No Gradle: JDK 17+ and Android SDK (platform 36, build-tools 36.0.0, `ANDROID_HOME` set or SDK in `~/Android/Sdk`).

```bash
bash build.sh
```

Produces `Guardian.apk` signed with your debug key. With your own key: `KEYSTORE=… KEYSTORE_PASS=… KEY_ALIAS=… bash build.sh`. An APK signed with another key does not install on top of the published version: uninstall first.

Translations: `res/values/strings.xml` (English) and `res/values-fr/strings.xml` (French). Pull requests for other languages are welcome.

## License

[MIT](LICENSE). Personal project, not affiliated with vivo. Provided as is: use at your own discretion.
