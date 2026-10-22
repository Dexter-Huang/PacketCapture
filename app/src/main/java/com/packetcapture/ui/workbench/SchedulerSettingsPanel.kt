package com.packetcapture.ui.workbench

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.packetcapture.core.SchedulerSettings
import com.packetcapture.llm.PlatformCatalog
import com.packetcapture.ui.components.Muted
import com.packetcapture.ui.components.Panel

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun SchedulerSettingsPanel(settings: SchedulerSettings, save: (SchedulerSettings) -> Unit) {
    var server by rememberSaveable(settings.serverUrl) { mutableStateOf(settings.serverUrl) }
    var name by rememberSaveable(settings.deviceName) { mutableStateOf(settings.deviceName) }
    var interval by rememberSaveable(settings.pollIntervalSeconds) { mutableStateOf(settings.pollIntervalSeconds.toString()) }
    // 以稳定字符串保存草稿，避免旋转屏幕后丢失平台选择。
    var providers by rememberSaveable(settings.providers.sorted().joinToString(",")) {
        mutableStateOf(settings.providers.sorted().joinToString(","))
    }
    val selected = providers.split(',').filter { it.isNotEmpty() }.toSet()
    val draft = SchedulerSettings(server.trim().trimEnd('/'), name.trim(), interval.toIntOrNull() ?: 0, selected)
    val changed = draft != settings
    val error = draft.validationError()
    Panel {
        Text("GEO 调度接入", fontWeight = FontWeight.Bold)
        Muted("保存接入配置后，在任务页验证设备凭据、领取任务和回传回答。")
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(server, { server = it }, Modifier.fillMaxWidth().testTag("geo-server"),
            label = { Text("GEO 服务地址") }, placeholder = { Text("https://geo.example.com") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().testTag("geo-device-name"),
            label = { Text("设备名称") }, placeholder = { Text("例如：监测手机 01") }, singleLine = true)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(interval, { interval = it }, Modifier.fillMaxWidth().testTag("geo-poll-interval"),
            label = { Text("计划轮询间隔（秒）") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            supportingText = { Text("空闲轮询 10–300 秒；有任务时心跳间隔最多 60 秒") })
        Spacer(Modifier.height(8.dp))
        Text("计划执行平台", fontWeight = FontWeight.Medium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlatformCatalog.platforms.forEach { platform ->
                FilterChip(selected = platform.id in selected, onClick = {
                    providers = (if (platform.id in selected) selected - platform.id else selected + platform.id).sorted().joinToString(",")
                }, label = { Text(platform.displayName) })
            }
        }
        Muted("接单需目标应用已安装、选中 HTTPS 采集且有完整回答样本；豆包自动发题已验证，元宝已通过不发送预检、实时闭环待验证。")
        Spacer(Modifier.height(12.dp))
        if (changed && error != null) Text(error, color = MaterialTheme.colorScheme.error)
        Button(onClick = { save(draft) }, enabled = changed && error == null,
            modifier = Modifier.fillMaxWidth().testTag("geo-save")) {
            Text(if (!changed && settings.validationError() == null) "接入配置已保存" else "保存接入配置")
        }
    }
}
