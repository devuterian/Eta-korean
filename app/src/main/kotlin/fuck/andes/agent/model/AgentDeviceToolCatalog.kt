package fuck.andes.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** 常用设备能力的结构化 schema；按风险组决定是否向模型公开。 */
internal object AgentDeviceToolCatalog {
    fun appendTo(
        tools: JSONArray,
        directTools: Boolean,
        sensitiveReadTools: Boolean,
        sensitiveActionTools: Boolean,
    ) {
        if (directTools) appendDirectTools(tools)
        if (sensitiveReadTools) appendSensitiveReadTools(tools)
        if (sensitiveActionTools) appendSensitiveActionTools(tools)
    }

    private fun appendDirectTools(tools: JSONArray) {
        tools
            .put(
                function(
                    "set_alarm",
                    "시스템 알람을 직접 생성하세요. GUI를 사용하지 마세요. 상대 날짜가 포함되면 get_current_context로 변환하세요. hour/minute는 기기 현지 시간을 사용합니다. 시스템이 직접 실행을 허용하지 않으면 시계 페이지만 열릴 수 있습니다.",
                    properties(
                        "hour" to integer("0~23", 0, 23),
                        "minute" to integer("0~59", 0, 59),
                        "label" to string("알람 라벨, 최대 100자", 100),
                        "repeat_days" to stringArray(
                            "반복 요일; 미설정 시 한 번만 알림",
                            "mon", "tue", "wed", "thu", "fri", "sat", "sun",
                        ),
                        "vibrate" to boolean("진동 여부, 기본값 true"),
                    ),
                    "hour", "minute",
                ),
            )
            .put(
                function(
                    "set_timer",
                    "시스템 타이머를 바로 생성합니다. GUI를 사용하지 마세요. duration_seconds는 1~86400초여야 합니다.",
                    properties(
                        "duration_seconds" to integer("타이머 초 수", 1, 86_400),
                        "label" to string("타이머 라벨, 최대 100자", 100),
                    ),
                    "duration_seconds",
                ),
            )
            .put(emptyFunction("device_status", "배터리, 메모리, 저장소, 시스템 버전, 부팅 시간 정보를 읽습니다."))
            .put(emptyFunction("network_info", "현재 네트워크 방식, 인증 상태, 현재 Wi‑Fi 기본 정보를 읽습니다. 저장된 비밀번호는 반환하지 않습니다."))
            .put(limitFunction("top_memory_apps", "현재 RSS 기준 메모리 사용량이 가장 높은 프로세스를 나열합니다."))
            .put(limitFunction("top_storage_apps", "앱, 데이터, 캐시 합산 저장소 사용량이 가장 많은 앱을 나열합니다."))
            .put(
                function(
                    "media_control",
                    "현재 미디어 세션을 직접 제어합니다. 플레이어 GUI를 조작하지 마세요.",
                    properties(
                        "action" to enumString(
                            "미디어 동작",
                            "play", "pause", "play_pause", "next", "previous", "stop",
                        ),
                    ),
                    "action",
                ),
            )
            .put(
                function(
                    "set_volume",
                    "시스템 볼륨을 직접 설정합니다. 볼륨 GUI를 조작하지 마세요.",
                    properties(
                        "stream" to enumString("볼륨 채널", "media", "alarm", "ring", "notification"),
                        "percent" to integer("0~100 볼륨 백분율", 0, 100),
                    ),
                    "stream", "percent",
                ),
            )
    }

    private fun appendSensitiveReadTools(tools: JSONArray) {
        tools
            .put(
                function(
                    "get_setting",
                    "Android Settings 값을 읽습니다. 결과에 기기 식별 등 민감 정보가 포함될 수 있으며, 원본 결과는 저장되지 않습니다.",
                    properties(
                        "namespace" to enumString("설정 네임스페이스", "system", "secure", "global"),
                        "key" to string("정확한 설정 키", 200),
                    ),
                    "namespace", "key",
                ),
            )
            .put(
                function(
                    "wifi_credentials",
                    "휴대폰에 저장된 Wi‑Fi 이름과 비밀번호를 읽습니다. 원본 결과는 저장되지 않습니다.",
                    properties(
                        "ssid" to string("선택적 정확한 Wi‑Fi 이름", 128),
                        "limit" to integer("최대 반환 개수, 기본값 20", 1, 50),
                    ),
                ),
            )
            .put(
                function(
                    "recent_notifications",
                    "현재 알림창의 알림 제목과 본문을 읽습니다. 결과는 세션에 저장되지 않습니다.",
                    properties(
                        "package_name" to string("선택적 정확한 앱 패키지명 필터", 255),
                        "limit" to integer("최대 반환 개수, 기본값 10", 1, 20),
                    ),
                ),
            )
            .put(
                function(
                    "read_sms_code",
                    "최근 문자메시지에서 4~8자리 인증번호, 발신자, 시간만 추출하며, 전체 메시지 본문은 반환하지 않습니다.",
                    properties(
                        "max_age_minutes" to integer("몇 분 이내의 문자만 확인합니다. 기본값은 10입니다.", 1, 1_440),
                    ),
                ),
            )
            .put(
                function(
                    "get_logcat",
                    "최근 시스템 로그를 읽습니다. query는 읽은 로그에서만 텍스트 필터링하며 Shell에는 접근하지 않습니다.",
                    properties(
                        "query" to string("필터링할 텍스트(선택 사항)", 200),
                        "max_lines" to integer("최대 로그 행 수, 기본값 200", 20, 500),
                    ),
                ),
            )
    }

