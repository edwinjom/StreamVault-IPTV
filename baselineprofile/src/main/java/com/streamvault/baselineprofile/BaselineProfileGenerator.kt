package com.streamvault.baselineprofile

import android.view.KeyEvent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates a Baseline Profile for StreamVault.
 *
 * Run against a connected device/emulator (API 33+ recommended):
 *
 *     ./gradlew :app:generateReleaseBaselineProfile
 *
 * The resulting human-readable profile is written to
 * `app/src/release/generated/baselineProfiles/` and packaged into the release APK, where
 * ProfileInstaller applies it on first run. Re-run whenever startup or the primary browse
 * surfaces change materially.
 *
 * The `nonMinifiedRelease` profiling variant seeds a public M3U provider on first boot (see the
 * `afterEvaluate` block in `app/build.gradle.kts`), so this journey covers not just cold start but
 * the real content-browsing paths — Live TV list rendering, channel-logo image loading, and the
 * Room-backed queries behind them — which is where a media browser spends most of its time. The
 * browse steps are best-effort/guarded so the capture still succeeds if seeding is unavailable
 * (e.g. no network), in which case it degrades to a clean startup profile.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generate() = baselineProfileRule.collect(
        packageName = PACKAGE_NAME,
        includeInStartupProfile = true,
    ) {
        // StreamVault runs fullscreen/immersive, so the system "swipe to exit full screen"
        // (ImmersiveModeConfirmation) overlay grabs window focus on first launch and prevents
        // macrobenchmark from confirming MainActivity's launch. Mark it acknowledged up front.
        device.executeShellCommand("settings put secure immersive_mode_confirmations confirmed")

        // MainActivity is launchMode="singleTask"; if the process is still alive, the launcher
        // intent only brings the existing task to the front and draws no new frame, which makes the
        // macrobenchmark frame-based launch confirmation fail. Force a genuine cold start.
        killProcess()
        pressHome()
        startActivityAndWait()

        // On the first iteration the app auto-onboards the seeded M3U provider and syncs it over the
        // network before any content appears, so wait generously for the browse UI to populate.
        // Later iterations reuse the already-synced database and reach content quickly.
        val contentReady = device.wait(Until.hasObject(By.scrollable(true)), CONTENT_LOAD_TIMEOUT_MS)
        device.waitForIdle(IDLE_TIMEOUT_MS)

        if (contentReady) {
            browseContent()
        }
    }

    private companion object {
        const val PACKAGE_NAME = "com.streamvault.app"
        const val CONTENT_LOAD_TIMEOUT_MS = 90_000L
    }
}

private const val IDLE_TIMEOUT_MS = 5_000L
private const val SCROLL_COUNT = 4
private const val DPAD_STEPS = 6

/** Exercises the primary browse surfaces so list rendering and image loading are profiled. */
private fun MacrobenchmarkScope.browseContent() {
    // Scroll the current list (Live TV) to profile lazy-list item composition and logo loading.
    device.findObject(By.scrollable(true))?.let { list ->
        list.setGestureMargin(device.displayWidth / 5)
        repeat(SCROLL_COUNT) {
            list.scroll(Direction.DOWN, 0.8f)
            device.waitForIdle(IDLE_TIMEOUT_MS)
        }
        list.scroll(Direction.UP, 1.0f)
    }

    // Move focus around with the D-pad (this is a TV-first UI) to profile row/category switching
    // and focus handling across the shell.
    repeat(DPAD_STEPS) {
        device.pressKeyCode(KeyEvent.KEYCODE_DPAD_DOWN)
        device.waitForIdle(IDLE_TIMEOUT_MS)
    }
    repeat(DPAD_STEPS) {
        device.pressKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT)
        device.waitForIdle(IDLE_TIMEOUT_MS)
    }
}
