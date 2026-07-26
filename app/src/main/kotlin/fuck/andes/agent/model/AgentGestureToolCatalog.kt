package fuck.andes.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** 屏幕手势与节点交互工具 schema。 */
internal object AgentGestureToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools
            .put(
                AgentToolSchema.function(
                    name = "tap",
                    description = "터치 좌표입니다. 기본적으로 최근 observe_screen 스크린샷의 픽셀 좌표를 사용합니다. 좌표가 ui_nodes의 center에서 온 경우 coordinate_space=screen으로 설정하세요.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("x", JSONObject().put("type", "integer"))
                                .put("y", JSONObject().put("type", "integer"))
                                .put("coordinate_space", AgentToolSchema.coordinateSpace())
                        )
                        .put("required", JSONArray().put("x").put("y"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "tap_area",
                    description = "사각형 영역의 중앙을 터치합니다. 기본적으로 최근 observe_screen 스크린샷의 픽셀 좌표를 사용하며, 큰 버튼, 큰 리스트 항목, 보이는 텍스트 영역에 우선 사용하세요.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("x1", JSONObject().put("type", "integer"))
                                .put("y1", JSONObject().put("type", "integer"))
                                .put("x2", JSONObject().put("type", "integer"))
                                .put("y2", JSONObject().put("type", "integer"))
                                .put("coordinate_space", AgentToolSchema.coordinateSpace())
                        )
                        .put("required", JSONArray().put("x1").put("y1").put("x2").put("y2"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "tap_element",
                    description = "지정된 관찰 스냅샷의 UI 노드를 터치합니다. index와 observation_id는 동일한 observe_screen에서 가져와야 하며, 만료 시 재관찰이 필요합니다. 실행 전 Eta 무장애 서비스 연결을 확인합니다.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "index",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "동일 observe_screen에서 반환된 UI 노드 index입니다.")
                                )
                                .put(
                                    "observation_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "index와 동일한 최근 observe_screen에서 가져온 observation_id입니다.")
                                )
                        )
                        .put("required", JSONArray().put("index").put("observation_id"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "long_press",
                    description = "길게 누를 좌표입니다. 기본적으로 최근 observe_screen 스크린샷의 픽셀 좌표를 사용합니다. 좌표가 ui_nodes의 center에서 온 경우 coordinate_space=screen으로 설정하세요.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("x", JSONObject().put("type", "integer"))
                                .put("y", JSONObject().put("type", "integer"))
                                .put(
                                    "duration_ms",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "길게 누르는 시간(300~3000ms), 기본값 800ms")
                                )
                                .put("coordinate_space", AgentToolSchema.coordinateSpace())
                        )
                        .put("required", JSONArray().put("x").put("y"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "long_press_element",
                    description = "지정된 관찰 스냅샷의 UI 노드를 길게 누릅니다. index와 observation_id는 동일한 최근 observe_screen에서 가져와야 합니다. 관찰이 만료되면 다시 관찰하세요. 실행 전에 Runtime이 Eta 접근성 서비스 연결을 확인합니다.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "index",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "동일 observe_screen에서 반환된 UI 노드 index입니다.")
                                )
                                .put(
                                    "observation_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "index와 동일한 최근 observe_screen에서 가져온 observation_id입니다.")
                                )
                                .put(
                                    "duration_ms",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "길게 누르는 시간(300~3000ms), 기본값 800ms")
                                )
                        )
                        .put("required", JSONArray().put("index").put("observation_id"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "swipe",
                    description = "한 좌표에서 다른 좌표로 스와이프합니다. 기본적으로 최근 observe_screen 스크린샷의 픽셀 좌표를 사용합니다. 위로 스와이프하면 리스트가 아래로 이동합니다.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("x1", JSONObject().put("type", "integer"))
                                .put("y1", JSONObject().put("type", "integer"))
                                .put("x2", JSONObject().put("type", "integer"))
                                .put("y2", JSONObject().put("type", "integer"))
                                .put(
                                    "duration_ms",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "스와이프 시간: 100~2000, 기본값 500")
                                )
                                .put("coordinate_space", AgentToolSchema.coordinateSpace())
                        )
                        .put("required", JSONArray().put("x1").put("y1").put("x2").put("y2"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "scroll",
                    description = "콘텐츠 방향에 따라 현재 화면을 스크롤합니다: down은 아래, up은 위, left는 왼쪽, right는 오른쪽 내용을 표시합니다.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "direction",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("up").put("down").put("left").put("right"))
                                )
                        )
                        .put("required", JSONArray().put("direction"))
                )
            )
            .put(
                AgentToolSchema.function(
                    name = "scroll_element",
                    description = "지정된 관찰 스냅샷의 스크롤 가능한 UI 노드를 콘텐츠 방향으로 스크롤합니다: down은 아래, up은 위, left는 왼쪽, right는 오른쪽 내용을 표시합니다. index와 observation_id는 동일한 최근 observe_screen에서 가져와야 합니다. 관찰이 만료되면 다시 관찰하세요. 실행 전에 Runtime이 Eta 접근성 서비스 연결을 확인합니다.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "index",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("description", "동일한 observe_screen에서 반환된 스크롤 가능한 UI 노드 index입니다.")
                                )
                                .put(
                                    "observation_id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "index와 동일한 최근 observe_screen에서 가져온 observation_id입니다.")
                                )
                                .put(
                                    "direction",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("up").put("down").put("left").put("right"))
                                        .put("description", "콘텐츠 방향: down은 아래, up은 위 내용을 표시합니다.")
                                )
                        )
                        .put("required", JSONArray().put("index").put("observation_id").put("direction"))
                )
            )
    }
}
