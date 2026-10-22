package com.packetcapture.execution

import org.junit.Assert.*
import org.junit.Test

class DeepseekExecutionTest {
    private val welcome = setOf("下午好，我能帮什么忙吗？", "深度思考", "智能搜索")

    @Test fun normalModeRequiresBothObservedSwitchesOff() {
        assertTrue(DeepseekExecution.isNew(welcome, false, false, false))
        for (thinking in listOf(null, false, true)) for (search in listOf(null, false, true)) {
            if (thinking == false && search == false) continue
            assertFalse(DeepseekExecution.isNew(welcome, thinking, search, false))
        }
    }

    @Test fun sidebarAndUnknownGreetingCannotAuthorizeAnOverwrite() {
        assertFalse(DeepseekExecution.isNew(welcome, false, false, true))
        assertFalse(DeepseekExecution.isNew(setOf("历史回答"), false, false, false))
        assertFalse(DeepseekExecution.isNew(setOf("下午好，我能帮什么忙吗？更多内容"), false, false, false))
    }

    @Test fun missingEditorAndUncertainSendNeverSend() {
        val screen = PhoneScreen(targetApp = true, newConversation = true, input = null, canSend = true)
        assertEquals(PhoneAction.WAIT, TextConversationExecution.decide("opening", screen, "问题"))
        assertEquals(PhoneAction.WAIT, TextConversationExecution.decide("verifying", screen, "问题"))
        for (phase in listOf("send_started", "sent", "blocked")) {
            assertEquals(PhoneAction.WAIT, TextConversationExecution.decide(phase, screen.copy(input = "问题"), "问题"))
        }
    }

    @Test fun exactHintAllowsOnlyFocusBeforeIndependentReadback() {
        val screen = PhoneScreen(targetApp = true, newConversation = true, canFocusInput = true)
        assertEquals(PhoneAction.FOCUS_INPUT, TextConversationExecution.decide("opening", screen, "问题"))
        assertEquals(PhoneAction.WAIT, TextConversationExecution.decide("verifying", screen, "问题"))
        assertEquals(PhoneAction.WAIT, TextConversationExecution.decide("opening", screen.copy(unlocked = false), "问题"))
        assertEquals(PhoneAction.WAIT, TextConversationExecution.decide("opening", screen.copy(targetApp = false), "问题"))
    }
}
