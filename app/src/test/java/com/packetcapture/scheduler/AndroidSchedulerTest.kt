package com.packetcapture.scheduler

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.packetcapture.core.*
import com.packetcapture.llm.AnswerReader
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import com.packetcapture.execution.*

/** 使用真实调度循环与 AnswerReader，验证自动化不会放宽关联及持久化规则。 */
class AndroidSchedulerTest {
    @Test fun automaticSendPersistsBeforeClickAndUncertainClickNeverRepeatsAfterRestart() =
        verifyUncertainSend("doubao", "com.larus.nova")

    @Test fun yuanbaoSendUsesSelectedAdapterAndDoesNotRepeatAfterRestart() =
        verifyUncertainSend("yuanbao", "com.tencent.hunyuan.app.chat")

    private fun verifyUncertainSend(provider: String, packageName: String) = runBlocking {
        val f = Fixture()
        try {
            f.job = f.job.copy(provider = provider)
            f.settings.update(AppSettings(capture = CaptureConfig(packages = setOf(packageName)),
                scheduler = SchedulerSettings(providers = setOf(provider, "deepseek"))))
            f.store.value = f.store.value.copy(automaticDoubao = true, automaticProvider = provider)
            f.phone = object : PhoneExecutor {
                override fun forProvider(provider: String): PhoneExecutor {
                    assertEquals(f.job.provider, provider)
                    return this
                }
                override fun available() = true
                override suspend fun observe(prompt: String) = PhoneScreen(targetApp = true, newConversation = true,
                    input = if (f.store.value.executionPhase == "verifying") prompt else "", canSend = true)
                override suspend fun perform(action: PhoneAction, prompt: String): Boolean {
                    f.phoneActions += action
                    if (action == PhoneAction.SEND) {
                        assertEquals("send_started", f.store.value.executionPhase)
                        return false // click delivery unknown, must never be retried
                    }
                    return true
                }
            }
            f.start()
            withTimeout(18000) { f.scheduler.state.first { it.executionPhase == "blocked" } }
            f.scope.cancel()
            val resumed = f.scheduler(CoroutineScope(SupervisorJob() + Dispatchers.Default).also { f.extraScope = it })
            withTimeout(5000) { resumed.state.first { it.loaded } }; resumed.start()
            withTimeout(5000) { resumed.state.first { it.message.contains("待确认") } }
            assertEquals(1, f.phoneActions.count { it == PhoneAction.SEND })
            assertEquals(listOf(PhoneAction.LAUNCH, PhoneAction.SET_TEXT, PhoneAction.SEND), f.phoneActions.toList())
        } finally { f.close() }
    }

    @Test fun claimPreparesAutomaticallyAndCompleteAnswerUploadsWithoutButtons() = runBlocking {
        val f = Fixture()
        try {
            f.start()
            f.await { it.prepared }
            val boundary = f.store.value.startedAfter
            f.records = listOf(f.exchange(start = boundary - 1), f.exchange(start = boundary + 1))
            f.await { it.job == null && it.message.contains("已确认") }
            assertEquals(1, f.uploads.size)
            assertNull(f.store.value.outbox)
            assertEquals("s", f.uploads.single()["sessionId"].asString)
            assertEquals("合成回答", f.uploads.single()["answer"].asString)
            assertEquals(listOf(boundary), f.savedBoundaries.distinct())
        } finally { f.close() }
    }

    @Test fun incompleteAnswerWaitsAndOnlyUploadsAfterTransportCompletes() = runBlocking {
        val f = Fixture()
        try {
            f.start(); f.await { it.prepared }
            val boundary = f.store.value.startedAfter
            f.records = listOf(f.exchange(start = boundary + 1).copy(completion = Completion.ACTIVE, endedAt = null))
            f.await { it.message.contains("等待回答完整结束") }
            assertTrue(f.uploads.isEmpty())
            f.records = listOf(f.exchange(start = boundary + 1))
            f.await { it.job == null }
            assertEquals(1, f.uploads.size)
        } finally { f.close() }
    }

    @Test fun ambiguousOrWrongPromptNeverUploads() = runBlocking {
        val f = Fixture()
        try {
            f.start(); f.await { it.prepared }
            f.records = listOf(f.exchange(), f.exchange().copy(id = "second"))
            f.await { it.message.contains("ambiguous") }
            assertTrue(f.uploads.isEmpty())
            f.prompt = "问题 "
            f.records = listOf(f.exchange())
            f.await { it.message.contains("等待本次问题") }
            assertTrue(f.uploads.isEmpty())
        } finally { f.close() }
    }

    @Test fun failedAndUnsupportedAnswersKeepEvidenceWithoutUpload() = runBlocking {
        val f = Fixture()
        try {
            f.start(); f.await { it.prepared }
            f.records = listOf(f.exchange().copy(completion = Completion.FAILED, error = "synthetic failure"))
            f.await { it.message.contains("unique_failed") }
            assertTrue(f.uploads.isEmpty())
            f.unsupported = true
            f.records = listOf(f.exchange())
            f.await { it.message.contains("unique_unsupported") }
            assertNotNull(f.store.value.job)
            assertTrue(f.uploads.isEmpty())
        } finally { f.close() }
    }

