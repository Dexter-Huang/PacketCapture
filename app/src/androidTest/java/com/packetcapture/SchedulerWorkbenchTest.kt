package com.packetcapture

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import com.packetcapture.core.*
import com.packetcapture.data.PreferenceSettings
import com.packetcapture.ui.home.CaptureActions
import com.packetcapture.ui.home.CaptureApp
import com.packetcapture.ui.home.CaptureUiState
import com.packetcapture.ui.theme.PacketCaptureTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** 仅使用合成页面；设置往返测试保存并恢复原值，不连接 GEO 或操作目标 App。 */
class SchedulerWorkbenchTest {
    @get:Rule val compose = createComposeRule()
    private val fixture = CaptureUiState(ready = true, apps = listOf(
        InstalledApp("com.tencent.hunyuan.app.chat", "元宝", "synthetic-1")))

    @Test fun workbenchAndTaskNavigationDoNotStartCaptureOrSendTasks() {
        var starts = 0
        var certificates = 0
        compose.setContent { PacketCaptureTheme {
            Box(Modifier.requiredSize(390.dp, 780.dp)) {
                CaptureApp(fixture, actions(start = { starts++ }, certificate = { certificates++ }))
            }
        } }
        compose.onNodeWithText("未绑定").assertIsDisplayed()
        compose.onNodeWithText("在任务页连接设备并开启轮询 · 问题由你发送一次").assertIsDisplayed()
        saveScreenshot("workbench")
        compose.onNodeWithTag("workbench-list").performScrollToNode(hasText("平台能力"))
        compose.onNodeWithTag("workbench-list").performScrollToNode(hasText("com.tencent.hunyuan.app.chat · synthetic-1"))
        compose.onNodeWithText("com.tencent.hunyuan.app.chat · synthetic-1").assertIsDisplayed()
        compose.onNodeWithText("任务").performClick()
        compose.onNodeWithText("等待接入任务服务").assertIsDisplayed()
        saveScreenshot("tasks")
        compose.onNodeWithText("采集").performClick()
        compose.onNodeWithTag("capture-list").assertExists()
        compose.onNodeWithText("会话历史").performClick()
        compose.onNodeWithTag("capture-list").assertDoesNotExist()
        compose.onNodeWithText("实时采集").performClick()
        compose.onNodeWithTag("capture-list").assertExists()
        compose.runOnIdle { assertEquals(0, starts); assertEquals(0, certificates) }
    }

    @Test fun connectionDraftValidatesAndSavesWithoutMarkingDeviceBound() {
        val state = mutableStateOf(fixture)
        compose.setContent { PacketCaptureTheme {
            CaptureApp(state.value, actions(save = { state.value = state.value.copy(settings = it) }))
        } }
        compose.onNodeWithText("配置 GEO 接入").performClick()
        compose.onNodeWithTag("geo-save").assertIsNotEnabled()
        compose.onNodeWithTag("geo-server").performTextInput("http://geo.example.com")
        compose.onNodeWithTag("geo-device-name").performTextInput("合成监测手机")
        compose.onNodeWithTag("settings-list").performScrollToNode(hasText("元宝"))
        compose.onNodeWithText("元宝").performClick()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("geo-save"))
        compose.onNodeWithTag("geo-save").assertIsNotEnabled()
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("geo-server"))
        compose.onNodeWithTag("geo-server").performTextReplacement("https://geo.example.com")
        compose.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("geo-save"))
        compose.onNodeWithTag("geo-save").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(setOf("yuanbao"), state.value.settings.scheduler.providers) }
        compose.onNodeWithText("工作台").performClick()
        compose.onNodeWithText("合成监测手机").assertIsDisplayed()
        compose.onNodeWithText("接入配置已保存").assertIsDisplayed()
        compose.onNodeWithText("未绑定").assertIsDisplayed()
    }

    @Test fun settingsRoundTripKeepsCapturePreferencesSeparate() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = PreferenceSettings(context)
        val original = repository.settings.first()
        try {
            val updated = original.copy(capture = original.capture.copy(packages = setOf("example.synthetic")),
                scheduler = SchedulerSettings("https://geo.example.com", "合成设备", 30, setOf("yuanbao")))
            repository.update(updated)
            assertEquals(updated, repository.settings.first())
            repository.update(updated.copy(theme = ThemeMode.DARK))
            val restored = repository.settings.first()
            assertEquals(updated.scheduler, restored.scheduler)
            assertEquals(updated.capture, restored.capture)
        } finally { repository.update(original) }
    }

    private fun saveScreenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "scheduler-$name.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun actions(start: () -> Unit = {}, certificate: () -> Unit = {}, save: (AppSettings) -> Unit = {}) = CaptureActions(
        start = start, stop = {}, filter = {}, settings = save, selectSession = {}, detail = {}, delete = {},
        historySearch = {}, certificate = certificate, export = {}, refreshStorage = {}, clearStorage = {}, clearMessage = {})
}
