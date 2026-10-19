package com.packetcapture.core

import org.junit.Assert.*
import org.junit.Test

class SchedulerSettingsTest {
    private val valid = SchedulerSettings("https://geo.example.com/api", "监测手机 01", 15, setOf("yuanbao"))

    @Test fun `接入地址拒绝携带凭据和不安全协议`() {
        listOf("http://geo.example.com", "https://user:secret@geo.example.com",
            "https://geo.example.com?token=secret", "https://geo.example.com#secret",
            "https://", "https://geo.example.com:0", "https://geo.example.com:65536",
            "https://geo.example.com/with space").forEach {
            assertNotNull(it, valid.copy(serverUrl = it).validationError())
        }
        assertNull(valid.validationError())
        assertNull(valid.copy(serverUrl = "https://geo.example.com:8443/geo/api").validationError())
    }

    @Test fun `缺少设备信息和越界轮询频率不能保存`() {
        assertNotNull(SchedulerSettings().validationError())
        assertNotNull(valid.copy(deviceName = " ").validationError())
        assertNotNull(valid.copy(deviceName = "a".repeat(41)).validationError())
        assertNotNull(valid.copy(providers = emptySet()).validationError())
        assertNotNull(valid.copy(pollIntervalSeconds = 9).validationError())
        assertNotNull(valid.copy(pollIntervalSeconds = 301).validationError())
        assertNull(valid.copy(pollIntervalSeconds = 10).validationError())
        assertNull(valid.copy(pollIntervalSeconds = 300).validationError())
    }
}
