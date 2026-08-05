package fuck.andes.agent.tool

import java.util.Locale
import fuck.andes.data.repository.AgentMemoryStore
import org.json.JSONObject

/**
 * 工具 schema 只约束模型输出；执行边界仍需拒绝缺失或类型错误的副作用参数，
 * 避免 `optInt`/`optString` 把畸形调用静默变成坐标 0 或空文本。
 */
internal object ToolArgumentContract {
    data class Issue(val field: String, val message: String)

    private enum class Kind(val label: String) {
        STRING("string"),
        INTEGER("integer"),
        BOOLEAN("boolean"),
        STRING_ARRAY("array of strings"),
    }

    private data class Field(
        val name: String,
        val kind: Kind,
        val required: Boolean = false,
        val values: Set<String> = emptySet(),
        val minimum: Int? = null,
        val maximum: Int? = null,
        val minimumItems: Int? = null,
        val maximumItems: Int? = null,
        val nonBlank: Boolean = false,
        val maximumLength: Int? = null,
        val maximumItemLength: Int? = null,
        val maximumTotalCharacters: Int? = null,
        val uniqueItems: Boolean = false,
    )

    private val contracts = mapOf(
        "observe_screen" to listOf(
            Field("include_screenshot", Kind.BOOLEAN),
            Field("include_ui_tree", Kind.BOOLEAN),
            Field("max_nodes", Kind.INTEGER, minimum = 1, maximum = 120),
        ),
        "tap" to coordinateFields("x", "y"),
        "tap_area" to coordinateFields("x1", "y1", "x2", "y2"),
        "tap_element" to elementFields(),
        "long_press" to coordinateFields("x", "y") +
            Field("duration_ms", Kind.INTEGER, minimum = 300, maximum = 3_000),
        "long_press_element" to elementFields() +
            Field("duration_ms", Kind.INTEGER, minimum = 300, maximum = 3_000),
        "swipe" to coordinateFields("x1", "y1", "x2", "y2") +
            Field("duration_ms", Kind.INTEGER, minimum = 100, maximum = 2_000),
        "scroll" to listOf(directionField()),
        "scroll_element" to elementFields() + directionField(),
        "input_text" to listOf(
            Field("text", Kind.STRING, required = true),
            Field("mode", Kind.STRING, values = setOf("append", "replace", "paste")),
            Field("index", Kind.INTEGER, minimum = 0),
            Field("observation_id", Kind.STRING),
        ),
        "replace_text" to editableFields(includeText = true),
        "clear_text" to editableFields(includeText = false),
        "set_clipboard" to listOf(Field("text", Kind.STRING, required = true)),
        "paste_text" to listOf(Field("text", Kind.STRING, required = true)),
        "press_key" to listOf(
            Field(
                "button",
                Kind.STRING,
                required = true,
                values = setOf(
                    "back",
                    "home",
                    "enter",
                    "recents",
                    "paste",
                    "notifications",
                    "quick_settings",
                ),
            ),
        ),
        "wait" to listOf(Field("duration_ms", Kind.INTEGER, minimum = 100, maximum = 30_000)),
        "wait_for_text" to listOf(
            Field("text", Kind.STRING, required = true),
            Field("timeout_ms", Kind.INTEGER, minimum = 500, maximum = 60_000),
            Field("include_desc", Kind.BOOLEAN),
            Field("match", Kind.STRING, values = setOf("contains", "exact", "prefix", "regex")),
        ),
        "wait_for_package" to listOf(
            Field("package_name", Kind.STRING, required = true),
            Field("timeout_ms", Kind.INTEGER, minimum = 500, maximum = 60_000),
        ),
        "open_system_panel" to listOf(
            Field(
                "panel",
                Kind.STRING,
                required = true,
                values = setOf("notifications", "quick_settings"),
            ),
        ),
        "set_alarm" to listOf(
            Field("hour", Kind.INTEGER, required = true, minimum = 0, maximum = 23),
            Field("minute", Kind.INTEGER, required = true, minimum = 0, maximum = 59),
            Field("label", Kind.STRING, maximumLength = 100),
            Field(
                "repeat_days",
                Kind.STRING_ARRAY,
                maximumItems = 7,
                maximumItemLength = 3,
                uniqueItems = true,
            ),
            Field("vibrate", Kind.BOOLEAN),
        ),
        "set_timer" to listOf(
            Field(
                "duration_seconds",
                Kind.INTEGER,
                required = true,
                minimum = 1,
                maximum = 86_400,
            ),
            Field("label", Kind.STRING, maximumLength = 100),
        ),
        "top_memory_apps" to listOf(
            Field("limit", Kind.INTEGER, minimum = 1, maximum = 30),
        ),
        "top_storage_apps" to listOf(
            Field("limit", Kind.INTEGER, minimum = 1, maximum = 30),
        ),
        "media_control" to listOf(
            Field(
                "action",
                Kind.STRING,
                required = true,
                values = setOf("play", "pause", "play_pause", "next", "previous", "stop"),
            ),
        ),
        "set_volume" to listOf(
            Field(
                "stream",
                Kind.STRING,
                required = true,
                values = setOf("media", "alarm", "ring", "notification"),
            ),
            Field("percent", Kind.INTEGER, required = true, minimum = 0, maximum = 100),
        ),
        "get_setting" to settingFields(includeValue = false),
        "set_setting" to settingFields(includeValue = true),
        "wifi_credentials" to listOf(
            Field("ssid", Kind.STRING, maximumLength = 128),
            Field("limit", Kind.INTEGER, minimum = 1, maximum = 50),
        ),
        "recent_notifications" to listOf(
            Field("package_name", Kind.STRING, maximumLength = 255),
            Field("limit", Kind.INTEGER, minimum = 1, maximum = 20),
        ),
        "search_notification_history" to listOf(
            Field("query", Kind.STRING, maximumLength = 200),
            Field("package_name", Kind.STRING, maximumLength = 255),
            Field("max_age_hours", Kind.INTEGER, minimum = 1, maximum = 168),
            Field("limit", Kind.INTEGER, minimum = 1, maximum = 50),
        ),
        "recent_app_activity" to listOf(
            Field("package_name", Kind.STRING, maximumLength = 255),
            Field("max_age_hours", Kind.INTEGER, minimum = 1, maximum = 168),
            Field("limit", Kind.INTEGER, minimum = 1, maximum = 50),
        ),
        "app_usage_summary" to listOf(
            Field("max_age_hours", Kind.INTEGER, minimum = 1, maximum = 168),
            Field("limit", Kind.INTEGER, minimum = 1, maximum = 50),
        ),
        "list_alarms" to listOf(
            Field("enabled_only", Kind.BOOLEAN),
            Field("limit", Kind.INTEGER, minimum = 1, maximum = 50),
        ),
        "list_active_timers" to listOf(
            Field("limit", Kind.INTEGER, minimum = 1, maximum = 50),
        ),
        "get_health_summary" to listOf(
            Field("days", Kind.INTEGER, minimum = 1, maximum = 30),
        ),
        "read_sms_code" to listOf(
            Field("max_age_minutes", Kind.INTEGER, minimum = 1, maximum = 1_440),
        ),
        "get_logcat" to listOf(
            Field("query", Kind.STRING, maximumLength = 200),
            Field("max_lines", Kind.INTEGER, minimum = 20, maximum = 500),
        ),
        "search_media" to personalSearchFields(),
        "search_audio" to personalSearchFields(),
        "search_recordings" to personalSearchFields(),
        "search_files" to personalSearchFields(),
        "search_calendar_events" to personalSearchFields(),
        "search_contacts" to personalSearchFields(),
        "search_call_history" to personalSearchFields(),
        "search_messages" to personalSearchFields(),
        "search_downloads" to personalSearchFields(),
        "search_coloros_notes" to personalSearchFields(),
        "search_coloros_recordings" to personalSearchFields(),
        "search_recording_summaries" to personalSearchFields(),
        "search_coloros_memories" to personalSearchFields(),
        "search_saved_places" to personalSearchFields(),
        "search_personal_orders" to personalSearchFields(),
        "search_clipboard_history" to personalSearchFields(),
        "search_qq_chat_images" to personalSearchFields(),
        "search_wechat_chat_images" to personalSearchFields(),
        "read_image" to listOf(
            Field("path", Kind.STRING, required = true, nonBlank = true, maximumLength = 1_024),
        ),
        "set_device_state" to listOf(
            Field(
                "target",
                Kind.STRING,
                required = true,
                values = setOf("wifi", "bluetooth"),
            ),
            Field("enabled", Kind.BOOLEAN, required = true),
        ),
        "app_state_control" to listOf(
            Field(
                "package_name",
                Kind.STRING,
                required = true,
                nonBlank = true,
                maximumLength = 255,
            ),
            Field(
                "action",
                Kind.STRING,
                required = true,
                values = setOf("force_stop", "freeze", "unfreeze"),
            ),
        ),
        "memory_get" to listOf(
            Field("query", Kind.STRING, maximumLength = 500),
            Field("start_line", Kind.INTEGER, minimum = 1),
            Field(
                "max_chars",
                Kind.INTEGER,
                minimum = AgentMemoryStore.MIN_READ_CHARS,
                maximum = AgentMemoryStore.MAX_READ_CHARS,
            ),
        ),
        "memory_write" to listOf(
            Field(
                "mode",
                Kind.STRING,
                required = true,
                values = setOf("replace_range", "append", "clear"),
            ),
            Field(
                "revision",
                Kind.STRING,
                required = true,
                nonBlank = true,
                maximumLength = 64,
            ),
            Field("start_line", Kind.INTEGER, minimum = 1),
            Field("end_line", Kind.INTEGER, minimum = 1),
            Field(
                "content",
                Kind.STRING,
                maximumLength = AgentMemoryStore.MAX_WRITE_CONTENT_CHARS,
            ),
        ),
        "skills_inspect_github" to listOf(
            Field(
                "repository",
                Kind.STRING,
                required = true,
                nonBlank = true,
                maximumLength = 500,
            ),
            Field("ref", Kind.STRING, nonBlank = true, maximumLength = 200),
            Field("path", Kind.STRING, nonBlank = true, maximumLength = 1_000),
        ),
        "skills_read_resource" to listOf(
            Field(
                "skillId",
                Kind.STRING,
                required = true,
                nonBlank = true,
                maximumLength = 500,
            ),
            Field(
                "relativePath",
                Kind.STRING,
                required = true,
                nonBlank = true,
                maximumLength = 1_000,
            ),
            Field("maxChars", Kind.INTEGER, minimum = 512, maximum = 64_000),
        ),
        "skills_install_from_github" to listOf(
            Field(
                "repository",
                Kind.STRING,
                required = true,
                nonBlank = true,
                maximumLength = 500,
            ),
            Field("ref", Kind.STRING, nonBlank = true, maximumLength = 200),
            Field(
                "paths",
                Kind.STRING_ARRAY,
                required = true,
                minimumItems = 1,
                maximumItems = 20,
                nonBlank = true,
                maximumItemLength = 1_000,
                maximumTotalCharacters = 10_000,
                uniqueItems = true,
            ),
            Field("replaceExisting", Kind.BOOLEAN),
            Field(
                "expectedReplacementId",
                Kind.STRING,
                nonBlank = true,
                maximumLength = 500,
            ),
        ),
    )