    @Test fun uncertainUploadIsPersistedAndReloadRetriesExactlyTheSameJson() = runBlocking {
        val f = Fixture()
        try {
            f.failUpload = true
            f.start(); f.await { it.prepared }
            f.records = listOf(f.exchange())
            f.await { it.pendingUpload && it.message.contains("连接中断") }
            val saved = f.store.value.outbox!!
            assertEquals(1, f.uploads.size)
            f.scope.cancel()
            f.failUpload = false
            val resumed = f.scheduler(CoroutineScope(SupervisorJob() + Dispatchers.Default).also { f.extraScope = it })
            withTimeout(5000) { resumed.state.first { it.loaded } }
            resumed.start()
            withTimeout(5000) { resumed.state.first { it.job == null && it.message.contains("已确认") } }
            assertEquals(saved, f.serializedUploads[1])
            assertEquals(1, f.claimCount)
        } finally { f.close() }
    }

    @Test fun restartKeepsOriginalBoundaryAndReadsOriginalSessionEvenWhenCaptureStopped() = runBlocking {
        val f = Fixture()
        try {
            f.store.value = f.store.value.copy(job = f.job, sessionId = "s", startedAfter = 100, claimRequestId = "same-claim")
            f.captureState.value = CaptureState(CapturePhase.IDLE)
            f.records = listOf(f.exchange(start = 101))
            f.start()
            f.await { it.job == null && it.message.contains("已确认") }
            assertEquals(0, f.claimCount)
            assertEquals("s", f.uploads.single()["sessionId"].asString)
            assertEquals(listOf(100L), f.savedBoundaries.distinct())
        } finally { f.close() }
    }

    @Test fun stoppedCaptureOnlyHeartbeatsAndNeverClaims() = runBlocking {
        val f = Fixture()
        try {
            f.captureState.value = CaptureState(CapturePhase.IDLE)
            f.start(); f.await { it.message.contains("先启动 HTTPS") }
            assertEquals(0, f.claimCount)
            assertFalse(f.scheduler.state.value.prepared)
            assertTrue(f.paths.contains("/devices/heartbeat"))
        } finally { f.close() }
    }

    @Test fun restartTracksNewCaptureWithoutMovingOriginalBoundaryAndDetectsCrossSessionDuplicates() = runBlocking {
        val f = Fixture()
        try {
            f.store.value = f.store.value.copy(job = f.job, sessionId = "s", startedAfter = 100, claimRequestId = "same-claim")
            f.captureState.value = CaptureState(CapturePhase.CAPTURING, "resumed")
            f.records = listOf(f.exchange(start = 101), f.exchange(start = 102).copy(sessionId = "resumed", id = "second"))
            f.start()
            f.await { it.message.contains("ambiguous") }
            assertEquals("s", f.store.value.sessionId)
            assertEquals(100L, f.store.value.startedAfter)
            assertEquals(listOf("resumed"), f.store.value.additionalSessionIds)
            assertTrue(f.uploads.isEmpty())
            f.records = f.records.filter { it.sessionId == "resumed" }
            f.await { it.job == null }
            assertEquals("resumed", f.uploads.single()["sessionId"].asString)
            assertNull(f.store.value.additionalSessionIds)
            assertEquals(0, f.claimCount)
        } finally { f.close() }
    }

    private class MemoryStore : SchedulerStorage {
        @Volatile var value = SchedulerRecord(token = "synthetic-device-token")
        override fun read() = value
        override fun write(record: SchedulerRecord) { value = record }
        override fun archive() = Unit
    }

