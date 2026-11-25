package com.aicode.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LaunchTest {
    @get:Rule(order = 0)
    val permissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS)

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun pageIndicatorHasAllPages() {
        listOf("网页", "终端", "文件", "设置", "鱼世界").forEach { label ->
            rule.onNodeWithContentDescription("切换到${label}页面").assertExists()
        }
    }

    @Test
    fun canSwitchToTerminalDirectly() {
        rule.onNodeWithContentDescription("切换到终端页面").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("终端 1").assertExists()
        rule.onNodeWithText("清屏").assertExists()
    }

    @Test
    fun settingsHasEngineAppearanceAndControl() {
        rule.onNodeWithContentDescription("切换到设置页面").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("DSH 连接").assertExists()
        rule.onNodeWithText("深色主题").assertExists()
        rule.onNodeWithText("无障碍服务").assertExists()
    }

    @Test
    fun filesPageHasNewFolderAndUpload() {
        rule.onNodeWithContentDescription("切换到文件页面").performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("新建文件夹").assertExists()
        rule.onNodeWithContentDescription("上传").assertExists()
    }

    @Test
    fun canSwitchBackToWeb() {
        rule.onNodeWithContentDescription("切换到设置页面").performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("切换到网页页面").performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("切换到网页页面").assertExists()
    }
}
