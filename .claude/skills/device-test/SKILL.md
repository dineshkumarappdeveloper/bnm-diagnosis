---
name: device-test
description: Build, install, and drive BNMAdmin on a physical Android device over adb (unlock, log in, navigate, and verify offline-sync by reading the on-device SQLite DB). Use when asked to test/verify BNMAdmin on the connected Vivo/I2011 device, reproduce a bug on-device, or confirm sync/data after a change.
---

# BNMAdmin on-device testing (adb)

`adb` = `/Users/dineshkumarr/Library/Android/sdk/platform-tools/adb`. Two devices are usually connected, and zsh does NOT word-split variables, so **always** scope with `export ANDROID_SERIAL=<serial>` then call the full adb path.

## Devices (screen PINs are PER-DEVICE — never reuse across devices)
- **I2011** = serial `3083929265000FH` (Android 13). **Screen PIN = `2196` (this device only).** Primary test device.
- vivo_1951 = serial `9531ea27` (Android 11). PIN unknown (alphanumeric "Enter Password" after reboot; `2196` did NOT clear it).

## Build + install
```bash
cd /Users/dineshkumarr/BNM/BNMAdmin && ./gradlew :composeApp:assembleFullDebug
# APK: composeApp/build/outputs/apk/full/debug/composeApp-full-debug.apk
export ANDROID_SERIAL=3083929265000FH
$ADB install -r <apk>     # over a prior debug build; uninstall first for a CLEAN test (clears app data → forces first-sync)
```
For a clean sync test: `$ADB uninstall com.bnm.admin` then `install`. The Play build was 1.3.0; the debug build is 1.4.0.

## Unlock (I2011) — ONE bash call
The secure lockscreen BLOCKS screencap (FLAG_SECURE) → read PIN-pad coords from `uiautomator dump`, and the pad TIMES OUT between separate adb calls, so do it in one shot:
```bash
$ADB shell input keyevent KEYCODE_WAKEUP; sleep 0.6
$ADB shell input swipe 540 2000 540 700; sleep 1.2
# Vivo keyguard button centers @1080x2408: 1=(280,1399) 2=(539,1399) 3=(798,1399)
#   4=(280,1603) 5=(539,1603) 6=(798,1603) 7=(280,1807) 8=(539,1807) 9=(798,1807)
#   0=(539,2011) Delete=(280,2011) Enter=(798,2011)
# 2196 = tap 2,1,9,6 then Enter. If a stray digit lingers, tap Delete x6 FIRST (saw '02196' once).
$ADB shell dumpsys window | grep isKeyguardShowing   # → false when unlocked
```
`input text` / digit-keycodes do NOT register on the keyguard — must tap coords.

## Launch + log in (login screen is STATIC @1080x2408)
```bash
$ADB shell monkey -p com.bnm.admin -c android.intent.category.LAUNCHER 1
sleep 9   # COLD START after a fresh install is slow — wait, else taps miss
$ADB shell input tap 540 1266; sleep 1.5    # "Allow" notifications dialog (Android 13, first launch)
$ADB shell input tap 540 810;  sleep 0.7; $ADB shell input text 'testing@bnmapp.com'   # Email field
$ADB shell input tap 540 1019; sleep 0.7; $ADB shell input text 'DemoStore@2026'        # Password field ('@' works via input text on I2011)
$ADB shell input keyevent 4; sleep 0.5      # hide keyboard
$ADB shell input tap 540 1306               # "Sign in"
# Forgot password link @ (797,1166)
```
Test creds **`testing@bnmapp.com` / `DemoStore@2026`** → **Demo Store** business (India ref `voelldnyfamrbzvthgfk`, _id `f4b9f9acc655c6a22a530a75`). A clean install lands on **Select Business** (card center ~ (540,405)) → tap Demo Store → Dashboard.

## Navigate (drawer hamburger top-left ~ (45,165))
Dashboard (270,462) · Chat (213,616) · My Store (251,770) · Company (257,1003) · Workflows (267,1236) · Business (254,1469) · Settings (245,1623) · Switch Business (320,2017) · Sign Out (248,2171). My Store bottom tabs: Overview/Orders/Catalog/Categories/**Stock** (Stock ~ (981,2216)). For any dynamic coord: `uiautomator dump /sdcard/u.xml` → pull → parse `bounds="[x1,y1][x2,y2]"`.

## Verify offline sync by reading the on-device DB (the definitive check)
The local SQLDelight DB is `databases/bnm_chat.db`. Pull it (debug build → `run-as`) and query locally — this isolates the AUTOMATIC sync from any manual Sync / navigation:
```bash
$ADB exec-out run-as com.bnm.admin cat databases/bnm_chat.db > /tmp/poll.db
sqlite3 /tmp/poll.db "select entity, count(*) from ecom_entity group by entity order by entity;"
sqlite3 /tmp/poll.db "select key, cursor from sync_state;"   # per-module delta cursors; missing cursor = that module's fetch FAILED
```
Demo Store server counts (compare): order=14, product=8, ledger(stock)=7, category=4, customer=5, **company**: employee=3, department=3, expense=8, expense_category=32, salary=2. A clean first-sync should match these exactly. NOTE: the top-bar Sync button is at ~ (1000,165) on Dashboard.

## Gotchas
- Screenshots: `$ADB exec-out screencap -p > /tmp/x.png` then Read it. Full-res 1080x2408 may exceed the image-read size limit — if so, rely on the DB poll / `uiautomator dump` text instead.
- App logs almost nothing to logcat (silent `runCatching`); use the DB poll, not logcat, to diagnose sync.
- Session token (for curling edge fns as the user): `run-as com.bnm.admin cat shared_prefs/com.bnm.admin_preferences.xml` → `session_token` key → `Authorization: Bearer <token>`.
- Demo Store has commerce + company data but **no chat conversations** (can't visually test chat alias names there).

See also memory: [[bnmadmin-device-testing]], [[demo-store-sandbox]], [[bnmadmin-sync-engine]].
