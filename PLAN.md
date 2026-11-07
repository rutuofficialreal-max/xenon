# Xenon — Android Web Browser (MVP) · Build Plan for Review

**Status:** plan only — no code written yet (waiting for your review / answers on 4 items at the end).
**Package:** `com.chromium.xenon`  ·  **App name:** Xenon
**Toolchain:** Kotlin + native Android WebView (system Chromium) · Gradle 8.7 / AGP 8.5.2 / Kotlin 1.9.24 · JDK 17 · compileSdk 34 · targetSdk 34

---

## 0. Two things I need to be straight with you about first

**A) "Chromium-backed" means the system WebView, not a bundled Chromium.**
The prompt says "native Chromium-backed Android WebView engine". That is exactly what we'll use — `android.webkit.WebView`, which on every modern Android device is rendered by the *system* Chromium WebView APK. We are **not** bundling Chromium (that's what `androidx.browser`/GeckoView/Chromium-Embedded does, ~100 MB+ and a completely different build). So: real Chromium engine, updatable by Google Play, zero extra APK weight. If you ever want the bundled-Chromium route, that's a separate (much bigger) project.

**B) Please don't paste a GitHub API key / PAT into this chat.**
Anything typed here lands in conversation history, and a single token can be a real liability. There are three safer ways to get "push → compile → monitor" done, and all of them work from your phone:

| Option | How it works | Token in chat? |
|---|---|---|
| **1. I compile here, you download the APK** *(recommended)* | I install JDK 17 + Android SDK in this sandbox, run `assembleDebug`, hand you the signed debug `.apk` as a workspace file you install directly on your phone. | ❌ none |
| **2. You push, GitHub Actions compiles** | I deliver the full repo **plus** `.github/workflows/android.yml`. You create the repo on your phone, drop the token into **Settings → Secrets**, push. GitHub builds the APK and I tell you where the artifact appears + how to read failures. | ❌ none |
| **3. Official GitHub connector** | If you connect the GitHub app to this conversation (OAuth, scoped, revocable), I can push and watch CI runs natively. It isn't connected right now — say the word and I'll verify. | ❌ none (OAuth, not a raw key) |

They're not exclusive — 1 + 2 together is the best combo (fast local loop for me, permanent CI for you). **I'll assume option 1 + 2 unless you say otherwise.**

---

## 1. Reality check on "the 4 files"

The prompt asks for 4 files. Those 4 files are correct but **not compilable on their own** — Gradle needs a wrapper, settings file, a theme, and launcher icons. Honest file list to actually get a green build:

```
xenon/
├── settings.gradle                      # rootProject.name, repositories (google, mavenCentral)
├── build.gradle                         # root: plugins block, AGP + Kotlin versions
├── gradle.properties                    # AndroidX, JVM args, non-transitive R class
├── gradlew / gradlew.bat                # wrapper scripts
├── gradle/wrapper/gradle-wrapper.properties + gradle-wrapper.jar
├── .gitignore
├── README.md                            # build + install instructions for phone-only workflow
├── .github/workflows/android.yml        # CI: compile debug APK, upload artifact
└── app/
    ├── build.gradle                     # ← requested file #2 (namespace com.chromium.xenon, sdk 34, JDK 17)
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml          # ← requested file #1
        ├── java/com/chromium/xenon/
        │   ├── MainActivity.kt          # ← requested file #4
        │   └── (optional) UrlUtils.kt   # address-bar normalization, kept testable
        └── res/
            ├── layout/activity_main.xml # ← requested file #3
            ├── values/strings.xml       # every user-visible string (no hardcoded text)
            ├── values/colors.xml        # Xenon palette
            ├── values/themes.xml        # Material3 DayNight, NoActionBar
            ├── values-night/themes.xml  # dark theme override
            ├── drawable/  ic_launcher_foreground.xml, bg_address_bar.xml, ic_back/ic_go/ic_refresh.xml
            ├── mipmap-anydpi-v26/ic_launcher.xml, ic_launcher_round.xml
            └── xml/ backup_rules.xml, data_extraction_rules.xml
```

That's ~24 files. All of them written out in full — no `...`, no TODOs, no "add your own icon here". Icons will be generated vector assets inside the bundle.

---

## 2. What the MVP actually does

Single-tab, single-activity browser. Deliberately small, but nothing that looks half-finished:

1. **Address bar** — type a URL or a search term. `IME_ACTION_GO` on the keyboard, plus a Go button that becomes ■ Stop while loading and ⟳ Refresh when idle.
2. **Input normalization** — `https://` prefixed if scheme missing; `http` upgraded to `https`; bare words / spaces / no dot → search engine query. `about:` and `file:`/`content:` blocked except whitelisted.
3. **In-app navigation** — `WebViewClient.shouldOverrideUrlLoading` keeps `http/https` inside the app; `mailto:`, `tel:`, `sms:`, `intent:`, `market:` are handed to the OS with a `try/catch` (no crash if no handler).
4. **Loading state** — thin `LinearProgressIndicator` under the top bar, driven by `WebChromeClient.onProgressChanged`; address bar shows live URL on `doUpdateVisitedHistory`; title shown on `onReceivedTitle`.
5. **Hardware back** — `onBackPressedDispatcher`: goes back in page history if `canGoBack()`, else exits (with optional double-tap-to-exit).
6. **Error handling** — main-frame `onReceivedError` → inline branded error state (offline / DNS / timeout, with Retry); `onReceivedSslError` → **cancelled** (never `proceed()`); sub-resource errors ignored.
7. **State survival** — `onSaveInstanceState` + `webView.saveState/restoreState`, `android:configChanges` to avoid rotation reloads; `pauseTimers`/`resumeTimers` + `destroy()` on exit so no leaked WebView.
8. **Deliberately out of scope for MVP v1:** tabs, bookmarks/history DB, download manager, incognito, file-chooser uploads, permissions prompts (camera/mic), reader mode, ad-block. All are clean v2 add-ons — say the word and I'll spec them.

