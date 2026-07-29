package fuck.andes.ui.screens.skills

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as LucideR
import fuck.andes.ui.components.MiuixDialogActions
import fuck.andes.ui.components.MiuixScaffoldPage
import fuck.andes.ui.components.PrefDivider
import fuck.andes.ui.model.AgentSkillsAction
import fuck.andes.ui.model.AgentSkillsUiState
import fuck.andes.ui.model.SkillItemUi
import fuck.andes.ui.model.canDeleteUserSkill
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

private val CardHorizontalPadding = 12.dp
private val CardBottomPadding = 12.dp

@Composable
fun AgentSkillsScreen(
    state: AgentSkillsUiState,
    onAction: (AgentSkillsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var deleteTarget by remember { mutableStateOf<SkillItemUi?>(null) }
    val operationPending = state.isImporting || state.busySkillId != null
    val zipPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) onAction(AgentSkillsAction.ImportZip(uri.toString()))
    }
    val openZipPicker = {
        zipPicker.launch(
            arrayOf(
                "application/zip",
                "application/x-zip-compressed",
                "application/octet-stream",
            ),
        )
    }

    MiuixScaffoldPage(
        title = "스킬",
        onBack = { onAction(AgentSkillsAction.NavigateBack) },
        modifier = modifier,
    ) {
        val installed = state.skills.filter { it.installed }
        val builtinInstalled = installed.filter { it.source == "builtin" }
        val userInstalled = installed.filter { it.canDeleteUserSkill }
        val removed = state.skills.filter { !it.installed }

        item(key = "zip-import-title") { SmallTitle("설치") }
        item(key = "zip-import-card") {
            Card(
                modifier = Modifier
                    .padding(horizontal = CardHorizontalPadding)
                    .padding(bottom = CardBottomPadding),
            ) {
                BasicComponent(
                    title = if (state.isImporting) "스킬 패키지 확인 중" else "ZIP에서 가져오기",
                    summary = if (state.isImporting) {
                        "검증하고 설치하는 중입니다. 잠시 기다려 주세요."
                    } else {
                        "SKILL.md가 포함된 스킬 패키지를 선택하세요."
                    },
                    startAction = {
                        if (state.isImporting) {
                            Box(
                                modifier = Modifier
                                    .padding(end = 12.dp)
                                    .size(36.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                InfiniteProgressIndicator(size = 22.dp)
                            }
                        } else {
                            ZipImportIcon()
                        }
                    },
                    enabled = !operationPending,
                    onClick = openZipPicker,
                    onClickLabel = "ZIP 스킬 패키지 선택",
                )
            }
        }

        if (builtinInstalled.isNotEmpty()) {
            item(key = "builtin-title") { SmallTitle("내장 스킬") }
            item(key = "builtin-card") {
                Card(
                    modifier = Modifier
                        .padding(horizontal = CardHorizontalPadding)
                        .padding(bottom = CardBottomPadding),
                ) {
                    builtinInstalled.forEachIndexed { index, skill ->
                        SkillSwitchRow(
                            skill = skill,
                            enabled = !operationPending,
                            onToggle = { enabled ->
                                onAction(AgentSkillsAction.ToggleSkill(skill.id, enabled))
                            },
                        )
                        if (index < builtinInstalled.lastIndex) PrefDivider()
                    }
                }
            }
        }

        if (userInstalled.isNotEmpty()) {
            item(key = "user-title") { SmallTitle("사용자 스킬") }
            item(key = "user-card") {
                Card(
                    modifier = Modifier
                        .padding(horizontal = CardHorizontalPadding)
                        .padding(bottom = CardBottomPadding),
                ) {
                    userInstalled.forEachIndexed { index, skill ->
                        SkillSwitchRow(
                            skill = skill,
                            enabled = !operationPending,
                            onToggle = { enabled ->
                                onAction(AgentSkillsAction.ToggleSkill(skill.id, enabled))
                            },
                            onDelete = { deleteTarget = skill },
                        )
                        if (index < userInstalled.lastIndex) PrefDivider()
                    }
                }
            }
        }

        if (removed.isNotEmpty()) {
            item(key = "removed-title") { SmallTitle("제거됨") }
            item(key = "removed-card") {
                Card(
                    modifier = Modifier
                        .padding(horizontal = CardHorizontalPadding)
                        .padding(bottom = CardBottomPadding),
                ) {
                    removed.forEachIndexed { index, skill ->
                        BasicComponent(
                            title = skill.name,
                            summary = "탭하여 다시 설치",
                            startAction = { SkillIcon(skill) },
                            enabled = !operationPending,
                            onClick = {
                                onAction(AgentSkillsAction.ReinstallBuiltin(skill.id))
                            },
                        )
                        if (index < removed.lastIndex) PrefDivider()
                    }
                }
            }
        }

        if (state.skills.isEmpty() && !state.isLoading) {
            item(key = "empty") { SmallTitle("설치된 스킬이 없습니다.") }
        }
    }

    state.replacement?.let { replacement ->
        WindowDialog(
            show = true,
            title = "사용자 스킬을 교체할까요?",
            summary = "사용자 스킬 「${replacement.name}」(${replacement.id})이 이미 있습니다. 교체하면 현재 파일을 모두 덮어씁니다.",
            onDismissRequest = { onAction(AgentSkillsAction.CancelZipReplacement) },
        ) {
            MiuixDialogActions(
                confirmText = "교체",
                confirmEnabled = !operationPending,
                onCancel = { onAction(AgentSkillsAction.CancelZipReplacement) },
                onConfirm = { onAction(AgentSkillsAction.ConfirmZipReplacement) },
            )
        }
    }

    deleteTarget?.let { skill ->
        WindowDialog(
            show = true,
            title = "사용자 스킬 삭제",
            summary = "「${skill.name}」을 삭제하면 복구하려면 다시 설치해야 합니다.",
            onDismissRequest = { deleteTarget = null },
        ) {
            MiuixDialogActions(
                confirmText = "삭제",
                destructive = true,
                confirmEnabled = !operationPending,
                onCancel = { deleteTarget = null },
                onConfirm = {
                    deleteTarget = null
                    onAction(AgentSkillsAction.DeleteSkill(skill.id))
                },
            )
        }
    }

    state.notice?.let { notice ->
        WindowDialog(
            show = true,
            title = notice.title,
            summary = notice.message,
            onDismissRequest = { onAction(AgentSkillsAction.DismissNotice) },
        ) {
            TextButton(
                text = "확인",
                onClick = { onAction(AgentSkillsAction.DismissNotice) },
                modifier = Modifier.fillMaxWidth(),
                colors = if (notice.isError) {
                    ButtonDefaults.textButtonColors()
                } else {
                    ButtonDefaults.textButtonColorsPrimary()
                },
            )
        }
    }
}

