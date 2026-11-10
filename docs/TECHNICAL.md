# Technical details and evidence

🇫🇷 [Version française](TECHNIQUE.md)

Everything below was observed on a **vivo X Fold6** (Chinese OriginOS 7 ROM, Android 17, no root), with `adb logcat -b events`, `dumpsys activity exit-info`, the Google Play Services GCM log and the code of `vivo-services.jar` / `com.vivo.sps` (disassembled).

## 1. Launcher pre-start

When a finger lands on an icon, the launcher notifies the system (`vboost` → `notifyTouchAppToAms`, active if the system answers "version ≥ 2"), then:

`QoSManager.notifyTouchAppFromLauncher` → `PreStartAppPolicy` (`com.vivo.services.qos.policy.list.appStart`) → `VivoAmsImpl.killPreStartProcess` → **`forceStopPackage`** if the app did not come to the foreground.

Test on Amazon, page-change gesture started on the icon:
```
19:11:24.598 am_proc_start [... com.amazon.mShop.android.shopping, preStart_down ...]
19:11:24.764 am_kill [... stop com.amazon.mShop.android.shopping due to stop]
```
Then, at the next notification, in the Google Play Services log:
```
Failed to broadcast to stopped app
```
- Switches: `persist.vivo.qos.prestartapp` / `persist.vivo.qos.downprestartapp` (refused by `setprop` without root) and vivo cloud config `pre_start_app_enable` / `down_pre_start_app_enable` (not modifiable). The exception list is hard-coded.
- Unticking the preload option in vivo settings changes nothing (the Amazon test above was done afterwards).
- The app drawer triggers the same thing when scrolled with the finger on the icons. A folder does not.

## 2. The other force-stops

- **Resource manager** `com.vivo.sps:rms`, 1 to 2 s after the screen turns off:
  ```
  19:04:30     screen off
  19:04:31.972 stop com.google.android.gm due to forceStopPackageByUid 1000pid 3604   (3604 = com.vivo.sps:rms)
  ```
- **Cleaner** ("Clear all"): `due to cleaner`. Swiping a card away: `due to single-cleaner` (a simple close for most apps, but a force-stop for a third-party accessibility service).

Conversely, a **killed** app (memory, `low memory`, `pruner`) is not "stopped": the next notification restarts it normally.

## 3. How Guardian unblocks an app

- It receives `ACTION_PACKAGE_RESTARTED` (broadcast by Android on every force-stop).
- 300 ms later, it sends an explicit intent (`com.phili.notifguard.WAKE`, `FLAG_INCLUDE_STOPPED_PACKAGES | FLAG_RECEIVER_FOREGROUND`) to an **exported, permission-free** receiver of the app: preferably `BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED` or `MY_PACKAGE_REPLACED`, otherwise any exported receiver. Delivering a broadcast to a "stopped" app clears that state. Up to 3 attempts on different receivers.
- An app without a usable receiver (or that ignores the signal) is logged once, then left alone until its state changes.
- If the Settings app is in the foreground when the stop happens, it is a user "Force stop": it is respected.
- The same Amazon test, with Guardian running:
  ```
  19:11:24.764 am_kill ... due to stop
  19:11:25.285 NotifGuard: force-stop detected: Amazon Shopping
  19:11:25.617 am_proc_start ... broadcast ... com.phili.notifguard.WAKE
  19:11:27.094 NotifGuard: Amazon Shopping unblocked ✓
  ```
  Gmail (real pre-start): stopped at 17:55:27.344, unblocked at 17:55:28.209 (0.9 s).

## 4. Keep-alive: a notification listener, not an accessibility service

Up to v12 Guardian was an accessibility service, only because it is a component Android keeps alive (`procState=5`). Banking apps (seen: BRAC Bank Astha, `com.bracbank.astha`) read the list of enabled accessibility services and refuse to run when a third-party one is on it. Since v12.1 the service is a **notification listener** (`NotificationListenerService`): Android binds an enabled listener the same way and binds it again when its process dies. The app declares no accessibility service any more, so it cannot appear in `enabled_accessibility_services`.

