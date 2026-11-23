package com.example

import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @get:Rule
  val composeRule = createAndroidComposeRule<MainActivity>()

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("AeroLink", appName)
  }

  @Test
  fun `launch MainActivity and navigate across tabs without crashing`() {
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("home_connect_button").assertExists()
    composeRule.onNodeWithTag("nav_vision").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("nav_models").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("nav_cockpit").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithTag("nav_home").performClick()
    composeRule.waitForIdle()
  }
}
