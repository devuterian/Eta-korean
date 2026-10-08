package io.github.mangi.eta.agent.roleplay

import io.github.mangi.eta.i18n.ko
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

internal data class UnsupportedWorldbookEntry(val index: Int, val reasons: List<String>)

/** 同一判定同时用于导入后的能力说明和每轮投影，未支持的条件不退化为无条件触发。 */
internal object CharacterWorldbookSupport {
    fun reasons(entry: JsonObject): List<String> = buildList {
        val extensions = entry["extensions"] as? JsonObject ?: JsonObject(emptyMap())
        val useRegex = (entry["use_regex"] as? JsonPrimitive)?.booleanOrNull != false
        if (useRegex && (entry.strings("keys") + entry.strings("secondary_keys")).any(::regexKey)) add(ko("正则关键字", "정규식 키워드"))
        if (entry.text("content").lineSequence().any { it.trimStart().startsWith("@@") }) add(ko("世界书装饰器", "월드북 데코레이터"))
        val position = (extensions["position"] as? JsonPrimitive)?.intOrNull
        if (extensions["position"].let { it != null && it != JsonNull } && (position == null || position !in 0..1)) add(ko("特殊插入位置", "특수 삽입 위치"))
        if (entry.text("position").let { it.isNotEmpty() && it !in setOf("before_char", "after_char") }) add(ko("特殊插入位置", "특수 삽입 위치"))
        if (extensions.nonzero("selectiveLogic")) add(ko("高级次级匹配", "고급 보조 매칭"))
        val probabilityEnabled = (extensions["useProbability"] as? JsonPrimitive)?.booleanOrNull != false
        val probability = (extensions["probability"] as? JsonPrimitive)?.doubleOrNull
        if (probabilityEnabled && probability != null && probability != 100.0) add(ko("概率触发", "확률 트리거"))
        if (extensions.nonzero("group")) add(ko("条目分组", "항목 그룹"))
        if (listOf("sticky", "cooldown", "delay", "delay_until_recursion").any { extensions.nonzero(it) }) add(ko("时序触发", "타이밍 트리거"))
        if (listOf("exclude_recursion", "prevent_recursion", "ignore_budget", "match_whole_words", "vectorized",
                "match_persona_description", "match_character_description", "match_character_personality",
                "match_character_depth_prompt", "match_scenario", "match_creator_notes")
            .any { (extensions[it] as? JsonPrimitive)?.booleanOrNull == true }) add(ko("扩展匹配条件", "확장 매칭 조건"))
        if ((extensions["triggers"] as? JsonArray)?.isNotEmpty() == true) add(ko("指定生成类型", "생성 유형 지정"))
        if (extensions.text("automation_id").isNotBlank()) add(ko("脚本自动化", "스크립트 자동화"))
    }.distinct()

    private fun regexKey(key: String): Boolean {
        val slash = key.lastIndexOf('/')
        return key.startsWith('/') && slash > 0 && key.substring(slash + 1).all(Char::isLetter)
    }

    private fun JsonObject.nonzero(key: String): Boolean {
        val value = get(key)
        if (value == null || value == JsonNull) return false
        return (value as? JsonPrimitive)?.let {
            it.booleanOrNull ?: it.doubleOrNull?.let { number -> number != 0.0 } ?: it.content.isNotBlank()
        } ?: (value as? JsonArray)?.isNotEmpty() ?: true
    }
}
