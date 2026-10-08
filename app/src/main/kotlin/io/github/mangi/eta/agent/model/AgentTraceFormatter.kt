package io.github.mangi.eta.agent.model

import io.github.mangi.eta.i18n.ko
import java.net.URI
import java.util.Locale
import org.json.JSONObject

/** 工具摘要面向用户展示，不包含敏感参数；终端命令通过独立字段提供给用户核对。 */
internal class AgentTraceFormatter {
    fun summarizeArguments(toolCall: AgentModelClient.ToolCall): String =
        when (toolCall.name) {
            BROWSER_TOOL_NAME -> summarizeBrowserArguments(toolCall.argumentsJson)
            "web_search" -> ko("搜索网页", "웹 검색")
            "fetch_url" -> summarizeFetchArguments(toolCall.argumentsJson)
            "open_uri" -> summarizeOpenUriArguments(toolCall.argumentsJson)
            "terminal" -> summarizeTerminalArguments(toolCall.argumentsJson)
            "run_command" -> ko("执行命令 · Android", "명령 실행 · Android")
            "write_file" -> summarizeTextLength(ko("写入文件", "파일 쓰기"), toolCall.argumentsJson, "content")
            "read_file" -> ko("读取文件", "파일 읽기")
            "list_directory" -> ko("列出目录", "디렉터리 목록")
            "edit_file" -> ko("精确编辑文件", "파일 편집")
            "stat_file" -> ko("查看文件信息", "파일 정보 보기")
            "glob_files" -> ko("按路径查找文件", "경로로 파일 찾기")
            "grep_files" -> ko("搜索文件内容", "파일 내용 검색")
            "inspect_app" -> ko("检查应用", "앱 검사")
            "type_text" -> summarizeTypeText(toolCall.argumentsJson)
            "input_text" -> summarizeTextLength(ko("输入文本", "텍스트 입력"), toolCall.argumentsJson, "text")
            "replace_text" -> summarizeTextLength(ko("替换文本", "텍스트 바꾸기"), toolCall.argumentsJson, "text")
            "paste_text", "set_clipboard" ->
                summarizeTextLength(ko("粘贴文本", "텍스트 붙여넣기"), toolCall.argumentsJson, "text")
            "clear_text" -> ko("清空文本", "텍스트 지우기")
            "get_clipboard" -> ko("读取剪贴板", "클립보드 읽기")
            "search_apps" -> summarizeQueryArguments(ko("搜索应用", "앱 검색"), toolCall.argumentsJson)
            "launch_app" -> ko("打开应用", "앱 실행")
            "get_current_context" -> ko("读取当前上下文", "현재 컨텍스트 읽기")
            "observe_screen" -> summarizeObservationArguments(toolCall.argumentsJson)
            "tap" -> summarizePointArguments(ko("点击屏幕", "화면 탭"), toolCall.argumentsJson)
            "long_press" -> summarizePointArguments(ko("长按屏幕", "화면 길게 누르기"), toolCall.argumentsJson)
            "tap_area" -> ko("点击区域", "영역 탭")
            "tap_element" -> summarizeElementArguments(ko("点击元素", "요소 탭"), toolCall.argumentsJson)
            "long_press_element" -> summarizeElementArguments(ko("长按元素", "요소 길게 누르기"), toolCall.argumentsJson)
            "swipe" -> ko("滑动屏幕", "화면 스와이프")
            "scroll" -> summarizeScrollArguments(ko("滚动屏幕", "화면 스크롤"), toolCall.argumentsJson)
            "scroll_element" ->
                summarizeScrollArguments(ko("滚动元素", "요소 스크롤"), toolCall.argumentsJson, withIndex = true)
            "press_key" -> summarizePressKeyArguments(toolCall.argumentsJson)
            "wait" -> summarizeWaitArguments(toolCall.argumentsJson)
            "wait_for_text" -> ko("等待文本出现", "텍스트 표시 대기")
            "wait_for_package" -> ko("等待应用就绪", "앱 준비 대기")
            "open_system_panel" -> ko("打开系统面板", "시스템 패널 열기")
            "read_image" -> ko("查看图片", "이미지 보기")
            AgentConversationToolCatalog.READ_HISTORY -> ko("读取当前会话历史", "현재 대화 기록 읽기")
            "memory_get", "character_memory_get" -> summarizeMemoryGetArguments(toolCall.argumentsJson)
            "memory_write", "character_memory_write" -> summarizeMemoryWriteArguments(toolCall.argumentsJson)
            "skills_list" -> ko("查看技能列表", "스킬 목록 보기")
            "skills_read" -> ko("读取技能", "스킬 읽기")
            "skills_read_resource" -> ko("读取技能资源", "스킬 리소스 읽기")
            "skills_list_curated" -> ko("浏览精选技能", "추천 스킬 둘러보기")
            "skills_inspect_github" -> ko("查看技能详情", "스킬 상세 보기")
            "skills_install_from_github" -> ko("安装技能", "스킬 설치")
            else -> {
                val label = DEVICE_ACTION_LABELS[toolCall.name]
                    ?: AgentPhoneToolCatalog.entries.firstOrNull { it.name == toolCall.name }?.displayTitle
                    ?: io.github.mangi.eta.agent.context.PersonalSearchTools.searches.firstOrNull { it.name == toolCall.name }?.title
                    ?: when (toolCall.name) { "search_notes" -> ko("查询便签", "메모 검색"); "search_system_memories" -> ko("查询系统记忆", "시스템 기억 검색"); "read_personal_item" -> ko("读取检索详情", "검색 항목 상세 읽기"); "summarize_bills" -> ko("汇总账单", "결제 내역 요약"); else -> null }
                when {
                    label == null -> ko("准备执行", "실행 준비")
                    toolCall.name.startsWith("search_") ->
                        summarizeQueryArguments(label, toolCall.argumentsJson)
                    else -> label
                }
            }
        }

