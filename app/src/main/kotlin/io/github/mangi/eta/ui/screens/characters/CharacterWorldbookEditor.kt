package io.github.mangi.eta.ui.screens.characters

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.roleplay.CharacterBookEntryDraft
import io.github.mangi.eta.agent.roleplay.CharacterWorldbook
import io.github.mangi.eta.i18n.ko
import io.github.mangi.eta.ui.app.CharacterLibraryStore
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaCard
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaSwitch
import io.github.mangi.eta.ui.components.EtaSwitchPreference
import io.github.mangi.eta.ui.components.EtaTextButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal fun LazyListScope.characterWorldbookEditor(
    store: CharacterLibraryStore,
    expanded: Boolean,
    expandedEntry: Int?,
    onToggleExpanded: () -> Unit,
    onExpandEntry: (Int?) -> Unit,
) {
    val card = store.draft ?: return
    val book = card.worldbookDraft()
    val enabled = !store.busy
    item(key = "worldbook-title") { EtaPreferenceGroupTitle(ko("世界书", "월드북")) }
    item(key = "worldbook-header") {
        EtaPreferenceGroup(modifier = Modifier.padding(horizontal = CharacterCardPadding)) {
            EtaArrowPreference(
                title = ko("内嵌世界书", "내장 월드북"),
                summary = ko("${book.entries.size} 个条目 · ${if (expanded) "收起" else "展开编辑"}", "항목 ${book.entries.size}개 · ${if (expanded) "접기" else "펼쳐서 편집"}"),
                onClick = onToggleExpanded,
            )
        }
    }
    if (!expanded) return
    item(key = "worldbook-name") {
        CharacterTextField(ko("世界书名称", "월드북 이름"), book.name, { value -> store.updateWorldbook { it.copy(name = value) } }, enabled, singleLine = true)
    }
    item(key = "worldbook-depth") {
        CharacterTextField(ko("扫描最近消息数（留空使用默认值）", "스캔할 최근 메시지 수(비우면 기본값)"), book.scanDepth?.toString().orEmpty(), { value ->
            optionalNonNegativeInt(value) { number -> store.updateWorldbook { it.copy(scanDepth = number) } }
        }, enabled, singleLine = true)
    }
    item(key = "worldbook-budget") {
        CharacterTextField(ko("Token 预算（留空自动分配）", "토큰 예산(비우면 자동 할당)"), book.tokenBudget?.toString().orEmpty(), { value ->
            optionalNonNegativeInt(value) { number -> store.updateWorldbook { it.copy(tokenBudget = number) } }
        }, enabled, singleLine = true)
    }
    item(key = "worldbook-recursive") {
        EtaPreferenceGroup(modifier = Modifier.padding(horizontal = CharacterCardPadding, vertical = 8.dp)) {
            EtaSwitchPreference(
                title = ko("递归匹配", "재귀 매칭"),
                summary = ko("使用已匹配条目的内容继续寻找相关条目", "매칭된 항목의 내용으로 관련 항목을 계속 찾습니다"),
                checked = book.recursiveScanning == true,
                enabled = enabled,
                onCheckedChange = { value -> store.updateWorldbook { it.copy(recursiveScanning = value) } },
            )
        }
    }
    item(key = "worldbook-entries-title") { EtaPreferenceGroupTitle(ko("条目", "항목")) }
    val unsupported = CharacterWorldbook.unsupportedEntries(card).associate { it.index to it.reasons }
    itemsIndexed(book.entries, key = { index, _ -> "worldbook-entry-$index" }) { index, entry ->
        EtaPreferenceGroup(
            modifier = Modifier
                .padding(horizontal = CharacterCardPadding, vertical = 8.dp),
        ) {
            EtaArrowPreference(
                title = entry.name.take(120).ifBlank { ko("条目 ${index + 1}", "항목 ${index + 1}") },
                summary = when {
                    !entry.enabled -> ko("已停用", "사용 안 함")
                    !unsupported[index].isNullOrEmpty() -> ko("已跳过：${unsupported[index].orEmpty().joinToString("；")}", "건너뜀: ${unsupported[index].orEmpty().joinToString("; ")}")
                    entry.constant -> ko("始终参与上下文", "항상 컨텍스트에 포함")
                    else -> entry.keys.take(4).joinToString(ko("、", ", ")).take(160).ifBlank { ko("尚未设置触发关键词", "트리거 키워드 미설정") }
                },
                onClick = { onExpandEntry(if (expandedEntry == index) null else index) },
                endActions = {
                    EtaSwitch(
                        checked = entry.enabled,
                        enabled = enabled,
                        onCheckedChange = { value ->
                            store.updateWorldbook { current ->
                                current.copy(entries = current.entries.toMutableList().apply { this[index] = entry.copy(enabled = value) })
                            }
                        },
                    )
                },
            )
            if (expandedEntry == index) {
                CharacterWorldbookEntryEditor(
                    entry = entry,
                    enabled = enabled,
                    unsupported = unsupported[index].orEmpty(),
                    onChange = { replacement ->
                        store.updateWorldbook { current -> current.copy(entries = current.entries.toMutableList().apply { this[index] = replacement }) }
                    },
                    onDelete = {
                        onExpandEntry(null)
                        store.updateWorldbook { current -> current.copy(entries = current.entries.filterIndexed { i, _ -> i != index }) }
                    },
                )
            }
        }
    }
    item(key = "worldbook-add-entry") {
        EtaTextButton(
            text = ko("添加条目", "항목 추가"),
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = CharacterCardPadding, vertical = 8.dp),
            onClick = {
                onExpandEntry(book.entries.size)
                store.updateWorldbook { it.copy(entries = it.entries + CharacterBookEntryDraft(insertionOrder = it.entries.size)) }
            },
        )
    }
}

