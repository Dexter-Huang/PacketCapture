package com.packetcapture.llm

/** IDs match GEO's provider catalog; a catalog entry does not imply TLS or protocol support. */
data class LlmPlatform(
    val id: String,
    val displayName: String,
    val packageCandidates: Set<String>,
    val support: String,
)

object PlatformCatalog {
    val platforms: List<LlmPlatform> = listOf(
        LlmPlatform("yuanbao", "元宝", setOf("com.tencent.hunyuan.app.chat"), "verified_sample"),
        LlmPlatform("doubao", "豆包", setOf("com.larus.nova"), "verified_sample"),
        LlmPlatform("deepseek", "DeepSeek", setOf("com.deepseek.chat"), "verified_sample"),
        LlmPlatform("kimi", "Kimi", setOf("com.moonshot.kimichat"), "discovery"),
        LlmPlatform("qianwen", "千问", setOf("com.aliyun.tongyi"), "verified_sample"),
        LlmPlatform("wenxin", "文心一言", setOf("com.baidu.newapp"), "discovery"),
    )

    fun byId(id: String): LlmPlatform? = platforms.find { it.id == id }
    fun forPackage(packageName: String?): LlmPlatform? = platforms.find { packageName in it.packageCandidates }
}
