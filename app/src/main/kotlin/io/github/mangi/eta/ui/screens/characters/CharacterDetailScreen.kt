package io.github.mangi.eta.ui.screens.characters

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.roleplay.CharacterCardFormat
import io.github.mangi.eta.agent.roleplay.RoleplayBinding
import io.github.mangi.eta.i18n.ko
import io.github.mangi.eta.ui.app.CharacterLibraryStore
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaCard
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaRadioButtonPreference
import io.github.mangi.eta.ui.components.EtaSwitchPreference
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.navigation.AppRoute
import top.yukonga.miuix.kmp.basic.BasicComponentColors
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val DescriptionPreviewChars = 220
private const val GreetingPreviewChars = 240

private data class CharacterTextPreview(val title: String, val text: String)

@Composable
internal fun CharacterDetailScreen(
    id: String,
    store: CharacterLibraryStore,
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
    onStart: (RoleplayBinding, String) -> Unit,
) {
    var showCompatibility by rememberSaveable(id) { mutableStateOf(false) }
    var showDeleteConfirm by rememberSaveable(id) { mutableStateOf(false) }
    var preview by remember(id) { mutableStateOf<CharacterTextPreview?>(null) }
    val pngExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) {
        if (it != null) store.export(id, CharacterCardFormat.PNG, it)
    }
    val jsonExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {
        if (it != null) store.export(id, CharacterCardFormat.JSON, it)
    }
    val profile = store.selected?.takeIf { it.id == id }
    MiuixScaffoldPage(title = profile?.card?.name ?: ko("角色详情", "캐릭터 정보"), onBack = onBack) {
        if (profile == null) {
            item { CharacterPageMessage(if (store.busy) ko("正在读取角色…", "캐릭터 불러오는 중…") else ko("无法读取角色，请返回后重试", "캐릭터를 불러올 수 없습니다. 돌아간 후 다시 시도하세요.")) }
            return@MiuixScaffoldPage
        }
        item(key = "profile") {
            EtaCard(
                modifier = Modifier.padding(horizontal = CharacterCardPadding, vertical = 8.dp),
                insideMargin = PaddingValues(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(profile.card.name, style = MiuixTheme.textStyles.title2)
                    if (profile.card.tags.isNotEmpty()) {
                        Text(
                            text = profile.card.tags.joinToString(" · "),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (profile.card.description.isNotBlank()) {
                    Text(
                        text = profile.card.description,
                        style = MiuixTheme.textStyles.body2,
                        modifier = Modifier.padding(top = 14.dp),
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (profile.card.description.length > DescriptionPreviewChars) {
                        Text(
                            text = ko("阅读全文", "전체 보기"),
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .clickable {
                                    preview = CharacterTextPreview(ko("角色设定", "캐릭터 설정"), profile.card.description)
                                },
                        )
                    }
                }
            }
        }
        item(key = "start") {
            EtaTextButton(
                text = ko("开始新对话", "새 대화 시작"),
                enabled = !store.busy,
                onClick = { store.startConversation(id, onStart) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CharacterCardPadding, vertical = 6.dp),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
        item(key = "persona") {
            EtaPreferenceGroup(
                modifier = Modifier.padding(horizontal = CharacterCardPadding, vertical = 8.dp),
            ) {
                EtaSwitchPreference(
                    title = ko("使用我的人设", "내 페르소나 사용"),
                    summary = store.persona.name.ifBlank { ko("未设置称呼", "호칭 미설정") },
                    checked = store.usePersona,
                    onCheckedChange = { store.usePersona = it },
                )
                EtaPreferenceDivider(hasLeading = false)
                EtaArrowPreference(title = ko("编辑我的人设", "내 페르소나 편집"), onClick = { onNavigate(AppRoute.CharacterPersona) })
            }
        }
        item(key = "greetings-title") { EtaPreferenceGroupTitle(ko("开场白", "인사말")) }
        item(key = "greetings") {
            EtaPreferenceGroup(modifier = Modifier.padding(horizontal = CharacterCardPadding)) {
                val greetings = listOf(profile.card.firstMessage) + profile.card.alternateGreetings
                greetings.forEachIndexed { index, greeting ->
                    if (index > 0) EtaPreferenceDivider(hasLeading = false)
                    EtaRadioButtonPreference(
                        title = if (index == 0) ko("默认开场白", "기본 인사말") else ko("开场白 ${index + 1}", "인사말 ${index + 1}"),
                        summary = greeting.take(GreetingPreviewChars)
                            .let { if (greeting.length > GreetingPreviewChars) "$it…" else it }
                            .ifBlank { ko("没有预设开场白，由你先开口", "설정된 인사말이 없습니다. 먼저 말을 걸어 보세요.") },
                        selected = store.greetingIndex == index,
                        onClick = { store.greetingIndex = index },
                        bottomAction = if (greeting.length > GreetingPreviewChars) {
                            {
                                Text(
                                    text = ko("阅读全文", "전체 보기"),
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .padding(start = 16.dp, bottom = 10.dp)
                                        .clickable {
                                            preview = CharacterTextPreview(
                                                if (index == 0) ko("默认开场白", "기본 인사말") else ko("开场白 ${index + 1}", "인사말 ${index + 1}"),
                                                greeting,
                                            )
                                        },
                                )
                            }
                        } else {
                            null
                        },
                    )
                }
            }
        }
        item(key = "management-title") { EtaPreferenceGroupTitle(ko("管理", "관리")) }
        item(key = "management") {
            EtaPreferenceGroup(modifier = Modifier.padding(horizontal = CharacterCardPadding)) {
                EtaArrowPreference(title = ko("编辑角色", "캐릭터 편집"), enabled = !store.busy, onClick = {
                    store.discardEditor()
                    onNavigate(AppRoute.CharacterEditor(id))
                })
                EtaPreferenceDivider(hasLeading = false)
                EtaArrowPreference(title = ko("剧情记忆", "스토리 메모리"), summary = ko("此角色各次对话共享的故事与关系记录", "이 캐릭터의 모든 대화에서 공유되는 스토리와 관계 기록"), onClick = {
                    onNavigate(AppRoute.CharacterMemory(id))
                })
                EtaPreferenceDivider(hasLeading = false)
                EtaArrowPreference(title = ko("复制角色", "캐릭터 복제"), enabled = !store.busy, onClick = {
                    store.duplicate(id) { onNavigate(AppRoute.CharacterDetail(it)) }
                })
                EtaPreferenceDivider(hasLeading = false)
                EtaArrowPreference(
                    title = ko("删除角色", "캐릭터 삭제"),
                    titleColor = BasicComponentColors(
                        color = MiuixTheme.colorScheme.error,
                        disabledColor = MiuixTheme.colorScheme.disabledOnSurface,
                    ),
                    enabled = !store.busy,
                    onClick = { showDeleteConfirm = true },
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    EtaTextButton(
                        text = ko("导出 PNG", "PNG로 내보내기"),
                        onClick = { pngExporter.launch(characterExportName(profile.card.name, "png")) },
                        enabled = !store.busy,
                        modifier = Modifier.weight(1f),
                    )
                    EtaTextButton(
                        text = ko("导出 JSON", "JSON으로 내보내기"),
                        onClick = { jsonExporter.launch(characterExportName(profile.card.name, "json")) },
                        enabled = !store.busy,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        if (store.compatibilityWarnings.isNotEmpty()) {
            item(key = "compatibility") {
                EtaPreferenceGroup(
                    modifier = Modifier
                        .padding(horizontal = CharacterCardPadding)
                        .padding(top = 12.dp),
                ) {
                    EtaArrowPreference(
                        title = ko("兼容说明", "호환성 안내"),
                        summary = ko("${store.compatibilityWarnings.size} 项内容按兼容范围处理", "${store.compatibilityWarnings.size}개 항목을 호환 범위 내에서 처리했습니다"),
                        onClick = { showCompatibility = !showCompatibility },
                    )
                    if (showCompatibility) {
                        store.compatibilityWarnings.forEach { warning ->
                            Text(
                                text = warning,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDeleteConfirm && profile != null) {
        EtaWindowDialog(
            show = true,
            title = ko("删除角色", "캐릭터 삭제"),
            summary = ko("「${profile.card.name}」将从角色库移除，角色图片与剧情记忆一并删除；已有对话保留。此操作无法撤销。", "'${profile.card.name}' 캐릭터를 목록에서 제거하고 캐릭터 이미지와 스토리 메모리도 함께 삭제합니다. 기존 대화는 유지됩니다. 이 작업은 되돌릴 수 없습니다."),
            onDismissRequest = { showDeleteConfirm = false },
        ) {
            MiuixDialogActions(
                confirmText = ko("删除", "삭제"),
                destructive = true,
                confirmEnabled = !store.busy,
                onCancel = { showDeleteConfirm = false },
                onConfirm = {
                    showDeleteConfirm = false
                    store.delete(id) { onBack() }
                },
            )
        }
    }

    preview?.let { current ->
        EtaWindowDialog(
            show = true,
            title = current.title,
            onDismissRequest = { preview = null },
        ) {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(current.text, style = MiuixTheme.textStyles.body2)
            }
            EtaTextButton(
                text = ko("关闭", "닫기"),
                onClick = { preview = null },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

private fun characterExportName(name: String, extension: String): String {
    val basename = name.map { if (it.isISOControl() || it in "\\/:*?\"<>|") '_' else it }
        .joinToString("").trim().trimEnd('.').take(64).ifBlank { ko("角色", "캐릭터") }
    return "$basename.$extension"
}
