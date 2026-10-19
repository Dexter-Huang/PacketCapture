package com.packetcapture.scheduler

import android.content.Intent
import android.net.VpnService
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.packetcapture.PacketCaptureApp
import com.packetcapture.core.CapturePhase
import com.packetcapture.ui.home.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** 仅显式授权的隔离真机联调运行；平台问题由外部操作一次，测试本身不发送或重发。 */
class GeoRealDeviceIntegrationTest {
    @Test fun captureOneExplicitlyAuthorizedNativeAnswer() = runBlocking {
        val input = InstrumentationRegistry.getArguments().getString("geoConfig")
        assumeTrue("仅显式提供隔离服务配置时运行", input != null)
        val config = Gson().fromJson(String(android.util.Base64.decode(input, android.util.Base64.NO_WRAP), Charsets.UTF_8), JsonObject::class.java)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as PacketCaptureApp).container
        container.ready.await()
        val original = container.settings.settings.first()
        assertEquals("先停止既有采集，禁止接管活动会话", CapturePhase.IDLE, container.controller.state.value.phase)
        assertEquals(setOf("com.aliyun.tongyi"), original.capture.packages)
        assertTrue(original.capture.decryptHttps)
        assertNull("先核实既有 VPN 授权", VpnService.prepare(context))
        val scheduler = container.scheduler
        withTimeout(10000) { scheduler.state.first { it.loaded } }
        val reuse = config["reuseBound"]?.asBoolean == true
        if (reuse) {
            assertTrue(scheduler.state.value.bound)
            assertEquals(config["deviceId"].asString, scheduler.state.value.deviceId)
            assertNull(scheduler.state.value.job)
        } else assertFalse("首次真机联调禁止替换既有绑定", scheduler.state.value.bound)
        val captureConfig = original.capture.copy(blockUdp443 = config["blockUdp443"]?.asBoolean ?: original.capture.blockUdp443)
        try {
            container.settings.update(original.copy(capture=captureConfig, adbExportEnabled=true, scheduler = original.scheduler.copy(
                serverUrl = config["serverUrl"].asString, deviceName = "千问监测手机", providers = setOf("qianwen"))))
            if (!reuse) scheduler.bind(config["serverUrl"].asString, config["deviceToken"].asString, config["certificateSha256"].asString)
            withTimeout(20000) { scheduler.state.first { it.bound } }
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            delay(1500)
            container.controller.start(captureConfig)
            withTimeout(20000) { container.controller.state.first { it.phase == CapturePhase.CAPTURING } }
            scheduler.start()
            val job = withTimeout(30000) { scheduler.state.first { it.job != null } }.job!!
            assertEquals(config["batchId"].asString, job.batchId)
            assertEquals("qianwen", job.provider)
            config["prompt"]?.let { assertEquals(it.asString,job.prompt) }
            scheduler.prepare()
            withTimeout(15000) { scheduler.state.first { it.prepared && !it.busy } }
            android.util.Log.i("GeoRealTest", "GEO_REAL_TASK_PREPARED")
            withTimeout(240000) {
                while (scheduler.state.value.job != null) {
                    scheduler.collect()
                    delay(2500)
                }
            }
            assertTrue(scheduler.state.value.message.contains("已确认"))
        } finally {
            scheduler.stop()
            container.controller.stop()
            withTimeoutOrNull(15000) { container.controller.state.first { it.phase == CapturePhase.IDLE } }
            container.settings.update(original)
        }
    }
}
