package com.packetcapture.scheduler

import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.packetcapture.PacketCaptureApp
import com.packetcapture.core.CapturePhase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** 显式隔离联调：只读取重启前已关联的回答，不领取新任务、不启动采集、不发送问题。 */
class GeoResumeSavedResultTest {
    @Test fun uploadExistingPreparedAnswerAfterDecoderUpdate() = runBlocking {
        val input = InstrumentationRegistry.getArguments().getString("geoResumeConfig")
        assumeTrue("仅显式提供隔离任务配置时运行", input != null)
        val config = Gson().fromJson(String(android.util.Base64.decode(input, android.util.Base64.NO_WRAP), Charsets.UTF_8), JsonObject::class.java)
        val container = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PacketCaptureApp).container
        container.ready.await()
        assertEquals(CapturePhase.IDLE, container.controller.state.value.phase)
        val scheduler = container.scheduler
        val saved = withTimeout(10000) { scheduler.state.first { it.loaded } }
        assertTrue(saved.bound)
        assertFalse(saved.running)
        assertTrue(saved.prepared)
        assertEquals(config["deviceId"].asString, saved.deviceId)
        assertEquals(config["batchId"].asString, saved.job!!.batchId)
        assertEquals(config["prompt"].asString, saved.job.prompt)
        assertFalse(saved.pendingUpload)
        scheduler.collect()
        val finished = withTimeout(30000) { scheduler.state.first { !it.busy && it.job == null } }
        assertTrue(finished.message.contains("已确认"))
        assertFalse(finished.pendingUpload)
        assertFalse(finished.running)
        // 仅便于紧随其后的本机原历史对照，专用收尾测试立即关闭。
        val settings = container.settings.settings.first()
        container.settings.update(settings.copy(adbExportEnabled = true))
    }
}