### WebView hardening (explicit, per your prompt)
```kotlin
settings.javaScriptEnabled        = true     // required for modern sites
settings.domStorageEnabled        = true
settings.databaseEnabled          = true
settings.cacheMode                = LOAD_DEFAULT
settings.setSupportZoom(true); builtInZoomControls = true; displayZoomControls = false
settings.useWideViewPort = true; loadWithOverviewMode = true
settings.mediaPlaybackRequiresUserGesture = true
settings.mixedContentMode         = MIXED_CONTENT_NEVER_ALLOW
settings.javaScriptCanOpenWindowsAutomatically = false
settings.allowFileAccess = false; allowContentAccess = false   // tighten default
settings.userAgentString          = default + " Xenon/1.0"     // identifiable UA
// hardware acceleration: android:hardwareAccelerated="true" in manifest + WebView.setLayerType(HARDWARE)
```
Plus optional `androidx.webkit` Safe Browsing (`WebViewFeature.SAFE_BROWSING_ENABLE`) — AndroidX only, ~0 cost, big safety win.

### UI (sleek/modern, per your prompt)
Material 3, DayNight. Top bar = rounded "pill" address bar (`bg_address_bar.xml`, 24dp corners, subtle stroke) + circular icon buttons (Back / Go-Stop-Refresh), 1dp hairline divider, 2dp progress line. WebView below, edge-to-edge with `WindowInsets` padding so it sits correctly under the status bar and above the keyboard. No toolbars, no overflow menu, no card stacks — flat and quiet.

---

## 3. Verification & monitoring I'll do (option 1)

1. Install Temurin **JDK 17** and Android **cmdline-tools** + `platforms;android-34`, `build-tools;34.0.0`, `platform-tools` in the sandbox (network to `dl.google.com` is confirmed reachable; ~2–3 GB).
2. Generate the Gradle wrapper at 8.7, then `./gradlew :app:assembleDebug --stacktrace`.
3. Fix anything red until the build is clean; also run `:app:lintDebug` and report warnings.
4. Hand you `Xenon-v1.0.0-debug.apk` (debug-signed → installs on any phone via "install unknown apps"). If you want a **release** APK, tell me — I'll generate a keystore, but then you must keep the `.jks` + passwords, since a lost release key can never update the same app.
5. Report: build log summary, APK size, permissions in the merged manifest, and known-limitations list.

**Honest caveat:** a "compile is clean" verdict is not the same as "it runs". With no emulator screen here I can't visually click through it. I can, however, boot an **arm/x86 emulator headless** and smoke-test that it launches without crashing if you want that extra step (adds ~20–30 min of setup). Otherwise first-run testing is on your phone and I fix what you report.

---

## 4. Risks / notes

- **`com.chromium.*` prefix** implies affiliation with the Chromium project. Fine for private/sideload use; if this ever goes to Play Store, expect an impersonation review hit. Say the word and I'll switch to `app.xenon.browser` in one pass.
- **`ACCESS_NETWORK_STATE`** is included for offline detection, not telemetry — nothing leaves the device, no analytics, no accounts.
- **WebView version** = whatever your phone has; a browser app can't force-update the engine. `minSdk 24` (Android 7.0) gives ~98% device coverage.
- **No `usesCleartextTraffic=true`** — plain HTTP pages will still load in WebView, but the app itself won't leak cleartext beyond what you browse.
- **Compile environment is ephemeral** — SDK downloads aren't persisted between sessions, so a rebuild in a later session re-installs the toolchain (~5 min). The source in `/home/user/xenon` always persists.

---

## 5. Execution order once you approve

| Step | Deliverable |
|---|---|
| 1 | Full repo written to `/home/user/xenon` (all ~24 files, complete) |
| 2 | Gradle wrapper + `.gitignore` + `README.md` + CI workflow |
| 3 | Toolchain install → `assembleDebug` → fix till green → `lintDebug` |
| 4 | APK + build report + install instructions in workspace |
| 5 | (Optional) GitHub push/CI as you choose |

---

## 6. Your call — 4 questions

1. **Push/compile route** — I compile here + you download (1), you push and CI compiles (2), official GitHub connector (3), or 1+2?
2. **Scope** — strictly this MVP (single tab, no bookmarks), or include a v1.1 pack (tabs + history/bookmarks + downloads)?
3. **minSdk** — 24 (Android 7, max reach), 26, or 29 (modern-only)?
4. **Default search engine** — Google, DuckDuckGo, Brave, or Startpage?