@Composable
private fun SkillSwitchRow(
    skill: SkillItemUi,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val truncatedSummary = remember(skill.description) {
        val desc = skill.description.ifBlank { "설명 없음" }
        if (desc.length > 80) desc.take(80) + "..." else desc
    }
    BasicComponent(
        title = skill.name,
        summary = truncatedSummary,
        startAction = { SkillIcon(skill) },
        endActions = {
            onDelete?.let {
                IconButton(
                    onClick = it,
                    enabled = enabled,
                    minWidth = 36.dp,
                    minHeight = 36.dp,
                ) {
                    Icon(
                        painter = painterResource(LucideR.drawable.lucide_ic_trash_2),
                        contentDescription = "${skill.name} 삭제",
                        modifier = Modifier.size(20.dp),
                        tint = MiuixTheme.colorScheme.error,
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
            }
            Switch(
                checked = skill.enabled,
                onCheckedChange = onToggle,
                enabled = enabled,
            )
        },
    )
}

@Composable
private fun ZipImportIcon() {
    Box(
        modifier = Modifier
            .padding(end = 12.dp)
            .size(36.dp)
            .background(MiuixTheme.colorScheme.surfaceContainerHigh, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(LucideR.drawable.lucide_ic_file_archive),
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MiuixTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun SkillIcon(skill: SkillItemUi) {
    Box(
        modifier = Modifier
            .padding(end = 12.dp)
            .size(36.dp)
            .background(MiuixTheme.colorScheme.surfaceContainerHigh, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconForSkill(skill.id)),
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = MiuixTheme.colorScheme.onBackground,
        )
    }
}

private fun iconForSkill(skillId: String): Int = when (skillId) {
    "self-improving-agent" -> LucideR.drawable.lucide_ic_refresh_cw
    "skill-creator" -> LucideR.drawable.lucide_ic_pencil_ruler
    else -> LucideR.drawable.lucide_ic_puzzle
}