    /** 命令以脱敏后的用户可见投影进入运行轨迹；日志仍只记录长度。 */
    fun displayCommand(toolCall: AgentModelClient.ToolCall): String? =
        if (toolCall.name == "terminal" || toolCall.name == "run_command") {
            runCatching {
                JSONObject(toolCall.argumentsJson)
                    .optString("command")
                    .trim()
                    .takeIf { it.isNotBlank() && it.length <= MAX_DISPLAY_COMMAND_CHARS }
                    ?.redactDisplaySecrets()
            }.getOrNull()
        } else {
            null
        }

    private fun String.redactDisplaySecrets(): String =
        replace(SENSITIVE_ASSIGNMENT) { match ->
            "${match.groupValues[1]}=" + ko("<已隐藏>", "<숨김>")
        }
            .replace(SENSITIVE_FLAG) { match ->
                "${match.groupValues[1]}" + ko("<已隐藏>", "<숨김>")
            }
            .replace(SENSITIVE_HEADER) { match ->
                "${match.groupValues[1]}${match.groupValues[2]}" + ko("<已隐藏>", "<숨김>")
            }

    /** 外部 URI 摘要不记录 path、query、fragment 或用户信息。 */
    fun summarizeOpenUriArguments(argumentsJson: String): String =
        runCatching {
            val raw = JSONObject(argumentsJson).optString("uri").trim()
            val uri = URI(raw)
            val scheme = uri.scheme?.lowercase()?.take(24)
            val host = uri.host?.lowercase()?.take(160)
            listOfNotNull(ko("交给外部应用", "외부 앱으로 열기"), scheme, host).joinToString(" · ")
        }.getOrDefault(ko("交给外部应用", "외부 앱으로 열기"))

    /** browser_use 摘要只暴露动作和安全提取的 host。 */
    fun summarizeBrowserArguments(argumentsJson: String): String =
        runCatching {
            val arguments = JSONObject(argumentsJson)
            val action = arguments.optString("action").browserActionLabel()
            val host = safeHttpHost(arguments.optString("url"))
            listOfNotNull(action, host).joinToString(" · ")
        }.getOrElse { ko("浏览器操作", "브라우저 작업") }

    private fun summarizeFetchArguments(argumentsJson: String): String = runCatching {
        val arguments = JSONObject(argumentsJson)
        val label = if (arguments.has("document_id")) ko("继续读取网页", "웹 페이지 이어 읽기") else ko("读取网页", "웹 페이지 읽기")
        listOfNotNull(label, safeHttpHost(arguments.optString("url"))?.removePrefix("www.")).joinToString(" · ")
    }.getOrDefault(ko("读取网页", "웹 페이지 읽기"))

    private fun summarizeTerminalArguments(argumentsJson: String): String =
        runCatching {
            val arguments = JSONObject(argumentsJson)
            val action = arguments.optString("action").terminalActionLabel()
            val environment = arguments.optString("environment", "android")
                .terminalEnvironmentLabel()
            val identity = arguments.optString("identity")
                .takeIf { it == "root" || it == "user" }
            buildList {
                add(ko("终端", "터미널"))
                add(action)
                add(environment)
                identity?.let(::add)
                if (arguments.optBoolean("async", false)) add(ko("后台", "백그라운드"))
            }.joinToString(" · ")
        }.getOrDefault(ko("终端", "터미널"))

