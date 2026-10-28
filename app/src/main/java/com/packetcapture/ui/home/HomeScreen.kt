package com.packetcapture.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.packetcapture.core.*
import com.packetcapture.export.ExportRequest
import com.packetcapture.ui.history.HistoryScreen
import com.packetcapture.ui.settings.AppPicker
import com.packetcapture.ui.settings.BypassDomainsDialog
import com.packetcapture.ui.settings.CertificateGuideDialog
import com.packetcapture.ui.settings.SettingsScreen
import com.packetcapture.ui.workbench.TasksScreen
import com.packetcapture.ui.workbench.WorkbenchScreen
import com.packetcapture.ui.workbench.RpaSetupGuide
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** 首页操作集合；具体功能页面只接收自身所需的数据和回调。 */
data class CaptureActions(
    val start: () -> Unit, val stop: () -> Unit, val filter: (ExchangeFilter) -> Unit,
    val settings: (AppSettings) -> Unit, val selectSession: (String?) -> Unit,
    val detail: (String) -> Unit, val delete: (String) -> Unit, val historySearch: (String) -> Unit,
    val certificate: () -> Unit, val export: (ExportRequest) -> Unit, val refreshStorage: () -> Unit,
    val clearStorage: () -> Unit,
    val clearMessage: () -> Unit,
    val refreshEnvironment: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun CaptureApp(state: CaptureUiState, actions: CaptureActions, scheduler: com.packetcapture.scheduler.AndroidScheduler? = null) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    var captureHistory by rememberSaveable { mutableStateOf(false) }
    var appPicker by remember { mutableStateOf(false) }
    var bypass by remember { mutableStateOf(false) }
    var guide by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val setupPreferences = remember { context.getSharedPreferences("rpa-setup", android.content.Context.MODE_PRIVATE) }
    var setupGuide by remember { mutableStateOf(!setupPreferences.getBoolean("guide-seen-v1", false)) }
    val scheduling = scheduler?.state?.collectAsStateWithLifecycle()?.value
    fun closeSetupGuide() {
        setupPreferences.edit().putBoolean("guide-seen-v1", true).apply()
        setupGuide = false
    }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); actions.clearMessage() } }
    LaunchedEffect(page) { if (page == 3) { actions.refreshStorage(); actions.certificate() } }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = {
        TopAppBar(title = { Text(listOf("GEO 设备工作台", "任务工作区", "采集工具", "设置")[page], fontWeight = FontWeight.Bold) },
            actions = { TextButton(onClick = { setupGuide = true }) { Text("运行指南") } })
    }, bottomBar = {
        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
            listOf("工作台" to Icons.Outlined.Dashboard, "任务" to Icons.AutoMirrored.Outlined.Assignment,
                "采集" to Icons.AutoMirrored.Outlined.ShowChart, "设置" to Icons.Outlined.Settings).forEachIndexed { index, item ->
                NavigationBarItem(selected = page == index, onClick = { page = index }, icon = { Icon(item.second, item.first) }, label = { Text(item.first) })
            }
        }
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (page) {
                0 -> WorkbenchScreen(state, { page = 3 }, { captureHistory = false; page = 2 },
                    { page = 1 }, actions.refreshEnvironment, scheduler)
                1 -> TasksScreen({ page = 3 }, { captureHistory = false; page = 2 }, scheduler, state.settings.scheduler.serverUrl)
                2 -> Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        FilterChip(!captureHistory, { captureHistory = false }, label = { Text("实时采集") })
                        FilterChip(captureHistory, { captureHistory = true }, label = { Text("会话历史") })
                    }
                    Box(Modifier.weight(1f)) {
                        if (!captureHistory) CaptureScreen(state, actions, { appPicker = true }, { page = 3 }, actions.detail)
                        else HistoryScreen(state.sessions, actions.historySearch,
                            { id -> actions.selectSession(id); captureHistory = false }, actions.delete,
                            { id -> actions.export(ExportRequest("har", id)) },
                            { id -> actions.export(ExportRequest("answers", id)) })
                    }
                }
                3 -> SettingsScreen(state.settings, state.certificate, state.usedBytes, actions.settings,
                    { actions.certificate(); actions.export(ExportRequest("certificate")) },
                    { appPicker = true }, { bypass = true }, { guide = true }, actions.clearStorage,
                    canClearStorage = state.ready && !state.clearingStorage && state.capture.phase in setOf(CapturePhase.IDLE, CapturePhase.FAILED),
                    clearingStorage = state.clearingStorage, installedApps = state.apps)
            }
            if (!state.ready) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
    if (appPicker) AppPicker(state.apps, state.settings.capture.packages, onDismiss = { appPicker = false }) { packages ->
        actions.settings(state.settings.copy(capture = state.settings.capture.copy(packages = packages)))
        appPicker = false
    }
    if (bypass) BypassDomainsDialog(state.settings.capture.bypassDomains, onDismiss = { bypass = false }) { domains ->
        actions.settings(state.settings.copy(capture = state.settings.capture.copy(bypassDomains = domains)))
        bypass = false
    }
    if (guide) CertificateGuideDialog(onDismiss = { guide = false })
    if (setupGuide && state.ready && scheduling?.running != true && scheduling?.job == null) {
        RpaSetupGuide(state.apps, state.settings.scheduler.providers, onDismiss = ::closeSetupGuide,
            onConfigure = { closeSetupGuide(); page = 3 })
    }
}
