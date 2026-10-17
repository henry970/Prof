# PerfPilot Mobile — Android app

PerfPilot Mobile tests the performance of any app installed on an Android phone, with no computer needed. You pick an app, choose how many runs, and tap **Start**. On each run, PerfPilot:

1. closes and reopens the app to measure a **cold start**,
2. taps through it automatically (or lets you use it yourself),
3. measures **janky frames**, **CPU** and **memory**.

At the end you get the same scored report as the computer version. You can save it as a PDF from the app.

## Why Shizuku is needed

Android doesn't let one app look inside another. **Shizuku**, a free open-source app, gives PerfPilot the same access a computer gets over adb. No root is needed, and on Android 11+ no computer is needed either.

## Getting the APK

The Android build tools weren't available where this was written, so build the APK one of these ways.

**Option A: GitHub (no setup on your computer)**

1. Create a new GitHub repository and upload this folder's contents, keeping the `.github` folder.
2. Open the **Actions** tab. The "Build APK" workflow runs automatically, which takes about 5 minutes.
3. Open the finished run and download **PerfPilotMobile-apk** under Artifacts. It's a zip containing the APK.

**Option B: Android Studio**

1. Open this folder in Android Studio and let it sync.
2. Choose **Build → Build App Bundle(s) / APK(s) → Build APK(s)**.
3. The APK appears in `app/build/outputs/apk/`.

The APK is signed with a debug key, so it installs directly. Sign it with your own key before publishing it anywhere.

## Setting up the phone (once)

1. Install the APK. You may need to allow "Install unknown apps" for your browser or file manager.
2. Install **Shizuku** from the Play Store.
3. Start Shizuku:
   - **Android 11+:** open Shizuku → *Start via Wireless debugging* and follow its pairing steps. This needs Wi-Fi; no computer is used.
   - **Android 10 or older:** start it once from a computer with the adb command Shizuku shows you.
4. Open PerfPilot and tap **Allow access**.

After a reboot, start Shizuku again before testing. On some phones Shizuku can do this automatically.

## Running a test

1. **App to test:** choose any installed app.
2. **Measured runs:** 5 is a good default.
3. **During each run:**
   - *Tap through the app automatically* uses Android's Monkey tool to tap and swipe randomly inside the app. A fixed seed means every run replays the same taps. Don't touch the phone during these runs.
   - *I'll use the app myself:* after each launch, do the same steps each time until the countdown ends.
4. Tap **Start test**. Progress shows in the notification and on the main screen. The report opens automatically when the test is done.

Past reports are listed on the main screen. Tap one to open it, or long-press it to delete it.

## What's measured

| Metric | How |
| --- | --- |
| Cold start | `am start -S -W` (the app is force-stopped first) |
| Janky frames, frame times p50–p99 | `dumpsys gfxinfo` for the app |
| CPU (100% = one core) | `/proc/<pid>/stat`, sampled every 2 s |
| Memory (PSS) | `dumpsys meminfo`, sampled every 2 s |
| Data used | Android's per-app network stats, before and after each run |
| Servers contacted | the app's open connections in `/proc/net/tcp`, sampled every 2 s, plus a connect-time check from the phone |
| Crashes and freezes (ANR) | `am_crash` / `am_anr` events in the system log |
| API response times | lines the app logs under the `PerfPilotApi` tag (see below) |
| Security checkup | read-only: manifest flags (debuggable, backup, plain HTTP, target API), components open to other apps, sensitive permissions granted, and port-80 connections seen during the test. It never probes the app or its servers. |

Scoring targets: cold start ≤ 1000 ms, janky frames ≤ 5%, CPU ≤ 25%, memory ≤ 200 MB. The score and report code is the same as the desktop CLI's: `app/src/main/assets/perf.js` is generated from it by `perfpilot-mobile/tools/build-android-assets.js`.

## API response times (for apps you build)

HTTPS is encrypted, so PerfPilot can't read another app's requests. If you build the app, log one line per API call under the tag `PerfPilotApi` in the form `METHOD path status timeMs`, and the report will show each endpoint's call count, median, p90 and errors. With OkHttp, for example:

```kotlin
class PerfPilotInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val t0 = System.nanoTime()
        val res = chain.proceed(req)
        val ms = (System.nanoTime() - t0) / 1_000_000
        Log.i("PerfPilotApi", "${req.method} ${req.url.encodedPath} ${res.code} ${ms}ms")
        return res
    }
}
```

Add it only to debug or test builds.

## Tips

- Test on a **low-end phone** if you can. Problems show up there first.
- Close other apps and keep the phone charged and cool. Repeat a test before trusting a big difference.
- For React Native/Expo apps, test a **release build**. Dev builds are much slower.

## Project layout

```
app/src/main/java/com/perfpilot/mobile/
  MainActivity.kt    main screen: access, app picker, settings, progress, history
  TestService.kt     foreground service that runs a test
  TestRunner.kt      the test itself (launches, journeys, sampling)
  Shell.kt           connection to Shizuku
  ShellService.kt    runs commands inside Shizuku's shell process
  Parsers.kt         reads the command output
  ReportStore.kt     saved results
  ReportActivity.kt  report viewer + PDF export
app/src/main/aidl/   shell service interface
app/src/main/assets/ report page and scoring/report code
```
