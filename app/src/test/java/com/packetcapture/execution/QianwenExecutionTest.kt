package com.packetcapture.execution

import org.junit.Assert.*
import org.junit.Test

class QianwenExecutionTest {
    @Test fun onlyDailyWelcomeWithoutSidebarIsNew() {
        assertTrue(QianwenExecution.isNew(true, true, false))
        assertFalse(QianwenExecution.isNew(true, true, true))
        assertFalse(QianwenExecution.isNew(true, false, false))
        assertFalse(QianwenExecution.isNew(false, true, false))
    }

    @Test fun focusAndReadbackDoNotBypassSingleSendGate() {
        val screen = PhoneScreen(targetApp=true, newConversation=true, canFocusInput=true)
        assertEquals(PhoneAction.FOCUS_INPUT, TextConversationExecution.decide("opening", screen, "问题"))
        assertEquals(PhoneAction.WAIT, TextConversationExecution.decide("verifying", screen.copy(input="问题", canSend=false), "问题"))
        assertEquals(PhoneAction.WAIT, TextConversationExecution.decide("send_started", screen.copy(input="问题", canSend=true), "问题"))
    }
}