    private fun summarizeTextLength(
        label: String,
        argumentsJson: String,
        key: String,
    ): String =
        runCatching {
            val chars = JSONObject(argumentsJson).optString(key).length
            ko("$label · $chars 字符", "$label · ${chars}자")
        }.getOrDefault(label)

    /** 只展示动作与长度，不回显正文：输入内容可能是密码或私人消息。 */
    private fun summarizeTypeText(argumentsJson: String): String =
        runCatching {
            val args = JSONObject(argumentsJson)
            val chars = args.optString("text").length
            val label = when {
                chars == 0 -> ko("清空文本", "텍스트 지우기")
                args.optString("mode") == "append" -> ko("追加文本", "텍스트 추가")
                else -> ko("输入文本", "텍스트 입력")
            }
            buildString {
                append(label)
                if (chars > 0) append(ko(" · $chars 字符", " · ${chars}자"))
                if (args.optBoolean("submit")) append(ko(" · 提交", " · 제출"))
            }
        }.getOrDefault(ko("输入文本", "텍스트 입력"))

    /** 搜索关键词是用户自己发起的查询，直接展示；仍做单行化与长度截断。 */
    private fun summarizeQueryArguments(label: String, argumentsJson: String): String =
        runCatching {
            val query = sanitizeSummaryValue(
                JSONObject(argumentsJson).optString("query"),
                MAX_QUERY_SUMMARY_CHARS,
            )
            if (query.isNotBlank()) "$label · $query" else label
        }.getOrDefault(label)

    private fun summarizePointArguments(label: String, argumentsJson: String): String =
        runCatching {
            val arguments = JSONObject(argumentsJson)
            "$label · (${arguments.optInt("x")}, ${arguments.optInt("y")})"
        }.getOrDefault(label)

    private fun summarizeElementArguments(label: String, argumentsJson: String): String =
        runCatching {
            val index = JSONObject(argumentsJson).optInt("index", -1)
            if (index >= 0) "$label · #$index" else label
        }.getOrDefault(label)

    private fun summarizeScrollArguments(
        label: String,
        argumentsJson: String,
        withIndex: Boolean = false,
    ): String =
        runCatching {
            val arguments = JSONObject(argumentsJson)
            buildList {
                add(label)
                if (withIndex) {
                    arguments.optInt("index", -1).takeIf { it >= 0 }?.let { add("#$it") }
                }
                arguments.optString("direction").scrollDirectionLabel()?.let(::add)
            }.joinToString(" · ")
        }.getOrDefault(label)

    private fun summarizePressKeyArguments(argumentsJson: String): String =
        runCatching {
            val button = JSONObject(argumentsJson).optString("button").pressKeyLabel()
            listOfNotNull(ko("按键", "키 누르기"), button).joinToString(" · ")
        }.getOrDefault(ko("按键", "키 누르기"))

    private fun summarizeWaitArguments(argumentsJson: String): String =
        runCatching {
            val durationMs = JSONObject(argumentsJson).optInt("duration_ms", 1_000)
                .coerceAtLeast(0)
            val duration = if (durationMs >= 1_000) {
                String.format(Locale.US, "%.1f", durationMs / 1_000f)
                    .trimEnd('0').trimEnd('.') + ko(" 秒", "초")
            } else {
                ko("$durationMs 毫秒", "${durationMs}ms")
            }
            ko("等待", "대기") + " · $duration"
        }.getOrDefault(ko("等待", "대기"))

    private fun summarizeObservationArguments(argumentsJson: String): String =
        runCatching {
            val options = AgentScreenObservationContract.resolve(JSONObject(argumentsJson))
            buildList {
                add(ko("观察屏幕", "화면 관찰"))
                if (options.includeScreenshot) add(ko("含截图", "스크린샷 포함"))
                if (options.includeUiTree) add(ko("含界面树", "UI 트리 포함"))
            }.joinToString(" · ")
        }.getOrDefault(ko("观察屏幕", "화면 관찰"))

    private fun summarizeMemoryGetArguments(argumentsJson: String): String =
        runCatching {
            val arguments = JSONObject(argumentsJson)
            if (arguments.optString("query").isNotBlank()) ko("检索记忆", "기억 검색") else ko("读取记忆", "기억 읽기")
        }.getOrDefault(ko("读取记忆", "기억 읽기"))

