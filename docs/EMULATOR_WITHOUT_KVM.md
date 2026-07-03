# Running instrumentation tests on an emulator without KVM

Cloud containers and CI runners often have no `/dev/kvm`, so the Android
emulator falls back to pure software emulation (TCG). Everything works, but
runs roughly 10–20× slower than the hardware Android's timeouts were tuned
for — and several things break in confusing ways. This is the playbook that
gets a TCG emulator stable enough to run real instrumentation suites,
distilled from getting `:compose:remote:integration-tests:player-compose-embedded`
green on one.

## Pick the leanest image that satisfies your tests

In order of preference:

1. **ATD images** (`system-images;android-XX;aosp_atd;x86_64`) — built for
   headless automated testing: no GMS, no launcher, stripped services. Boots
   in ~6 minutes under TCG where a Google-APIs image takes 20–45. Only
   published up to API 34 as of this writing.
2. **AOSP default** (`...;default;x86_64`) — no GMS, but full system.
   Also not available for every API level.
3. **Google APIs** (`...;google_apis;x86_64`) — last resort. GMS,
   the launcher, and friends crash-loop and saturate the CPU under TCG,
   which starves `system_server` (see watchdog section below).

Mind `@SdkSuppress` before choosing: AndroidX screenshot tests are typically
pinned `minSdkVersion = 35, maxSdkVersion = 35` (goldens are rendered on a
specific API level). On any other API level those classes are *silently
filtered* — `am instrument` reports `OK (0 tests)` and Gradle reports
nothing ran. If your tests are pinned to a level with no ATD/default image,
you must tame a Google-APIs image.

## Launch flags

```sh
emulator -avd <name> -no-window -no-audio -no-boot-anim \
  -accel off -gpu swiftshader_indirect -memory 4096 -cores 4 \
  -no-snapshot-load -no-snapshot-save
```

Bump `hw.ramSize`/`disk.dataPartition.size` in `config.ini` if the AVD was
created with defaults (800M data fills up fast).

## The watchdog problem (Google-APIs images)

Symptoms, all of which we hit:

- `adb shell pm list packages` → `cmd: Can't find service: package`
- installs fail with empty output, or ddmlib's
  `Failed to install-write all apks`
- Gradle: `Found 1 connected device(s), 0 of which were compatible`
- ddmlib: `Device ... API level=1. Cannot install split APKs with API level < 21`
- `logcat`: `Watchdog: *** WATCHDOG KILLING SYSTEM PROCESS: Blocked in
  handler on main thread (main) for 66s` every ~4 minutes

Root cause: under TCG, `system_server`'s main thread legitimately blocks
longer than the 60 s watchdog limit, so the framework is beheaded and
restarted in a loop, and the package manager is down more often than up.

Two fixes, apply both:

**1. Disable the load sources.** GMS and friends crash-loop at boot and eat
the CPU that `system_server` needs. Once `pm` answers (there is a usable
window shortly after boot):

```sh
for pkg in com.google.android.gms com.google.android.gsf \
    com.google.android.apps.nexuslauncher com.google.android.inputmethod.latin \
    com.google.android.ext.services com.google.android.googlequicksearchbox \
    com.android.vending; do
  adb shell pm disable-user --user 0 $pkg
done
```

This persists in `/data`, so it survives reboots of the same AVD.

**2. Scale Android's timeouts.** `ro.hw_timeout_multiplier` is the official
knob for slow (emulated) hardware — it multiplies the watchdog limit, ANR
timeouts, and broadcast timeouts framework-wide. The emulator's `-prop` flag
does **not** set it; on a userdebug image, set it after boot instead
(a read-only prop can be *defined* via `setprop` as long as nothing set it
first), then soft-restart the framework so it re-reads the value:

```sh
adb root
adb shell setprop ro.hw_timeout_multiplier 10
adb shell getprop ro.hw_timeout_multiplier   # verify: 10
adb shell "stop && start"                     # fast; wait for sys.boot_completed
```

Without this, app startup alone can exceed the broadcast timeout and the
instrumentation dies with `INSTRUMENTATION_RESULT: shortMsg=Process crashed.`
(logcat shows the app killed for `bg anr`).

Also disable animations, as usual:

```sh
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
```

## Bypass Gradle's device pipeline

AGP/UTP drive devices through ddmlib, whose 5-second property fetch times
out on a busy TCG device. That single failure surfaces as *both* of these:

- the device is declared "not compatible" (its API level is unknown), and
- installs abort with `API level=1. Cannot install split APKs with API
  level < 21` (the version cache was never populated).

This overlay raises the timeout AGP feeds into ddmlib
(`installation.timeOutInMs`, see `androidchka.extras.gradle.kts`), which
fixes the compatibility check. If ddmlib still misbehaves — its device
cache also goes stale across emulator restarts (`./gradlew --stop` and
`adb kill-server` reset it) — skip Gradle entirely. Plain `adb` is far more
tolerant:

```sh
adb install -r -t <module>-debug.apk
adb install -r -t <module>-debug-androidTest.apk
adb shell am instrument -w \
  -e class com.example.FooTest,com.example.BarTest \
  <test.package>/androidx.test.runner.AndroidJUnitRunner
```

Wrap installs in a small retry loop; the first attempt occasionally lands
during a framework hiccup.

## Screenshot tests

Missing goldens fail with `Missing golden image 'x.png'` *after* writing the
actual render to the device. Pull the actuals as golden candidates from:

```
/storage/emulated/0/Android/data/<app.package>/cache/androidx_screenshots/
```

and place them (renamed to the expected `<test>_emulator.png`) in the golden
directory this overlay wires into androidTest assets
(`../androidx-main/golden/<module-path>/`). Rebuild the androidTest APK to
repack assets, reinstall, rerun.

Don't `mkdir` the app's `Android/data` output directories by hand as root —
MediaProvider attributes those paths by package, and root-owned directories
make subsequent app writes fail. Let the framework create them.

## Budget expectations

On a 4-core host: ATD boot ~6 min, Google-APIs boot 15–45 min, a
`connectedDebugAndroidTest`-sized suite runs at very roughly 20–30 s per
test. Run one emulator at a time; two TCG guests thrash each other.
