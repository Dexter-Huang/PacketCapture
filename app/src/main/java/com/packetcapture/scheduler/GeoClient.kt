package com.packetcapture.scheduler

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** 仅连接明确绑定的 HTTPS 服务；拒绝重定向，绝不把设备凭据转给其他主机。 */
internal class GeoClient(private val record: SchedulerRecord) {
    private val gson = Gson()
    fun request(method: String, path: String, body: Any = emptyMap<String, String>()): JsonObject {
        val uri = URI(record.serverUrl)
        require(uri.scheme == "https" && uri.host != null && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null) { "接入地址必须为 HTTPS" }
        val connection = URI(record.serverUrl.trimEnd('/') + "/api/android-rpa/v1" + path).toURL().openConnection() as HttpsURLConnection
        try {
            if (record.certificateSha256.isNotEmpty()) {
                // 仅用于显式固定证书的本地联调；证书有效期和默认主机名校验仍然生效。
                val expected = record.certificateSha256.lowercase()
                require(expected.matches(Regex("[0-9a-f]{64}"))) { "证书指纹格式不正确" }
                val trust = object : X509TrustManager {
                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) { error("不支持客户端证书") }
                    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                        require(chain.isNotEmpty())
                        chain[0].checkValidity()
                        val actual = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded)
                        val pinned = expected.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                        require(MessageDigest.isEqual(actual, pinned)) { "服务端证书指纹不匹配" }
                    }
                }
                connection.sslSocketFactory = SSLContext.getInstance("TLS").apply {
                    init(null, arrayOf(trust), SecureRandom())
                }.socketFactory
            }
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            connection.requestMethod = method
            connection.setRequestProperty("Authorization", "Bearer ${record.token}")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.doOutput = true
            val json = if (body is String) body else gson.toJson(body)
            connection.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) throw GeoHttpException(status)
            val bytes = connection.inputStream.use { stream -> stream.readNBytesCompat(2 * 1024 * 1024) }
            return gson.fromJson(String(bytes, Charsets.UTF_8), JsonObject::class.java)
        } finally { connection.disconnect() }
    }
}

internal class GeoHttpException(val status: Int) : Exception(when (status) {
    401, 403 -> "设备凭据或监测权限失效（$status）"
    409 -> "任务租约失效或结果冲突，需人工核对（409）"
    else -> "GEO 请求失败（$status），本地任务已保留"
})

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(output.size() + count <= limit) { "GEO 响应超过大小限制" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