    private fun summarizeMemoryWriteArguments(argumentsJson: String): String =
        runCatching {
            val arguments = JSONObject(argumentsJson)
            val mode = when (arguments.optString("mode")) {
                "replace_range" -> ko("替换片段", "일부 교체")
                "append" -> ko("追加", "추가")
                "clear" -> ko("清空", "비우기")
                else -> null
            }
            val content = arguments.optString("content")
            val lines = if (content.isEmpty()) 0 else content.count { it == '\n' } + 1
            buildList {
                add(ko("更新记忆", "기억 업데이트"))
                mode?.let(::add)
                add(ko("$lines 行", "${lines}줄"))
                add(content.toByteArray(Charsets.UTF_8).size.let { ko("$it 字节", "${it}바이트") })
            }.joinToString(" · ")
        }.getOrDefault(ko("更新记忆", "기억 업데이트"))

    /** 结果成败供事件与 UI 状态使用，不再依赖摘要文本里的标记。 */
    fun isSuccessResult(result: AgentModelClient.ToolResult): Boolean =
        parseResultJson(result)?.optBoolean("ok", true) ?: true

    fun summarizeResult(
        toolName: String,
        result: AgentModelClient.ToolResult,
    ): String {
        val json = parseResultJson(result)
        if (toolName == "web_search" || toolName == "fetch_url") return summarizeWebResult(toolName, json)
        // 终端 exit_code != 0 时 ok=false 但没有 code 字段，必须走专用分支保留退出码与输出
        if (toolName == "terminal" || toolName == "run_command") {
            return summarizeTerminalResult(json)
        }
        if (json?.optString("status") == "unconfirmed") return ko("操作已提交，效果未确认", "작업 제출됨(효과 미확인)")
        if (!isSuccessResult(result)) return summarizeFailure(json)
        return when (toolName) {
            BROWSER_TOOL_NAME -> json?.let(::summarizeBrowserResult) ?: ko("浏览器操作完成", "브라우저 작업 완료")
            AgentConversationToolCatalog.READ_HISTORY -> ko("已读取历史分页", "기록 페이지 읽음")
            "memory_get", "memory_write", "character_memory_get", "character_memory_write" ->
                json?.let { summarizeMemoryResult(toolName, it) } ?: ko("完成", "완료")
            "search_apps" -> json?.let(::summarizeSearchAppsResult) ?: ko("完成", "완료")
            "launch_app" -> json?.let(::summarizeLaunchAppResult) ?: ko("已打开", "실행됨")
            else -> json?.let { summarizeGenericResult(it, result) } ?: ko("完成", "완료")
        }
    }

    private fun parseResultJson(result: AgentModelClient.ToolResult): JSONObject? =
        runCatching { JSONObject(result.content) }.getOrNull()

    /**
     * 网页错误正文、搜索词和页面标题可能回显私密 URL，只保留计数。动作与主机已在参数摘要中，
     * 结果只给出结论，避免工作过程同一行重复。
     */
    private fun summarizeWebResult(toolName: String, json: JSONObject?): String {
        val label = if (toolName == "web_search") ko("网页搜索", "웹 검색") else ko("网页读取", "웹 페이지 읽기")
        if (json == null) return ko("完成", "완료")
        if (!json.optBoolean("ok", true)) {
            val code = json.optString("code").takeIf { it.matches(Regex("[A-Z][A-Z0-9_]{0,79}")) }
            return listOfNotNull(ko("${label}失败", "${label} 실패"), code?.let { "code=$it" }).joinToString(" · ")
        }
        if (toolName == "web_search") {
            val count = json.optJSONArray("results")?.length() ?: json.firstNonNegativeInt("count")
            return when (count) {
                null -> ko("完成", "완료")
                0 -> ko("没有找到结果", "결과 없음")
                else -> ko("$count 条结果", "결과 ${count}개")
            }
        }
        val chars = (json.opt("text") as? String)?.length
            ?: json.firstNonNegativeInt("returned_chars", "text_chars")
            ?: return ko("完成", "완료")
        return if (json.optBoolean("has_more")) ko("已读前 ${formatCharCount(chars)}", "앞부분 ${formatCharCount(chars)} 읽음") else formatCharCount(chars)
    }

    /** 失败摘要保留 code= 标记，供运行日志提取稳定错误码；message 是工具侧给出的中文原因。 */
    private fun summarizeFailure(json: JSONObject?): String {
        val code = json?.optString("code")?.takeIf { it.isNotBlank() }
        val reason = json?.optString("message")
            ?.let(::sanitizeSummaryValue)
            ?.takeIf { it.isNotBlank() }
        return buildList {
            add(ko("失败", "실패"))
            reason?.let(::add)
            code?.let { add("code=$it") }
        }.joinToString(" · ")
    }

