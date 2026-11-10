# sensors.social — Android app (v0.3)

Android app for [sensors.social](https://sensors.social), the open air-quality map, and for
Altruist sensors: follow your sensors and the ones around you, see every parameter and its history,
get an alert when the air turns poor, and set up a new Altruist step by step.
Made by [Pinout Limited](https://github.com/PinoutLimited), Cyprus. Capacitor 6.

**Install:** download the APK from [Releases](../../releases) and open it on the phone
(allow installing from this source once). Android 7.0+.

Styled by the Brand Family System (Master Prompt V9): sensors.social #007BFF / #004EC4 / #F4F4F2,
Orbitron for measured values, Roboto Flex for everything else — fonts are the brandbook masters
in `www/fonts/`, the approved sensors.social mark and wordmark in `www/img/`. Light and dark themes
follow the phone setting (`prefers-color-scheme`, `values-night/app_colors.xml` for the window).
Filled buttons use #004EC4: white on #007BFF is 3.97:1. Good / Fair / Poor are functional data colours.

- **Add a sensor** (`#/add`) — set up a new Altruist, scan a QR code, or paste an address.
- **Altruist setup** (`#/setup/1..5`) — steps from the Altruist AI guide v1.13 §10: power →
  join `Altruist-xxxx` (password copy, "Open Wi-Fi settings" via `AlertsPlugin.openWifiSettings`) →
  home Wi-Fi at 192.168.4.1; the user pastes "Copy as text" from CONNECTED and the app pulls the
  IP and the Robonomics address out of it → coordinates ("Use my location", shifted ~100 m by
  default; location permission only on that tap), "Open the sensor page" opens `http://<IP>/`
  in the browser → the sensor is added with alerts on.
  Assumption to confirm on a real device: the Robonomics address on CONNECTED is the sensor_id.


- **My sensors** (`#/`) — saved sensors and owners; one card per device with its key reading
  (PM2.5 for Urban, CO₂ for Insight) and a status dot. Add by address, owner address,
  a sensors.social / share.sensors.social link, or a QR code.
- **Device** (`#/s/<id>`) — a tile per parameter with the latest reading and Good / Fair / Poor,
  an Alerts switch, Open on map (live sensors.social in the same WebView), Rename, Remove.
- **Parameter** (`#/s/<id>/<key>`) — chart for 24 h / 7 days / 30 days (SVG, averaged into
  10-minute / 1-hour / ~4-hour points; touch to read a value), min / average / max of single
  readings, what the colours mean.

Thresholds (also used by alerts): PM2.5 15 / 35 µg/m³ (WHO 24 h guideline / poor),
PM10 45 / 100, CO₂ 800 / 1200 ppm, noise 55 / 70 dB, humidity 30–60 % good.
Values are raw readings, not corrected for humidity. Pressure: RoSeMAN mmHg × 1.33322 → hPa.

Navigation uses `history.pushState` with a depth counter, so the in-app ‹ and Android Back agree.

## Alerts

Native, no server and no push service: `AlertsPlugin.java` (JS bridge: `configure`,
`checkNow`, `requestNotifications`) stores the followed sensors in SharedPreferences and
schedules `AlertsWorker.java` with WorkManager every 15 min (network required). The worker
averages the last 30 min of PM2.5 / PM10 / CO₂ and notifies once when the air turns poor,
once when it is good again, and once when a sensor has been silent for 2 hours.
"Test notification" on the device screen runs one check immediately.

Not used on purpose: `@capacitor/background-runner` (its JS engine `.so` is not 16 KB-aligned
and it adds location and exact-alarm permissions) and `@capacitor/local-notifications`.
Permissions in the APK: internet, notifications, plus WorkManager's wake lock / boot / network state.

## QR

Google code scanner (`@capacitor-mlkit/barcode-scanning` `scan()`, Play services UI, no camera
permission). Back in the scanner arrives as "Failed to scan code" and is treated as a cancel.
`android/variables.gradle` pins ML Kit barcode 17.3.0 and CameraX 1.4.2 — older ones ship `.so`
files not aligned to 16 KB pages, and Android 15+ shows a compatibility warning.

## Build

JDK 17 and Android SDK live in the home dir, no root:

    export JAVA_HOME=~/opt/jdk-17.0.21+9 PATH=~/opt/jdk-17.0.21+9/bin:$PATH ANDROID_HOME=~/Android/Sdk
    npm install && npm run apk
    # → android/app/build/outputs/apk/debug/app-debug.apk

Release build: `cd android && ./gradlew assembleRelease`. It is signed only when
`~/.android-keys/sensors-social-release.properties` (storeFile, storePassword, keyAlias, keyPassword)
exists — the key is never in this repo. Every update must be signed with the same key.

## Licences

Code: AGPL-3.0-only (as sensors.social), see `LICENSE`. Fonts Orbitron and Roboto Flex: SIL Open Font
License 1.1, see `www/fonts/OFL-*.txt`. The sensors.social mark and wordmark in `www/img/` are brand
assets of the Brand Family System and are not covered by the code licence.
