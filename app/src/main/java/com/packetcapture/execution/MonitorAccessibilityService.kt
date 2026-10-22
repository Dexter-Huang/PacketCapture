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

/** 每次操作重新取根节点；仅精确输入提示可按节点边界聚焦，事件回调不自动重试。 */
class MonitorAccessibilityService : AccessibilityService() {
    private var confirmedDraftProvider: String? = null
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
        (provider in setOf("yuanbao", "deepseek", "qianwen") || it.viewIdResourceName == "${DoubaoExecution.PACKAGE}:id/input_text") && it.isEditable && it.isVisibleToUser
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
        if (provider in setOf("deepseek", "qianwen") && Build.VERSION.SDK_INT >= 33) clearCache()
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
        return nodes.singleOrNull { it.isVisibleToUser && it.text?.toString() == label }
    }
    private fun act(action: PhoneAction, prompt: String, provider: String): Boolean {
        if (!unlocked()) return false
        if (action == PhoneAction.LAUNCH) {
            confirmedDraftProvider = null
            val intent = packageManager.getLaunchIntentForPackage(PhonePlatforms.packageName(provider)) ?: return false
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true
        }
        if (provider in setOf("deepseek", "qianwen") && Build.VERSION.SDK_INT >= 33) clearCache()
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
                        // 只聚焦精确提示节点的当前边界；填写和发送不使用手势。
                        val bounds = Rect().also(hint::getBoundsInScreen)
                        if (bounds.isEmpty) false else dispatchGesture(GestureDescription.Builder().addStroke(
                            GestureDescription.StrokeDescription(Path().apply {
                                moveTo(bounds.exactCenterX(), bounds.exactCenterY())
                            }, 0, 80)).build(), null, null)
                    }
                }
            }
            PhoneAction.NEW_CONVERSATION -> {
                confirmedDraftProvider = null
                val enteredNew = provider == "yuanbao" && all.any {
                    it.isVisibleToUser && (it.contentDescription?.toString() == "新建对话" || it.text?.toString() == "新建对话")
                }
                val accepted = create(all, provider)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
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
                else send(all, provider)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
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
                connected?.act(action, prompt, provider) == true
            }
        }
    }
}
