package com.packetcapture.core

import java.net.URI

/** 手机执行器的接入意向配置；保存配置不代表设备已绑定或具备执行能力。 */
data class SchedulerSettings(
    val serverUrl: String = "",
    val deviceName: String = "",
    val pollIntervalSeconds: Int = 15,
    val providers: Set<String> = emptySet(),
) {
    fun validationError(): String? = when {
        serverUrl.isBlank() -> "请输入 GEO 服务地址"
        !validServerUrl(serverUrl) -> "请填写 HTTPS 地址，不含账号、查询参数或片段"
        deviceName.isBlank() || deviceName.length > 40 -> "设备名称需为 1–40 个字符"
        pollIntervalSeconds !in 10..300 -> "轮询间隔需为 10–300 秒"
        providers.isEmpty() -> "请至少选择一个计划执行的平台"
        else -> null
    }

    companion object {
        private fun validServerUrl(value: String): Boolean = runCatching {
            val uri = URI(value)
            value.length <= 2048 && uri.scheme.equals("https", ignoreCase = true) &&
                !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
                uri.rawQuery == null && uri.rawFragment == null && (uri.port == -1 || uri.port in 1..65535)
        }.getOrDefault(false)
    }
}
