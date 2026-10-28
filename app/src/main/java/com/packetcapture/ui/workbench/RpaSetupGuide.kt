package com.packetcapture.ui.workbench

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.packetcapture.core.InstalledApp
import com.packetcapture.execution.PhonePlatforms

/** 首次运行说明与可重复打开的设置入口；用户阅读确认不冒充系统授权或采集验收。 */
@Composable internal fun RpaSetupGuide(
    apps: List<InstalledApp>, providers: Set<String>,
    onDismiss: () -> Unit, onConfigure: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("先准备好手机 RPA") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("每台新手机都需要单独配置。以下设置不会自动替你授权，也不会发送问题。")
                Text("1. 安装并登录计划使用的平台", style = MaterialTheme.typography.titleSmall)
                for ((id, name) in PhonePlatforms.names) {
                    val app = apps.find { it.packageName == PhonePlatforms.packageName(id) }
                    Text("$name：${if (app == null) "未找到应用" else "已安装 · ${app.versionName ?: "版本未知"}"}${if (id in providers) " · 已选入计划" else ""}")
                    if (app != null) TextButton(onClick = {
                        context.packageManager.getLaunchIntentForPackage(app.packageName)?.let(context::startActivity)
                    }) { Text("打开$name，确认登录") }
                }
                Text("安装状态不代表已登录。首次打开对应平台时，请先完成其登录、协议和系统权限提示。只按实际需要授权；验证码或未知弹窗需人工处理。")
                Text("2. 允许工具跨应用操作", style = MaterialTheme.typography.titleSmall)
                Text("在系统开启“GEO 平台自动执行”无障碍服务。允许后台打开应用；小米手机在“其他权限”中允许“后台弹出界面”。保持手机解锁，按系统需要配置自启动和后台省电。")
                TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                    Text("设置无障碍服务")
                }
                TextButton(onClick = {
                    val vendor = Intent("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_pkgname", context.packageName)
                    context.startActivity(if (vendor.resolveActivity(context.packageManager) != null) vendor else
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                }) { Text("设置后台启动权限") }
                TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }) {
                    Text("查看后台省电设置")
                }
                Text("3. 配置并验证 HTTPS 采集", style = MaterialTheme.typography.titleSmall)
                Text("在采集设置中启用 HTTPS、按说明安装并信任本机证书，首次启动接受 VPN 授权。平台 App 必须实际允许采集；普通安装包、版本或机型不同都需重新验证。文心、千问当前还有专用测试条件。")
                Text("4. 连接 GEO 后再开启自动接单", style = MaterialTheme.typography.titleSmall)
                Text("保存本机可访问的 HTTPS 服务地址并绑定设备凭据。到任务页逐个平台运行“检查选定平台界面（不发送）”；模式、空对话、原文回读和发送控件均确认后再接单。")
                Text("正式执行会再次确认目标 App 已进入前台；未确认时保留任务并提示处理。发送前保存记录，发送结果不确定不会补发。")
            }
        },
        confirmButton = { TextButton(onClick = onConfigure) { Text("去配置") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("我已了解") } },
    )
}
