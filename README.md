# Xenon

A fast, private, fully branded Android web browser built on the native Chromium-backed `WebView` engine. Kotlin, no trackers, no accounts, no analytics — one activity, one tab, zero bloat.

<p align="center">
  <b>Package</b> com.chromium.xenon &nbsp;·&nbsp;
  <b>Min SDK</b> 26 (Android 8.0) &nbsp;·&nbsp;
  <b>Target SDK</b> 34 (Android 14) &nbsp;·&nbsp;
  <b>Kotlin</b> 1.9.24 &nbsp;·&nbsp;
  <b>AGP</b> 8.5.2 &nbsp;·&nbsp;
  <b>JDK</b> 17
</p>

---

## What it does

- **Address bar that just works.** Types a URL → loads it. Types words → searches. `example.com` becomes `https://example.com`; `localhost:3000` and IPs are recognised; `javascript:`/`file:`/`content:` typed by hand are refused and searched instead (self-XSS guard).
- **Go / Stop / Refresh in one button** with a live 2dp progress line under the bar.
- **Hardware back** walks page history, then exits. Long-press back clears history.
- **Branded start page** (bundled, offline-capable) whose search box follows your chosen engine.
- **Settings**: search engine (Google / DuckDuckGo / Brave / Startpage), custom homepage, JavaScript on/off, desktop user agent, one-tap "clear browsing data", reset to defaults.
- **Downloads** go through the platform `DownloadManager` (with your cookies, so authenticated downloads work) and land in `/Downloads`.
- **Errors stay in-app**: offline / DNS / timeout get a branded retry screen; an invalid TLS certificate is a hard stop — Xenon never calls `proceed()` on an SSL error.
- **Light & dark** Material 3 themes with a matching icon, adaptive + monochrome.

Out of scope for v1 (clean add-ons): tabs, bookmarks/history DB, incognito, file-upload chooser, permissions prompts, ad-block.

---

## Project layout

```
app/src/main/
├── AndroidManifest.xml            permissions, launcher, http(s) VIEW filter
├── assets/home.html               bundled Xenon start page (inline CSS/JS)
├── java/com/chromium/xenon/
│   ├── MainActivity.kt            WebView host, address bar, clients, errors, downloads
│   ├── SettingsActivity.kt        preferences UI
│   ├── Prefs.kt                   SharedPreferences wrapper + engine templates
│   ├── UrlUtils.kt                URL-vs-search normalisation
│   ├── DownloadHelper.kt          DownloadManager hand-off
│   └── BrowserData.kt             "clear browsing data"
└── res/
    ├── layout/activity_main.xml   top bar + progress + WebView + error state
    ├── layout/activity_settings.xml
    ├── values/{colors,themes,strings}.xml + values-night/
    ├── drawable/                  vector icons, address-bar pill, launcher foreground
    └── xml/                       backup rules (WebView DBs excluded)
```

---

## Build it

### On any machine with a JDK 17 + Android SDK 34

```bash
git clone https://github.com/<you>/xenon.git
cd xenon
export ANDROID_HOME=$HOME/Android/Sdk     # or set sdk.dir in local.properties
./gradlew :app:assembleDebug
# APK → app/build/outputs/apk/debug/app-debug.apk
```

Install on a phone: enable "install unknown apps" for your file manager, tap the APK.

### Phone-only, no PC (GitHub Actions)

1. Push this repo to GitHub (public or private, both work).
2. Actions runs `.github/workflows/android.yml` on every push.
3. Open the run → **Artifacts** → `xenon-debug-apk` → download the zip → install the APK.

No secrets or signing keys are needed for debug builds.

### Release build (when you want one)

```bash
keytool -genkeypair -v -keystore xenon-release.jks -alias xenon \
        -keyalg RSA -keysize 2048 -validity 10000
```
Then add a `signingConfigs` block to `app/build.gradle` and run `./gradlew :app:assembleRelease`.
**Keep the `.jks` and its passwords forever** — lose them and you can never update the same app again. (`*.jks` / `*.keystore` / `keystore.properties` are already gitignored.)

---

## Security posture

| Area | Xenon's choice |
|---|---|
| TLS | Certificate errors cancel the load. No exception, no warning-only path. |
| Mixed content | `MIXED_CONTENT_NEVER_ALLOW` |
| File access | `allowFileAccess=false`, `allowContentAccess=false` (bundled assets still load) |
| Popups | `setSupportMultipleWindows(false)`, JS cannot open windows |
| Geolocation | Disabled |
| Third-party cookies | Rejected |
| Safe Browsing | Enabled (system-backed) |
| External schemes | `mailto:`, `tel:`, `sms:`, `intent:`, `market:` are handed to the OS, never to the WebView |
| Backup | WebView cookie/site databases excluded from cloud backup & device transfer |
| Telemetry | None. Xenon makes no network calls of its own. |

`android:usesCleartextTraffic="true"` is present because a browser must be able to open legacy `http://` sites (WebView inherits the app's network policy). Turn it off if you are happy to drop all plaintext sites.

---

## Known limitations

- The rendering engine is the **device's** system WebView, updated by Google Play. A standalone app cannot bundle or force-upgrade Chromium — that is a different (GeckoView / Chromium-Embedded) project.
- Single tab, no history/bookmarks database.
- The `com.chromium.*` package prefix implies no affiliation with the Chromium project. Fine for sideloading; change it before any Play Store submission.

## Roadmap

1. Tabs + tab switcher
2. History & bookmarks (Room)
3. Incognito session
4. Reader mode + per-site settings
5. Ad/tracker blocking via `shouldInterceptRequest`

---

MIT licensed. Do what you want.
