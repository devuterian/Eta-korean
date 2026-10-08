package io.github.mangi.eta.agent.roleplay

import io.github.mangi.eta.i18n.ko
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal object CharacterCardCompatibility {
    private val html = Regex(
        "<\\s*/?\\s*(?:script|iframe|style|div|span|details|summary|html|body|button|input|img|audio|video|canvas|table|p|br|a)(?=[\\s/>])",
        RegexOption.IGNORE_CASE,
    )

    fun warnings(card: CharacterCard): List<String> = buildList {
        val fields = listOf(card.description, card.personality, card.scenario, card.firstMessage,
            card.exampleMessages, card.systemPrompt, card.postHistoryInstructions) + card.alternateGreetings +
            ((card.extensions["depth_prompt"] as? JsonObject)?.text("prompt") ?: "") +
            (card.characterBook?.get("entries") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.text("content") }
        if (fields.any { CharacterMacros.hasUnsupportedMacros(it, card) }) {
            add(ko("含未支持的宏，已保留原文，不执行变量、条件或脚本操作。", "지원하지 않는 매크로가 있습니다. 원문은 그대로 두고 변수, 조건, 스크립트는 실행하지 않습니다."))
        }
        if (fields.any { html.containsMatchIn(it) }) {
            add(ko("含 HTML 或脚本界面标记，按文本保留，不运行交互界面。", "HTML 또는 스크립트 UI 태그가 있습니다. 텍스트로만 보존하고 인터랙티브 UI는 실행하지 않습니다."))
        }
        val extensionKeys = mutableSetOf<String>()
        collectExtensionKeys(card.extensions, extensionKeys, 0)
        if (extensionKeys.any { it.contains("regex") }) add(ko("含正则替换扩展，数据会保留，替换规则不执行。", "정규식 치환 확장이 있습니다. 데이터는 보존하지만 치환 규칙은 실행하지 않습니다."))
        if (extensionKeys.any { it.contains("script") }) add(ko("含脚本扩展，数据会保留，脚本不执行。", "스크립트 확장이 있습니다. 데이터는 보존하지만 스크립트는 실행하지 않습니다."))
        card.extensions["depth_prompt"]?.takeUnless { it == JsonNull }?.let { value ->
            if (value !is JsonObject || value.text("prompt").isNotBlank() && card.depthPrompt == null) {
                add(ko("角色深度备注的参数未受支持，已跳过该备注。", "캐릭터 깊이 메모의 매개변수를 지원하지 않아 해당 메모를 건너뛰었습니다."))
            }
        }
        val unsupported = CharacterWorldbook.unsupportedEntries(card)
        if (unsupported.isNotEmpty()) add(ko("${unsupported.size} 条世界书使用未支持的触发条件，已跳过这些条目。", "월드북 항목 ${unsupported.size}개가 지원하지 않는 트리거 조건을 사용해 건너뛰었습니다."))
        if ((card.data["assets"] as? JsonArray)?.isNotEmpty() == true) add(ko("附带资源清单已保留，本版使用角色卡主图，不加载额外资源。", "첨부 리소스 목록은 보존했습니다. 이 버전은 캐릭터 카드 대표 이미지만 사용하며 추가 리소스는 불러오지 않습니다."))
        if (card.data.strings("group_only_greetings").isNotEmpty()) add(ko("群聊专用开场白已保留，本版仅用于单角色对话。", "그룹 채팅 전용 인사말은 보존했습니다. 이 버전은 1:1 캐릭터 대화만 지원합니다."))
    }

    private fun collectExtensionKeys(value: JsonElement, keys: MutableSet<String>, depth: Int) {
        if (depth >= 4 || value !is JsonObject) return
        value.forEach { (key, child) ->
            val present = when (child) {
                JsonNull -> false
                is JsonArray -> child.isNotEmpty()
                is JsonObject -> child.isNotEmpty()
                is JsonPrimitive -> child.content.isNotBlank() && child.content != "false"
            }
            if (present) keys += key.lowercase()
            collectExtensionKeys(child, keys, depth + 1)
        }
    }
}