    private fun summarizeMemoryResult(toolName: String, json: JSONObject): String =
        buildList {
            add(if (toolName.endsWith("memory_get")) ko("已读取记忆", "기억 읽음") else ko("已更新记忆", "기억 업데이트됨"))
            if (json.has("line_count")) add(json.optInt("line_count").let { ko("$it 行", "${it}줄") })
            if (json.has("bytes")) add(json.optInt("bytes").let { ko("$it 字节", "${it}바이트") })
        }.joinToString(" · ")

    private fun summarizeGenericResult(
        json: JSONObject,
        result: AgentModelClient.ToolResult,
    ): String =
        buildList {
            add(if (json.has("verified") && !json.optBoolean("verified")) ko("已执行，效果未确认", "실행됨(효과 미확인)") else ko("完成", "완료"))
            json.optJSONArray("entries")?.let { add(ko("${it.length()} 个条目", "항목 ${it.length()}개")) }
            json.optJSONArray("matches")?.let { add(ko("${it.length()} 个匹配", "일치 ${it.length()}개")) }
            if (json.has("bytes_read")) add(json.optLong("bytes_read").let { ko("已读 $it 字节", "${it}바이트 읽음") })
            if (json.has("replacements")) add(json.optInt("replacements").let { ko("替换 $it 处", "${it}곳 바꿈") })
            if (json.optBoolean("truncated") || json.optBoolean("has_more")) add(ko("可继续读取", "이어 읽기 가능"))
            if (json.optBoolean("partial") || json.optBoolean("scan_limited")) add(ko("采集范围有限", "수집 범위 제한됨"))
            json.optJSONArray("apps")?.let { add(ko("找到 ${it.length()} 个应用", "앱 ${it.length()}개 찾음")) }
            json.optJSONArray("candidates")?.let { add(ko("${it.length()} 个候选", "후보 ${it.length()}개")) }
            if (result.images.isNotEmpty()) add(ko("${result.images.size} 张图片", "이미지 ${result.images.size}장"))
        }.joinToString(" · ")

    private fun summarizeSearchAppsResult(json: JSONObject): String {
        val apps = json.optJSONArray("apps") ?: return ko("未找到匹配应用", "일치하는 앱 없음")
        val total = apps.length()
        if (total == 0) return ko("未找到匹配应用", "일치하는 앱 없음")
        val names = (0 until total).mapNotNull { index ->
            apps.optJSONObject(index)?.optString("app_name")
                ?.let(::sanitizeSummaryValue)
                ?.takeIf { it.isNotBlank() }
        }
        return buildString {
            append(ko("已找到 $total 个应用", "앱 ${total}개 찾음"))
            val shown = names.take(MAX_LISTED_APP_NAMES)
            if (shown.isNotEmpty()) {
                append(" · ").append(shown.joinToString(ko("、", ", ")))
                if (total > shown.size) append(ko(" 等", " 등"))
            }
        }
    }

    private fun summarizeLaunchAppResult(json: JSONObject): String {
        val appName = sanitizeSummaryValue(json.optString("app_name"))
        return if (appName.isNotBlank()) ko("已打开 · $appName", "실행됨 · $appName") else ko("已打开", "실행됨")
    }

    /**
     * 终端结果面向用户展示退出状态与输出预览；输出可能很长，
     * 只保留开头几行，截断时追加省略标记。
     */
    private fun summarizeTerminalResult(json: JSONObject?): String {
        if (json == null) return ko("终端", "터미널")
        if (json.optString("code").isNotBlank()) return summarizeFailure(json)
        if (!json.has("exit_code") || json.isNull("exit_code")) {
            val action = json.optString("action").terminalActionLabel()
            return if (json.optBoolean("ok", true)) ko("终端 · $action", "터미널 · $action") else ko("失败 · $action", "실패 · $action")
        }
        val exitCode = json.optInt("exit_code")
        val timedOut = json.optBoolean("timed_out", false)
        val status = when {
            timedOut -> ko("失败 · 执行超时", "실패 · 시간 초과")
            exitCode == 0 -> ko("执行完成", "실행 완료")
            else -> ko("失败 · 退出码 $exitCode", "실패 · 종료 코드 $exitCode")
        }
        val output = if (exitCode == 0) {
            json.optString("stdout")
        } else {
            json.optString("stderr").ifBlank { json.optString("stdout") }
        }
        val truncated = json.optBoolean("stdout_truncated", false) ||
            json.optBoolean("stderr_truncated", false)
        val preview = terminalOutputPreview(output, truncated) ?: return status
        return "$status\n$preview"
    }

