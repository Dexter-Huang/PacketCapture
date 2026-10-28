package com.packetcapture.ui.workbench

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.provider.Settings
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
    var confirmMigration by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    if (confirmMigration) AlertDialog(
        onDismissRequest = { confirmMigration = false },
        title = { Text("归档旧环境并重新绑定") },
        text = { Text("仅在原服务无法恢复、旧租约已经到期时使用。旧任务、待回传回答和凭据将加密保存在本机；不会确认原服务成功或退款，也不会向新服务上传旧回答。旧问题不会重新发送。请保留应用数据，卸载会丢失归档和密钥。") },
        confirmButton = { TextButton(onClick = {
            confirmMigration = false
            scheduler.archiveExpiredConnection()
        }) { Text("加密归档并更换连接") } },
        dismissButton = { TextButton(onClick = { confirmMigration = false }) { Text("取消") } },
    )
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
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("APK 自动发题", Modifier.weight(1f))
                Switch(state.automaticDoubao, scheduler::automaticDoubao,
                    enabled = !state.running && state.job == null && !state.busy)
            }
            if (state.automaticDoubao) Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("按计划平台自动轮转", Modifier.weight(1f))
                Switch(state.automaticAllPlatforms, scheduler::automaticAllPlatforms,
                    enabled = !state.running && state.job == null && !state.busy)
            }
            for (platforms in com.packetcapture.execution.PhonePlatforms.names.entries.chunked(3)) {
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((id, name) in platforms) {
                    FilterChip(selected = state.automaticProvider == id, onClick = { scheduler.automaticProvider(id) },
                        enabled = !state.running && state.job == null && !state.busy, label = { Text(name) })
                }
              }
            }
            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                Text("启用 GEO 无障碍服务")
            }
            if (state.automaticDoubao) {
                TextButton(onClick = {
                    val vendor = Intent("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_pkgname", context.packageName)
                    val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:${context.packageName}"))
                    context.startActivity(if (vendor.resolveActivity(context.packageManager) != null) vendor else details)
                }) { Text("系统后台启动设置") }
                Muted("跨平台切换需要允许后台打开应用。小米手机请在其他权限中允许“后台弹出界面”。")
            }
            if (state.job == null) TextButton(onClick = scheduler::checkDoubaoControls,
                enabled = !state.running && !state.busy) { Text("检查选定平台界面（不发送）") }
            Muted(if (state.automaticDoubao && state.automaticAllPlatforms) "按设置中的计划平台串行执行，自动切换采集；下方平台用于界面预检。需保持解锁及网络可用，发送不确定时不会补发。"
                else if (state.automaticDoubao) "仅自动领取选定平台；需保持解锁、采集运行及网络可用。发送不确定时不会补发。"
                else "当前由你在目标应用发送一次问题。")
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
            TextButton(onClick = { clipboard.setText(AnnotatedString(job.prompt)) },
                enabled = state.prepared && state.running && !state.pendingUpload &&
                    state.executionPhase in listOf(null, "manual")) { Text("复制问题原文") }
            Muted("租约截止 ${job.leaseUntil} · ${job.status}")
            if (state.pendingUpload) {
                Muted(if (state.running) "完整结果已保存，正在自动回传；网络恢复后重试同一份结果。"
                    else "完整结果已保存；点击继续接单，自动恢复回传。")
            } else if (!state.prepared) {
                Muted(if (state.running) "正在自动准备本次采集，请等待就绪提示后发送。"
                    else "点击继续接单，自动准备本次采集。")
            } else {
                Muted(if (state.executionPhase !in listOf(null, "manual")) "手机自动执行中，请勿手动发题；完整回答会自动核对并回传。"
                    else if (state.running) "请在目标应用的新对话中发送一次原文。完整回答会自动核对并回传，无需返回点击。"
                    else "采集边界已保存；点击继续接单，自动等待已有回答并回传。请勿重复发送。")
            }
            TextButton(onClick = scheduler::finishFailed, enabled = !state.busy && !state.pendingUpload) {
                Text("本次未交付回答，结束为失败")
            }
            TextButton(onClick = scheduler::reviewTerminal, enabled = !state.busy && !state.running) {
                Text("核对服务端终态并归档本地记录")
            }
            TextButton(onClick = { confirmMigration = true }, enabled = !state.busy && !state.running) {
                Text("原服务无法恢复：归档并迁移")
            }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}
