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


- **Saved sensors** (`#/`) — saved sensors and owners; one card per device: the map's type icon
  (Urban / Insight / Dual / DIY), the user's name for it or the id masked `4EEg…wUTr`, PM2.5 and PM10
  with the map's rating, and "updated 2 min ago" (grey "no data for 3 h" after an hour of silence).
  Add by address, owner address, a sensors.social / share.sensors.social link, or a QR code.
- **Device** (`#/s/<id>`) — full id with Copy, a tile per parameter with the map's rating,
  an Alerts switch, Open on map, Rename (name kept on the phone only), Remove, Help and support.
- **Parameter** (`#/s/<id>/<key>`) — chart for 24 h / 7 days / 30 days, the line coloured by the
  map's zones and gaps in grey, as on the map; min / average / max; the zone legend.

Ratings come from the sensors.social map only — one source for the site and the app.
`node tools/sync-zones.mjs [path/to/sensors.social]` copies the zones (thresholds, colours, names)
from the map's `src/measurements` into `www/zones.js`; change a threshold on the map, then re-run it.
Alerts use the same zones: unhealthy = above the last calm zone (PM2.5 > 55, PM10 > 100,
CO₂ > 1000 ppm), good again back in the green zone.
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

Capacitor 8, targetSdk/compileSdk 36 (required by Google Play from 31 Aug 2026), minSdk 24.
JDK 21, Node 22 and Android SDK (platform 36, build-tools 36) live in the home dir, no root:

    export JAVA_HOME=~/opt/jdk-21.0.13+9 PATH=~/opt/jdk-21.0.13+9/bin:~/opt/node/bin:$PATH ANDROID_HOME=~/Android/Sdk
    npm install && npm run apk
    # → android/app/build/outputs/apk/debug/app-debug.apk

Release build: `cd android && ./gradlew assembleRelease` (APK) or `bundleRelease` (AAB for Google Play). It is signed only when
`~/.android-keys/sensors-social-release.properties` (storeFile, storePassword, keyAlias, keyPassword)
exists — the key is never in this repo. Every update must be signed with the same key.

## Licences

Code: AGPL-3.0-only (as sensors.social), see `LICENSE`. Fonts Orbitron and Roboto Flex: SIL Open Font
License 1.1, see `www/fonts/OFL-*.txt`. The sensors.social mark and wordmark in `www/img/` are brand
assets of the Brand Family System and are not covered by the code licence.
