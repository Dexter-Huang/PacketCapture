package com.packetcapture.ui.workbench

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.packetcapture.scheduler.AndroidScheduler
import com.packetcapture.ui.components.Panel
import com.packetcapture.ui.components.Muted

/** 凭据只在未保存草稿中短暂显示；任务区准确区分租约、采集准备和服务端确认。 */
@Composable internal fun SchedulerPanel(scheduler: AndroidScheduler, serverUrl: String) {
    val state = scheduler.state.collectAsStateWithLifecycle().value
    var token by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    Panel {
        Text(if (state.bound) "GEO 调度连接" else "连接设备凭据", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(state.message)
        if (!state.bound) {
            Spacer(Modifier.height(12.dp))
            Muted("先在设置保存 GEO 的 HTTPS 根地址，由 GEO 管理者签发本监测的设备凭据。")
            OutlinedTextField(token, { token = it }, label = { Text("设备凭据") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(pin, { pin = it }, label = { Text("本地联调证书 SHA256（公网可信证书留空）") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(onClick = { scheduler.bind(serverUrl, token, pin); token = ""; pin = "" },
                enabled = state.loaded && !state.busy && token.isNotBlank() && serverUrl.isNotBlank(),
                modifier = Modifier.fillMaxWidth()) { Text("验证并连接") }
        } else {
            Muted(state.serverUrl)
            Muted("设备 ${state.deviceId}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = scheduler::start, enabled = !state.running && !state.busy) { Text("继续接单") }
                OutlinedButton(onClick = scheduler::stop, enabled = state.running) { Text("暂停轮询") }
            }
            Muted("依赖本机进程和采集服务运行；暂停轮询不会暂停服务端租约。")
            if (state.job == null) TextButton(onClick = scheduler::disconnect,
                enabled = !state.running && !state.busy) { Text("清除本机连接") }
        }
        state.job?.let { job ->
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text("当前任务 · ${job.provider}", style = MaterialTheme.typography.titleMedium)
            Muted("批次 ${job.batchId}")
            Text(job.prompt, Modifier.padding(vertical = 10.dp))
            TextButton(onClick = { clipboard.setText(AnnotatedString(job.prompt)) }) { Text("复制问题原文") }
            Muted("租约截止 ${job.leaseUntil} · ${job.status}")
            if (state.pendingUpload) {
                Button(onClick = scheduler::collect, enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth()) { Text("重试回传已保存结果") }
            } else if (!state.prepared) {
                Button(onClick = scheduler::prepare, enabled = !state.busy && !state.pendingUpload,
                    modifier = Modifier.fillMaxWidth()) { Text("准备本次采集") }
            } else {
                Muted("请在目标应用的新对话中发送一次原文，然后回到这里核对。采集失败时不要重复发送。")
                Button(onClick = scheduler::collect, enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth()) { Text("核对完整回答并回传") }
            }
            TextButton(onClick = scheduler::finishFailed, enabled = !state.busy && !state.pendingUpload) {
                Text("本次未交付回答，结束为失败")
            }
            TextButton(onClick = scheduler::reviewTerminal, enabled = !state.busy && !state.running) {
                Text("核对服务端终态并归档本地记录")
            }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}
