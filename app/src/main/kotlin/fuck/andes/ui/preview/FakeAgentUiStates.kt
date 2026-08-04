package fuck.andes.ui.preview

import fuck.andes.ui.model.AgentChatHomeUiState
import fuck.andes.ui.model.AgentChatMessageUi
import fuck.andes.ui.model.AgentChatUiState
import fuck.andes.ui.model.AgentSystemEnhanceUiState
import fuck.andes.ui.model.AgentToolsUiState
import fuck.andes.ui.model.ConversationModeUi
import fuck.andes.ui.model.ConversationPaneUiState
import fuck.andes.ui.model.ConversationSummaryUi
import fuck.andes.ui.model.PermissionHealthItemUi
import fuck.andes.ui.model.PermissionHealthUiState
import fuck.andes.ui.model.PermissionStatusUi
import fuck.andes.ui.model.SystemEnhanceItemUi
import fuck.andes.ui.model.SystemEnhanceSectionUi
import fuck.andes.ui.model.SystemEnhanceStatusUi
import fuck.andes.ui.model.ThinkingMessageUi
import fuck.andes.ui.model.TokenUsageUi
import fuck.andes.ui.model.ToolActivityMessageUi
import fuck.andes.ui.model.ToolActivityStatusUi
import fuck.andes.ui.model.ToolGroupUi
import fuck.andes.ui.model.ToolItemUi
import fuck.andes.ui.model.UserMessageUi
import fuck.andes.ui.model.AgentMessageUi

internal object FakeAgentUiStates {

    val conversations = ConversationPaneUiState(
        selectedConversationId = "c-001",
        searchQuery = "",
        conversations = listOf(
            ConversationSummaryUi(
                id = "c-001",
                title = "Eta로 휴대폰 조작하기",
                preview = "화면과 도구를 제어할 준비가 되었습니다. 다음 작업을 기다립니다.",
                timeLabel = "지금",
                mode = ConversationModeUi.PhoneAgent,
                isPinned = true,
                isActiveRun = true,
            ),
            ConversationSummaryUi(
                id = "c-002",
                title = "오늘 일정과 알림",
                preview = "날씨를 확인하고 일정을 동기화한 뒤 외출 알림 설정",
                timeLabel = "10:41",
                mode = ConversationModeUi.Chat,
                isPinned = true,
            ),
            ConversationSummaryUi(
                id = "c-003",
                title = "NetEase Cloud Music을 열어 오늘의 추천 재생",
                preview = "도구 호출 4개 완료 · 8초 소요",
                timeLabel = "10:23",
                mode = ConversationModeUi.PhoneAgent,
            ),
            ConversationSummaryUi(
                id = "c-004",
                title = "현재 화면 캡처 분석",
                preview = "화면 구조를 요약하고 버튼 계층 문제 확인",
                timeLabel = "어제",
                mode = ConversationModeUi.Chat,
            ),
            ConversationSummaryUi(
                id = "c-005",
                title = "Alpine 터미널 환경 확인",
                preview = "사용 가능한 명령, Python/Node 버전, 백그라운드 작업 확인",
                timeLabel = "금요일",
                mode = ConversationModeUi.Terminal,
            ),
            ConversationSummaryUi(
                id = "c-006",
                title = "매일 밤 다운로드 폴더 정리",
                preview = "정기적으로 파일을 검사해 이미지, 설치 파일, 문서 분류",
                timeLabel = "5-18",
                mode = ConversationModeUi.Automation,
            ),
        ),
    )

    val chatHome: AgentChatHomeUiState = AgentChatHomeUiState(
        messages = emptyList(),
        input = "",
        isStreaming = false,
        thinkingEnabled = false,
    )

    val chat = AgentChatUiState(
        messages = listOf(
            UserMessageUi(
                id = "m-01",
                content = "설정의 배터리 최적화 열기",
            ),
            AgentMessageUi(
                id = "m-02",
                content = "현재 휴대폰 화면은 **홈 화면**입니다. 주요 정보는 다음과 같습니다.\n\n| 항목 | 내용 |\n| --- | --- |\n| 시간 | 09:55 |\n| 네트워크 | 5G + Wi-Fi |\n| 배터리 | 68% |",
                usage = TokenUsageUi(
                    contextTokens = 17100,
                    inputTokens = 9000,
                    outputTokens = 111,
                    reasoningTokens = 8200,
                ),
            ),
            ThinkingMessageUi(
                id = "thinking-01",
                content = "사용자가 현재 화면 확인을 요청했습니다. 먼저 observe_screen으로 화면 구조를 가져온 뒤 보이는 정보를 요약해야 합니다.",
                isStreaming = false,
                elapsedSeconds = 24,
                collapsed = true,
            ),
            ToolActivityMessageUi(
                id = "tools-01",
                toolName = "observe_screen",
                status = ToolActivityStatusUi.Success,
                argumentsSummary = "{\"include_screenshot\":true}",
                resultSummary = "ok=true, chars=1820, images=1",
                imageCount = 1,
            ),
        ),
        input = "",
        isStreaming = false,
        thinkingEnabled = true,
    )

