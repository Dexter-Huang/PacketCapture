package com.packetcapture.execution

import org.junit.Assert.*
import org.junit.Test

class WenxinExecutionTest {
    @Test fun newConversationRequiresCurrentModeAndNoOverlay() {
        assertTrue(WenxinExecution.isNew(true, true, true, false))
        assertFalse(WenxinExecution.isNew(true, true, false, false))
        assertFalse(WenxinExecution.isNew(true, true, true, true))
        assertFalse(WenxinExecution.isNew(false, true, true, false))
        assertFalse(WenxinExecution.isNew(true, false, true, false))
    }

    @Test fun obfuscatedRowCannotAcceptUnknownOrExtraControls() {
        val row = listOf("android.widget.FrameLayout", "android.widget.LinearLayout", "android.widget.LinearLayout")
        assertTrue(WenxinExecution.sendRow(row))
        assertFalse(WenxinExecution.sendRow(row.reversed().drop(1)))
        assertFalse(WenxinExecution.sendRow(row + "android.widget.ImageView"))
        assertFalse(WenxinExecution.sendRow(listOf("android.view.View") + row.drop(1)))
    }

    @Test fun modeAndNewChatMustStayInOpening() {
        val creating = PhoneScreen(targetApp=true, canCreateConversation=true)
        assertEquals(PhoneAction.NEW_CONVERSATION, TextConversationExecution.decide("opening", creating, "x"))
        assertEquals(PhoneAction.WAIT, TextConversationExecution.decide("editing", creating, "x"))
        assertEquals("opening", TextConversationExecution.nextPhase(PhoneAction.NEW_CONVERSATION, "wenxin"))
        assertEquals("opening", TextConversationExecution.nextPhase(PhoneAction.NEW_CONVERSATION, "yuanbao"))
        assertEquals("opening", TextConversationExecution.nextPhase(PhoneAction.NEW_CONVERSATION, "qianwen"))
        val ready = PhoneScreen(targetApp=true, newConversation=true, input="")
        assertEquals(PhoneAction.SET_TEXT, TextConversationExecution.decide("opening", ready, "x"))
    }

    @Test fun uncertainSendAndChangedDraftNeverSendAgain() {
        val screen = PhoneScreen(targetApp=true, newConversation=true, input="合成问题", canSend=true)
        assertEquals(PhoneAction.SEND, TextConversationExecution.decide("verifying", screen, "合成问题"))
        for (phase in listOf("send_started", "sent", "blocked"))
            assertEquals(PhoneAction.WAIT, TextConversationExecution.decide(phase, screen, "合成问题"))
        assertEquals(PhoneAction.WAIT, TextConversationExecution.decide("verifying", screen.copy(input="不同问题"), "合成问题"))
        assertEquals(PhoneAction.WAIT, TextConversationExecution.decide("verifying", screen.copy(promptVisible=true), "合成问题"))
    }
}