    fun validate(toolName: String, args: JSONObject): Issue? {
        val fields = contracts[toolName] ?: return null
        for (field in fields) {
            if (!args.has(field.name) || args.isNull(field.name)) {
                if (field.required) {
                    return Issue(field.name, "缺少必填参数 ${field.name}")
                }
                continue
            }
            val value = args.opt(field.name)
            if (!matchesKind(value, field.kind)) {
                return Issue(field.name, "参数 ${field.name} 必须是 ${field.kind.label}")
            }
            if (field.kind == Kind.INTEGER) {
                val number = (value as Number).toLong()
                if (field.minimum != null && number < field.minimum) {
                    return Issue(field.name, "参数 ${field.name} 不能小于 ${field.minimum}")
                }
                if (field.maximum != null && number > field.maximum) {
                    return Issue(field.name, "参数 ${field.name} 不能大于 ${field.maximum}")
                }
            }
            if (field.kind == Kind.STRING && field.nonBlank && (value as String).isBlank()) {
                return Issue(field.name, "参数 ${field.name} 不能为空")
            }
            if (
                field.kind == Kind.STRING &&
                field.maximumLength != null &&
                (value as String).length > field.maximumLength
            ) {
                return Issue(field.name, "参数 ${field.name} 不能超过 ${field.maximumLength} 个字符")
            }
            if (field.kind == Kind.STRING_ARRAY) {
                val array = value as org.json.JSONArray
                if (field.minimumItems != null && array.length() < field.minimumItems) {
                    return Issue(field.name, "参数 ${field.name} 至少需要 ${field.minimumItems} 项")
                }
                if (field.maximumItems != null && array.length() > field.maximumItems) {
                    return Issue(field.name, "参数 ${field.name} 最多支持 ${field.maximumItems} 项")
                }
                if (field.nonBlank && (0 until array.length()).any { array.getString(it).isBlank() }) {
                    return Issue(field.name, "参数 ${field.name} 不能包含空字符串")
                }
                val items = (0 until array.length()).map(array::getString)
                if (
                    field.maximumItemLength != null &&
                    items.any { it.length > field.maximumItemLength }
                ) {
                    return Issue(
                        field.name,
                        "参数 ${field.name} 的单项不能超过 ${field.maximumItemLength} 个字符",
                    )
                }
                if (
                    field.maximumTotalCharacters != null &&
                    items.sumOf(String::length) > field.maximumTotalCharacters
                ) {
                    return Issue(
                        field.name,
                        "参数 ${field.name} 的总长度不能超过 ${field.maximumTotalCharacters} 个字符",
                    )
                }
                if (field.uniqueItems && items.distinct().size != items.size) {
                    return Issue(field.name, "参数 ${field.name} 不能包含重复项")
                }
            }
            if (
                field.values.isNotEmpty() &&
                (value as String).lowercase() !in field.values
            ) {
                return Issue(
                    field.name,
                    "参数 ${field.name} 仅支持 ${field.values.joinToString("/")}",
                )
            }
        }

        if (toolName in EDITABLE_TOOLS && args.has("index") && !args.isNull("index")) {
            if (!args.has("observation_id") || args.isNull("observation_id")) {
                return Issue("observation_id", "指定 index 时必须提供 observation_id")
            }
        }
        if (
            toolName == "input_text" &&
            args.has("index") &&
            !args.isNull("index") &&
            !args.optString("mode", "append").equals("replace", ignoreCase = true)
        ) {
            return Issue("index", "input_text 仅在 mode=replace 时支持 index")
        }
        if (toolName == "paste_text" && args.optString("text").isEmpty()) {
            return Issue("text", "paste_text 的 text 不能为空")
        }
        if (toolName == "set_alarm") {
            val days = args.optJSONArray("repeat_days")
            if (
                days != null &&
                (0 until days.length()).any {
                    days.getString(it).lowercase(Locale.ROOT) !in REPEAT_DAYS
                }
            ) {
                return Issue("repeat_days", "repeat_days 只支持 mon/tue/wed/thu/fri/sat/sun")
            }
        }
        if (
            toolName == "skills_install_from_github" &&
            args.optBoolean("replaceExisting", false) &&
            (!args.has("expectedReplacementId") || args.isNull("expectedReplacementId"))
        ) {
            return Issue(
                "expectedReplacementId",
                "replaceExisting=true 时必须提供上一轮冲突的 expectedReplacementId",
            )
        }
        if (toolName == "memory_write") {
            val revision = args.optString("revision")
            if (!REVISION.matches(revision)) {
                return Issue("revision", "revision 必须是 64 位 SHA-256")
            }
            when (args.optString("mode").lowercase(Locale.ROOT)) {
                "replace_range" -> {
                    if (!args.has("start_line") || args.isNull("start_line")) {
                        return Issue("start_line", "replace_range 必须提供 start_line")
                    }
                    if (!args.has("end_line") || args.isNull("end_line")) {
                        return Issue("end_line", "replace_range 必须提供 end_line")
                    }
                    if (!args.has("content") || args.isNull("content")) {
                        return Issue("content", "replace_range 必须提供 content，删除时传空字符串")
                    }
                    if (args.optInt("end_line") < args.optInt("start_line")) {
                        return Issue("end_line", "end_line 不能小于 start_line")
                    }
                }
                "append" -> {
                    if (!args.has("content") || args.isNull("content") || args.optString("content").isBlank()) {
                        return Issue("content", "append 必须提供非空 content")
                    }
                }
                "clear" -> {
                    if (args.has("content") || args.has("start_line") || args.has("end_line")) {
                        return Issue("mode", "clear 不能携带 content 或行范围")
                    }
                }
            }
        }
        return null
    }