    val permissionHealth = PermissionHealthUiState(
        items = listOf(
            PermissionHealthItemUi(
                id = "accessibility",
                title = "접근성 서비스",
                summary = "활성화됨. 에이전트가 UI를 조작할 수 있음",
                status = PermissionStatusUi.Available,
                primaryActionLabel = null,
            ),
            PermissionHealthItemUi(
                id = "overlay",
                title = "다른 앱 위에 표시 권한",
                summary = "허용됨. 실행 오버레이를 표시할 수 있음",
                status = PermissionStatusUi.Available,
                primaryActionLabel = null,
            ),
            PermissionHealthItemUi(
                id = "notification",
                title = "알림 권한",
                summary = "백그라운드 작업 완료 알림에 사용",
                status = PermissionStatusUi.Available,
                primaryActionLabel = null,
            ),
            PermissionHealthItemUi(
                id = "location",
                title = "위치 권한",
                summary = "에이전트가 도구를 호출할 때만 읽습니다.",
                status = PermissionStatusUi.Available,
                primaryActionLabel = null,
            ),
            PermissionHealthItemUi(
                id = "root",
                title = "Root 권한",
                summary = "허용되지 않음. 일부 터미널 명령이 제한됨",
                status = PermissionStatusUi.Warning,
                primaryActionLabel = "확인",
            ),
            PermissionHealthItemUi(
                id = "shizuku",
                title = "Shizuku",
                summary = "설정되지 않음. ADB 수준 기능을 사용할 수 없음",
                status = PermissionStatusUi.Disabled,
                primaryActionLabel = "설정",
            ),
            PermissionHealthItemUi(
                id = "xposed",
                title = "Hook / Xposed",
                summary = "프레임워크가 활성화되지 않아 시스템 강화 기능을 사용할 수 없음",
                status = PermissionStatusUi.Missing,
                primaryActionLabel = "보기",
            ),
            PermissionHealthItemUi(
                id = "background",
                title = "백그라운드 유지",
                summary = "배터리 최적화가 켜져 있어 긴 작업이 중단될 수 있음",
                status = PermissionStatusUi.Warning,
                primaryActionLabel = "설정",
            ),
        ),
    )

    val tools = AgentToolsUiState(
        groups = listOf(
            ToolGroupUi(
                id = "screen",
                title = "화면 조작",
                tools = listOf(
                    ToolItemUi("observe", "화면 확인", "현재 화면을 캡처하고 설명"),
                    ToolItemUi("click", "탭", "지정한 좌표 또는 요소 탭"),
                    ToolItemUi("long_press", "길게 누르기", "지정한 요소 길게 누르기"),
                    ToolItemUi("swipe", "스와이프", "스와이프, 스크롤, 뒤로 가기 등 제스처"),
                ),
            ),
            ToolGroupUi(
                id = "input",
                title = "입력과 키",
                tools = listOf(
                    ToolItemUi("input_text", "텍스트 입력", "포커스 위치에 텍스트 입력"),
                    ToolItemUi("clipboard", "클립보드", "클립보드 읽기 또는 쓰기"),
                    ToolItemUi("wait_text", "텍스트 대기", "화면에 지정한 텍스트가 나타날 때까지 대기"),
                ),
            ),
            ToolGroupUi(
                id = "web",
                title = "웹 탐색",
                tools = listOf(
                    ToolItemUi("browser_use", "에이전트 브라우저", "오프스크린 탐색 및 인계 가능한 세션 유지"),
                    ToolItemUi("browser_read", "웹페이지 읽기", "렌더링된 웹페이지 본문 추출"),
                    ToolItemUi("browser_interact", "웹페이지 조작", "요소 검색, 탭, 입력"),
                    ToolItemUi("browser_screenshot", "페이지 스크린샷", "웹페이지 뷰포트를 비전 모델에 전달"),
                ),
            ),
            ToolGroupUi(
                id = "app",
                title = "앱과 URI",
                tools = listOf(
                    ToolItemUi("get_current_context", "시간 및 위치", "시스템 시간과 최근 위치를 읽습니다."),
                    ToolItemUi("open_app", "앱 열기", "패키지명으로 앱 실행"),
                    ToolItemUi("open_uri", "앱으로 열기", "외부 앱에 명시적으로 전달"),
                ),
            ),
            ToolGroupUi(
                id = "terminal",
                title = "터미널 및 파일",
                tools = listOf(
                    ToolItemUi("terminal", "터미널 명령", "Android/Alpine 환경에서 Shell 실행"),
                    ToolItemUi("terminal_job", "백그라운드 작업", "비동기 작업과 출력 읽기"),
                ),
            ),
        ),
    )

    val systemEnhance = AgentSystemEnhanceUiState(
        sections = listOf(
            SystemEnhanceSectionUi(
                id = "status",
                title = "상태",
                items = listOf(
                    SystemEnhanceItemUi(
                        id = "hook",
                        title = "훅 상태",
                        summary = "프레임워크 비활성",
                        status = SystemEnhanceStatusUi.Inactive,
                    ),
                    SystemEnhanceItemUi(
                        id = "root",
                        title = "Root 상태",
                        summary = "root 권한을 받지 못했습니다.",
                        status = SystemEnhanceStatusUi.Inactive,
                    ),
                    SystemEnhanceItemUi(
                        id = "shizuku",
                        title = "Shizuku 상태",
                        summary = "페어링되지 않음",
                        status = SystemEnhanceStatusUi.Unsupported,
                    ),
                ),
            ),
            SystemEnhanceSectionUi(
                id = "capabilities",
                title = "강화 가능한 기능",
                items = listOf(
                    SystemEnhanceItemUi(
                        id = "power_key",
                        title = "전원 버튼을 길게 눌러 실행",
                        summary = "훅 프레임워크 필요",
                        status = SystemEnhanceStatusUi.Inactive,
                    ),
                    SystemEnhanceItemUi(
                        id = "assistant_replace",
                        title = "기본 어시스턴트 대체",
                        summary = "Root 또는 훅 지원 필요",
                        status = SystemEnhanceStatusUi.Inactive,
                    ),
                ),
            ),
        ),
    )
}
