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

    @Test fun observedNeutralGreetingStillRequiresBothSwitchesOffAndNoSidebar() {
        // 真机上午使用中性欢迎语，不能借此放宽模式及侧栏边界。
        val neutral = setOf("你好，我能帮什么忙吗？", "深度思考", "智能搜索")
        assertTrue(DeepseekExecution.isNew(neutral, false, false, false))
        assertTrue(DeepseekExecution.isNew(setOf("你好，有什么我能帮你的吗？"), false, false, false))
        assertFalse(DeepseekExecution.isNew(setOf("你好，有什么我能帮你的吗？"), false, true, false))
        assertTrue(DeepseekExecution.isNew(setOf("欢迎回来，想从哪里开始？"), false, false, false))
        assertFalse(DeepseekExecution.isNew(setOf("欢迎回来，想从哪里开始？"), false, true, false))
        assertFalse(DeepseekExecution.isNew(neutral, null, false, false))
        assertFalse(DeepseekExecution.isNew(neutral, false, true, false))
        assertFalse(DeepseekExecution.isNew(neutral, false, false, true))
        assertFalse(DeepseekExecution.isNew(neutral + "历史回答", false, false, true))
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
        for (provider in listOf("deepseek", "qianwen")) {
            val phase = TextConversationExecution.nextPhase(PhoneAction.NEW_CONVERSATION, provider)!!
            assertEquals("opening", phase)
            assertEquals(PhoneAction.FOCUS_INPUT, TextConversationExecution.decide(phase, screen, "问题"))
        }
    }
}
