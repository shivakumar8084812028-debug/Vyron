package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VyronUiRobolectricTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun `studio screen renders and navigates across vision ai and diagnostics lab`() {
        composeTestRule.waitForIdle()

        // Studio screen visible
        composeTestRule.onNodeWithText("VYRON STUDIO").assertIsDisplayed()
        composeTestRule.onNodeWithTag("screen_brightness_slider").assertIsDisplayed()
        composeTestRule.onNodeWithTag("pick_local_video_button").assertIsDisplayed()

        // Navigate to Vision AI tab
        composeTestRule.onNodeWithTag("nav_vision_ai").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("VYRON VISION AI").assertIsDisplayed()

        // Switch mode to HDR to SDR + Smart Enhancement
        composeTestRule.onNodeWithTag("vision_mode_hdr_to_sdr_smart").performClick()
        composeTestRule.waitForIdle()

        // Navigate to Lab tab
        composeTestRule.onNodeWithTag("nav_lab").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("HARDWARE & THERMAL LAB").assertIsDisplayed()
        composeTestRule.onNodeWithTag("start_benchmark_button").performScrollTo().assertIsDisplayed()
    }
}
