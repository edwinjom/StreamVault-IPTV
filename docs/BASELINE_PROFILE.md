# Baseline Profiles

StreamVault ships a [Baseline Profile](https://developer.android.com/topic/performance/baselineprofiles/overview)
so that hot startup and first-scroll code paths are AOT-compiled from the very first launch instead
of relying on the interpreter/JIT to warm up. For a Compose app this is typically a meaningful
cold-start improvement and a reduction in first-run scroll jank.

## How it is wired

- **`:baselineprofile`** — a `com.android.test` module (`androidx.baselineprofile` plugin) that
  targets `:app`. It contains:
  - `BaselineProfileGenerator` — the journey that gets recorded into the profile.
  - `StartupBenchmarks` — a macrobenchmark that measures cold start with and without the profile so
    the win can be quantified.
- **`:app`** — applies the `androidx.baselineprofile` plugin, consumes the generated profile via
  `baselineProfile(project(":baselineprofile"))`, and depends on `androidx.profileinstaller` so the
  packaged profile is installed at first run.

The generated profile is written to `app/src/release/generated/baselineProfiles/` and packaged into
the release (and beta) APK automatically. It is a build output; regenerate it rather than editing it
by hand.

## Generating the profile

```bash
./gradlew :app:generateReleaseBaselineProfile
```

This builds the `nonMinifiedRelease` variant, runs `BaselineProfileGenerator` on the device, and
copies the result into `app/src/release/generated/baselineProfiles/`. Commit that file.

> If you do not have the release keystore, the benchmark variants fall back to debug signing
> automatically (see the `afterEvaluate` block in `app/build.gradle.kts`), so generation works
> without any secrets.

### Device requirements (important)

The generator device must satisfy **all** of the following, or capture fails:

1. **API 33+** on a non-rooted device, **or** API 28+ on a rooted device (e.g. `adb root` on a
   `userdebug` emulator). Macrobenchmark cannot capture a profile otherwise.
2. **It must emit per-frame `dumpsys gfxinfo <pkg> framestats` PROFILEDATA rows.** Macrobenchmark
   confirms each activity launch by reading that frame timeline; without it you get
   `IllegalStateException: Unable to confirm activity launch completion []`. Many software-GPU or
   virtualization-constrained emulators (e.g. running under Hyper-V/VBS) render frames but emit an
   *empty* PROFILEDATA section — those cannot be used. Verify with:

   ```bash
   adb shell dumpsys gfxinfo <pkg> framestats | awk '/---PROFILEDATA---/{f=!f;next} f' | grep -c ,
   ```

   A healthy device prints a non-zero row count shortly after the app renders a frame.
3. Enough GPU/CPU performance that a cold launch completes within the tool's launch-detection window.
   Prefer a **physical device**, a GPU-accelerated emulator on a bare-metal host, a Gradle Managed
   Device ATD image, or CI.

> **Tooling/API note:** keep `androidx.benchmark` current with the device's API level. The frame
> detection parses `gfxinfo framestats`, whose format changes across platform versions — older
> benchmark releases fail on newer devices with `Unable to confirm activity launch completion []`
> even though the app launches fine. The committed profile here was generated on a Pixel 9 Pro
> (API 37) with benchmark 1.4.1; 1.3.4 could not parse that device's framestats.

Note on ABIs: the app ships `arm64-v8a`/`armeabi-v7a` only, so on an x86/x86_64 emulator it runs via
ARM translation (slow). To run natively on an x86_64 emulator for a faster capture, add that ABI for
the run via the existing hook: `./gradlew :app:generateReleaseBaselineProfile -PcompatAbi=x86_64`
(does not change the shipped release APK).

## Measuring the improvement

```bash
./gradlew :app:benchmarkReleaseBenchmarkAndroidTest
```

Compare `StartupBenchmarks.startupNoCompilation` (JIT only) against
`StartupBenchmarks.startupBaselineProfile` (profile applied) in the test output.

## When to regenerate

Regenerate after changes that materially affect startup or the primary browse surfaces — new
navigation entry points, a reworked home/onboarding screen, or a different list implementation. A
stale profile is never incorrect (it only ever helps or is a no-op), but keeping it current keeps the
benefit maximal.

## Note on the recorded journey

StreamVault gates most surfaces behind provider onboarding, so the generator cannot assume a
configured provider. It records a clean cold start plus the first idle frame — which already covers
the `Application`/Hilt graph, initial Compose composition, and navigation setup — and opportunistically
scrolls the first surface if one is scrollable. To widen coverage, extend `BaselineProfileGenerator`
to drive a seeded provider (see `docs/DEV_SEEDING.md`) through Live TV / Movies browsing.