    private fun appendSensitiveActionTools(tools: JSONArray) {
        tools
            .put(
                function(
                    "set_setting",
                    "안전과 관련 없는 Android Settings 값을 수정합니다. 무장애, ADB, 기기 초기화 등 주요 키는 거부됩니다.",
                    properties(
                        "namespace" to enumString("설정 네임스페이스", "system", "secure", "global"),
                        "key" to string("정확한 설정 키", 200),
                        "value" to string("새 값", 2_000),
                    ),
                    "namespace", "key", "value",
                ),
            )
            .put(
                function(
                    "set_device_state",
                    "Wi‑Fi/블루투스를 직접 켜거나 끕니다. 설정 GUI는 조작하지 않습니다.",
                    properties(
                        "target" to enumString("기기 기능", "wifi", "bluetooth"),
                        "enabled" to boolean("true: 활성화, false: 비활성화"),
                    ),
                    "target", "enabled",
                ),
            )
            .put(
                function(
                    "app_state_control",
                    "정확한 패키지명을 중지, 동결 또는 해제합니다. 핵심 시스템 패키지는 보호되며, 시스템 앱은 동결할 수 없습니다.",
                    properties(
                        "package_name" to string("정확한 Android 패키지명", 255),
                        "action" to enumString("동작", "force_stop", "freeze", "unfreeze"),
                    ),
                    "package_name", "action",
                ),
            )
            .put(
                function(
                    "send_message",
                    "Eta 무장애 서비스를 통해 WeChat에서 정확한 연락처를 찾아 메시지를 입력하거나 실제로 보냅니다. 정확히 일치하는 연락처만 허용하며, 동명이인은 거부됩니다. mode=send는 한 번만 전송 및 검증하며, 실패 또는 결과 미확정 시 자동 재시도나 tap/input_text 재실행을 하지 않습니다.",
                    properties(
                        "contact" to string("WeChat 연락처(정확히 일치해야 함)", 64),
                        "message" to string("메시지 내용", 2_000),
                        "mode" to enumString("draft: 입력만, send: 실제 전송", "draft", "send"),
                    ),
                    "contact", "message", "mode",
                ),
            )
    }

    private fun emptyFunction(name: String, description: String): JSONObject =
        function(name, description, properties())

    private fun limitFunction(name: String, description: String): JSONObject =
        function(
            name,
            description,
            properties("limit" to integer("최대 반환 개수, 기본값 10", 1, 30)),
        )

    private fun function(
        name: String,
        description: String,
        properties: JSONObject,
        vararg required: String,
    ): JSONObject =
        AgentToolSchema.function(
            name = name,
            description = description,
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", properties)
                .also { schema ->
                    if (required.isNotEmpty()) schema.put("required", JSONArray(required.toList()))
                },
        )

    private fun properties(vararg entries: Pair<String, JSONObject>): JSONObject =
        JSONObject().also { target -> entries.forEach { (name, schema) -> target.put(name, schema) } }

    private fun string(description: String, maxLength: Int? = null): JSONObject =
        JSONObject()
            .put("type", "string")
            .put("description", description)
            .also { schema -> maxLength?.let { schema.put("maxLength", it) } }

    private fun boolean(description: String): JSONObject =
        JSONObject().put("type", "boolean").put("description", description)

    private fun integer(description: String, minimum: Int, maximum: Int): JSONObject =
        JSONObject()
            .put("type", "integer")
            .put("minimum", minimum)
            .put("maximum", maximum)
            .put("description", description)

    private fun enumString(description: String, vararg values: String): JSONObject =
        string(description).put("enum", JSONArray(values.toList()))

    private fun stringArray(description: String, vararg values: String): JSONObject =
        JSONObject()
            .put("type", "array")
            .put("items", enumString(description, *values))
            .put("uniqueItems", true)
            .put("maxItems", 7)
            .put("description", description)
}
