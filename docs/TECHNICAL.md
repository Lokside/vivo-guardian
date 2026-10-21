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

## 4. Why an accessibility service

It is the only component an ordinary app can keep alive on vivo without root: Android maintains it (`procState=5`), vivo does not freeze it. It only requests `typeWindowStateChanged` (used as a reminder to check the apps) and `canRetrieveWindowContent=false`.

Constraints discovered:
- **Cleaner**: `com.vivo.sps` (`NRMS-AccessibilityServices` classes) force-stops third-party accessibility services on "Clear all", except those on its list. Dead ends:
  - `settings put secure third_srceen_read_packages …`: read only once when `sps` starts, then reset to the factory value at every reboot;
  - "notclean" lists (`content://com.iqoo.secure.provider.secureprovider/speedupwhitelist`, etc.): writing refused from adb.
  - **Solution**: the recent-apps lock (`UserLockedAppFilter` / `LockedAppManager`) is remembered **per app**. Tested: "Clear all" closed 96 processes but not Guardian (same pid, accessibility still on), also after a reboot. This is why the app has a dedicated screen, shown in recent apps long enough to put the lock on, while its main screen is excluded from them (`excludeFromRecents`, `finishAndRemoveTask()`).
- **Settings**: vivo's security centre (`com.vivo.safecenter`) removes the service from `enabled_accessibility_services` while the Settings app is open and puts it back ~1 min later (seen: cut at 18:22:05, restored at 18:23:02). A reboot within that minute leaves it off. Visible with `adb shell dumpsys settings | grep name:enabled_accessibility_services`.
- **`GET_INSTALLED_APPS`**: Chinese-standard permission; without it, `getInstalledApplications` only returns system apps.

## 5. Cost

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