    private fun matchesKind(value: Any?, kind: Kind): Boolean = when (kind) {
        Kind.STRING -> value is String
        Kind.BOOLEAN -> value is Boolean
        Kind.INTEGER -> value is Number && value.toDouble().let { number ->
            number.isFinite() && number % 1.0 == 0.0 &&
                number >= Int.MIN_VALUE.toDouble() && number <= Int.MAX_VALUE.toDouble()
        }
        Kind.STRING_ARRAY -> value is org.json.JSONArray &&
            (0 until value.length()).all { value.opt(it) is String }
    }

    private fun coordinateFields(vararg names: String): List<Field> =
        names.map { Field(it, Kind.INTEGER, required = true, minimum = 0) } +
            Field(
                "coordinate_space",
                Kind.STRING,
                values = setOf("screenshot", "screen"),
            )

    private fun elementFields(): List<Field> = listOf(
        Field("index", Kind.INTEGER, required = true, minimum = 0),
        Field("observation_id", Kind.STRING, required = true),
    )

    private fun editableFields(includeText: Boolean): List<Field> = buildList {
        if (includeText) add(Field("text", Kind.STRING, required = true))
        add(Field("index", Kind.INTEGER, minimum = 0))
        add(Field("observation_id", Kind.STRING))
    }

    private fun directionField(): Field = Field(
        "direction",
        Kind.STRING,
        required = true,
        values = setOf("up", "down", "left", "right"),
    )

    private fun settingFields(includeValue: Boolean): List<Field> = buildList {
        add(
            Field(
                "namespace",
                Kind.STRING,
                required = true,
                values = setOf("system", "secure", "global"),
            ),
        )
        add(Field("key", Kind.STRING, required = true, nonBlank = true, maximumLength = 200))
        if (includeValue) {
            add(Field("value", Kind.STRING, required = true, maximumLength = 2_000))
        }
    }

    private fun personalSearchFields(): List<Field> = listOf(
        Field("query", Kind.STRING, maximumLength = 200),
        Field("limit", Kind.INTEGER, minimum = 1, maximum = 30),
    )

    private val EDITABLE_TOOLS = setOf("input_text", "replace_text", "clear_text")
    private val REPEAT_DAYS = setOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
    private val REVISION = Regex("^[0-9a-f]{64}$")
}
