package com.packetcapture.scheduler

import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.packetcapture.PacketCaptureApp
import com.packetcapture.core.*
import com.packetcapture.llm.AnswerReader
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** 真机 HTTPS 与持久化联调只用合成问答；不点击平台、不修改真实抓包或登录。 */
class GeoSchedulerIntegrationTest {
    @Test fun syntheticAnswerTravelsThroughSchedulerAndSurvivesReload() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val input = InstrumentationRegistry.getArguments().getString("geoConfig")
        assumeTrue("仅显式提供隔离服务配置时运行", input != null)
        val config = Gson().fromJson(String(android.util.Base64.decode(input, android.util.Base64.NO_WRAP), Charsets.UTF_8), JsonObject::class.java)
        val context = instrumentation.targetContext
        val container = (context.applicationContext as PacketCaptureApp).container
        val name = "geo-test-${UUID.randomUUID()}.enc"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val fakeSettings = object : SettingsRepository {
            override val settings = MutableStateFlow(AppSettings(
                capture = CaptureConfig(packages = setOf("com.tencent.hunyuan.app.chat")),
                scheduler = SchedulerSettings(providers = setOf("yuanbao"))))
            override suspend fun update(settings: AppSettings) { this.settings.value = settings }
        }
        val fakeCapture = object : CaptureController {
            override val state = MutableStateFlow(CaptureState(CapturePhase.CAPTURING, "synthetic-session"))
            override fun start(config: CaptureConfig) = error("测试不启动 VPN")
            override fun stop() = error("测试不停止 VPN")
            override suspend fun clearStorage() = error("测试不清理抓包")
        }
        var prompt = ""
        val fakeRepository = object : CaptureRepository by container.repository {
            override suspend fun session(id: String) = CaptureSession(id=id, title="合成数据", packages=listOf("com.tencent.hunyuan.app.chat"))
            override suspend fun allConnections(sessionId: String) = emptyList<ConnectionRecord>()
            override suspend fun allExchanges(sessionId: String) = listOf(HttpExchange(
                id="synthetic-exchange", sessionId=sessionId, connectionId="synthetic-connection",
                packageName="com.tencent.hunyuan.app.chat", method="POST", protocol="HTTP/1.1",
                url="https://api.yuanbao.tencent.com/api/chat/test", startedAt=System.currentTimeMillis(),
                endedAt=System.currentTimeMillis(), status=200, completion=Completion.COMPLETE,
                responseHeaders=listOf(Header("Content-Type","text/event-stream")),
                requestBody=BodyRef("synthetic-request",savedBytes=100), responseBody=BodyRef("synthetic-response",savedBytes=300)))
        }
        val fakeBodies = object : BodyStore by container.bodies {
            override suspend fun preview(ref: BodyRef?, headers: List<Header>) = BodyPreview(
                if (ref?.key == "synthetic-request") Gson().toJson(mapOf("prompt" to prompt))
                else "data: {\"type\":\"text\",\"msg\":\"示例品牌的安卓联调合成回答\"}\n\n" +
                    "data: {\"type\":\"meta\",\"stopReason\":\"stop\",\"modelErrorCode\":0}\n\ndata: [DONE]\n\n", false, false)
        }
        try {
            val scheduler = AndroidScheduler(context, fakeSettings, fakeCapture, AnswerReader(fakeRepository, fakeBodies), scope, name)
            withTimeout(10000) { scheduler.state.first { it.loaded } }
            scheduler.bind(config["serverUrl"].asString, config["deviceToken"].asString, config["certificateSha256"].asString)
            withTimeout(30000) { scheduler.state.first { it.bound } }
            scheduler.start()
            val received = withTimeout(30000) { scheduler.state.first { it.job != null } }.job!!
            assertEquals("yuanbao", received.provider)
            prompt = received.prompt
            withTimeout(30000) { scheduler.state.first { it.prepared && !it.busy } }
            withTimeout(30000) { scheduler.state.first { it.job == null && !it.busy && it.message.contains("已确认") } }
            val restored = SchedulerJournal(context, name).read()
            assertNull(restored.job)
            assertNull(restored.outbox)
            assertFalse(String(File(context.noBackupFilesDir, name).readBytes(), Charsets.ISO_8859_1).contains(config["deviceToken"].asString))
            val wrongPin = restored.copy(certificateSha256="0".repeat(64))
            assertTrue(runCatching { GeoClient(wrongPin).request("POST", "/devices/heartbeat") }.isFailure)
        } finally { scope.cancel(); File(context.noBackupFilesDir, name).delete() }
    }
}
