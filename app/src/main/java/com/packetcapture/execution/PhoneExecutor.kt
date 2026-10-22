package com.packetcapture.execution

/** 只描述界面操作，不读取网络答案或 GEO 凭据。 */
internal interface PhoneExecutor {
    fun forProvider(provider: String): PhoneExecutor = this
    fun available(): Boolean
    suspend fun observe(prompt: String): PhoneScreen
    suspend fun perform(action: PhoneAction, prompt: String): Boolean
}

internal data class PhoneScreen(
    val targetApp: Boolean = false,
    val unlocked: Boolean = true,
    val newConversation: Boolean = false,
    val canCreateConversation: Boolean = false,
    val input: String? = null,
    val canSend: Boolean = false,
    val promptVisible: Boolean = false,
    val diagnostic: String = "",
    val canFocusInput: Boolean = false,
)

internal enum class PhoneAction { WAIT, LAUNCH, NEW_CONVERSATION, FOCUS_INPUT, SET_TEXT, SEND }

/** 已持久化 send_started/sent 后只观察回答；无法证明未发出的点击也不重试。 */
internal object DoubaoExecution {
    const val PACKAGE = "com.larus.nova"
}

/** 仅接入已采集验证的普通文本，开关未知也不能发送。 */
internal object DeepseekExecution {
    const val PACKAGE = "com.deepseek.chat"
    private val welcome = setOf("下午好，我能帮什么忙吗？", "欢迎回来，随时开始吧")
    fun isNew(texts: Set<String>, thinking: Boolean?, search: Boolean?, sidebar: Boolean) =
        texts.any { it in welcome } && thinking == false && search == false && !sidebar
}

/** 仅匹配已登录的日常快速模式欢迎页，侧栏中的残留欢迎文字不算空会话。 */
internal object QianwenExecution {
    const val PACKAGE = "com.aliyun.tongyi"
    fun isNew(welcome: Boolean, dailySelected: Boolean, sidebar: Boolean) = welcome && dailySelected && !sidebar
}

/** 各平台共用的单次发送决策，界面和快速模式条件由平台适配确认。 */
internal object TextConversationExecution {
    fun decide(phase: String, screen: PhoneScreen, prompt: String): PhoneAction {
        if (!screen.unlocked || phase in setOf("manual", "send_started", "sent", "blocked")) return PhoneAction.WAIT
        if (phase == "launch") return PhoneAction.LAUNCH
        if (!screen.targetApp) return PhoneAction.WAIT
        return when (phase) {
            "opening" -> when {
                screen.newConversation && screen.input != null -> PhoneAction.SET_TEXT
                screen.newConversation && screen.canFocusInput -> PhoneAction.FOCUS_INPUT
                screen.canCreateConversation -> PhoneAction.NEW_CONVERSATION
                else -> PhoneAction.WAIT
            }
            "editing" -> if (screen.newConversation && screen.input != null) PhoneAction.SET_TEXT else PhoneAction.WAIT
            "verifying" -> if (screen.newConversation && screen.input == prompt && screen.canSend && !screen.promptVisible) PhoneAction.SEND else PhoneAction.WAIT
            else -> PhoneAction.WAIT
        }
    }
}

/** 逐个平台加入已观察的控件规则，调度仍使用同一单次发送状态机。 */
internal object PhonePlatforms {
    val packages = mapOf("doubao" to DoubaoExecution.PACKAGE, "yuanbao" to "com.tencent.hunyuan.app.chat", "deepseek" to DeepseekExecution.PACKAGE, "qianwen" to QianwenExecution.PACKAGE)
    val names = mapOf("doubao" to "豆包", "yuanbao" to "元宝", "deepseek" to "DeepSeek", "qianwen" to "千问")
    fun packageName(provider: String) = packages.getValue(provider)
}
