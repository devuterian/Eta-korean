package fuck.andes.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** 文本输入、等待与系统操作工具 schema。 */
internal object AgentTextSystemToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools
            .put(
                AgentToolSchema.function(
                    name = "input_text",
                    description = "실제 입력 포커스를 가진 입력창에 최대 1000자까지 텍스트를 입력합니다. 기본 mode=append로 현재 커서 위치에 삽입하거나 선택 영역을 대체합니다. 비가시 입력창(비밀번호 등)은 재구성 거부될 수 있으니, 전체 값을 입력하려면 replace_text를 사용하세요.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "text",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 1_000)
                                        .put("description", "입력할 텍스트(최대 1000자). 실제 입력 포커스 확인을 위해 접근성 서비스가 필요합니다.")
                                )
                                .put(
                                    "mode",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("append").put("replace").put("paste"))
                                        .put("description", "append: 커서 위치 입력 또는 선택 영역 대체, replace: 텍스트 전체 대체, paste: 붙여넣기 경로 사용. 세 가지 모드 모두 최대 1000자 제한. 기본값은 append입니다.")
                                )
                                .put(
                                    "index",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "mode=replace일 때 editable 노드 index를 지정할 수 있습니다. 반드시 같은 observe_screen의 observation_id도 함께 전달해야 합니다.")
                                )
                                .put(
                                    "observation_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "mode=replace이고 index를 지정할 경우 반드시 전달해야 하며, index는 최근 observe_screen에서 가져와야 합니다.")
                                )
                        )
                        .put("required", JSONArray().put("text"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "replace_text",
                    description = "현재 포커스된 입력창 또는 지정한 editable 노드의 텍스트를 주어진 내용으로 대체합니다. index를 지정할 경우 index와 observation_id는 최근 observe_screen에서 가져와야 하며, 만료된 경우 다시 관찰하세요. 접근성 서비스가 필요합니다.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "text",
                                    JSONObject().put("type", "string").put("maxLength", 4_000),
                                )
                                .put(
                                    "index",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "선택 사항: 최근 observe_screen의 editable 노드 index. 전달 시 같은 관찰의 observation_id도 함께 입력해야 하며, 미입력 시 현재 포커스된 입력창을 사용합니다.")
                                )
                                .put(
                                    "observation_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "index를 지정할 경우 반드시 전달해야 하며, index는 최근 observe_screen에서 가져와야 합니다. 미지정 시 생략하세요.")
                                )
                        )
                        .put("required", JSONArray().put("text"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "clear_text",
                    description = "현재 포커스된 입력창 또는 지정한 editable 노드를 비웁니다. index를 지정할 경우 index와 observation_id는 최근 observe_screen에서 가져와야 하며, 만료된 경우 다시 관찰하세요. 접근성 서비스가 필요합니다.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "index",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "선택 사항: 최근 observe_screen의 editable 노드 index. 전달 시 같은 관찰의 observation_id도 함께 입력해야 하며, 미입력 시 현재 포커스된 입력창을 사용합니다.")
                                )
                                .put(
                                    "observation_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "index를 지정할 경우 반드시 전달해야 하며, index는 최근 observe_screen에서 가져와야 합니다. 미지정 시 생략하세요.")
                                )
                        )
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "set_clipboard",
                    description = "텍스트를 시스템 클립보드에 저장합니다. 긴 텍스트, 한글, 이모지, 특수 문자 붙여넣기에 적합합니다.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "text",
                                    JSONObject().put("type", "string").put("maxLength", 20_000),
                                )
                        )
                        .put("required", JSONArray().put("text"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "get_clipboard",
                    description = "시스템 클립보드의 텍스트를 읽습니다. Android 버전이나 백그라운드 제한으로 인해 읽기에 실패할 수 있습니다.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put("properties", JSONObject())
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "paste_text",
                    description = "실제 입력 포커스를 확인한 후 현재 선택 영역에 긴 텍스트를 입력합니다. 대상이 직접 설정을 지원하지 않을 때만 시스템 클립보드 붙여넣기로 대체합니다. 포커스가 없으면 클립보드는 덮어쓰지 않습니다. 비밀번호 등 읽을 수 없는 필드는 replace_text로 전체 값을 입력하세요.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "text",
                                    JSONObject().put("type", "string").put("maxLength", 20_000),
                                )
                        )
                        .put("required", JSONArray().put("text"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "press_key",
                    description = "시스템 키 또는 글로벌 동작을 실행합니다. BACK/HOME/RECENTS/NOTIFICATIONS/QUICK_SETTINGS는 접근성 글로벌 동작을 우선 사용합니다. ENTER는 입력기 엔터를 우선 사용합니다.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "button",
                                    JSONObject()
                                        .put("type", "string")
                                        .put(
                                            "enum",
                                            JSONArray()
                                                .put("BACK")
                                                .put("HOME")
                                                .put("ENTER")
                                                .put("RECENTS")
                                                .put("PASTE")
                                                .put("NOTIFICATIONS")
                                                .put("QUICK_SETTINGS")
                                        )
                                )
                        )
                        .put("required", JSONArray().put("button"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "wait",
                    description = "잠시 대기하여 애니메이션, 네트워크 로딩 또는 페이지 이동이 완료되도록 합니다. wait_for_text/wait_for_package의 검증 대기 대신 사용하지 마세요.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "duration_ms",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "대기 시간: 100~30000, 기본값 1000입니다.")
                                )
                        )
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "wait_for_text",
                    description = "현재 화면에 지정된 텍스트나 설명이 나타날 때까지 대기합니다. 클릭 후 페이지 도착, 목록 로딩 완료, 팝업 등장 확인에 적합합니다.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("text", JSONObject().put("type", "string"))
                                .put(
                                    "timeout_ms",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "최대 대기 시간: 500~60000, 기본값 10000입니다.")
                                )
                                .put(
                                    "include_desc",
                                    JSONObject()
                                        .put("type", "boolean")
                                        .put("description", "content-desc 매칭 여부, 기본값 true입니다.")
                                )
                                .put(
                                    "match",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("contains").put("exact").put("prefix").put("regex"))
                                        .put("description", "매칭 방식, 기본값 contains입니다.")
                                )
                        )
                        .put("required", JSONArray().put("text"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "wait_for_package",
                    description = "지정한 Android 패키지가 포그라운드에 올 때까지 대기합니다. launch_app/open_uri 후 대상 앱이 열렸는지 확인할 때 사용하세요.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("package_name", JSONObject().put("type", "string"))
                                .put(
                                    "timeout_ms",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "최대 대기 시간: 500~60000, 기본값 10000입니다.")
                                )
                        )
                        .put("required", JSONArray().put("package_name"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "open_system_panel",
                    description = "알림 바 또는 빠른 설정 패널을 엽니다.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "panel",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("notifications").put("quick_settings"))
                                )
                        )
                        .put("required", JSONArray().put("panel"))
                )
            )
    }
}