    private fun terminalOutputPreview(output: String, truncated: Boolean): String? {
        val normalized = output.trim()
        if (normalized.isEmpty()) return null
        val allLines = normalized.lines()
        var preview = allLines.take(MAX_TERMINAL_PREVIEW_LINES).joinToString("\n")
        var capped = allLines.size > MAX_TERMINAL_PREVIEW_LINES || truncated
        if (preview.length > MAX_TERMINAL_PREVIEW_CHARS) {
            preview = preview.take(MAX_TERMINAL_PREVIEW_CHARS)
            capped = true
        }
        return if (capped) "$preview\n…" else preview
    }

    private fun summarizeBrowserResult(json: JSONObject): String {
        val page = json.optJSONObject("page")
            ?: json.optJSONObject("page_info")
            ?: json.optJSONObject("pageInfo")
        val action = json.optString("action")
            .takeIf { it in BROWSER_ACTIONS }
            ?: "unknown"
        val host = sequenceOf(json, page)
            .filterNotNull()
            .flatMap { source ->
                sequenceOf("url", "current_url", "currentUrl", "final_url", "finalUrl")
                    .map(source::optString)
            }
            .mapNotNull(::safeHttpHost)
            .firstOrNull()
        val title = sequenceOf(json, page)
            .filterNotNull()
            .map { it.opt("title") }
            .filterIsInstance<String>()
            .map(::sanitizeSummaryValue)
            .firstOrNull { it.isNotBlank() }
        val textChars = sequenceOf(json, page)
            .filterNotNull()
            .mapNotNull { source ->
                source.firstNonNegativeInt("text_length", "textLength", "text_chars", "textChars")
            }
            .firstOrNull()
            ?: sequenceOf(json, page)
                .filterNotNull()
                .flatMap { source -> sequenceOf("text", "readable", "content").map(source::opt) }
                .filterIsInstance<String>()
                .map(String::length)
                .firstOrNull()
        val elementCount = json.firstNonNegativeInt("element_count", "elementCount", "elements_count")
            ?: json.optJSONArray("elements")?.length()

        return buildList {
            add(action.browserSuccessLabel())
            host?.let(::add)
            title?.let { add(ko("《$it》", "「$it」")) }
            if (action in BROWSER_TEXT_ACTIONS) {
                textChars?.let { add(ko("约 ${formatCharCount(it)}", "약 ${formatCharCount(it)}")) }
            }
            elementCount?.let { add(ko("$it 个元素", "요소 ${it}개")) }
            if (json.optBoolean("truncated", false)) add(ko("已截断", "잘림"))
        }.joinToString(" · ")
    }

    private fun formatCharCount(chars: Int): String =
        if (chars >= 10_000) {
            String.format(Locale.US, "%.1f", chars / 10_000f).trimEnd('0').trimEnd('.') + ko(" 万字", "만 자")
        } else {
            ko("$chars 字", "${chars}자")
        }

    private fun JSONObject.firstNonNegativeInt(vararg keys: String): Int? =
        keys.firstNotNullOfOrNull { key ->
            if (!has(key)) return@firstNotNullOfOrNull null
            optInt(key, -1).takeIf { it >= 0 }
        }

    private fun sanitizeSummaryValue(value: String, maxChars: Int = 80): String =
        value.replace(Regex("\\s+"), " ")
            .trim()
            .replace(',', '，')
            .replace('=', '＝')
            .let { if (it.length <= maxChars) it else it.take(maxChars) + "..." }

    private fun safeHttpHost(rawUrl: String): String? =
        rawUrl.trim()
            .takeIf(String::isNotEmpty)
            ?.let { value ->
                runCatching {
                    val uri = URI(value)
                    uri.host
                        ?.takeIf {
                            uri.scheme.equals("http", ignoreCase = true) ||
                                uri.scheme.equals("https", ignoreCase = true)
                        }
                        ?.lowercase()
                        ?.take(160)
                }.getOrNull()
            }

