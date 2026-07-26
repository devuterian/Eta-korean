package fuck.andes.agent.model

import fuck.andes.agent.runtime.AgentEvent
import fuck.andes.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject

/**
 * 单次 Agent run 的纯编排循环。
 *
 * 轮次边界参考 pi-agent-core：一次 assistant 响应及其完整工具批次构成一个 turn；
 * steering 只在 turn 结束后注入，不能用取消网络或关闭工具资源来模拟。
 */
internal class AgentLoop(
    private val config: AgentModelClient.ModelConfig,
    private val messages: JSONArray,
    private val tools: JSONArray,
    private val provider: AgentProviderClient,
    private val toolExecutor: AgentModelClient.ToolExecutor,
    private val runController: AgentRunController,
    private val traceFormatter: AgentTraceFormatter,
    private val onEvent: (AgentEvent) -> Unit,
    private val limits: Limits = Limits(),
) {
    data class Limits(
        val maxRounds: Int = 64,
        val maxToolCalls: Int = 256,
    ) {
        init {
            require(maxRounds > 0) { "maxRounds must be positive" }
            require(maxToolCalls > 0) { "maxToolCalls must be positive" }
        }
    }

    data class Result(
        val content: String,
        val reasoningContent: String,
        val sensitiveToolCallIds: Set<String>,
    )

    private data class ToolOutcome(
        val call: AgentModelClient.ToolCall,
        val result: AgentModelClient.ToolResult,
    )

    private val toolCallValidator = AgentToolCallValidator(tools)
    private val accumulatedReasoning = StringBuilder()
    private val sensitiveToolCallIds = linkedSetOf<String>()
    private var pendingToolImageMessage: JSONObject? = null

    fun reasoningSnapshot(): String = accumulatedReasoning.toString().trim()

    fun sensitiveToolCallIdsSnapshot(): Set<String> = sensitiveToolCallIds.toSet()

    fun run(): Result {
        var round = 1
        var seenToolCalls = 0

        while (true) {
            checkRoundLimit(round)
            runController.throwIfCancelled()
            appendPendingSteeringMessage()
            onEvent(AgentEvent.RoundStarted(round = round, messageCount = messages.length()))

            val reasoningLengthBeforeRound = accumulatedReasoning.length
            val providerResponse = try {
                provider.complete(
                    request = ProviderRequest(
                        config = config,
                        messages = messages,
                        tools = tools,
                    ),
                    runController = runController,
                ) { providerEvent ->
                    if (
                        providerEvent is ProviderEvent.BlockDelta &&
                        providerEvent.kind == AssistantBlockKind.THINKING
                    ) {
                        accumulatedReasoning.append(providerEvent.delta)
                    }
                    providerEvent.toAgentEvent(round)?.let(onEvent)
                }
            } finally {
                // 截图只供紧接着的一次推理消费；成功、失败或取消后都不进入后续上下文与归档。
                discardPendingToolImageMessage()
            }

            runController.throwIfCancelled()
            val assistantMessage = providerResponse.assistantMessage
            val toolCalls = AgentConversationCodec.parseToolCalls(assistantMessage)
            val assistantReasoning = assistantMessage.optString("reasoning_content")
            if (
                assistantReasoning.isNotBlank() &&
                accumulatedReasoning.length == reasoningLengthBeforeRound
            ) {
                accumulatedReasoning.append(assistantReasoning)
            }

            messages.put(
                AgentConversationCodec.assistantHistoryMessage(
                    source = assistantMessage,
                    toolCalls = toolCalls,
                )
            )
            onEvent(
                AgentEvent.AssistantReceived(
                    round = round,
                    contentChars = assistantMessage.optString("content").length,
                    reasoningContent = assistantReasoning,
                    toolNames = toolCalls.map { it.name },
                )
            )

            if (toolCalls.isNotEmpty()) {
                if (seenToolCalls + toolCalls.size > limits.maxToolCalls) {
                    appendToolOutcomes(
                        round = round,
                        outcomes = toolCalls.map { call ->
                            rejectedToolOutcome(
                                round = round,
                                toolCall = call,
                                code = "TOOL_CALL_LIMIT_EXCEEDED",
                                message = "에이전트 도구 호출 횟수가 안전 한도 ${limits.maxToolCalls}를 초과했습니다. 이번 호출은 실행되지 않았습니다.",
                            )
                        },
                    )
                    error("에이전트 도구 호출 횟수가 안전 한도 ${limits.maxToolCalls}를 초과했습니다.")
                }
                if (round >= limits.maxRounds) {
                    appendToolOutcomes(
                        round = round,
                        outcomes = toolCalls.map { call ->
                            rejectedToolOutcome(
                                round = round,
                                toolCall = call,
                                code = "ROUND_LIMIT_EXCEEDED",
                                message = "에이전트가 최대 라운드 ${limits.maxRounds}에 도달했습니다. 이번 호출은 실행되지 않았습니다.",
                            )
                        },
                    )
                    error("에이전트가 최대 라운드 ${limits.maxRounds}에 도달하여 도구 결과를 처리할 수 없으므로 이번 도구 호출은 실행되지 않았습니다.")
                }
                val outcomes = when (providerResponse.stopReason) {
                    AssistantStopReason.TOOL_USE ->
                        toolCalls.map { call -> executeTool(round, call) }
                    AssistantStopReason.OUTPUT_LIMIT ->
                        toolCalls.map { call ->
                            rejectedToolOutcome(
                                round = round,
                                toolCall = call,
                                code = "TRUNCATED_TOOL_CALL",
                                message = "모델 출력이 길이 제한에 도달하여 도구 파라미터가 불완전할 수 있습니다. 이번 호출은 실행되지 않았으니, 전체 파라미터를 다시 제출하세요.",
                            )
                        }
                    else ->
                        toolCalls.map { call ->
                            rejectedToolOutcome(
                                round = round,
                                toolCall = call,
                                code = "UNEXPECTED_TOOL_CALL",
                                message = "모델이 ${providerResponse.stopReason.name} 종료 상태에서 도구 호출을 반환했습니다." +
                                    "이번 호출은 실행되지 않았습니다. 다시 계획하세요.",
                            )
                        }
                }
                seenToolCalls += toolCalls.size
                appendToolOutcomes(round, outcomes)
                round += 1
                continue
            }

            // assistant 已自然结束时再检查 steering。这样补充消息不会丢掉刚完成的回答。
            if (appendPendingSteeringOrSeal()) {
                round += 1
                continue
            }

            val content = assistantMessage.optString("content").trim()
            if (content.isBlank() || content == "null") {
                val finishReason = assistantMessage.optString("finish_reason")
                error("모델 인터페이스 $round번째 라운드 결과가 비어 있습니다${finishReason.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}.")
            }

            onEvent(AgentEvent.RunFinished(round = round, contentChars = content.length))
            return Result(
                content = content,
                reasoningContent = reasoningSnapshot(),
                sensitiveToolCallIds = sensitiveToolCallIds.toSet(),
            )
        }
    }

    private fun checkRoundLimit(round: Int) {
        if (round > limits.maxRounds) {
            error("에이전트 라운드가 안전 한도 ${limits.maxRounds}를 초과했습니다.")
        }
    }

    private fun appendPendingSteeringMessage(): Boolean {
        val supplement = runController.pollSteeringMessage() ?: return false
        messages.put(AgentConversationCodec.userTextMessage(steeringPrompt(supplement)))
        return true
    }

    private fun appendPendingSteeringOrSeal(): Boolean {
        val supplement = runController.pollSteeringOrSeal() ?: return false
        messages.put(AgentConversationCodec.userTextMessage(steeringPrompt(supplement)))
        return true
    }

    private fun steeringPrompt(supplement: String): String =
        "사용자 추가 지시: $supplement\n\n현재 작업 컨텍스트를 기반으로 계속 진행하세요. 이미 완료하거나 검증된 작업을 처음부터 반복하지 마세요."

    private fun executeTool(
        round: Int,
        toolCall: AgentModelClient.ToolCall,
    ): ToolOutcome {
        runController.throwIfCancelled()
        toolCallValidator.validate(toolCall)?.let { validationError ->
            return rejectedToolOutcome(
                round = round,
                toolCall = toolCall,
                code = "INVALID_TOOL_ARGUMENTS",
                message = validationError,
            )
        }
        onEvent(
            AgentEvent.ToolStarted(
                round = round,
                toolCallId = toolCall.id,
                name = toolCall.name,
                argsPreview = traceFormatter.summarizeArguments(toolCall),
            )
        )

        val result = try {
            toolExecutor.execute(toolCall)
        } catch (throwable: Exception) {
            runController.throwIfCancelled()
            AgentModelClient.ToolResult(
                content = JSONObject()
                    .put("ok", false)
                    .put("code", "TOOL_ERROR")
                    .put("message", throwable.message ?: throwable.javaClass.simpleName)
                    .toString(),
            )
        }
        if (result.sensitive || AgentSensitiveToolPolicy.isSensitive(toolCall.name)) {
            sensitiveToolCallIds += toolCall.id
        }

        runController.throwIfCancelled()
        emitToolFinished(round, toolCall, result)
        return ToolOutcome(toolCall, result)
    }

    private fun rejectedToolOutcome(
        round: Int,
        toolCall: AgentModelClient.ToolCall,
        code: String,
        message: String,
    ): ToolOutcome {
        onEvent(
            AgentEvent.ToolStarted(
                round = round,
                toolCallId = toolCall.id,
                name = toolCall.name,
                argsPreview = traceFormatter.summarizeArguments(toolCall),
            )
        )
        val result = AgentModelClient.ToolResult(
            content = JSONObject()
                .put("ok", false)
                .put("code", code)
                .put("message", message)
                .toString(),
            sensitive = AgentSensitiveToolPolicy.isSensitive(toolCall.name),
        )
        if (result.sensitive) sensitiveToolCallIds += toolCall.id
        emitToolFinished(round, toolCall, result)
        return ToolOutcome(toolCall, result)
    }

    private fun emitToolFinished(
        round: Int,
        toolCall: AgentModelClient.ToolCall,
        result: AgentModelClient.ToolResult,
    ) {
        onEvent(
            AgentEvent.ToolFinished(
                round = round,
                toolCallId = toolCall.id,
                name = toolCall.name,
                resultSummary = traceFormatter.summarizeResult(toolCall.name, result),
                imageCount = result.images.size,
                imageBytes = result.images.sumOf { it.bytes },
            )
        )
    }

    private fun appendToolOutcomes(
        round: Int,
        outcomes: List<ToolOutcome>,
    ) {
        // Provider 要求同一 assistant 批次的全部 tool result 连续出现；图片观察统一放在批次之后。
        outcomes.forEach { outcome ->
            messages.put(AgentConversationCodec.toolResultMessage(outcome.call, outcome.result))
        }

        val imageOutcomes = outcomes.filter { outcome -> outcome.result.images.isNotEmpty() }
        if (imageOutcomes.isEmpty()) return

        // 工具截图是瞬时观察，不是会话资产。下一次推理消费后立即删除。
        discardPendingToolImageMessage()
        val images = imageOutcomes.flatMap { outcome -> outcome.result.images }
        val toolNames = imageOutcomes
            .map { outcome -> outcome.call.name }
            .distinct()
            .joinToString(", ")
        pendingToolImageMessage = AgentConversationCodec.userMessage(
            text = "Latest observation image(s) returned by tool(s): $toolNames.",
            images = images,
        ).also(messages::put)

        imageOutcomes.forEach { outcome ->
            onEvent(
                AgentEvent.ToolImagesAttached(
                    round = round,
                    toolName = outcome.call.name,
                    imageCount = outcome.result.images.size,
                    imageBytes = outcome.result.images.sumOf { it.bytes },
                )
            )
        }
    }

    private fun discardPendingToolImageMessage() {
        val pending = pendingToolImageMessage ?: return
        pendingToolImageMessage = null
        for (index in messages.length() - 1 downTo 0) {
            if (messages.optJSONObject(index) === pending) {
                messages.remove(index)
                return
            }
        }
    }

    private fun ProviderEvent.toAgentEvent(round: Int): AgentEvent? =
        when (this) {
            ProviderEvent.RequestStarted -> AgentEvent.ProviderRequestStarted(round)
            is ProviderEvent.ResponseHeaders -> AgentEvent.ProviderResponseStarted(round, httpCode)
            is ProviderEvent.BlockStart -> AgentEvent.AssistantBlockStart(
                round = round,
                kind = kind.toRuntimeKind(),
                index = index,
                blockId = blockId,
                name = name,
            )
            is ProviderEvent.BlockDelta -> AgentEvent.AssistantBlockDelta(
                round = round,
                kind = kind.toRuntimeKind(),
                index = index,
                deltaChars = delta.length,
                delta = delta,
            )
            is ProviderEvent.BlockEnd -> AgentEvent.AssistantBlockEnd(
                round = round,
                kind = kind.toRuntimeKind(),
                index = index,
                blockId = blockId,
                name = name,
                contentChars = content.length,
            )
            is ProviderEvent.Usage -> AgentEvent.UsageReceived(round = round, usage = usage)
            is ProviderEvent.Completed -> null
        }

    private fun AssistantBlockKind.toRuntimeKind(): AgentEvent.AssistantBlockKind =
        when (this) {
            AssistantBlockKind.TEXT -> AgentEvent.AssistantBlockKind.TEXT
            AssistantBlockKind.THINKING -> AgentEvent.AssistantBlockKind.THINKING
            AssistantBlockKind.TOOL_CALL -> AgentEvent.AssistantBlockKind.TOOL_CALL
        }

}
