package fuck.andes.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** 上下文、应用入口与屏幕观察工具 schema。 */
internal object AgentContextAppToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools
            .put(
                AgentToolSchema.function(
                    name = "get_current_context",
                    description = "휴대폰의 현재 시간, 시간대, 최근 시스템 위치를 가져옵니다. 지금, 오늘, 내일 또는 위치 관련 요청 시 호출됩니다.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put("properties", JSONObject())
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "search_apps",
                    description = "휴대폰에 설치된 Android 앱을 검색하여 앱 이름과 패키지명을 반환합니다. 앱을 열기 전에 패키지명이 확실하지 않으면 먼저 이 도구를 사용하세요.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "query",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "앱 이름 또는 패키지명 일부(예: QQ, WeChat, com.tencent)")
                                )
                                .put(
                                    "include_system",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "시스템 앱 포함 여부입니다. 기본값은 false입니다.")
                                )
                                .put(
                                    "limit",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "최대 1~20개 결과를 반환합니다. 기본값은 10입니다.")
                                )
                        )
                        .put("required", JSONArray().put("query"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "launch_app",
                    description = "설치된 Android 앱을 실행합니다. package_name을 우선 제공하세요. 앱 이름만 있을 경우 모호하게 매칭하며, 여러 개가 일치하면 후보만 반환하고 실행하지 않습니다.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "package_name",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "정확한 Android 패키지명(예: com.tencent.mobileqq)")
                                )
                                .put(
                                    "app_name",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "앱 표시 이름(예: QQ)")
                                )
                        )
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "open_uri",
                    description = "유효한 URI를 Android 외부 앱에 명시적으로 전달합니다(예: https, tel, geo 또는 앱 딥링크). 웹 페이지 읽기나 상호작용에는 사용하지 않습니다. URI를 임의로 만들지 마세요.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "uri",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "시스템에서 처리 가능한 유효한 URI")
                                )
                        )
                        .put("required", JSONArray().put("uri"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "observe_screen",
                    description = "현재 휴대폰 화면을 관찰하여 전면 앱, 화면 크기, observation_id, 보이는 UI 노드를 반환합니다. 노드 작업 시 observation_id를 그대로 사용해야 합니다. 트리가 잘릴 경우 max_nodes를 120으로 늘려서 다시 시도하세요.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "include_screenshot",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "현재 화면 캡처를 모델에 첨부할지 여부입니다. 기본값은 true입니다.")
                                )
                                .put(
                                    "include_ui_tree",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "UI 노드 목록을 반환할지 여부입니다. 기본값은 true입니다.")
                                )
                                .put(
                                    "max_nodes",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "최대 1~120개 UI 노드를 반환합니다. 기본값은 60입니다.")
                                )
                        )
                )
            )
    }
}
