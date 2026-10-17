package com.packetcapture.core

/** Explicit peer identities for clients which send a literal address as SNI. */
object TlsHostOverrides {
    private val domain = Regex("(?=.{1,253}$)[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+")

    fun parse(text: String): Map<String, String> = buildMap {
        text.lineSequence().map(String::trim).filter(String::isNotEmpty).forEach { line ->
            val parts = line.split('=')
            require(parts.size == 2) { "每行填写 原主机=证书域名" }
            val source = parts[0].trim().lowercase()
            val target = parts[1].trim().lowercase()
            require(domain.matches(source) || source.matches(Regex("[0-9a-f:]+")) && source.contains(':')) { "原主机格式无效" }
            require(domain.matches(target) && !target.matches(Regex("[0-9.]+"))) { "请填写有效的证书域名" }
            require(!containsKey(source)) { "原主机重复" }
            put(source, target)
        }
    }

    fun format(values: Map<String, String>): String = values.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}=${it.value}" }
}