    private class Fixture {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var extraScope: CoroutineScope? = null
        val store = MemoryStore()
        var job = MobileJob("job", "batch", "task", "yuanbao", "问题", "leased", "synthetic-lease", "2099-01-01T00:00:00Z")
        var phone: PhoneExecutor? = null
        val phoneActions = CopyOnWriteArrayList<PhoneAction>()
        val gson = Gson()
        @Volatile var records = emptyList<HttpExchange>()
        @Volatile var prompt = "问题"
        @Volatile var failUpload = false
        @Volatile var unsupported = false
        @Volatile var claimCount = 0
        val uploads = CopyOnWriteArrayList<JsonObject>()
        val serializedUploads = CopyOnWriteArrayList<String>()
        val savedBoundaries = CopyOnWriteArrayList<Long>()
        val paths = CopyOnWriteArrayList<String>()
        val settings = object : SettingsRepository {
            override val settings = MutableStateFlow(AppSettings(capture = CaptureConfig(
                packages = setOf("com.tencent.hunyuan.app.chat"), decryptHttps = true),
                scheduler = SchedulerSettings(providers = setOf("yuanbao"), pollIntervalSeconds = 10)))
            override suspend fun update(settings: AppSettings) { this.settings.value = settings }
        }
        val captureState = MutableStateFlow(CaptureState(CapturePhase.CAPTURING, "s"))
        val capture = object : CaptureController {
            override val state = captureState
            override fun start(config: CaptureConfig) = error("测试不操作 VPN")
            override fun stop() = error("测试不操作 VPN")
            override suspend fun clearStorage() = error("测试不清除数据")
        }
        private val repo = object : CaptureRepository {
            override suspend fun session(id: String): CaptureSession {
                check(id in setOf("s", "resumed"))
                return CaptureSession(id = id, title = "合成", packages = listOf("com.tencent.hunyuan.app.chat"))
            }
            override suspend fun allConnections(sessionId: String) = emptyList<ConnectionRecord>()
            override suspend fun allExchanges(sessionId: String): List<HttpExchange> {
                savedBoundaries += store.value.startedAfter
                return records.filter { it.sessionId == sessionId }
            }
            override fun sessions(query: String): Flow<List<CaptureSession>> = error("unused")
            override fun exchanges(sessionId: String?, filter: ExchangeFilter, limit: Int): Flow<List<HttpExchange>> = error("unused")
            override fun connections(sessionId: String?): Flow<List<ConnectionRecord>> = error("unused")
            override fun exchange(id: String): Flow<HttpExchange?> = error("unused")
            override suspend fun connection(id: String): ConnectionRecord? = null
            override suspend fun saveSession(session: CaptureSession) = error("unused")
            override suspend fun saveConnection(connection: ConnectionRecord) = error("unused")
            override suspend fun saveExchange(exchange: HttpExchange) = error("unused")
            override suspend fun deleteSession(id: String) = error("unused")
            override suspend fun clearStorage() = error("unused")
            override suspend fun recoverInterrupted() = error("unused")
        }
        private val bodies = object : BodyStore {
            override suspend fun preview(ref: BodyRef?, headers: List<Header>) = BodyPreview(
                if (ref?.key == "request") gson.toJson(mapOf("prompt" to prompt)) else
                    (if (unsupported) "data: {\"type\":\"new-schema\"}\n\n" else "") +
                    "data: {\"type\":\"text\",\"msg\":\"合成回答\"}\n\n" +
                    "data: {\"type\":\"meta\",\"stopReason\":\"stop\",\"modelErrorCode\":0}\n\ndata: [DONE]\n\n", false, false)
            override fun open(sessionId: String, exchangeId: String, part: BodyPart, limit: Long, totalLimit: Long): BodySink = error("unused")
            override suspend fun read(ref: BodyRef, maxBytes: Int): ByteArray = error("unused")
            override suspend fun deleteSession(sessionId: String) = error("unused")
            override suspend fun clearAll() = error("unused")
            override suspend fun usedBytes(): Long = error("unused")
            override suspend fun recoverOrphans(liveSessionIds: Set<String>) = error("unused")
        }
        fun scheduler(scope: CoroutineScope) = AndroidScheduler(settings, capture, AnswerReader(repo, bodies), scope, store,
            { _, method, path, body ->
                paths += path
                when {
                    path == "/tasks/claim" -> { claimCount++; gson.toJsonTree(mapOf("job" to job)).asJsonObject }
                    path == "/devices/heartbeat" -> gson.toJsonTree(mapOf("deviceId" to "device")).asJsonObject
                    method == "PUT" -> {
                        check(store.value.outbox == body) { "上传前必须持久化完全相同的 JSON" }
                        serializedUploads += body as String
                        uploads += gson.fromJson(body, JsonObject::class.java)
                        if (failUpload) throw IOException("synthetic lost response")
                        gson.fromJson("{\"accepted\":true}", JsonObject::class.java)
                    }
                    else -> gson.toJsonTree(job).asJsonObject
                }
            }, { true }, "synthetic-test", phone)
        val scheduler by lazy { scheduler(scope) }
        suspend fun start() {
            withTimeout(5000) { scheduler.state.first { it.loaded } }
            scheduler.start()
        }
        suspend fun await(predicate: (SchedulerUiState) -> Boolean) = withTimeout(8000) { scheduler.state.first(predicate) }
        fun exchange(start: Long = System.currentTimeMillis() + 1) = HttpExchange(id = "exchange", sessionId = "s", connectionId = "c",
            packageName = "com.tencent.hunyuan.app.chat", method = "POST", url = "https://api.yuanbao.tencent.com/api/chat/test",
            protocol = "HTTP/1.1", startedAt = start, endedAt = start + 1, status = 200, completion = Completion.COMPLETE,
            responseHeaders = listOf(Header("Content-Type", "text/event-stream")),
            requestBody = BodyRef("request", savedBytes = 100), responseBody = BodyRef("response", savedBytes = 500))
        fun close() { scope.cancel(); extraScope?.cancel() }
    }
}
