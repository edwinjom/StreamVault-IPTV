package com.streamvault.baselineprofile

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
 * The journey is intentionally conservative: StreamVault gates most surfaces behind provider
 * onboarding, so a generic instrumentation run cannot assume a configured provider. Capturing a
 * clean cold start plus the first idle frame already covers the Application/Hilt graph, the initial
 * Compose composition, and navigation setup — the classes that dominate cold-start cost. The
 * optional scroll below extends coverage to list rendering when a scrollable surface is present.
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
        pressHome()
        startActivityAndWait()

        // Let first-frame composition and initial data loading settle so the profile captures the
        // home/onboarding rendering path rather than just the splash.
        device.waitForIdle(WAIT_TIMEOUT_MS)

        // If the current surface is scrollable (e.g. a browse list once a provider exists), exercise
        // it so list item composition and image binding are profiled. Absent on onboarding — guarded
        // so the run stays green either way.
        device.wait(Until.hasObject(By.scrollable(true)), WAIT_TIMEOUT_MS)
        device.findObject(By.scrollable(true))?.let { scrollable ->
            scrollable.setGestureMargin(device.displayWidth / 5)
            repeat(2) { scrollable.scroll(Direction.DOWN, 0.8f) }
            device.waitForIdle(WAIT_TIMEOUT_MS)
        }
    }

    private companion object {
        const val PACKAGE_NAME = "com.streamvault.app"
        const val WAIT_TIMEOUT_MS = 5_000L
    }
}
