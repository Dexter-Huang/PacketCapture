package com.packetcapture

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.packetcapture.core.*
import com.packetcapture.ui.detail.RequestDetailActivity
import com.packetcapture.ui.detail.RequestDetailViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SseDetailTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun eventViewRefreshesWhileOpenAndRawViewPreservesWireText() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as PacketCaptureApp).container
        container.ready.await()
        val session = CaptureSession(title = "SSE streaming validation", packages = emptyList(), completion = Completion.COMPLETE)
        val exchange = HttpExchange(sessionId = session.id, connectionId = "sse-test", method = "GET",
            url = "https://example.test/sse-validation", protocol = "HTTP/2", status = 200,
            responseHeaders = listOf(Header("Content-Type", "text/event-stream")))
        val sink = container.bodies.open(session.id, exchange.id, BodyPart.RESPONSE, DEFAULT_BODY_LIMIT, DEFAULT_STORAGE_LIMIT)
        container.repository.saveSession(session)
        sink.append("event: delta\ndata: SSE第一条\n\n".toByteArray())
        container.repository.saveExchange(exchange.copy(responseBody = sink.snapshot()))
        try {
            ActivityScenario.launch<RequestDetailActivity>(RequestDetailActivity.createIntent(context, exchange.id)).use { scenario ->
                lateinit var model: RequestDetailViewModel
                scenario.onActivity { model = ViewModelProvider(it)[RequestDetailViewModel::class.java] }
                compose.waitUntil(10000) { model.state.value.detail.sse?.totalEvents == 1 }
                compose.onNodeWithTag("detail-content").performScrollToNode(hasText("SSE第一条"))
                compose.onNodeWithText("SSE第一条").assertIsDisplayed()
                sink.append("data: SSE第二条\n\n".toByteArray())
                container.repository.saveExchange(exchange.copy(responseBody = sink.snapshot()))
                compose.waitUntil(10000) { model.state.value.detail.sse?.totalEvents == 2 }
                assertEquals(Completion.ACTIVE, model.state.value.detail.exchange?.completion)
                compose.onNodeWithTag("detail-content").performScrollToNode(hasText("SSE第二条"))
                compose.onNodeWithText("SSE第二条").assertIsDisplayed()
                compose.onNodeWithTag("detail-content").performScrollToNode(hasText("SSE 原文"))
                compose.onNodeWithText("SSE 原文").performClick()
                val raw = "event: delta\ndata: SSE第一条\n\ndata: SSE第二条\n\n"
                compose.onNodeWithTag("detail-content").performScrollToNode(hasText(raw))
                compose.onNodeWithText(raw).assertIsDisplayed()
                container.repository.saveExchange(exchange.copy(responseBody = sink.finish(), completion = Completion.COMPLETE))
                compose.waitUntil(10000) { model.state.value.detail.exchange?.completion == Completion.COMPLETE }
                assertEquals(2, model.state.value.detail.sse?.totalEvents)
            }
        } finally {
            sink.finish()
            container.repository.deleteSession(session.id)
        }
    }
}