    private fun String.browserActionLabel(): String = when (this) {
        "navigate" -> ko("打开网页", "웹 페이지 열기")
        "get_readable" -> ko("提取正文", "본문 추출")
        "get_text" -> ko("读取文本", "텍스트 읽기")
        "find_elements" -> ko("查找元素", "요소 찾기")
        "click" -> ko("点击网页", "웹 페이지 클릭")
        "type" -> ko("输入内容", "내용 입력")
        "scroll" -> ko("滚动网页", "웹 페이지 스크롤")
        "screenshot" -> ko("网页截图", "웹 페이지 스크린샷")
        "get_page_info" -> ko("查看网页信息", "웹 페이지 정보 보기")
        "go_back" -> ko("网页后退", "뒤로 가기")
        "go_forward" -> ko("网页前进", "앞으로 가기")
        "reload" -> ko("刷新网页", "웹 페이지 새로고침")
        "wait_for_selector" -> ko("等待网页元素", "웹 요소 대기")
        else -> ko("浏览器操作", "브라우저 작업")
    }

    private fun String.browserSuccessLabel(): String = when (this) {
        "navigate" -> ko("已打开", "열림")
        "get_readable" -> ko("已提取正文", "본문 추출됨")
        "get_text" -> ko("已读取文本", "텍스트 읽음")
        "find_elements" -> ko("已找到元素", "요소 찾음")
        "click" -> ko("已点击网页", "클릭함")
        "type" -> ko("已输入内容", "입력함")
        "scroll" -> ko("已滚动网页", "스크롤함")
        "screenshot" -> ko("已截图", "스크린샷 찍음")
        "get_page_info" -> ko("已读取页面信息", "페이지 정보 읽음")
        "go_back" -> ko("已后退", "뒤로 감")
        "go_forward" -> ko("已前进", "앞으로 감")
        "reload" -> ko("已刷新", "새로고침함")
        "wait_for_selector" -> ko("已等到目标元素", "대상 요소 확인됨")
        else -> ko("浏览器操作完成", "브라우저 작업 완료")
    }

    private fun String.scrollDirectionLabel(): String? = when (lowercase(Locale.US)) {
        "up" -> ko("向上", "위로")
        "down" -> ko("向下", "아래로")
        "left" -> ko("向左", "왼쪽으로")
        "right" -> ko("向右", "오른쪽으로")
        else -> null
    }

    private fun String.pressKeyLabel(): String? = when (lowercase(Locale.US)) {
        "back" -> ko("返回", "뒤로")
        "home" -> ko("主页", "홈")
        "recents", "recent" -> ko("最近任务", "최근 앱")
        "notifications" -> ko("通知栏", "알림창")
        "quick_settings" -> ko("控制中心", "빠른 설정")
        "power" -> ko("电源", "전원")
        "volume_up" -> ko("音量加", "볼륨 크게")
        "volume_down" -> ko("音量减", "볼륨 작게")
        "mute" -> ko("静音", "음소거")
        else -> null
    }

    private fun String.terminalActionLabel(): String = when (this) {
        "open" -> ko("创建会话", "세션 생성")
        "exec" -> ko("执行命令", "명령 실행")
        "open_and_exec" -> ko("单次执行", "단일 실행")
        "read_async_result" -> ko("读取后台输出", "백그라운드 출력 읽기")
        "close" -> ko("关闭终端", "터미널 닫기")
        "daemon_start" -> ko("启动守护任务", "데몬 시작")
        "daemon_list" -> ko("守护任务列表", "데몬 목록")
        "daemon_logs" -> ko("查看守护日志", "데몬 로그 보기")
        "daemon_stop" -> ko("停止守护任务", "데몬 중지")
        else -> ko("终端操作", "터미널 작업")
    }

    private fun String.terminalEnvironmentLabel(): String = when (this) {
        "linux" -> "Linux"
        "alpine" -> "Alpine"
        "debian" -> "Debian"
        else -> "Android"
    }

