package com.packetcapture.execution

import org.junit.Assert.*
import org.junit.Test

/** 只在本次快速模式、新会话和全文核对成立时发送；不确定发送不补发。 */
class KimiExecutionTest {
    @Test fun requiresConfirmedQuickModeAndNewConversation() {
        assertFalse(KimiExecution.isNew(true, false, false, false))
        assertFalse(KimiExecution.isNew(true, false, true, true))
        assertFalse(KimiExecution.isNew(false, false, true, false))
        assertTrue(KimiExecution.isNew(true, false, true, false))
        assertTrue(KimiExecution.isNew(false, true, true, false))
        assertEquals("opening", TextConversationExecution.nextPhase(PhoneAction.NEW_CONVERSATION, "kimi"))
    }
    @Test fun exactDraftAndSingleSendBoundary() {
        val ready = PhoneScreen(targetApp=true, newConversation=true, input="原文", canSend=true)
        assertEquals(PhoneAction.SEND, TextConversationExecution.decide("verifying", ready, "原文"))
        assertEquals(PhoneAction.WAIT, TextConversationExecution.decide("verifying", ready, "原文 "))
        for (phase in listOf("send_started", "sent", "blocked")) {
            assertEquals(PhoneAction.WAIT, TextConversationExecution.decide(phase, ready, "原文"))
        }
    }
}