- **No notification is read.** The manifest declares `android.service.notification.disabled_filter_types = conversations|alerting|silent|ongoing`: the system delivers none of these types to the listener and the user cannot turn them on. The service overrides no `onNotification…` callback. Check: `adb shell dumpsys notification | grep -A3 notifguard`.
- **What accessibility events were used for, and what replaces them**:
  - a check of the watched apps on window changes → a check 4 s after the screen turns off (the resource manager strikes 1 to 2 s after it), in addition to the force-stop broadcast, screen-on and the 5-minute check;
  - knowing that the Settings app is in the foreground (user "Force stop") → `UsageStatsManager` (last `ACTIVITY_RESUMED` event), only if the optional usage access is granted and the screen is on. Without it every force-stop is undone.
- **Measured on a vivo V2502DA (OriginOS 7, Android 17), v12.2**: listener bound by the system after install without opening the app, `curProcState=5` (the same as the accessibility version); `am force-stop com.google.android.gm` → "force-stop detected" at 05:08:16.411, "unblocked ✓" at 05:08:18.219; `enabled_accessibility_services` = null.
- **Binding**: seen once, right after the very first install (app never opened yet): access granted with `cmd notification allow_listener` but the service was not bound, and Android did not try again. Opening Guardian now asks for a bind (`requestUnbind` + `requestRebind`); a reinstall also rebinds.
- **Wake-ups refused by vivo**: on that phone, the apps whose unblocking failed were exactly those with autostart off in `content://com.vivo.permissionmanager.provider.permission/bg_start_up_apps` (`currentstate=1`). This does not depend on how Guardian is kept alive.
- **Cleaner**: "Clear all" force-stops Guardian like any app. The recent-apps lock (`UserLockedAppFilter` / `LockedAppManager`) is remembered **per app** and protects it. This is why the app has a dedicated screen, shown in recent apps long enough to put the lock on, while its main screen is excluded from them (`excludeFromRecents`, `finishAndRemoveTask()`).
- **Settings**: the removal of the accessibility service by vivo's security centre (`com.vivo.safecenter`) while the Settings app is open concerned accessibility services only.
- **`GET_INSTALLED_APPS`**: Chinese-standard permission; without it, `getInstalledApplications` only returns system apps.

## 5. Cost

Measured with the accessibility version (v12); not measured again for v12.1, which does strictly less work (no window events).

Day of 19/10/2026, 08:39 → 19:00, unplugged, one reboot (`dumpsys batterystats --charged`):
- Guardian: **0.73 mAh** out of 1,794 mAh (0.04 %), of which 0.42 mAh of screen during its 13 s on display; 7.9 s of CPU in total; no wakelock, no alarm, no network.
- Process never killed during the day; 14 service interruptions, all during a visit to Settings, resumed on their own.
- Cached app list: the full scan (~450 installed apps) only happens at screen-on, every 5 min and when an app is installed / updated. Frequent checks: < 10 ms of CPU over 70 s with 6 app switches.

## 6. GmsCore (ReVanced / Morphe microG)

- The push service `org.microg.gms.gcm.McsService` runs in `app.revanced.android.gms:persistent`, killed by vivo (`low memory`, `pruner`) and not restarted (seen: disconnected ~3 h).
- Code of `TriggerReceiver.onReceive`: if `McsService` is not connected → "Not connected to GCM but should be, asking the service to start up"; if it is → "service is running. heartbeat instead". Checked on the TCP connection to port 5228: same local port before/after the signal, so no needless reconnection.
- Test: `:persistent` killed (`am kill`) → screen off/on → reconnected in < 3 s.

## 7. The "fast_freezer" is not the cause

In "smart" battery mode, vivo freezes an app ~5 s after each notification (`am_app_frozen … from fast_freezer`) and unfreezes it at the next one. It shows its notification before being frozen again: mail received by Google at 19:24:29.739, Gmail restarted, notification shown at 19:24:30.835, frozen again at 19:24:36. "High background power consumption" removes this freeze (`add to non-freezable`) but is expensive (39 mAh/day for Gmail vs 10 for Outlook in smart mode) and does not protect against force-stops.

## Useful diagnostics

```bash
adb shell dumpsys package <package> | grep -o "stopped=[a-z]*"
```
```bash
adb shell dumpsys activity exit-info <package>
```
(look for `FORCE STOP … due to stop`, `cleaner`, `forceStopPackageByUid`)
