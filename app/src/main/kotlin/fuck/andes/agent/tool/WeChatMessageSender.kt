package fuck.andes.agent.tool

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import fuck.andes.agent.accessibility.AgentAccessibilityService
import fuck.andes.agent.model.AgentModelClient
import fuck.andes.core.AgentLogger
import java.util.Locale
import kotlin.math.abs
import org.json.JSONObject

/**
 * 微信消息的窄自动化流程。
 *
 * 只接受精确联系人匹配；发送按钮只点击一次。发送后若无法验证，不重试，避免重复消息。
 */
internal class WeChatMessageSender(
    private val context: Context,
    private val logger: AgentLogger,
    private val isCancelled: () -> Boolean = { false },
) {
    fun execute(args: JSONObject): AgentModelClient.ToolResult {
        val contact = args.getString("contact").trim()
        val message = args.getString("message")
        val mode = args.getString("mode").lowercase(Locale.ROOT)
        val send = mode == "send"
        if (isCancelled()) return sensitiveError("CANCELLED", "실행이 중지되었습니다. 이번에는 전송하지 않았습니다.")
        val service = AgentAccessibilityService.current()
            ?: return sensitiveError("ACCESSIBILITY_UNAVAILABLE", "Eta 접근성 서비스가 아직 연결되지 않았습니다.")
        val launchIntent = context.packageManager.getLaunchIntentForPackage(WECHAT_PACKAGE)
            ?: return sensitiveError("WECHAT_NOT_INSTALLED", "WeChat이 설치되어 있지 않거나 실행할 수 있는 진입점이 없습니다.")
        return runCatching {
            context.startActivity(
                launchIntent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
                ),
            )
            if (!waitForPackage(service, WECHAT_PACKAGE, PACKAGE_TIMEOUT_MS)) {
                return sensitiveError("WECHAT_LAUNCH_TIMEOUT", "WeChat이 시간 내에 전면으로 실행되지 않았습니다.")
            }

            val searchSnapshot = findOrOpenSearch(service)
                ?: return sensitiveError("WECHAT_SEARCH_NOT_FOUND", "WeChat 검색 진입점을 정확히 찾을 수 없습니다.")
            val searchInput = searchSnapshot.nodes
                .firstOrNull { it.enabled && it.editable && !it.password }
                ?: return sensitiveError("WECHAT_SEARCH_INPUT_NOT_FOUND", "WeChat 검색창을 정확히 찾을 수 없습니다.")
            val searchWrite = service.setTextNode(searchSnapshot, searchInput.index, contact)
            if (!searchWrite.ok) {
                return sensitiveError(searchWrite.code, searchWrite.message.ifBlank { "연락처 검색에 실패했습니다." })
            }

            val resultSnapshot = waitForSnapshot(service, CONTENT_TIMEOUT_MS) { snapshot ->
                exactContactRows(snapshot, contact).isNotEmpty()
            } ?: return sensitiveError("CONTACT_NOT_FOUND", "정확히 일치하는 WeChat 연락처를 찾지 못했습니다.")
            val rows = exactContactRows(resultSnapshot, contact)
            if (rows.size != 1) {
                return sensitiveError("AMBIGUOUS_CONTACT", "동명이인 연락처가 여러 명 있습니다. 잘못 전송되는 것을 방지하기 위해 이번에는 대화를 열지 않았습니다.")
            }
            val openResult = service.clickNode(resultSnapshot, rows.single().index)
            if (!openResult.ok) {
                return sensitiveError(openResult.code, openResult.message.ifBlank { "연락처 대화를 열 수 없습니다." })
            }

            val displayHeight = service.displaySize()?.second ?: Int.MAX_VALUE
            val chatSnapshot = waitForSnapshot(service, CONTENT_TIMEOUT_MS) { snapshot ->
                snapshot.packageName == WECHAT_PACKAGE &&
                    snapshot.nodes.any { node ->
                        node.enabled && node.editable && !node.password &&
                            node.bounds.centerY() > displayHeight * 0.55
                    }
            } ?: return sensitiveError("CHAT_INPUT_NOT_FOUND", "연락처 채팅 페이지에 정확히 진입하지 못했습니다. 이번에는 메시지를 입력하지 않았습니다.")
            val chatInput = chatSnapshot.nodes.first {
                it.enabled && it.editable && !it.password &&
                    it.bounds.centerY() > displayHeight * 0.55
            }
            val writeResult = service.setTextNode(chatSnapshot, chatInput.index, message)
            if (!writeResult.ok) {
                return sensitiveError(writeResult.code, writeResult.message.ifBlank { "메시지 입력에 실패했습니다." })
            }
            if (!send) {
                logger.info("Agent direct tool action=send_message outcome=drafted")
                return sensitiveOk()
                    .put("mode", "draft")
                    .put("contact_matched", true)
                    .put("message_chars", message.length)
                    .toToolResult()
            }

            if (isCancelled()) {
                return sensitiveError("CANCELLED", "실행이 중지되었습니다. 메시지는 입력되었으나 전송되지 않았습니다.")
            }
            val sendSnapshot = waitForSnapshot(service, CONTENT_TIMEOUT_MS) { snapshot ->
                snapshot.nodes.any { node ->
                    node.enabled &&
                        node.bounds.centerY() > displayHeight * 0.55 &&
                        (node.text == SEND_TEXT || node.desc == SEND_TEXT)
                }
            } ?: return sensitiveError("SEND_BUTTON_NOT_FOUND", "메시지는 입력되었으나 확인 가능한 전송 버튼을 찾지 못했습니다. 이번에는 전송하지 않았습니다.")
            val sendNodes = deduplicateRows(
                sendSnapshot.nodes.filter { node ->
                    node.enabled &&
                        node.bounds.centerY() > displayHeight * 0.55 &&
                        (node.text == SEND_TEXT || node.desc == SEND_TEXT)
                },
            )
            if (sendNodes.size != 1) {
                return sensitiveError("AMBIGUOUS_SEND_BUTTON", "전송 버튼이 하나가 아닙니다. 메시지는 입력되었으나 전송되지 않았습니다.")
            }
            val click = service.clickNode(sendSnapshot, sendNodes.single().index)
            if (!click.ok) {
                return sensitiveError(
                    click.code,
                    click.message.ifBlank { "전송 결과를 알 수 없습니다. 중복 전송을 방지하기 위해 자동 재시도하지 않습니다." },
                )
            }

            val verified = waitForSnapshot(service, SEND_VERIFY_TIMEOUT_MS) { snapshot ->
                val messageVisible = snapshot.nodes.any { it.text == message || it.desc == message }
                val inputCleared = snapshot.nodes.any { node ->
                    node.enabled && node.editable && node.text.isBlank() &&
                        node.bounds.centerY() > displayHeight * 0.55
                }
                val sendButtonGone = snapshot.nodes.none { node ->
                    node.enabled &&
                        node.bounds.centerY() > displayHeight * 0.55 &&
                        (node.text == SEND_TEXT || node.desc == SEND_TEXT)
                }
                inputCleared && (messageVisible || sendButtonGone)
            } != null
            if (!verified) {
                return sensitiveError(
                    "ACTION_OUTCOME_UNKNOWN",
                    "전송 버튼을 한 번 클릭했으나 결과를 정확히 확인하지 못했습니다. 자동 재시도하지 않으니 WeChat에서 직접 확인해 주세요.",
                )
            }
            logger.info("Agent direct tool action=send_message outcome=verified")
            sensitiveOk()
                .put("mode", "send")
                .put("contact_matched", true)
                .put("sent", true)
                .put("verified", true)
                .put("message_chars", message.length)
                .toToolResult()
        }.getOrElse {
            sensitiveError("WECHAT_AUTOMATION_FAILED", "WeChat 자동 전송 과정이 실패했습니다. 이번에는 자동 재시도하지 않습니다.")
        }
    }

    private fun findOrOpenSearch(
        service: AgentAccessibilityService,
    ): AgentAccessibilityService.NodeSnapshot? {
        repeat(MAX_BACK_ATTEMPTS) {
            if (isCancelled()) return null
            if (service.currentPackageName() != WECHAT_PACKAGE) return null
            val snapshot = service.captureNodeSnapshot(MAX_NODES)
            val search = snapshot?.nodes?.firstOrNull { node ->
                node.enabled && (node.text == SEARCH_TEXT || node.desc == SEARCH_TEXT)
            }
            if (snapshot != null && search != null) {
                val clicked = service.clickNode(snapshot, search.index)
                if (!clicked.ok) return null
                return waitForSnapshot(service, CONTENT_TIMEOUT_MS) { current ->
                    current.packageName == WECHAT_PACKAGE &&
                        current.nodes.any { it.enabled && it.editable && !it.password }
                }
            }
            val back = service.globalActionResult("BACK")
            if (!back.ok) return null
            SystemClock.sleep(STEP_SETTLE_MS)
        }
        return null
    }

    private fun exactContactRows(
        snapshot: AgentAccessibilityService.NodeSnapshot,
        contact: String,
    ): List<AgentAccessibilityService.UiNode> {
        val exact = snapshot.nodes.filter { node ->
            node.enabled && !node.editable && (node.text == contact || node.desc == contact)
        }.sortedBy { it.bounds.centerY() }
        return deduplicateRows(exact)
    }

    private fun deduplicateRows(
        nodes: List<AgentAccessibilityService.UiNode>,
    ): List<AgentAccessibilityService.UiNode> {
        val rows = mutableListOf<AgentAccessibilityService.UiNode>()
        nodes.sortedBy { it.bounds.centerY() }.forEach { candidate ->
            if (
                rows.none {
                    abs(it.bounds.centerY() - candidate.bounds.centerY()) <= SAME_ROW_TOLERANCE_PX
                }
            ) {
                rows += candidate
            }
        }
        return rows
    }

    private fun waitForPackage(
        service: AgentAccessibilityService,
        packageName: String,
        timeoutMillis: Long,
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        do {
            if (isCancelled()) return false
            if (service.currentPackageName() == packageName) return true
            SystemClock.sleep(POLL_MS)
        } while (SystemClock.elapsedRealtime() < deadline)
        return service.currentPackageName() == packageName
    }

    private fun waitForSnapshot(
        service: AgentAccessibilityService,
        timeoutMillis: Long,
        predicate: (AgentAccessibilityService.NodeSnapshot) -> Boolean,
    ): AgentAccessibilityService.NodeSnapshot? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        do {
            if (isCancelled()) return null
            val snapshot = service.captureNodeSnapshot(MAX_NODES)
            if (snapshot != null && predicate(snapshot)) return snapshot
            SystemClock.sleep(POLL_MS)
        } while (SystemClock.elapsedRealtime() < deadline)
        return service.captureNodeSnapshot(MAX_NODES)?.takeIf(predicate)
    }

    private fun sensitiveOk(): JSONObject =
        JSONObject().put("ok", true).put("tool", "send_message")

    private fun JSONObject.toToolResult(): AgentModelClient.ToolResult =
        AgentModelClient.ToolResult(content = toString(), sensitive = true)

    private fun sensitiveError(code: String, message: String): AgentModelClient.ToolResult =
        AgentModelClient.ToolResult(
            content = JSONObject()
                .put("ok", false)
                .put("code", code)
                .put("message", message)
                .toString(),
            sensitive = true,
        )

    private companion object {
        const val WECHAT_PACKAGE = "com.tencent.mm"
        const val SEARCH_TEXT = "搜索"
        const val SEND_TEXT = "发送"
        const val MAX_NODES = 120
        const val MAX_BACK_ATTEMPTS = 5
        const val PACKAGE_TIMEOUT_MS = 6_000L
        const val CONTENT_TIMEOUT_MS = 6_000L
        const val SEND_VERIFY_TIMEOUT_MS = 3_000L
        const val POLL_MS = 160L
        const val STEP_SETTLE_MS = 300L
        const val SAME_ROW_TOLERANCE_PX = 24
    }
}
