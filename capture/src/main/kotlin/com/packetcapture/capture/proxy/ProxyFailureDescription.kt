package com.packetcapture.capture.proxy

/** 无 message 的连接关闭异常不能被显示成 null，更不能据此断定应用拒绝了证书。 */
internal fun describeProxyFailure(cause: Throwable?): String {
    if (cause == null) return "未提供异常原因"
    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    val descriptions = mutableListOf<String>()
    var current: Throwable? = cause
    while (current != null && descriptions.size < 4 && seen.add(current)) {
        val error = current
        descriptions += error.javaClass.simpleName + error.message?.takeIf { it.isNotBlank() }?.take(512)?.let { ": $it" }.orEmpty()
        current = error.cause
    }
    return descriptions.joinToString(" → ")
}
