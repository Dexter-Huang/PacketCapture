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
        // 已验证 3.1.4 本地 CA 测试版、新对话数字枚举 Connect 文本/推理；工具流仍待验证。
        LlmPlatform("kimi", "Kimi", setOf("com.moonshot.kimichat"), "verified_sample"),
        LlmPlatform("qianwen", "千问", setOf("com.aliyun.tongyi"), "verified_sample"),
        // 已验证本地 CA 测试版与私有传输缓冲条件；不代表官方原版即可解密。
        LlmPlatform("wenxin", "文心一言", setOf("com.baidu.newapp"), "verified_sample"),
    )

    fun byId(id: String): LlmPlatform? = platforms.find { it.id == id }
    fun forPackage(packageName: String?): LlmPlatform? = platforms.find { packageName in it.packageCandidates }
}
