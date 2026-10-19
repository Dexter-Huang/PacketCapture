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

data class SchedulerUiState(
    val loaded: Boolean = false, val bound: Boolean = false, val running: Boolean = false,
    val deviceId: String = "", val serverUrl: String = "", val job: MobileJob? = null,
    val prepared: Boolean = false, val pendingUpload: Boolean = false,
    val busy: Boolean = false, val message: String = "尚未连接 GEO",
)

/** 同机串行调度。执行方式明确为人工发送，代码只领取、续租、关联采集并幂等上传。 */
class AndroidScheduler(
    private val context: Context, private val settings: SettingsRepository,
    private val capture: CaptureController, private val answers: AnswerReader,
    private val scope: CoroutineScope,
    journalName: String = "geo-scheduler.enc",
) {
    private val journal = SchedulerJournal(context, journalName)
    private val mutex = Mutex()
    private val gson = Gson()
    private var record = SchedulerRecord()
    private var polling: Job? = null
    private val mutable = MutableStateFlow(SchedulerUiState())
    val state = mutable.asStateFlow()
    init { scope.launch { mutex.withLock {
        try { record = journal.read(); publish("已载入本地记录；点击继续接单恢复") }
        catch (_: Exception) { mutable.update { it.copy(message = "调度记录读取失败，请保留本机数据并检查") } }
    } } }
    private fun publish(message: String) { mutable.update { it.copy(loaded = true,
        bound = record.token.isNotBlank(), deviceId = record.deviceId, serverUrl = record.serverUrl,
        job = record.job, prepared = record.sessionId != null, pendingUpload = record.outbox != null,
        message = message) } }
    private fun persist(value: SchedulerRecord) { journal.write(value); record = value }
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
        val response = GeoClient(candidate).request("POST", "/devices/heartbeat")
        persist(candidate.copy(deviceId = response["deviceId"].asString))
        publish("设备已连接；请先启动目标应用的 HTTPS 采集")
    }
    fun start() = action {
        require(state.value.loaded)
        require(record.token.isNotBlank())
        if (polling?.isActive == true) return@action
        mutable.update { it.copy(running = true) }
        polling = scope.launch {
            try {
                while (isActive) {
                    mutex.withLock {
                        try { tick() }
                        catch (e: CancellationException) { throw e }
                        catch (e: GeoHttpException) {
                            publish(e.message.orEmpty())
                            if (e.status in setOf(401, 403, 409, 422)) return@launch
                        }
                        catch (_: Exception) { publish("连接中断；本地任务保留，等待重试") }
                    }
                    val configured = settings.settings.first().scheduler.pollIntervalSeconds.coerceIn(10, 300)
                    delay((if (state.value.job != null) configured.coerceAtMost(60) else configured) * 1000L)
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
    private suspend fun tick() {
        val client = GeoClient(record)
        val job = record.job
        if (record.outbox != null && job != null) {
            val acknowledgment = client.request("PUT", "/tasks/${job.jobId}/result", record.outbox!!)
            check(acknowledgment["accepted"]?.asBoolean == true) { "服务端尚未确认结果" }
            persist(record.copy(job = null, sessionId = null, startedAfter = 0, outbox = null, claimRequestId = ""))
            publish("GEO 已确认结果；等待下一次轮询")
            return
        }
        if (job != null) {
            val renewed = gson.fromJson(client.request("POST", "/tasks/${job.jobId}/heartbeat",
                mapOf("leaseToken" to job.leaseToken)), MobileJob::class.java)
            persist(record.copy(job = renewed)); publish(if (record.sessionId == null) "已领取，等待准备采集" else "租约有效，等待人工提问后采集完整回答")
            return
        }
        val config = settings.settings.first()
        val current = capture.state.value
        if (current.phase != CapturePhase.CAPTURING || !config.capture.decryptHttps) {
            client.request("POST", "/devices/heartbeat")
            publish("设备在线；先启动 HTTPS 采集才会领取任务")
            return
        }
        val providers = config.scheduler.providers.filter { id ->
            val platform = PlatformCatalog.byId(id)
            platform != null && platform.support == "verified_sample" && platform.packageCandidates.any { pkg ->
                pkg in config.capture.packages && runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
            }
        }
        if (providers.isEmpty()) { publish("没有已选择、已安装且有完整回答样本的平台；请检查接入和采集配置"); return }
        if (record.claimRequestId.isBlank()) persist(record.copy(claimRequestId = UUID.randomUUID().toString()))
        val response = client.request("POST", "/tasks/claim", mapOf("requestId" to record.claimRequestId, "providers" to providers))
        val element = response["job"]
        if (element == null || element.isJsonNull) {
            persist(record.copy(claimRequestId = "")); publish("设备在线，暂无适配任务")
        } else {
            val received = gson.fromJson(element, MobileJob::class.java)
            persist(record.copy(job = received, sessionId = null, startedAfter = 0))
            publish(if (received.status == "leased") "已领取；先准备采集，再由你发送一次问题" else "任务已结束或租约失效，请人工核对")
        }
    }
    fun prepare() = action {
        val job = requireNotNull(record.job)
        require(record.sessionId == null && record.outbox == null)
        val current = capture.state.value
        require(current.phase == CapturePhase.CAPTURING && current.sessionId != null)
        val config = settings.settings.first().capture
        require(PlatformCatalog.byId(job.provider)!!.packageCandidates.any { it in config.packages })
        GeoClient(record).request("POST", "/tasks/${job.jobId}/heartbeat", mapOf("leaseToken" to job.leaseToken))
        persist(record.copy(sessionId = current.sessionId, startedAfter = System.currentTimeMillis()))
        publish("已保存本次采集边界；请在目标应用新对话中发送一次任务原文")
    }
    fun collect() = action {
        val job = requireNotNull(record.job)
        if (record.outbox != null) { tick(); return@action }
        val session = requireNotNull(record.sessionId)
        val batch = answers.read(session, AnswerSelector(provider = job.provider, prompt = job.prompt, startedAfter = record.startedAfter))
        if (batch.selectionStatus != "unique_complete") {
            publish("采集状态：${batch.selectionStatus}；仅唯一完整回答可回传，请勿重复提问")
            return@action
        }
        val answer = batch.results.single()
        val body = gson.toJson(mapOf("leaseToken" to job.leaseToken, "provider" to job.provider,
            "prompt" to job.prompt, "status" to "complete", "answer" to answer.answer,
            "reasoning" to answer.reasoning, "searchSources" to answer.searchSources,
            "sessionId" to session, "exchangeId" to answer.exchangeId,
            "decoder" to answer.decoder.orEmpty(), "packageName" to answer.packageName.orEmpty(),
            "appVersion" to com.packetcapture.BuildConfig.VERSION_NAME,
            "referenceCaptureStatus" to answer.referenceCaptureStatus))
        persist(record.copy(outbox = body)); publish("完整结果已保存，正在回传")
        tick()
    }
    fun finishFailed() = action {
        val job = requireNotNull(record.job)
        require(record.outbox == null)
        val body = gson.toJson(mapOf("leaseToken" to job.leaseToken, "provider" to job.provider,
            "prompt" to job.prompt, "status" to "failed", "sessionId" to (record.sessionId ?: "not-prepared"),
            "exchangeId" to "not-delivered"))
        persist(record.copy(outbox = body)); tick()
    }
    fun reviewTerminal() = action {
        val job = requireNotNull(record.job)
        require(record.claimRequestId.isNotBlank())
        val response = GeoClient(record).request("POST", "/tasks/claim", mapOf(
            "requestId" to record.claimRequestId, "providers" to listOf(job.provider)))
        val confirmed = gson.fromJson(response["job"], MobileJob::class.java)
        require(confirmed.jobId == job.jobId && confirmed.status in setOf("completed", "failed", "expired"))
        // 人工确认后仍保留本地密文证据；不把未确认的 outbox 静默删除。
        journal.archive()
        persist(record.copy(job = null, outbox = null, sessionId = null, startedAfter = 0, claimRequestId = ""))
        publish("服务端任务已结束，本地记录已加密归档；可继续接单")
    }
}