@Composable
private fun CharacterWorldbookEntryEditor(
    entry: CharacterBookEntryDraft,
    enabled: Boolean,
    unsupported: List<String>,
    onChange: (CharacterBookEntryDraft) -> Unit,
    onDelete: () -> Unit,
) {
    if (unsupported.isNotEmpty()) {
        Text(
            ko("此条目暂不参与匹配：${unsupported.joinToString("；")}", "이 항목은 현재 매칭에 사용되지 않습니다: ${unsupported.joinToString("; ")}"),
            style = MiuixTheme.textStyles.body2,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
    CharacterFieldGroupLabel(ko("内容", "내용"))
    CharacterTextField(ko("条目标题", "항목 제목"), entry.name, { onChange(entry.copy(name = it)) }, enabled, singleLine = true)
    CharacterTextField(ko("内容", "내용"), entry.content, { onChange(entry.copy(content = it)) }, enabled, minLines = 4)
    CharacterFieldGroupLabel(ko("触发", "트리거"))
    CharacterTextField(ko("主关键词（每行一个）", "기본 키워드(한 줄에 하나)"), entry.keys.joinToString("\n"), { onChange(entry.copy(keys = it.lines())) }, enabled)
    CharacterTextField(ko("次级关键词（每行一个）", "보조 키워드(한 줄에 하나)"), entry.secondaryKeys.joinToString("\n"), { onChange(entry.copy(secondaryKeys = it.lines())) }, enabled)
    EtaSwitchPreference(
        title = ko("同时匹配次级关键词", "보조 키워드도 함께 매칭"),
        checked = entry.selective,
        enabled = enabled,
        onCheckedChange = { onChange(entry.copy(selective = it)) },
    )
    CharacterFieldGroupLabel(ko("插入", "삽입"))
    EtaSwitchPreference(
        title = ko("常驻上下文", "상시 컨텍스트"),
        checked = entry.constant,
        enabled = enabled,
        onCheckedChange = { onChange(entry.copy(constant = it)) },
    )
    EtaSwitchPreference(
        title = ko("放在角色设定之前", "캐릭터 설정 앞에 배치"),
        summary = ko("关闭时放在角色设定之后", "끄면 캐릭터 설정 뒤에 배치"),
        checked = entry.position == "before_char",
        enabled = enabled,
        onCheckedChange = { onChange(entry.copy(position = if (it) "before_char" else "after_char")) },
    )
    CharacterTextField(ko("插入顺序", "삽입 순서"), entry.insertionOrder.toString(), { value ->
        value.toIntOrNull()?.takeIf { it >= 0 }?.let { onChange(entry.copy(insertionOrder = it)) }
    }, enabled, singleLine = true)
    EtaTextButton(
        text = ko("移除条目", "항목 삭제"),
        enabled = enabled,
        onClick = onDelete,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

private fun optionalNonNegativeInt(value: String, onValue: (Int?) -> Unit) {
    if (value.isBlank()) onValue(null)
    else value.toIntOrNull()?.takeIf { it >= 0 }?.let(onValue)
}
