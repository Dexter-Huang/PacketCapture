package com.packetcapture.scheduler

import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.packetcapture.PacketCaptureApp
import com.packetcapture.core.CapturePhase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** 显式联调收尾：核对原 CA 和历史，不需要解锁或启动新的抓包；只恢复临时导出关闭。 */
class GeoDeviceCleanupTest {
    @Test fun verifyPreservedDeviceAndDisableTemporaryExport() = runBlocking {
        val input = InstrumentationRegistry.getArguments().getString("cleanupConfig")
        assumeTrue("仅显式提供本机核对参数时运行", input != null)
        val config = Gson().fromJson(String(android.util.Base64.decode(input, android.util.Base64.NO_WRAP), Charsets.UTF_8), JsonObject::class.java)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as PacketCaptureApp).container
        container.ready.await()
        assertEquals(CapturePhase.IDLE, container.controller.state.value.phase)
        val original = container.settings.settings.first()
        assertEquals(setOf("com.aliyun.tongyi"), original.capture.packages)
        assertTrue(original.capture.decryptHttps)
        assertFalse(original.capture.blockUdp443)
        assertFalse(original.capture.upstreamProxy.enabled)
        assertEquals(1, original.capture.tlsHostOverrides.size)
        assertEquals(config["fingerprint"].asString, container.certificates.ensureCertificate().fingerprint)
        assertEquals(config["sessionCount"].asInt, container.repository.sessions().first().size)
        val scheduler = container.scheduler.state.first { it.loaded }
        assertTrue(scheduler.bound)
        assertNull(scheduler.job)
        assertFalse(scheduler.running)
        container.settings.update(original.copy(adbExportEnabled = false))
        assertEquals(original.copy(adbExportEnabled = false), container.settings.settings.first())
    }
}
