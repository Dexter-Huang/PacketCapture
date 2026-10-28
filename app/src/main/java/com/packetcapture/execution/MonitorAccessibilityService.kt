package com.packetcapture.execution

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 每次操作重新取根节点；手势仅用于精确节点的当前边界，事件回调不自动重试。 */
class MonitorAccessibilityService : AccessibilityService() {
    private var confirmedDraftProvider: String? = null
    private var wenxinModeConfirmed = false
    private var kimiQuickConfirmed = false
    override fun onServiceConnected() { connected = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun onDestroy() { if (connected === this) connected = null; super.onDestroy() }

    private fun unlocked() = !getSystemService(KeyguardManager::class.java).isKeyguardLocked
    private fun nodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = buildList {
        fun visit(node: AccessibilityNodeInfo) {
            add(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
        }
        visit(root)
    }
    private fun input(nodes: List<AccessibilityNodeInfo>, provider: String) = nodes.singleOrNull {
        (provider in setOf("yuanbao", "deepseek", "qianwen", "wenxin", "kimi") || it.viewIdResourceName == "${DoubaoExecution.PACKAGE}:id/input_text") && it.isEditable && it.isVisibleToUser
    }
    private fun clickable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current = node
        repeat(4) {
            val value = current ?: return null
            if (value.isVisibleToUser && value.isEnabled && value.isClickable) return value
            current = value.parent
        }
        return null
    }
    private fun create(nodes: List<AccessibilityNodeInfo>, provider: String): AccessibilityNodeInfo? {
        if (provider == "kimi") {
            fun label(text: String) = nodes.singleOrNull { it.isVisibleToUser && it.text?.toString() == text }
            if (label("快速对话，即时响应") != null) return clickable(label("快速"))
            clickable(nodes.singleOrNull { it.isVisibleToUser && it.contentDescription?.toString() == "开启新会话" })?.let { return it }
            // 3.1.4 抽屉顶部新会话图标无标签，按头像所在顶栏的唯一相邻按钮识别。
            if (label("历史会话") != null) {
                val avatar = nodes.singleOrNull { it.isVisibleToUser && it.contentDescription?.toString() == "用户头像" }
                val profile = avatar?.parent
                val drawer = profile?.parent
                if (profile?.childCount == 2 && profile.getChild(0) == avatar && drawer != null) {
                    val index = (0 until drawer.childCount).singleOrNull { drawer.getChild(it) == profile }
                    val button = index?.let { drawer.getChild(it + 1) }
                    if (button?.className?.toString() == "android.widget.Button" && button.childCount == 0 &&
                        button.text.isNullOrEmpty() && button.contentDescription.isNullOrEmpty()) return clickable(button)
                }
            }
            if (!kimiQuickConfirmed && (confirmedDraftProvider == provider || nodes.any { it.isVisibleToUser && it.text?.toString()?.let { text ->
                    text.startsWith("嗨") && text.endsWith("今天要和 Kimi 一起做点什么") } == true })) return clickable(label("快速"))
            // 再次打开已有空白页时欢迎语可能消失；经导航新建会话重新建立边界。
            if (!kimiQuickConfirmed && label("快速") != null) return clickable(nodes.singleOrNull {
                it.isVisibleToUser && it.contentDescription?.toString() == "导航按钮"
            })
            return null
        }
        if (provider == "wenxin") {
            fun label(text: String) = nodes.singleOrNull { it.isVisibleToUser && it.text?.toString() == text }
            if (label("自动模式") != null && label("智能切换思考模式，更懂你") != null) return clickable(label("自动模式"))
            clickable(label("新建对话"))?.let { return it }
            if (label("Hi，我是文心") != null && !wenxinModeConfirmed) return clickable(label("新对话"))
            // 已有会话标题是动态正文，不能依赖「对话」字样；仅匹配已核实的唯一原生顶栏。
            val toolbar = nodes.singleOrNull { node -> node.isVisibleToUser &&
                node.viewIdResourceName == "${WenxinExecution.PACKAGE}:id/obfuscated" &&
                node.className?.toString() == "android.widget.RelativeLayout" && node.childCount == 3 &&
                (0 until node.childCount).map { node.getChild(it)?.className?.toString().orEmpty() } ==
                    listOf("android.widget.LinearLayout", "android.widget.ImageView", "android.view.ViewGroup")
            } ?: return null
            return clickable(toolbar.getChild(1))
        }
        if (provider == "qianwen") return clickable(nodes.singleOrNull {
            it.isVisibleToUser && it.viewIdResourceName == "${QianwenExecution.PACKAGE}:id/layout_new_session_float"
        }) ?: clickable(nodes.singleOrNull { it.isVisibleToUser && it.contentDescription?.toString() == "打开侧边栏" })
        if (provider == "deepseek") return clickable(nodes.singleOrNull {
            it.isVisibleToUser && it.contentDescription?.toString() == "开启新对话"
        })
        if (provider == "yuanbao") {
            val entry = nodes.singleOrNull { it.isVisibleToUser && it.text?.toString() == "新建对话" }
                ?: nodes.singleOrNull { it.isVisibleToUser && it.contentDescription?.toString() == "新建对话" }
            return clickable(entry) ?: nodes.singleOrNull { it.isVisibleToUser &&
                (it.viewIdResourceName == "ic_navigation_show" || it.viewIdResourceName?.endsWith(":id/ic_navigation_show") == true) }
        }
        return nodes.singleOrNull {
        it.viewIdResourceName == "${DoubaoExecution.PACKAGE}:id/larus_chat_top_left_create_new_cvs" && it.isVisibleToUser
        }
    }
    private fun send(nodes: List<AccessibilityNodeInfo>, provider: String): AccessibilityNodeInfo? {
        // Kimi 的发送图标不提供 ACTION_CLICK，向上找会误命中整个输入面板。
        // 必须保留图标本身的当前边界，单次发送时由系统手势点击该节点中心。
        if (provider == "kimi") return nodes.singleOrNull {
            it.isVisibleToUser && it.isEnabled && it.contentDescription?.toString() == "发送讯息"
        }?.takeIf { node ->
            val bounds = Rect().also(node::getBoundsInScreen)
            bounds.width() in 1..200 && bounds.height() in 1..200 && input(nodes, provider) != null
        }
        if (provider == "wenxin") {
            val field = input(nodes, provider) ?: return null
            val row = field.parent?.parent?.parent ?: return null
            if (row.className?.toString() != "android.view.ViewGroup" || !WenxinExecution.sendRow(
                    (0 until row.childCount).map { row.getChild(it)?.className?.toString().orEmpty() })) return null
            val tools = row.getChild(2) ?: return null
            if (tools.childCount != 2) return null
            val frame = tools.getChild(1) ?: return null
            if (frame.className?.toString() != "android.widget.FrameLayout" || frame.childCount != 1) return null
            return frame.getChild(0)?.takeIf { it.className?.toString() == "android.widget.ImageView" &&
                it.viewIdResourceName == "${WenxinExecution.PACKAGE}:id/obfuscated" &&
                it.isVisibleToUser && it.isEnabled && it.isClickable && !field.text.isNullOrEmpty() }
        }
        if (provider == "qianwen") {
            val mention = nodes.singleOrNull { it.isVisibleToUser && it.contentDescription?.toString() == "@" } ?: return null
            val row = mention.parent ?: return null
            if (row.className?.toString() != "android.widget.LinearLayout" || row.childCount != 3 || row.getChild(0) != mention) return null
            val send = row.getChild(2) ?: return null
            return send.takeIf { it.isVisibleToUser && it.isEnabled && it.isClickable &&
                it.className?.toString() == "android.widget.FrameLayout" && it.childCount == 1 &&
                it.getChild(0)?.className?.toString() == "android.widget.ImageView" }
        }
        if (provider == "deepseek") return clickable(nodes.singleOrNull {
            it.isVisibleToUser && it.contentDescription?.toString() == "发送"
        })
        if (provider == "yuanbao") {
            val container = nodes.singleOrNull { it.isVisibleToUser && it.viewIdResourceName == "${PhonePlatforms.packageName(provider)}:id/cv_chat_input_container" }
                ?: return null
            return nodes(container).singleOrNull {
                it.isVisibleToUser && it.isEnabled && it.isClickable && it.className?.toString() == "android.widget.Button"
            }
        }
        return nodes.singleOrNull {
        it.isVisibleToUser && it.isEnabled && it.isClickable &&
            (it.contentDescription?.toString() == "发送" || it.viewIdResourceName == "${DoubaoExecution.PACKAGE}:id/action_send")
        }
    }
    private fun mode(nodes: List<AccessibilityNodeInfo>, label: String): Boolean? {
        var current = nodes.singleOrNull { it.isVisibleToUser && it.text?.toString() == label }
        repeat(4) {
            val node = current ?: return null
            if (node.isCheckable) return node.isChecked
            current = node.parent
        }
        return null
    }
    private fun isNew(nodes: List<AccessibilityNodeInfo>, provider: String): Boolean {
        if (provider == "kimi") return KimiExecution.isNew(
            nodes.any { it.isVisibleToUser && it.text?.toString()?.let { text ->
                text.startsWith("嗨") && text.endsWith("今天要和 Kimi 一起做点什么") } == true },
            confirmedDraftProvider == provider, kimiQuickConfirmed,
            nodes.none { it.isVisibleToUser && it.text?.toString() == "快速" } ||
                nodes.any { it.isVisibleToUser && (it.text?.toString() in setOf("历史会话", "思考等级", "快速对话，即时响应") ||
                    it.contentDescription?.toString() == "开启新会话") })
        if (provider == "wenxin") return WenxinExecution.isNew(
            nodes.any { it.isVisibleToUser && it.text?.toString() == "Hi，我是文心" },
            nodes.any { it.isVisibleToUser && it.text?.toString() == "新对话" }, wenxinModeConfirmed,
            nodes.any { it.isVisibleToUser && it.text?.toString() in setOf("新建对话", "智能切换思考模式，更懂你", "文心大模型5.1") })
        if (provider == "qianwen") return QianwenExecution.isNew(
            nodes.any { it.isVisibleToUser && it.text?.toString() == "我是千问" },
            nodes.any { it.isVisibleToUser && it.contentDescription?.toString() == "日常" && it.isSelected },
            nodes.any { it.isVisibleToUser && it.viewIdResourceName == "${QianwenExecution.PACKAGE}:id/layout_top_bar" })
        if (provider == "deepseek") return DeepseekExecution.isNew(
            nodes.filter { it.isVisibleToUser && !it.isEditable }.mapNotNull { it.text?.toString() }.toSet(),
            mode(nodes, "深度思考"), mode(nodes, "智能搜索"),
            nodes.any { it.isVisibleToUser && it.contentDescription?.toString() == "关闭侧边栏" })
        if (provider == "yuanbao") return (nodes.any { it.isVisibleToUser && it.text?.toString()?.startsWith("Hi，") == true } ||
            confirmedDraftProvider == provider && nodes.singleOrNull {
                it.isVisibleToUser && it.viewIdResourceName == "${PhonePlatforms.packageName(provider)}:id/chat_compose_view"
            }?.let { chat -> nodes(chat).none { it.isVisibleToUser && !it.text.isNullOrEmpty() } } == true) &&
            nodes.any { it.isVisibleToUser && it.text?.toString() == "快速" } &&
            nodes.none { it.isVisibleToUser && it.viewIdResourceName == "${PhonePlatforms.packageName(provider)}:id/cv_drawer" }
        return nodes.any {
        it.viewIdResourceName == "${DoubaoExecution.PACKAGE}:id/larus_mode_switch_welcome_title" && it.isVisibleToUser
        }
    }
    private fun promptVisible(nodes: List<AccessibilityNodeInfo>, prompt: String) = nodes.any {
        it.isVisibleToUser && !it.isEditable &&
            (it.text?.toString() == prompt || it.contentDescription?.toString() == prompt)
    }
    /** Compose 未暴露编辑节点时，只读取系统提供且包名匹配的当前输入连接。 */
    private fun deepseekInput(): String? {
        if (Build.VERSION.SDK_INT < 33) return null
        val method = inputMethod ?: return null
        if (!method.currentInputStarted || method.currentInputEditorInfo?.packageName != DeepseekExecution.PACKAGE) return null
        val surrounding = method.currentInputConnection?.getSurroundingText(2001, 2001, 0) ?: return null
        if (surrounding.offset != 0 || surrounding.text.length > 2000) return null
        return surrounding.text.toString()
    }
    private fun snapshot(prompt: String, provider: String): PhoneScreen {
        if (!unlocked()) return PhoneScreen(unlocked = false)
        if (provider in setOf("deepseek", "qianwen", "wenxin", "kimi") && Build.VERSION.SDK_INT >= 33) clearCache()
        val root = rootInActiveWindow ?: return PhoneScreen()
        root.refresh()
        if (root.packageName?.toString() != PhonePlatforms.packageName(provider)) return PhoneScreen()
        val all = nodes(root)
        val field = input(all, provider)
        // Android 可能把空输入框的提示文字暴露为 text。
        val text = field?.let { if (it.isShowingHintText) "" else it.text?.toString().orEmpty() }
            ?: if (provider == "deepseek") deepseekInput() else null
        return PhoneScreen(true, true, isNew(all, provider), create(all, provider) != null, text, send(all, provider) != null,
            prompt.isNotEmpty() && promptVisible(all, prompt),
            if (provider == "yuanbao") "draft=${confirmedDraftProvider == provider},fast=${all.any { it.isVisibleToUser && it.text?.toString() == "快速" }},chat=${all.count { it.viewIdResourceName?.endsWith(":id/chat_compose_view") == true }},drawer=${all.any { it.isVisibleToUser && it.viewIdResourceName?.endsWith(":id/cv_drawer") == true }}"
            else if (provider == "deepseek") "节点=${all.size} 可编辑=${all.count { it.isEditable }} 深度思考=${mode(all, "深度思考")} 智能搜索=${mode(all, "智能搜索")} 输入连接=${if (Build.VERSION.SDK_INT >= 33) inputMethod?.currentInputEditorInfo?.packageName else null}" else "",
            inputHint(all, provider) != null)
    }
    private fun inputHint(nodes: List<AccessibilityNodeInfo>, provider: String): AccessibilityNodeInfo? {
        val label = when (provider) { "deepseek" -> "发消息或按住说话"; "qianwen" -> "发消息或按住说话..."; else -> return null }
        nodes.singleOrNull { it.isVisibleToUser && it.text?.toString() == label }?.let { return it }
        if (provider != "deepseek") return null
        // Compose 有时省略提示语节点。只在已确认空会话中，按完整工具栏结构定位输入区域。
        val thinking = nodes.singleOrNull { it.isVisibleToUser && it.text?.toString() == "深度思考" } ?: return null
        val modes = thinking.parent?.parent ?: return null
        val panel = modes.parent ?: return null
        if (panel.childCount != 4 || panel.getChild(1) != modes || modes.childCount != 2 ||
            (0 until modes.childCount).map { modes.getChild(it)?.getChild(0)?.text?.toString() } != listOf("深度思考", "智能搜索") ||
            (0 until modes.childCount).any { modes.getChild(it)?.isCheckable != true || modes.getChild(it)?.isChecked != false } ||
            nodes(panel.getChild(2) ?: return null).none { it.contentDescription?.toString() == "上传文件" } ||
            nodes(panel.getChild(3) ?: return null).none { it.contentDescription?.toString() == "切换至语音" }) return null
        val wrapper = panel.getChild(0) ?: return null
        if (wrapper.childCount != 1) return null
        return wrapper.getChild(0)?.takeIf { it.isVisibleToUser && it.isEnabled &&
            it.className?.toString() == "android.view.View" && it.childCount == 0 && it.text.isNullOrEmpty() }
    }
    private fun act(action: PhoneAction, prompt: String, provider: String): Boolean {
        if (!unlocked()) return false
        if (action == PhoneAction.LAUNCH) {
            confirmedDraftProvider = null
            wenxinModeConfirmed = false
            kimiQuickConfirmed = false
            val intent = packageManager.getLaunchIntentForPackage(PhonePlatforms.packageName(provider)) ?: return false
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true
        }
        if (provider in setOf("deepseek", "qianwen", "wenxin", "kimi") && Build.VERSION.SDK_INT >= 33) clearCache()
        val root = rootInActiveWindow ?: return false
        root.refresh()
        if (root.packageName?.toString() != PhonePlatforms.packageName(provider)) return false
        val all = nodes(root)
        return when (action) {
            PhoneAction.FOCUS_INPUT -> {
                if (provider !in setOf("deepseek", "qianwen") || !isNew(all, provider)) false
                else {
                    val hint = inputHint(all, provider) ?: return false
                    if (hint.performAction(AccessibilityNodeInfo.ACTION_CLICK)) true else {
                        // 只聚焦精确提示节点的当前边界，不使用固定坐标或手势填写。
                        val bounds = Rect().also(hint::getBoundsInScreen)
                        if (bounds.isEmpty) false else dispatchGesture(GestureDescription.Builder().addStroke(
                            GestureDescription.StrokeDescription(Path().apply {
                                moveTo(bounds.exactCenterX(), bounds.exactCenterY())
                            }, 0, 80)).build(), null, null)
                    }
                }
            }
            PhoneAction.NEW_CONVERSATION -> {
                if (provider != "kimi") confirmedDraftProvider = null
                val kimiNew = provider == "kimi" && all.any { it.isVisibleToUser &&
                    (it.contentDescription?.toString() == "开启新会话" || it.text?.toString() == "历史会话" || it.text?.toString()?.let { text ->
                        text.startsWith("嗨") && text.endsWith("今天要和 Kimi 一起做点什么") } == true) }
                val choosingWenxinMode = provider == "wenxin" && all.any { it.isVisibleToUser && it.text?.toString() == "自动模式" }
                val choosingKimiQuick = provider == "kimi" && all.any { it.isVisibleToUser && it.text?.toString() == "快速对话，即时响应" }
                val enteredNew = provider == "yuanbao" && all.any {
                    it.isVisibleToUser && (it.contentDescription?.toString() == "新建对话" || it.text?.toString() == "新建对话")
                }
                val accepted = create(all, provider)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                if (accepted && choosingWenxinMode) wenxinModeConfirmed = true
                if (accepted && choosingKimiQuick) kimiQuickConfirmed = true
                // 快速模式选择会隐藏欢迎语，保留此前明确进入空会话的证据供全文回读。
                if (accepted && kimiNew) confirmedDraftProvider = provider
                if (accepted && enteredNew) confirmedDraftProvider = provider
                accepted
            }
            PhoneAction.SET_TEXT -> {
                if (!isNew(all, provider)) false else {
                    confirmedDraftProvider = provider
                    val editor = input(all, provider)
                    if (editor != null) editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                        Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, prompt) })
                    else if (provider == "deepseek" && Build.VERSION.SDK_INT >= 33 && deepseekInput() != null) {
                        val old = deepseekInput() ?: return false
                        val connection = inputMethod?.currentInputConnection ?: return false
                        connection.setSelection(0, old.length)
                        connection.commitText(prompt, 1, null)
                        true // 异步输入必须由下一次独立全文回读确认。
                    } else false
                }
            }
            PhoneAction.SEND -> {
                // 单次点击前再次核对原文以及已提交问题不存在。
                val field = input(all, provider)
                val actual = field?.text?.toString() ?: if (provider == "deepseek") deepseekInput() else null
                if (!isNew(all, provider) || actual != prompt || promptVisible(all, prompt)) false
                else if (provider == "kimi") {
                    val node = send(all, provider) ?: return false
                    val bounds = Rect().also(node::getBoundsInScreen)
                    dispatchGesture(GestureDescription.Builder().addStroke(
                        GestureDescription.StrokeDescription(Path().apply {
                            moveTo(bounds.exactCenterX(), bounds.exactCenterY())
                        }, 0, 80)).build(), null, null)
                } else send(all, provider)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            }
            else -> false
        }
    }
    companion object {
        @Volatile private var connected: MonitorAccessibilityService? = null
        internal fun executor(provider: String = "doubao"): PhoneExecutor = object : PhoneExecutor {
            override fun forProvider(provider: String) = executor(provider)
            override fun available() = connected != null
            override suspend fun observe(prompt: String) = withContext(Dispatchers.Main) { connected?.snapshot(prompt, provider) ?: PhoneScreen() }
            override suspend fun perform(action: PhoneAction, prompt: String) = withContext(Dispatchers.Main) {
                if (connected?.act(action, prompt, provider) != true) false
                else if (action != PhoneAction.LAUNCH) true
                else {
                    // 系统可能拒绝后台启动而不抛异常；必须确认目标真实前台后才推进执行阶段。
                    var foreground = false
                    repeat(20) {
                        if (!foreground) {
                            kotlinx.coroutines.delay(250)
                            if (Build.VERSION.SDK_INT >= 33) connected?.clearCache()
                            foreground = connected?.rootInActiveWindow?.packageName?.toString() == PhonePlatforms.packageName(provider)
                        }
                    }
                    foreground
                }
            }
        }
    }
}
