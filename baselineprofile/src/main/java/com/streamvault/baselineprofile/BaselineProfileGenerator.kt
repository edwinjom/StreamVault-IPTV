package com.streamvault.baselineprofile

import android.os.SystemClock
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
import kotlin.random.Random

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
 * the real content paths: Live TV list rendering, channel-logo image loading, the Room-backed
 * queries behind them, and opening a channel to exercise Media3/ExoPlayer setup — which is where a
 * media browser spends most of its time. The browse/playback steps are best-effort/guarded so the
 * capture still succeeds if seeding or a stream is unavailable (e.g. no network), in which case it
 * degrades to a clean startup profile.
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
            // Best-effort: UI automation over a live, recomposing app can hit stale nodes or missing
            // elements. Never let that fail the capture — the profile still includes startup plus
            // whatever browsing/playback completed before any hiccup.
            runCatching { browseContent() }
        }
    }

    private companion object {
        const val PACKAGE_NAME = "com.streamvault.app"
        const val CONTENT_LOAD_TIMEOUT_MS = 90_000L
    }
}

private const val IDLE_TIMEOUT_MS = 5_000L
private const val SECTION_LOAD_TIMEOUT_MS = 30_000L
private const val SCROLL_COUNT = 4
private const val PLAYBACK_SETTLE_MS = 6_000L

/** Exercises the primary browse surfaces so list rendering, image loading, and playback are profiled. */
private fun MacrobenchmarkScope.browseContent() {
    // The app lands on Home (content shelves); the playable channel list lives under the Live TV
    // destination, so navigate there explicitly, then into the All Channels category.
    if (openSection("Live TV")) {
        selectCategory("All Channels")
        scrollGrid()
        playRandomChannel()
    }

    // Also visit the Movies (VOD) grid to profile that browse surface.
    if (openSection("Movies")) {
        scrollGrid()
    }
}

/**
 * Selects a top-level destination from the shell's nav rail by its label (rendered as both text and
 * content-description). Returns true if a browsable list appeared. Best-effort.
 */
private fun MacrobenchmarkScope.openSection(label: String): Boolean {
    val item = device.findObject(By.text(label)) ?: device.findObject(By.desc(label)) ?: return false
    item.click()
    val ready = device.wait(Until.hasObject(By.scrollable(true)), SECTION_LOAD_TIMEOUT_MS)
    device.waitForIdle(IDLE_TIMEOUT_MS)
    return ready
}

/** Selects a Live TV category (e.g. the virtual "All Channels" group) by its label. Best-effort. */
private fun MacrobenchmarkScope.selectCategory(label: String) {
    device.findObject(By.text(label))?.click()
    device.waitForIdle(IDLE_TIMEOUT_MS)
}

/**
 * The channel/VOD grid is the scrollable with the most clickable tiles (the category column has few).
 * Re-resolved on every call so a recomposed list never yields a stale handle.
 */
private fun MacrobenchmarkScope.grid() =
    device.findObjects(By.scrollable(true)).maxByOrNull { it.findObjects(By.clickable(true)).size }

/** Scrolls the grid to profile lazy-list item composition and logo/poster image loading. */
private fun MacrobenchmarkScope.scrollGrid() {
    repeat(SCROLL_COUNT) {
        val list = grid() ?: return
        list.setGestureMargin(device.displayWidth / 5)
        list.scroll(Direction.DOWN, 0.8f)
        device.waitForIdle(IDLE_TIMEOUT_MS)
    }
}

/**
 * Plays a random channel from the current grid to profile the Media3/ExoPlayer setup path. Choosing
 * a different channel each run varies coverage and avoids getting stuck on a single dead stream.
 * Public IPTV streams are unreliable, but even a failed prepare exercises player creation, the
 * data-source and renderer wiring, and the playback UI — the code that runs when a user starts
 * watching.
 */
private fun MacrobenchmarkScope.playRandomChannel() {
    // Scroll a random amount so a different set of channels is on screen each iteration.
    repeat(Random.nextInt(0, SCROLL_COUNT)) {
        grid()?.scroll(Direction.DOWN, 0.8f)
        device.waitForIdle(IDLE_TIMEOUT_MS)
    }

    val tile = grid()?.findObjects(By.clickable(true))?.randomOrNull() ?: return
    tile.click()
    // Let the player create the ExoPlayer instance, resolve the stream, wire renderers, and render.
    SystemClock.sleep(PLAYBACK_SETTLE_MS)
    device.waitForIdle(IDLE_TIMEOUT_MS)
    // Some surfaces open an inline preview first and require a confirm to go full-screen.
    device.pressKeyCode(KeyEvent.KEYCODE_DPAD_CENTER)
    SystemClock.sleep(PLAYBACK_SETTLE_MS)
    device.waitForIdle(IDLE_TIMEOUT_MS)
    // Return to the browse surface so player teardown is profiled too.
    device.pressBack()
    device.waitForIdle(IDLE_TIMEOUT_MS)
    device.pressBack()
    device.waitForIdle(IDLE_TIMEOUT_MS)
}
