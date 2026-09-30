package com.cold04.picomanage

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.pressBack
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun backFromSecondaryTabReturnsToSend() {
        composeRule.onNodeWithText("文件管理").performClick()
        composeRule.onNodeWithText("设备未连接").assertIsDisplayed()

        pressBack()

        composeRule.onNodeWithText("欢迎使用").assertIsDisplayed()
    }

    @Test
    fun backFromDevicesReturnsToSourcePage() {
        composeRule.onNodeWithText("未连接").performClick()
        composeRule.onNodeWithText("当前没有已连接的设备").assertIsDisplayed()

        pressBack()

        composeRule.onNodeWithText("欢迎使用").assertIsDisplayed()
    }
}
