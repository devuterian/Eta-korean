package io.github.mangi.eta.agent.context

import io.github.mangi.eta.i18n.ko
import org.json.JSONArray
import org.json.JSONObject

/** 模型面对领域名称；索引资源与协议仅在这里映射，不成为公开工具参数。 */
internal object PersonalSearchTools {
    data class Search(
        val name: String,
        val source: String,
        val title: String,
        /** 发送给模型的工具描述，必须保持原文。 */
        val description: String,
        /** 仅用于界面展示的描述（随系统语言本地化）。 */
        val displayDescription: String = description,
    )

    val searches =
        listOf(
            Search("search_bills", "bills", ko("查询账单", "결제 내역 검색"), "按商户、内容和时间查找账单记录。", ko("按商户、内容和时间查找账单记录。", "가맹점, 내용, 시간으로 결제 내역을 찾습니다.")),
            Search("search_todos", "todos", ko("查询待办线索", "할 일 단서 검색"), "查询系统从个人信息中提取的待办线索，未识别时间的记录不能按时间命中。", ko("查询系统从个人信息中提取的待办线索，未识别时间的记录不能按时间命中。", "시스템이 개인 정보에서 추출한 할 일 단서를 검색합니다. 시간이 인식되지 않은 기록은 시간으로 검색되지 않습니다.")),
            Search("search_calendar_todos", "calendar_todos", ko("查询日历待办", "캘린더 할 일 검색"), "查询日历中的待办及其计划时间。", ko("查询日历中的待办及其计划时间。", "캘린더의 할 일과 예정 시간을 검색합니다.")),
            Search("search_memory_collections", "collections", ko("查询记忆合集", "기억 모음 검색"), "查找已保存的记忆合集，不支持业务时间筛选。", ko("查找已保存的记忆合集，不支持业务时间筛选。", "저장된 기억 모음을 찾습니다. 시간 필터는 지원하지 않습니다.")),
            Search("search_daily_events", "events", ko("查询生活事件", "생활 이벤트 검색"), "查找系统记录或推断的生活事件，不把推断当成确认事实。", ko("查找系统记录或推断的生活事件，不把推断当成确认事实。", "시스템이 기록하거나 추론한 생활 이벤트를 찾습니다. 추론은 확인된 사실로 취급하지 않습니다.")),
            Search("search_flights", "flights", ko("查询航班行程", "항공편 일정 검색"), "按航班、机场和计划起飞时间检索行程。", ko("按航班、机场和计划起飞时间检索行程。", "항공편, 공항, 예정 출발 시간으로 일정을 검색합니다.")),
            Search("search_hotels", "hotels", ko("查询酒店预订", "호텔 예약 검색"), "按酒店、地址、订单和入住时间检索预订。", ko("按酒店、地址、订单和入住时间检索预订。", "호텔, 주소, 주문, 체크인 시간으로 예약을 검색합니다.")),
            Search("search_trains", "trains", ko("查询火车行程", "열차 일정 검색"), "按车次、车站和出发时间检索行程。", ko("按车次、车站和出发时间检索行程。", "열차 번호, 역, 출발 시간으로 일정을 검색합니다.")),
        )
    val names =
        searches.mapTo(linkedSetOf()) { it.name } + setOf("read_personal_item", "summarize_bills")
    val aliases =
        mapOf(
            "search_coloros_notes" to "search_notes",
            "search_coloros_memories" to "search_system_memories",
        )

    fun canonical(name: String): String = aliases[name] ?: name

    fun isIndexedSearch(name: String): Boolean = searches.any { it.name == name }
}

internal class IndexedPersonalSearch(private val service: PersonalContextQueryService) {
    fun execute(name: String, arguments: JSONObject, fallback: Boolean = false): JSONObject? {
        val request = JSONObject(arguments.toString())
        val source =
            when (name) {
                "search_media" ->
                    if (request.optString("match", "name") == "content") "photos" else return null
                "search_files" ->
                    if (request.optString("match", "name") == "content") "files" else return null
                "search_notes" -> if (fallback) "notes" else return null
                "search_system_memories" ->
                    if (hasExtendedFilter(request)) "memories" else return null
                "summarize_bills" -> "bills"
                "read_personal_item" -> {
                    val reference = request.opt("ref") as? String ?: return invalid()
                    val parts = reference.split(':')
                    if (
                        parts.size != 3 ||
                            parts[0] != "eta-index" ||
                            PersonalContextSources.find(parts[1]) == null ||
                            !Regex("[0-9]{1,19}").matches(parts[2]) ||
                            parts[2].toLongOrNull() == null ||
                            request.length() != 1
                    )
                        return invalid()
                    request.remove("ref")
                    request.put("id", parts[2])
                    parts[1]
                }
                else ->
                    PersonalSearchTools.searches.firstOrNull { it.name == name }?.source
                        ?: return null
            }
        request.remove("match")
        request.remove("current_only")
        request
            .put("source", source)
            .put(
                "action",
                when (name) {
                    "read_personal_item" -> "read"
                    "summarize_bills" -> "bill_summary"
                    else -> "search"
                },
            )
        return service.execute(request).put("tool", name).also { result ->
            val items = result.optJSONArray("items") ?: JSONArray()
            for (index in 0 until items.length()) {
                val item = items.getJSONObject(index)
                item
                    .optString("id")
                    .takeIf { it.toLongOrNull() != null }
                    ?.let {
                        item.put("ref", "eta-index:$source:$it")
                    }
            }
            result.put("reference_scope", "索引引用仅用于读取；修改原始应用记录必须重新核对原始对象 ID")
        }
    }

    private fun hasExtendedFilter(args: JSONObject) =
        listOf("start_time", "end_time", "offset", "sort").any(args::has)

    private fun invalid() =
        JSONObject()
            .put("ok", false)
            .put("code", "INVALID_ARGUMENT")
            .put("message", "必须提供查询结果返回的完整 ref")
}
