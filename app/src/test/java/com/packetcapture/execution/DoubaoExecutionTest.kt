package com.packetcapture.execution

import org.junit.Assert.*
import org.junit.Test

class DoubaoExecutionTest {
    private val screen = PhoneScreen(targetApp = true, newConversation = true, input = "问题", canSend = true)
    @Test fun onlyExactInputInNewPreparedConversationCanSend() {
        assertEquals(PhoneAction.SEND, TextConversationExecution.decide("verifying", screen, "问题"))
        for (input in listOf("", "问题问题", "问题 ", "其他问题")) {
            assertEquals(PhoneAction.WAIT, TextConversationExecution.decide("verifying", screen.copy(input = input), "问题"))
        }
        assertEquals(PhoneAction.WAIT, TextConversationExecution.decide("verifying", screen.copy(promptVisible = true), "问题"))
        assertEquals(PhoneAction.WAIT, TextConversationExecution.decide("verifying", screen.copy(unlocked = false), "问题"))
        assertEquals(PhoneAction.WAIT, TextConversationExecution.decide("verifying", screen.copy(targetApp = false), "问题"))
        assertEquals(PhoneAction.WAIT, TextConversationExecution.decide("verifying", screen.copy(newConversation = false), "问题"))
    }
    @Test fun submittedOrUncertainOrOldTasksNeverSendAgain() {
        for (phase in listOf("manual", "sent", "send_started", "blocked")) {
            assertEquals(PhoneAction.WAIT, TextConversationExecution.decide(phase, screen, "问题"))
        }
    }
    @Test fun openingOldConversationCreatesNewOneBeforeOverwritingInput() {
        assertEquals(PhoneAction.NEW_CONVERSATION, TextConversationExecution.decide("opening",
            screen.copy(newConversation = false, canCreateConversation = true), "问题"))
        assertEquals(PhoneAction.WAIT, TextConversationExecution.decide("editing", screen.copy(newConversation = false), "问题"))
        assertEquals(PhoneAction.SET_TEXT, TextConversationExecution.decide("editing", screen.copy(input = "重复重复"), "问题"))
    }
}
