package fuck.andes.agent.overlay

import androidx.compose.runtime.Immutable
import fuck.andes.agent.runtime.AgentEvent

/** Agent 浮窗所处的阶段。 */
internal enum class AgentOverlayPhase { RUNNING, PAUSED, FINISHED, FAILED }

/**
 * Agent 浮窗的渲染状态。由 [AgentEvent] 流累积而来，[AgentOverlayBubble] 直接消费。
 */
@Immutable
internal data class AgentOverlayState(
    val phase: AgentOverlayPhase = AgentOverlayPhase.RUNNING,
    val round: Int = 0,
    val statusText: String = "준비 중…",
    val detailText: String = "",
) {
    companion object {
        val Initial = AgentOverlayState(statusText = "명령을 받아 모델 호출 준비 중")
    }
}

/**
 * 将一个 [AgentEvent] 折叠进当前渲染状态。
 *
 * 文案逻辑只保留面向用户的一句话状态，
 * 工具名经 [toToolLabel] 中文化。详细 trace 流作为后续任务，此处不展开。
 */
internal fun AgentOverlayState.applyEvent(event: AgentEvent): AgentOverlayState = when (event) {
    is AgentEvent.RunStarted -> copy(
        phase = AgentOverlayPhase.RUNNING,
        statusText = "도구 준비 중: ${event.toolCount}개",
        detailText = "",
    )

    is AgentEvent.RoundStarted -> copy(
        phase = AgentOverlayPhase.RUNNING,
        round = event.round,
        statusText = "${event.round}번째 라운드 생각 중",
    )

    is AgentEvent.ProviderRequestStarted -> copy(
        phase = AgentOverlayPhase.RUNNING,
        round = event.round,
        statusText = "모델 요청 중",
    )

    is AgentEvent.ProviderResponseStarted -> copy(
        phase = AgentOverlayPhase.RUNNING,
        round = event.round,
        statusText = "모델이 응답했습니다",
    )

    is AgentEvent.AssistantBlockStart -> when (event.kind) {
        AgentEvent.AssistantBlockKind.TEXT,
        AgentEvent.AssistantBlockKind.THINKING -> this

        AgentEvent.AssistantBlockKind.TOOL_CALL -> copy(
            phase = AgentOverlayPhase.RUNNING,
            round = event.round,
            statusText = "도구 파라미터 생성 중",
        )
    }

    is AgentEvent.AssistantBlockDelta -> when (event.kind) {
        AgentEvent.AssistantBlockKind.TEXT -> appendStreamingText(event)
        AgentEvent.AssistantBlockKind.THINKING -> copy(
            phase = AgentOverlayPhase.RUNNING,
            round = event.round,
            statusText = "생각 중입니다.",
        )

        AgentEvent.AssistantBlockKind.TOOL_CALL -> copy(
            phase = AgentOverlayPhase.RUNNING,
            round = event.round,
            statusText = "도구 파라미터 생성 중",
        )
    }

    is AgentEvent.AssistantBlockEnd -> this

    is AgentEvent.AssistantReceived -> copy(
        phase = AgentOverlayPhase.RUNNING,
        round = event.round,
        statusText = if (event.toolNames.isEmpty()) {
            "답변 정리 중입니다."
        } else {
            "실행 예정: ${event.toolNames.joinToString("、") { it.toToolLabel() }}"
        },
    )

    is AgentEvent.UsageReceived -> this

    is AgentEvent.UserSupplementReceived -> copy(
        phase = AgentOverlayPhase.RUNNING,
        statusText = "추가 내용 수신됨, 계속 진행합니다.",
        detailText = "",
    )

    is AgentEvent.ToolStarted -> copy(
        phase = AgentOverlayPhase.RUNNING,
        round = event.round,
        statusText = "도구 실행: ${event.name.toToolLabel()}",
    )

    is AgentEvent.ToolFinished -> copy(
        phase = AgentOverlayPhase.RUNNING,
        round = event.round,
        statusText = "도구 완료: ${event.name.toToolLabel()}",
    )

    is AgentEvent.ToolImagesAttached -> copy(
        phase = AgentOverlayPhase.RUNNING,
        round = event.round,
        statusText = "이미지 ${event.imageCount}장 읽음",
    )

    is AgentEvent.RunFinished -> copy(
        phase = AgentOverlayPhase.FINISHED,
        round = event.round,
        statusText = "결과가 반환되었습니다",
    )

    is AgentEvent.RunFailed -> copy(
        phase = AgentOverlayPhase.FAILED,
        statusText = "호출 실패",
        detailText = event.reason,
    )
}

/**
 * 工具原始名 -> 中文标签，供渲染层共用。
 */
private fun String.toToolLabel(): String = when (this) {
    "observe_screen" -> "화면 확인"
    "tap" -> "탭"
    "tap_element" -> "요소 탭"
    "long_press" -> "길게 누르기"
    "long_press_element" -> "요소 길게 누르기"
    "swipe" -> "스와이프"
    "scroll" -> "스크롤"
    "scroll_element" -> "요소 스크롤"
    "input_text" -> "텍스트 입력"
    "replace_text" -> "텍스트 바꾸기"
    "clear_text" -> "텍스트 지우기"
    "set_clipboard" -> "클립보드에 쓰기"
    "get_clipboard" -> "클립보드 읽기"
    "paste_text" -> "텍스트 붙여넣기"
    "press_key" -> "버튼"
    "wait" -> "대기"
    "wait_for_text" -> "텍스트 대기"
    "wait_for_package" -> "앱 대기"
    "open_system_panel" -> "시스템 패널"
    "search_apps" -> "앱 검색"
    "launch_app" -> "앱 열기"
    "open_uri" -> "앱으로 열기"
    "browser_use" -> "웹 탐색"
    "terminal" -> "터미널"
    "run_command" -> "명령 실행"
    "read_file" -> "파일 읽기"
    "write_file" -> "파일 쓰기"
    "list_directory" -> "디렉터리 목록 보기"
    else -> this
}

private const val MaxStreamingPreviewChars = 320

private fun AgentOverlayState.appendStreamingText(event: AgentEvent.AssistantBlockDelta): AgentOverlayState {
    val nextPreview = (detailText + event.delta)
        .trimStart()
        .take(MaxStreamingPreviewChars)
    return copy(
        phase = AgentOverlayPhase.RUNNING,
        round = event.round,
        statusText = "답변 생성 중",
        detailText = nextPreview,
    )
}

/**
 * 面向用户的副状态文案，由阶段派生，供底部任务卡片展示。
 */
internal val AgentOverlayState.subStatusText: String
    get() = when (phase) {
        AgentOverlayPhase.RUNNING -> "지능형 실행 중"
        AgentOverlayPhase.PAUSED -> "일시 중지됨, 클릭하면 계속됩니다"
        AgentOverlayPhase.FINISHED -> "완료됨"
        AgentOverlayPhase.FAILED -> "실행 실패"
    }
