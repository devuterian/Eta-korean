package io.github.mangi.eta.ui.screens.characters

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.i18n.ko
import io.github.mangi.eta.ui.app.CharacterLibraryStore
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaCard
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun CharacterEditorScreen(
    id: String?,
    store: CharacterLibraryStore,
    onBack: () -> Unit,
    onSaved: (String) -> Unit,
) {
    var advanced by rememberSaveable { mutableStateOf(false) }
    var worldbookExpanded by rememberSaveable { mutableStateOf(false) }
    var worldbookEntry by rememberSaveable { mutableStateOf<Int?>(null) }
    val card = store.draft
    MiuixScaffoldPage(
        title = if (id == null) ko("创建角色", "캐릭터 만들기") else ko("编辑角色", "캐릭터 편집"),
        onBack = onBack,
        modifier = Modifier.imePadding(),
        actions = {
            IconButton(
                onClick = { store.saveEditor(onSaved) },
                enabled = !store.busy && store.draftName.isNotBlank(),
            ) {
                Icon(Icons.Rounded.Check, contentDescription = ko("保存角色", "캐릭터 저장"))
            }
        },
    ) {
        if (card == null) {
            item { CharacterPageMessage(if (store.busy) ko("正在读取…", "불러오는 중…") else ko("无法读取角色，请返回重试", "캐릭터를 불러올 수 없습니다. 돌아가서 다시 시도하세요.")) }
            return@MiuixScaffoldPage
        }
        item(key = "name") { CharacterTextField(ko("名称", "이름"), store.draftName, store::updateName, !store.busy, singleLine = true) }
        item(key = "description-title") { EtaPreferenceGroupTitle(ko("角色设定", "캐릭터 설정")) }
        item(key = "description") { CharacterTextField(ko("外貌、性格与经历", "외모, 성격, 경력"), card.description, { value -> store.updateDraft { it.withEdits(description = value) } }, !store.busy, minLines = 5) }
        item(key = "greeting-title") { EtaPreferenceGroupTitle(ko("开场白", "인사말")) }
        item(key = "greeting") { CharacterTextField(ko("默认开场白", "기본 인사말"), card.firstMessage, { value -> store.updateDraft { it.withEdits(firstMessage = value) } }, !store.busy) }
        itemsIndexed(card.alternateGreetings, key = { index, _ -> "alternate-$index" }) { index, value ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextField(
                    value = value,
                    onValueChange = { updated ->
                        store.updateDraft { it.withEdits(alternateGreetings = it.alternateGreetings.toMutableList().apply { this[index] = updated }) }
                    },
                    label = ko("开场白 ${index + 2}", "인사말 ${index + 2}"),
                    enabled = !store.busy,
                    minLines = 2,
                    maxLines = 14,
                    modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                )
                IconButton(
                    onClick = {
                        store.updateDraft { it.withEdits(alternateGreetings = it.alternateGreetings.filterIndexed { i, _ -> i != index }) }
                    },
                    enabled = !store.busy,
                    minWidth = 36.dp,
                    minHeight = 36.dp,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = ko("移除此开场白", "이 인사말 삭제"),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
        item(key = "add-greeting") {
            EtaTextButton(
                ko("添加备用开场白", "추가 인사말 넣기"),
                onClick = { store.updateDraft { it.withEdits(alternateGreetings = it.alternateGreetings + "") } },
                enabled = !store.busy,
                modifier = Modifier.padding(start = 16.dp, top = 2.dp),
            )
        }
        item(key = "advanced") {
            EtaPreferenceGroup(modifier = Modifier.padding(horizontal = CharacterCardPadding, vertical = 8.dp)) {
                EtaArrowPreference(
                    title = ko("高级设置", "고급 설정"),
                    summary = ko("性格、背景、示例对话、提示词、作者信息与世界书", "성격, 배경, 예시 대화, 프롬프트, 제작자 정보, 월드북"),
                    onClick = { advanced = !advanced },
                )
            }
        }
        if (advanced) {
            item(key = "personality") { CharacterTextField(ko("性格与说话风格", "성격과 말투"), card.personality, { value -> store.updateDraft { it.withEdits(personality = value) } }, !store.busy) }
            item(key = "scenario") { CharacterTextField(ko("故事背景", "스토리 배경"), card.scenario, { value -> store.updateDraft { it.withEdits(scenario = value) } }, !store.busy) }
            item(key = "examples") { CharacterTextField(ko("示例对话", "예시 대화"), card.exampleMessages, { value -> store.updateDraft { it.withEdits(exampleMessages = value) } }, !store.busy, minLines = 4) }
            item(key = "prompts-title") { EtaPreferenceGroupTitle(ko("提示词", "프롬프트")) }
            item(key = "system") { CharacterTextField(ko("系统提示词", "시스템 프롬프트"), card.systemPrompt, { value -> store.updateDraft { it.withEdits(systemPrompt = value) } }, !store.busy) }
            item(key = "post-history") { CharacterTextField(ko("对话后置指令", "대화 후 지시문"), card.postHistoryInstructions, { value -> store.updateDraft { it.withEdits(postHistoryInstructions = value) } }, !store.busy) }
            item(key = "credits-title") { EtaPreferenceGroupTitle(ko("作者信息", "제작자 정보")) }
            item(key = "creator-notes") { CharacterTextField(ko("作者备注", "제작자 메모"), card.creatorNotes, { value -> store.updateDraft { it.withEdits(creatorNotes = value) } }, !store.busy) }
            item(key = "tags") { CharacterTextField(ko("标签（每行一个）", "태그(한 줄에 하나)"), card.tags.joinToString("\n"), { value -> store.updateDraft { it.withEdits(tags = value.lines()) } }, !store.busy) }
            item(key = "creator") { CharacterTextField(ko("作者", "제작자"), card.creator, { value -> store.updateDraft { it.withEdits(creator = value) } }, !store.busy, singleLine = true) }
            item(key = "version") { CharacterTextField(ko("角色版本", "캐릭터 버전"), card.version, { value -> store.updateDraft { it.withEdits(version = value) } }, !store.busy, singleLine = true) }
            characterWorldbookEditor(
                store = store, expanded = worldbookExpanded, expandedEntry = worldbookEntry,
                onToggleExpanded = { worldbookExpanded = !worldbookExpanded },
                onExpandEntry = { worldbookEntry = it },
            )
            item(key = "compatibility") { CharacterPageMessage(ko("角色卡中的第三方脚本与扩展界面不会执行，相关数据会保留在导出的角色卡中。", "캐릭터 카드의 서드파티 스크립트와 확장 UI는 실행되지 않으며, 관련 데이터는 내보낸 캐릭터 카드에 보존됩니다.")) }
        }
        item(key = "save") {
            EtaTextButton(
                text = if (store.busy) ko("正在处理…", "처리 중…") else ko("保存角色", "캐릭터 저장"),
                onClick = { store.saveEditor(onSaved) },
                enabled = !store.busy && store.draftName.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CharacterCardPadding, vertical = 12.dp),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

@Composable
internal fun CharacterTextField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    enabled: Boolean,
    singleLine: Boolean = false,
    minLines: Int = 2,
) {
    TextField(
        value = value, onValueChange = onChange, label = label, enabled = enabled,
        singleLine = singleLine, minLines = if (singleLine) 1 else minLines,
        maxLines = if (singleLine) 1 else 14,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
internal fun CharacterFieldGroupLabel(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
    )
}
