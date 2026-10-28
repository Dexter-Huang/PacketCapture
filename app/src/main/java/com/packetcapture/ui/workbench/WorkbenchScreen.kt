package com.packetcapture.ui.workbench

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.packetcapture.core.CapturePhase
import com.packetcapture.llm.PlatformCatalog
import com.packetcapture.ui.components.*
import com.packetcapture.ui.home.CaptureUiState

@Composable internal fun WorkbenchScreen(
    state: CaptureUiState,
    settings: () -> Unit,
    capture: () -> Unit,
    tasks: () -> Unit,
    refresh: () -> Unit,
    scheduler: com.packetcapture.scheduler.AndroidScheduler? = null,
) {
    val scheduling = scheduler?.state?.collectAsStateWithLifecycle()?.value
    val config = state.settings.scheduler
    val configured = config.validationError() == null
    val installed = PlatformCatalog.platforms.count { platform -> state.apps.any { it.packageName in platform.packageCandidates } }
    LazyColumn(Modifier.fillMaxSize().testTag("workbench-list"), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.PhoneAndroid, null, Modifier.size(28.dp))
                        Text(config.deviceName.ifBlank { "这台手机" }, Modifier.weight(1f).padding(horizontal = 10.dp),
                            fontWeight = FontWeight.SemiBold)
                        StatusLabel(if (scheduling?.bound == true) "已连接" else "未绑定")
                    }
                    Text(if (scheduling?.bound == true) "${if (scheduling.running) "轮询运行中" else "设备已连接"}"
                        else if (configured) "接入配置已保存" else "连接 GEO，准备开始监测", fontSize = 24.sp,
                        lineHeight = 32.sp, fontWeight = FontWeight.Bold)
                    Text("由 GEO 分配品牌监测任务；手机领取、采集并回传，可为已适配平台启用自动发题。", lineHeight = 22.sp)
                    Button(onClick = settings, modifier = Modifier.fillMaxWidth()) {
                        Text(if (configured) "管理接入配置" else "配置 GEO 接入")
                        Spacer(Modifier.width(8.dp)); Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(18.dp))
                    }
                    Text("在任务页连接设备、选择平台并开启轮询", fontSize = 12.sp)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SummaryTile("$installed / ${PlatformCatalog.platforms.size}", "已安装平台", Modifier.weight(1f))
                SummaryTile(config.providers.size.toString(), "计划执行平台", Modifier.weight(1f))
                SummaryTile("${config.pollIntervalSeconds}s", "计划轮询间隔", Modifier.weight(1f))
            }
        }
        item {
            SectionTitle("运行准备", "检查设备")
            Spacer(Modifier.height(8.dp))
            Panel {
                ReadinessRow(Icons.Outlined.CloudQueue, "接入配置", when {
                    scheduling?.bound == true -> "设备已连接 GEO，任务页可继续接单"
                    configured -> "配置已保存，尚未绑定设备"
                    else -> "填写服务地址和设备信息"
                }, scheduling?.bound == true)
                HorizontalDivider()
                ReadinessRow(Icons.Outlined.TouchApp, "应用操作", "六平台限定模式已有单次发题与回传样本；任务页可开启按计划平台串行轮转，需保持解锁", scheduling?.automaticDoubao == true)
                HorizontalDivider()
                val active = state.capture.phase == CapturePhase.CAPTURING
                ReadinessRow(Icons.Outlined.Sensors, "回答采集", when (state.capture.phase) {
                    CapturePhase.CAPTURING -> "采集运行中，完整回答按任务自动核对与回传"
                    CapturePhase.STARTING -> "采集正在启动"
                    CapturePhase.STOPPING -> "采集正在停止"
                    CapturePhase.FAILED -> "采集异常，请打开工具检查"
                    else -> "未启动，可先手动验证采集"
                }, active)
                HorizontalDivider()
                ReadinessRow(Icons.Outlined.VerifiedUser, "HTTPS 信任", "需按平台核对目标应用的证书信任", false)
                OutlinedButton(onClick = capture, Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("打开采集工具") }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("平台能力", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                TextButton(onClick = refresh, enabled = state.ready) {
                    Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("刷新")
                }
            }
            Panel {
                PlatformCatalog.platforms.forEachIndexed { index, platform ->
                    if (index > 0) HorizontalDivider()
                    val app = state.apps.firstOrNull { it.packageName in platform.packageCandidates }
                    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(platform.displayName, Modifier.weight(1f), fontWeight = FontWeight.Medium)
                            StatusLabel(if (!state.ready) "读取中" else if (app == null) "未找到应用" else "已安装")
                        }
                        Muted(if (app == null) "候选包未匹配" else "${app.packageName} · ${app.versionName ?: "版本未知"}")
                        Muted(if (platform.id == "yuanbao") "快速文本自动发题已验证 · 较长搜索回答仍有解析阻塞"
                            else if (platform.id == "deepseek") "普通文本自动发题已有样本 · 搜索和深度思考必须关闭"
                            else if (platform.id == "doubao") "回答解析：已有指定版本样本 · 可启用自动发题"
                            else if (platform.id == "wenxin") "自动模式已有闭环样本 · 依赖本机私有传输缓冲"
                            else if (platform.id == "kimi") "快速模式已有闭环样本 · 搜索与工具流仍待验证"
                            else if (platform.id == "qianwen") "快速模式已有闭环样本 · 依赖本机私有传输条件"
                            else if (platform.support == "verified_sample") "回答解析：已有指定版本样本 · 自动操作需按版本验证"
                            else "回答解析：待验证 · 自动操作：待实现")
                        if (platform.id in config.providers) Text("已列入执行计划", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                    }
                }
                Muted("安装状态来自本机应用列表；样本解析通过不代表当前安装版本、登录或自动操作已就绪。")
            }
        }
        item {
            Panel {
                SectionTitle("任务动态", "在任务页查看实时连接与领取状态")
                Spacer(Modifier.height(10.dp))
                Muted(scheduling?.message ?: "绑定后，在任务页查看当前任务、回传情况与需要人工处理的异常。")
                TextButton(onClick = tasks) { Text("查看任务工作区") }
            }
        }
    }
}

