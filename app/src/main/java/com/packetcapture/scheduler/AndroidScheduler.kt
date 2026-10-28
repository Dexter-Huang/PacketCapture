package com.packetcapture.scheduler

import android.content.Context
import com.google.gson.Gson
import com.packetcapture.core.*
import com.packetcapture.llm.AnswerReader
import com.packetcapture.llm.AnswerSelector
import com.packetcapture.llm.PlatformCatalog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import com.packetcapture.execution.*

data class SchedulerUiState(
    val loaded: Boolean = false, val bound: Boolean = false, val running: Boolean = false,
    val deviceId: String = "", val serverUrl: String = "", val job: MobileJob? = null,
    val prepared: Boolean = false, val pendingUpload: Boolean = false,
    val busy: Boolean = false, val message: String = "尚未连接 GEO",
    val automaticDoubao: Boolean = false, val executionPhase: String? = null,
    val automaticProvider: String = "doubao",
    val automaticAllPlatforms: Boolean = false,
)

/** 同机串行调度：自动准备、按选定平台单次发题、等待完整回答和幂等上传。 */
class AndroidScheduler internal constructor(
    private val settings: SettingsRepository,
    private val capture: CaptureController, private val answers: AnswerReader,
    private val scope: CoroutineScope,
    private val journal: SchedulerStorage,
    private val request: (SchedulerRecord, String, String, Any) -> com.google.gson.JsonObject,
    private val installed: (String) -> Boolean,
    private val appVersion: String,
    private val executor: PhoneExecutor? = null,
) {
    constructor(context: Context, settings: SettingsRepository, capture: CaptureController,
        answers: AnswerReader, scope: CoroutineScope, journalName: String = "geo-scheduler.enc") : this(
        settings, capture, answers, scope, SchedulerJournal(context, journalName),
        { record, method, path, body -> GeoClient(record).request(method, path, body) },
        { pkg -> kotlin.runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess },
        com.packetcapture.BuildConfig.VERSION_NAME,
        MonitorAccessibilityService.executor(),
    )
    private val mutex = Mutex()
    private val gson = Gson()
    private var record = SchedulerRecord()
    private var polling: Job? = null
    private var automaticCaptureSession: String? = null
    private var automaticCaptureProvider: String? = null
    private val mutable = MutableStateFlow(SchedulerUiState())
    val state = mutable.asStateFlow()
    init { scope.launch { mutex.withLock {
        try { record = journal.read(); publish("已载入本地记录；点击继续接单恢复") }
        catch (_: Exception) { mutable.update { it.copy(message = "调度记录读取失败，请保留本机数据并检查") } }
    } } }
    private fun publish(message: String) { mutable.update { it.copy(loaded = true,
        bound = record.token.isNotBlank(), deviceId = record.deviceId, serverUrl = record.serverUrl,
        job = record.job, prepared = record.sessionId != null, pendingUpload = record.outbox != null,
        automaticDoubao = record.automaticDoubao, executionPhase = record.executionPhase,
        automaticProvider = record.automaticProvider ?: "doubao",
        automaticAllPlatforms = record.automaticAllPlatforms,
        message = message) } }
    private fun persist(value: SchedulerRecord) { journal.write(value); record = value }
    private fun send(method: String, path: String, body: Any = emptyMap<String, String>()) =
        request(record, method, path, body)
    private fun action(block: suspend () -> Unit) { scope.launch {
        mutex.withLock {
            mutable.update { it.copy(busy = true) }
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { publish(if (e is GeoHttpException) e.message.orEmpty() else "连接或本地操作失败，任务已保留，请检查后重试") }
            finally { mutable.update { it.copy(busy = false) } }
        }
    } }
    fun bind(serverUrl: String, token: String, certificateSha256: String = "") = action {
        require(state.value.loaded)
        require(record.job == null && record.outbox == null) { "当前任务尚未结束" }
        val candidate = SchedulerRecord(serverUrl = serverUrl.trim().trimEnd('/'), token = token.trim(),
            certificateSha256 = certificateSha256.trim().replace(":", "").lowercase())
        require(candidate.token.length in 32..256)
        val response = request(candidate, "POST", "/devices/heartbeat", emptyMap<String, String>())
        persist(candidate.copy(deviceId = response["deviceId"].asString))
        publish("设备已连接；请先启动目标应用的 HTTPS 采集")
    }
    fun start() = action {
        require(state.value.loaded)
        require(record.token.isNotBlank())
        if (polling?.isActive == true) return@action
        if (record.automaticDoubao && !record.automaticAllPlatforms && capture.state.value.phase in setOf(CapturePhase.IDLE, CapturePhase.FAILED)) {
            val config = settings.settings.first().capture
            val provider = record.automaticProvider ?: "doubao"
            require(PhonePlatforms.packageName(provider) in config.packages && config.decryptHttps) { "请先选择自动发题平台并启用 HTTPS 采集" }
            // 已授权 VPN 后可由手机启动；仅本次运行使用已验证的 TCP 回退，保留原设置。
            capture.start(config.copy(blockUdp443 = provider in setOf("doubao", "qianwen")))
        }
        if (record.automaticDoubao && record.automaticAllPlatforms && record.job != null &&
            record.sessionId != null && record.outbox == null && record.executionPhase != "manual") {
            // 恢复只追加采集会话，不移动旧边界，也不重置 send_started/sent。
            prepareAutomaticCapture(record.job!!.provider)
        }
        mutable.update { it.copy(running = true) }
        polling = scope.launch {
            var nextSyncAt = 0L
            try {
                while (isActive) {
                    mutex.withLock {
                        try {
                            if (System.currentTimeMillis() >= nextSyncAt) {
                                val configured = settings.settings.first().scheduler.pollIntervalSeconds.coerceIn(10, 300)
                                // 网络失败同样遵守重试间隔，本地回答检查不增加心跳频率。
                                nextSyncAt = System.currentTimeMillis() +
                                    (if (record.job != null) configured.coerceAtMost(60) else configured) * 1000L
                                tick()
                                if (record.job != null) nextSyncAt = minOf(nextSyncAt,
                                    System.currentTimeMillis() + configured.coerceAtMost(60) * 1000L)
                            }
                            if (record.job != null && record.job?.status != "leased") return@launch
                            if (record.job != null && record.sessionId != null && record.outbox == null) {
                                collectCurrent()
                                if (record.job != null && record.outbox == null) executeCurrent()
                            }
                        }
                        catch (e: CancellationException) { throw e }
                        catch (e: GeoHttpException) {
                            publish(e.message.orEmpty())
                            if (e.status in setOf(401, 403, 409, 422)) return@launch
                        }
                        catch (_: Exception) { publish("连接中断；本地任务保留，等待重试") }
                    }
                    // 每 2.5 秒检查本地回答；服务端心跳、空闲领取与失败重试使用配置间隔。
                    delay(2500L)
                }
            } finally { mutable.update { it.copy(running = false) } }
        }
    }
    fun stop() {
        polling?.cancel(); polling = null
        mutable.update { it.copy(running = false, message = "已暂停本机轮询；当前租约不会暂停，请及时继续") }
    }
    fun disconnect() = action {
        require(!state.value.running && record.job == null && record.outbox == null)
        persist(SchedulerRecord())
        publish("已清除本机连接；服务端凭据需由 GEO 管理者撤销")
    }
    fun automaticDoubao(enabled: Boolean) = action {
        require(!state.value.running && record.job == null && record.outbox == null)
        persist(record.copy(automaticDoubao = enabled))
        publish(if (enabled) "自动执行已开启；请在系统中启用 GEO 无障碍服务并保持手机解锁" else "已切换为人工单次发题")
    }
    fun automaticProvider(provider: String) = action {
        require(!state.value.running && record.job == null && record.outbox == null)
        require(provider in PhonePlatforms.packages)
        persist(record.copy(automaticProvider = provider))
        publish("已选择${PhonePlatforms.names.getValue(provider)}；先检查界面，再继续接单")
    }
    fun automaticAllPlatforms(enabled: Boolean) = action {
        require(!state.value.running && record.job == null && record.outbox == null)
        persist(record.copy(automaticAllPlatforms = enabled))
        publish(if (enabled) "已开启按计划平台自动轮转；各任务独立采集、单次发送并回传" else "已恢复仅领取选定平台")
    }
    fun checkDoubaoControls() = action {
        require(record.job == null && !state.value.running)
        val provider = record.automaticProvider ?: "doubao"
        val phone = requireNotNull(executor).forProvider(provider)
        if (!phone.available()) { publish("预检停止：GEO 无障碍服务未连接"); return@action }
        val text = "GEO界面预检，不发送"
        if (!phone.perform(PhoneAction.LAUNCH, text)) { publish("预检停止：目标应用未打开"); return@action }
        var screen = phone.observe(text)
        repeat(12) {
            if (!screen.newConversation) {
                if (screen.targetApp && screen.canCreateConversation) {
                    if (!phone.perform(PhoneAction.NEW_CONVERSATION, text)) { publish("预检停止：新对话入口未确认"); return@action }
                }
                delay(1000)
                screen = phone.observe(text)
            }
        }
        screen = phone.observe(text)
        if (screen.newConversation && screen.input == null && screen.canFocusInput) {
            phone.perform(PhoneAction.FOCUS_INPUT, text)
            delay(1000)
            screen = phone.observe(text)
        }
        if (!screen.newConversation || screen.input == null) { publish("预检停止：目标=${screen.targetApp} 空对话=${screen.newConversation} 新建=${screen.canCreateConversation} 输入框=${screen.input != null} ${screen.diagnostic}"); return@action }
        if (!phone.perform(PhoneAction.SET_TEXT, text)) { publish("预检停止：覆盖填写未确认"); return@action }; delay(1000)
        screen = phone.observe(text)
        val valid = screen.input == text && screen.canSend && !screen.promptVisible
        if (!phone.perform(PhoneAction.SET_TEXT, "")) { publish("预检停止：草稿清空未确认，未发送"); return@action }
        delay(500)
        if (phone.observe("").input != "") { publish("预检停止：草稿清空回读未确认，未发送"); return@action }
        publish(if (valid) "${PhonePlatforms.names.getValue(provider)}界面预检通过：可覆盖填写、全文核对及识别发送按钮；未发送问题"
            else "界面预检未通过：全文=${screen.input == text} 发送按钮=${screen.canSend} 已发问题=${screen.promptVisible}；输入已清空，未发送问题")
    }
    private suspend fun tick() {
        val job = record.job
        if (record.outbox != null && job != null) {
            val acknowledgment = send("PUT", "/tasks/${job.jobId}/result", record.outbox!!)
            check(acknowledgment["accepted"]?.asBoolean == true) { "服务端尚未确认结果" }
            persist(record.copy(job = null, sessionId = null, startedAfter = 0, outbox = null,
                claimRequestId = "", additionalSessionIds = null, executionPhase = null, executionStartedAt = 0))
            publish("GEO 已确认结果；等待下一次轮询")
            return
        }
        if (job != null) {
            val renewed = gson.fromJson(send("POST", "/tasks/${job.jobId}/heartbeat",
                mapOf("leaseToken" to job.leaseToken)), MobileJob::class.java)
            require(renewed.jobId == job.jobId) { "续租返回了其他任务" }
            persist(record.copy(job = renewed))
            if (renewed.status != "leased") {
                publish("任务已结束或租约失效，请核对服务端终态并归档")
                return
            }
            if (record.sessionId == null) prepareCurrent()
            return
        }
        val config = settings.settings.first()
        val current = capture.state.value
        val rotating = record.automaticDoubao && record.automaticAllPlatforms
        if ((!rotating && current.phase != CapturePhase.CAPTURING) || !config.capture.decryptHttps) {
            send("POST", "/devices/heartbeat")
            publish("设备在线；先启动 HTTPS 采集才会领取任务")
            return
        }
        val providers = config.scheduler.providers.filter { id ->
            val platform = PlatformCatalog.byId(id)
            (!record.automaticDoubao || (rotating || id == (record.automaticProvider ?: "doubao")) &&
                id in PhonePlatforms.packages && executor?.available() == true) &&
            platform != null && platform.support == "verified_sample" && platform.packageCandidates.any { pkg ->
                (rotating || pkg in config.capture.packages) && installed(pkg)
            }
        }
        if (providers.isEmpty()) {
            send("POST", "/devices/heartbeat")
            publish("设备在线；没有已选择、已安装且有完整回答样本的平台，请检查接入和采集配置")
            return
        }
        if (record.claimRequestId.isBlank()) persist(record.copy(claimRequestId = UUID.randomUUID().toString()))
        val response = send("POST", "/tasks/claim", mapOf("requestId" to record.claimRequestId, "providers" to providers))
        val element = response["job"]
        if (element == null || element.isJsonNull) {
            persist(record.copy(claimRequestId = "")); publish("设备在线，暂无适配任务")
        } else {
            val received = gson.fromJson(element, MobileJob::class.java)
            require(received.provider in providers) { "服务端返回了本机未就绪的平台" }
            persist(record.copy(job = received, sessionId = null, startedAfter = 0, additionalSessionIds = null,
                executionPhase = if (record.automaticDoubao && (rotating || received.provider == (record.automaticProvider ?: "doubao"))) "launch" else "manual",
                executionStartedAt = System.currentTimeMillis()))
            if (received.status == "leased") prepareCurrent()
            else publish("任务已结束或租约失效，请核对服务端终态并归档")
        }
    }
    fun prepare() = action {
        val job = requireNotNull(record.job)
        if (record.sessionId != null || record.outbox != null) return@action
        val renewed = gson.fromJson(send("POST", "/tasks/${job.jobId}/heartbeat",
            mapOf("leaseToken" to job.leaseToken)), MobileJob::class.java)
        require(renewed.jobId == job.jobId && renewed.status == "leased")
        persist(record.copy(job = renewed))
        prepareCurrent()
    }
    private suspend fun prepareCurrent() {
        val job = requireNotNull(record.job)
        if (record.sessionId != null || record.outbox != null) return
        require(job.status == "leased")
        if (record.automaticDoubao && record.automaticAllPlatforms && record.executionPhase == "launch") {
            prepareAutomaticCapture(job.provider)
        }
        val current = capture.state.value
        val config = settings.settings.first().capture
        if (current.phase != CapturePhase.CAPTURING || current.sessionId == null || !config.decryptHttps ||
            !captureMatches(job.provider, current.sessionId, config)) {
            publish("已领取；等待目标应用的 HTTPS 采集启动后自动准备，请暂勿发题")
            return
        }
        persist(record.copy(sessionId = current.sessionId, startedAfter = System.currentTimeMillis()))
        publish("采集已自动准备；请在目标应用新对话发送一次原文，完整回答将自动回传")
    }
    /** 只在新任务发送前切换 VPN；临时白名单及 UDP 策略不改用户设置，也不清理历史。 */
    private suspend fun prepareAutomaticCapture(provider: String) {
        val config = settings.settings.first().capture
        require(config.decryptHttps && provider in PhonePlatforms.packages)
        val current = capture.state.value
        if (current.phase == CapturePhase.CAPTURING && current.sessionId == automaticCaptureSession &&
            provider == automaticCaptureProvider) return
        if (current.phase !in setOf(CapturePhase.IDLE, CapturePhase.FAILED)) {
            capture.stop()
            withTimeout(20_000) { capture.state.first { it.phase in setOf(CapturePhase.IDLE, CapturePhase.FAILED) } }
        }
        automaticCaptureSession = null
        automaticCaptureProvider = null
        capture.start(config.copy(packages = setOf(PhonePlatforms.packageName(provider)),
            blockUdp443 = provider in setOf("doubao", "qianwen")))
        val ready = withTimeout(20_000) {
            capture.state.first { it.phase in setOf(CapturePhase.CAPTURING, CapturePhase.FAILED) }
        }
        check(ready.phase == CapturePhase.CAPTURING && ready.sessionId != null) { "目标平台采集未就绪" }
        automaticCaptureSession = ready.sessionId
        automaticCaptureProvider = provider
    }
    private fun captureMatches(provider: String, session: String?, config: CaptureConfig): Boolean =
        if (record.automaticDoubao && record.automaticAllPlatforms)
            session != null && session == automaticCaptureSession && provider == automaticCaptureProvider
        else PlatformCatalog.byId(provider)?.packageCandidates?.any { it in config.packages } == true
    fun collect() = action {
        if (record.outbox != null) { tick(); return@action }
        collectCurrent()
    }
    private suspend fun collectCurrent() {
        val job = requireNotNull(record.job)
        require(job.status == "leased")
        val session = requireNotNull(record.sessionId)
        val config = settings.settings.first().capture
        val current = capture.state.value
        val activeSession = current.sessionId
        if (current.phase == CapturePhase.CAPTURING && activeSession != null && activeSession != session &&
            activeSession !in record.additionalSessionIds.orEmpty() && config.decryptHttps &&
            captureMatches(job.provider, activeSession, config)) {
            // 覆盖更新/重启后仍读取原证据，新增会话沿用原时间边界，不把它当作重新发送授权。
            persist(record.copy(additionalSessionIds = record.additionalSessionIds.orEmpty() + activeSession))
        }
        val selector = AnswerSelector(provider = job.provider, prompt = job.prompt, startedAfter = record.startedAfter)
        val results = (listOf(session) + record.additionalSessionIds.orEmpty()).distinct()
            .flatMap { answers.read(it, selector).results }
        val selection = when (results.size) {
            0 -> "none"
            1 -> "unique_${results.single().status}"
            else -> "ambiguous"
        }
        if (selection != "unique_complete") {
            publish(when (selection) {
                "none" -> "采集已自动准备；等待本次问题的回答，请仅发送一次原文"
                "unique_incomplete" -> "正在等待回答完整结束，完成后自动回传"
                else -> "采集状态：$selection；记录已保留，请检查采集，勿重复提问"
            })
            return
        }
        val answer = results.single()
        val body = gson.toJson(mapOf("leaseToken" to job.leaseToken, "provider" to job.provider,
            "prompt" to job.prompt, "status" to "complete", "answer" to answer.answer,
            "reasoning" to answer.reasoning, "searchSources" to answer.searchSources,
            "sessionId" to answer.sessionId, "exchangeId" to answer.exchangeId,
            "decoder" to answer.decoder.orEmpty(), "packageName" to answer.packageName.orEmpty(),
            "appVersion" to appVersion,
            "referenceCaptureStatus" to answer.referenceCaptureStatus))
        persist(record.copy(outbox = body)); publish("完整结果已保存，正在回传")
        tick()
    }
    fun finishFailed() = action {
        failCurrent()
    }
    private suspend fun failCurrent() {
        val job = requireNotNull(record.job)
        require(record.outbox == null)
        val body = gson.toJson(mapOf("leaseToken" to job.leaseToken, "provider" to job.provider,
            "prompt" to job.prompt, "status" to "failed", "sessionId" to (record.sessionId ?: "not-prepared"),
            "exchangeId" to "not-delivered"))
        persist(record.copy(outbox = body)); tick()
    }
    private suspend fun executeCurrent() {
        val job = record.job ?: return
        val phase = record.executionPhase ?: "manual" // 旧任务绝不因更新获得发题授权。
        if (phase == "manual" || !record.automaticDoubao ||
            (!record.automaticAllPlatforms && job.provider != (record.automaticProvider ?: "doubao"))) return
        if (System.currentTimeMillis() - record.executionStartedAt > 240_000L) {
            publish("自动执行超时，保存失败结果；不会重复发送")
            failCurrent(); return
        }
        if (phase in setOf("send_started", "sent", "blocked")) {
            publish(if (phase == "sent") "已执行单次发送操作，等待本次完整回答；不会重复发送"
                else "发送结果待确认；只检查已有采集，不自动补发")
            return
        }
        val current = capture.state.value
        if (current.phase != CapturePhase.CAPTURING || !settings.settings.first().capture.decryptHttps) return
        val phone = executor?.forProvider(job.provider) ?: return
        if (!phone.available()) { publish("等待 GEO 无障碍服务连接；不会发送问题"); return }
        val screen = phone.observe(job.prompt)
        val action = TextConversationExecution.decide(phase, screen, job.prompt)
        if (action == PhoneAction.WAIT) { publish("等待目标应用可操作页面及手机解锁；不会重复发送"); return }
        val next = TextConversationExecution.nextPhase(action, job.provider) ?: return
        // 所有 UI 操作前写盘；send_started 即使崩溃/点击返回 false 也永不再点。
        persist(record.copy(executionPhase = next))
        val accepted = phone.perform(action, job.prompt)
        persist(record.copy(executionPhase = if (!accepted) "blocked" else if (action == PhoneAction.SEND) "sent" else next))
        publish(if (!accepted && action == PhoneAction.LAUNCH) "目标应用未进入前台；请检查系统后台启动权限。记录已保留，不自动补发"
            else if (!accepted) "界面操作未确认；保留记录，不自动重复操作"
            else if (action == PhoneAction.SEND) "已执行单次发送操作，等待本次完整回答；不会重复发送"
            else "正在自动新建对话并核对问题全文")
    }
    fun reviewTerminal() = action {
        val job = requireNotNull(record.job)
        require(record.claimRequestId.isNotBlank())
        val response = send("POST", "/tasks/claim", mapOf(
            "requestId" to record.claimRequestId, "providers" to listOf(job.provider)))
        val confirmed = gson.fromJson(response["job"], MobileJob::class.java)
        require(confirmed.jobId == job.jobId && confirmed.status in setOf("completed", "failed", "expired"))
        // 人工确认后仍保留本地密文证据；不把未确认的 outbox 静默删除。
        journal.archive()
        persist(record.copy(job = null, outbox = null, sessionId = null, startedAfter = 0,
            claimRequestId = "", additionalSessionIds = null, executionPhase = null, executionStartedAt = 0))
        publish("服务端任务已结束，本地记录已加密归档；可继续接单")
    }
}