    private companion object {
        const val BROWSER_TOOL_NAME = "browser_use"
        const val MAX_DISPLAY_COMMAND_CHARS = 4_000
        const val MAX_QUERY_SUMMARY_CHARS = 30
        const val MAX_LISTED_APP_NAMES = 3
        const val MAX_TERMINAL_PREVIEW_LINES = 3
        const val MAX_TERMINAL_PREVIEW_CHARS = 240
        val SENSITIVE_ASSIGNMENT = Regex(
            """(?i)\b([A-Z0-9_]*(?:API[_-]?KEY|ACCESS[_-]?TOKEN|AUTH[_-]?TOKEN|TOKEN|PASSWORD|PASSWD|SECRET)[A-Z0-9_]*)\s*=\s*(?:"[^"]*"|'[^']*'|[^\s;&|]+)"""
        )
        val SENSITIVE_FLAG = Regex(
            """(?i)(--?(?:password|passwd|token|api[-_]?key|secret)(?:\s*=\s*|\s+))(?:"[^"]*"|'[^']*'|[^\s;&|]+)"""
        )
        val SENSITIVE_HEADER = Regex(
            """(?i)\b(Authorization|Proxy-Authorization|X-Api-Key)(\s*:\s*)[^'"\r\n;&|]+"""
        )
        val BROWSER_ACTIONS = setOf(
            "navigate",
            "get_readable",
            "get_text",
            "find_elements",
            "click",
            "type",
            "scroll",
            "screenshot",
            "get_page_info",
            "go_back",
            "go_forward",
            "reload",
            "wait_for_selector",
        )
        val BROWSER_TEXT_ACTIONS = setOf("get_readable", "get_text")

        /** 结构化设备工具只展示动作标签，不暴露任何参数。 */
        val DEVICE_ACTION_LABELS: Map<String, String>
            get() = mapOf(
            "set_alarm" to ko("设置闹钟", "알람 설정"),
            "set_timer" to ko("设置计时器", "타이머 설정"),
            "device_status" to ko("查看设备状态", "기기 상태 보기"),
            "network_info" to ko("查看网络信息", "네트워크 정보 보기"),
            "top_memory_apps" to ko("查看内存占用排行", "메모리 사용량 순위 보기"),
            "top_storage_apps" to ko("查看存储占用排行", "저장공간 사용량 순위 보기"),
            "media_control" to ko("控制媒体播放", "미디어 재생 제어"),
            "set_volume" to ko("调整音量", "볼륨 조절"),
            "get_setting" to ko("读取系统设置", "시스템 설정 읽기"),
            "wifi_credentials" to ko("读取 Wi-Fi 密码", "Wi-Fi 비밀번호 읽기"),
            "recent_notifications" to ko("读取最近通知", "최근 알림 읽기"),
            "search_notification_history" to ko("搜索通知历史", "알림 기록 검색"),
            "recent_app_activity" to ko("查看应用活动", "앱 활동 보기"),
            "app_usage_summary" to ko("查看应用使用统计", "앱 사용 통계 보기"),
            "get_current_location" to ko("获取当前位置", "현재 위치 가져오기"),
            "get_device_environment" to ko("查看设备环境", "기기 환경 보기"),
            "list_alarms" to ko("查看闹钟列表", "알람 목록 보기"),
            "list_active_timers" to ko("查看计时器", "타이머 보기"),
            "search_clipboard_history" to ko("搜索剪贴板历史", "클립보드 기록 검색"),
            "get_health_summary" to ko("查看健康摘要", "건강 요약 보기"),
            "read_sms_code" to ko("读取短信验证码", "SMS 인증번호 읽기"),
            "get_logcat" to ko("读取系统日志", "시스템 로그 읽기"),
            "search_media" to ko("搜索媒体文件", "미디어 파일 검색"),
            "search_audio" to ko("搜索音频", "오디오 검색"),
            "search_recordings" to ko("搜索录音", "녹음 검색"),
            "search_files" to ko("搜索文件", "파일 검색"),
            "search_calendar_events" to ko("搜索日程", "일정 검색"),
            "search_contacts" to ko("搜索联系人", "연락처 검색"),
            "search_call_history" to ko("搜索通话记录", "통화 기록 검색"),
            "search_messages" to ko("搜索短信", "문자 메시지 검색"),
            "search_downloads" to ko("搜索下载内容", "다운로드 검색"),
            "search_coloros_notes" to ko("搜索便签", "메모 검색"),
            "search_coloros_recordings" to ko("搜索录音机", "녹음기 검색"),
            "search_recording_summaries" to ko("搜索录音摘要", "녹음 요약 검색"),
            "search_coloros_memories" to ko("搜索小布记忆", "Breeno 기억 검색"),
            "search_saved_places" to ko("搜索收藏地点", "저장된 장소 검색"),
            "search_personal_orders" to ko("搜索个人订单", "개인 주문 검색"),
            "search_qq_chat_images" to ko("搜索 QQ 聊天图片", "QQ 채팅 이미지 검색"),
            "search_wechat_chat_images" to ko("搜索微信聊天图片", "WeChat 채팅 이미지 검색"),
            "set_setting" to ko("修改系统设置", "시스템 설정 변경"),
            "set_device_state" to ko("修改设备状态", "기기 상태 변경"),
            "app_state_control" to ko("管理应用状态", "앱 상태 관리"),
        )
    }
}
