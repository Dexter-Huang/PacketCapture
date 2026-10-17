package com.packetcapture.core

/** Events are delimited by an empty line, not by network packets or JSON boundaries. */
data class SseEvent(val number: Int, val type: String, val id: String, val data: String)
data class SseSnapshot(val events: List<SseEvent>, val totalEvents: Int, val pending: Boolean,
    val retryMillis: Long?, val comments: Int)

fun List<Header>.isEventStream(): Boolean = firstHeader("content-type")
    ?.substringBefore(';')?.trim()?.equals("text/event-stream", ignoreCase = true) == true

object ServerSentEvents {
    /** Keep a bounded event window for the UI; the original body remains available separately. */
    fun parse(text: String, maxEvents: Int = 500): SseSnapshot {
        require(maxEvents > 0)
        val events = java.util.ArrayDeque<SseEvent>()
        val data = StringBuilder()
        var hasData = false
        var type = ""
        var id = ""
        var retry: Long? = null
        var total = 0
        var comments = 0
        var start = if (text.startsWith('\uFEFF')) 1 else 0
        var index = start
        while (index < text.length) {
            if (text[index] != '\r' && text[index] != '\n') { index++; continue }
            val line = text.substring(start, index)
            if (text[index++] == '\r' && index < text.length && text[index] == '\n') index++
            start = index
            when {
                line.isEmpty() -> {
                    if (hasData) {
                        if (events.size == maxEvents) events.removeFirst()
                        events.addLast(SseEvent(++total, type.ifEmpty { "message" }, id, data.toString()))
                    }
                    data.setLength(0); hasData = false; type = ""
                }
                line.startsWith(':') -> comments++
                else -> {
                    val field = line.substringBefore(':')
                    val value = line.substringAfter(':', "").removePrefix(" ")
                    when (field) {
                        "data" -> { if (hasData) data.append('\n'); data.append(value); hasData = true }
                        "event" -> type = value
                        "id" -> if ('\u0000' !in value) id = value
                        "retry" -> if (value.isNotEmpty() && value.all { it in '0'..'9' }) value.toLongOrNull()?.let { retry = it }
                    }
                }
            }
        }
        return SseSnapshot(events.toList(), total, hasData || start < text.length, retry, comments)
    }
}
