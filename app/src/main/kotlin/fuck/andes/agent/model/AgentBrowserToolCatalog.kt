package fuck.andes.agent.model

import org.json.JSONArray
import org.json.JSONObject

internal object AgentBrowserToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools.put(
            AgentToolSchema.function(
                name = "browser_use",
                description = "操作 Eta 共享的离屏 Agent 浏览器，不会切换到外部浏览器。一次调用只执行一个 action；网页浏览通常先 navigate，再用 get_readable 提取正文，或用 find_elements 查找可交互元素。需要把 URI 显式交给外部应用时使用 open_uri。",
                parameters = JSONObject()
                    .put("type", "object")
                    .put(
                        "properties",
                        JSONObject()
                            .put(
                                "action",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "이번에 실행되는 유일한 브라우저 동작입니다.")
                                    .put(
                                        "enum",
                                        JSONArray()
                                            .put("navigate")
                                            .put("get_readable")
                                            .put("get_text")
                                            .put("find_elements")
                                            .put("click")
                                            .put("type")
                                            .put("scroll")
                                            .put("screenshot")
                                            .put("get_page_info")
                                            .put("go_back")
                                            .put("go_forward")
                                            .put("reload")
                                            .put("wait_for_selector")
                                    )
                            )
                            .put(
                                "url",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "navigate 要访问的 URL。")
                            )
                            .put(
                                "selector",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "click, type, get_text, find_elements, wait_for_selector에서 사용하는 CSS selector입니다.")
                            )
                            .put(
                                "text",
                                JSONObject()
                                    .put("type", "string")
                                    .put("description", "type에서 입력할 텍스트입니다. 도구에만 전달되며 실행 요약에는 표시되지 않습니다.")
                            )
                            .put(
                                "submit",
                                JSONObject()
                                    .put("type", "boolean")
                                    .put("description", "type 输入后是否提交所在表单，默认 false。")
                            )
                            .put(
                                "coordinate_x",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("description", "click 또는 type의 뷰포트 X 좌표입니다. coordinate_y와 함께 사용하세요.")
                            )
                            .put(
                                "coordinate_y",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("description", "click 또는 type의 뷰포트 Y 좌표입니다. coordinate_x와 함께 사용하세요.")
                            )
                            .put(
                                "amount",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("description", "scroll의 픽셀 단위 스크롤 양입니다.")
                            )
                            .put(
                                "direction",
                                JSONObject()
                                    .put("type", "string")
                                    .put("enum", JSONArray().put("up").put("down"))
                                    .put("description", "scroll의 스크롤 방향입니다.")
                            )
                            .put(
                                "offset",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("description", "get_readable 또는 get_text의 텍스트 시작 오프셋입니다. 기본값은 0입니다.")
                            )
                            .put(
                                "max_chars",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("description", "get_readable 또는 get_text가 반환할 최대 문자 수입니다.")
                            )
                            .put(
                                "read_image",
                                JSONObject()
                                    .put("type", "boolean")
                                    .put("description", "screenshot 시 캡처 이미지를 모델에 직접 첨부할지 여부입니다. 기본값은 true입니다.")
                            )
                            .put(
                                "timeout_ms",
                                JSONObject()
                                    .put("type", "integer")
                                    .put("description", "navigate 또는 wait_for_selector의 타임아웃(밀리초)입니다.")
                            )
                    )
                    .put("required", JSONArray().put("action"))
            )
        )
    }

}
