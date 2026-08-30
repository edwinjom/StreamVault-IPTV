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

Requires a connected device or emulator running **API 33+** (a rooted/`userdebug` emulator image is
recommended so the profile can be captured):

```bash
./gradlew :app:generateReleaseBaselineProfile
```

This builds the `nonMinifiedRelease` variant, runs `BaselineProfileGenerator` on the device, and
copies the result into `app/src/release/generated/baselineProfiles/`. Commit that file.

> If you do not have the release keystore, the benchmark variants fall back to debug signing
> automatically (see the `afterEvaluate` block in `app/build.gradle.kts`), so generation works
> without any secrets.

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