@Composable internal fun TasksScreen(settings: () -> Unit, capture: () -> Unit,
    scheduler: com.packetcapture.scheduler.AndroidScheduler? = null, serverUrl: String = "") {
    LazyColumn(Modifier.fillMaxSize().testTag("tasks-list"), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (scheduler != null) item { SchedulerPanel(scheduler, serverUrl) }
        if (scheduler == null)
        item {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Outlined.Assignment, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text("等待接入任务服务", fontWeight = FontWeight.SemiBold)
                        Muted("当前没有 GEO 任务数据")
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("接入后查看这台手机的当前任务、待回传结果、执行记录与人工处理事项。", lineHeight = 24.sp)
                Button(onClick = settings, Modifier.fillMaxWidth().padding(top = 16.dp)) { Text("配置接入") }
            }
        }
        item {
            SectionTitle("单台手机，一次执行一个任务", "当前执行流程")
            Spacer(Modifier.height(8.dp))
            Panel {
                FlowStep("01", "领取任务", "手机轮询 GEO，领取适配本机平台能力的任务租约。")
                FlowStep("02", "准备采集与发题", "手机自动保存本次采集边界；已适配平台可由 APK 核对原文后发送一次，具体已验收模式见应用操作说明。")
                FlowStep("03", "自动等待完整回答", "持续关联本次请求、检查完整性并续租；无需返回点击核对。")
                FlowStep("04", "自动保存并回传", "完整结果先加密保存再幂等上传；服务端确认后才领取下一条任务。")
            }
        }
        item {
            Panel {
                Text("中断后如何继续", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Muted("断网时保留结果等待回传；已经发送的问题只续采。无法确认是否发送、回答关联不唯一或租约失效时，转人工处理，避免重复提问。")
            }
        }
        item { OutlinedButton(onClick = capture, Modifier.fillMaxWidth()) { Text("先验证手动采集") } }
    }
}

@Composable private fun SummaryTile(value: String, label: String, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(vertical = 16.dp, horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp)); Muted(label)
        }
    }
}

@Composable private fun SectionTitle(title: String, subtitle: String) {
    Column { Text(title, fontWeight = FontWeight.SemiBold); Muted(subtitle) }
}

@Composable private fun StatusLabel(text: String) {
    Text(text, Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
        .padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable private fun ReadinessRow(icon: ImageVector, title: String, detail: String, ready: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text(title, fontWeight = FontWeight.Medium); Muted(detail) }
        Icon(if (ready) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            if (ready) "已满足此项" else "待就绪", Modifier.size(20.dp),
            tint = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
    }
}

@Composable private fun FlowStep(number: String, title: String, description: String) {
    Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(number, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Column { Text(title, fontWeight = FontWeight.Medium); Muted(description) }
    }
}
