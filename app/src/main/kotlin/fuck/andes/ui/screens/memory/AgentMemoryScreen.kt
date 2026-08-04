package fuck.andes.ui.screens.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import fuck.andes.ui.components.MiuixDialogActions
import fuck.andes.ui.components.MiuixScaffold
import fuck.andes.ui.model.AgentMemoryAction
import fuck.andes.ui.model.AgentMemoryUiState
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun AgentMemoryScreen(
    state: AgentMemoryUiState,
    onAction: (AgentMemoryAction) -> Unit,
) {
    var showClearDialog by remember { mutableStateOf(false) }

    MiuixScaffold(
        title = "메모리",
        onBack = { onAction(AgentMemoryAction.NavigateBack) },
    ) { paddingValues, scrollBehavior ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            // 状态区与编辑器滚动分离。weight fill=false：内容少时只占自身高度，避免中部空档；
            // 空间不足（键盘弹出、横屏）时压缩为可滚动区域，编辑器保持完整可见
            LazyColumn(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .overScrollVertical()
                    .scrollEndHaptic()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                overscrollEffect = null,
            ) {
                item(key = "status-title") { SmallTitle("메모리") }
                item(key = "status-card") {
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        SwitchPreference(
                            title = "메모리 활성화",
                            summary = "닫은 후에는 메모리가 주입되지 않으며 모델을 읽거나 쓸 수 없습니다. 기존 콘텐츠는 그대로 유지됩니다.",
                            checked = state.enabled,
                            enabled = !state.isLoading,
                            onCheckedChange = { onAction(AgentMemoryAction.ToggleEnabled(it)) },
                        )
                        BasicComponent(
                            title = "코어 메모리 주입 예산",
                            summary = "각 라운드에는 최대 ${formatNumber(state.coreBudgetChars)} 문자가 주입되며, 요청 시 모델에서 세부 정보를 읽습니다.",
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .imePadding()
                    .navigationBarsPadding(),
            ) {
                SmallTitle("MEMORY.md")
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        TextField(
                            value = state.draft,
                            onValueChange = { onAction(AgentMemoryAction.DraftChanged(it)) },
                            label = "# 코어 메모리 \\n- 사용자 이름: \\n- 장기 선호 사항:",
                            useLabelAsPlaceholder = true,
                            enabled = !state.isLoading && !state.isSaving,
                            minLines = 6,
                            maxLines = 12,
                            textStyle = MiuixTheme.textStyles.body2.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val overLimit = state.draftBytes > state.maxBytes
                            Text(
                                text = when {
                                    overLimit -> "1MiB 한도를 초과했습니다. 삭제하세요."
                                    state.hasUnsavedChanges -> "저장되지 않은 변경사항"
                                    else -> ""
                                },
                                color = if (overLimit) {
                                    MiuixTheme.colorScheme.error
                                } else {
                                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                                },
                                style = MiuixTheme.textStyles.footnote1,
                            )
                            Text(
                                text = "${formatBytes(state.draftBytes)} / 1 MiB",
                                color = if (overLimit) {
                                    MiuixTheme.colorScheme.error
                                } else {
                                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                                },
                                style = MiuixTheme.textStyles.footnote1,
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TextButton(
                                text = "분명한",
                                enabled = !state.isLoading && !state.isSaving && state.draft.isNotEmpty(),
                                onClick = { showClearDialog = true },
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                text = if (state.isSaving) "절약" else "저장",
                                enabled = state.canSave,
                                onClick = { onAction(AgentMemoryAction.Save) },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.textButtonColorsPrimary(),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showClearDialog) {
        WindowDialog(
            show = true,
            title = "모든 메모리를 지우시겠습니까?",
            summary = "MEMORY.md의 전체 내용이 삭제되고 메모리 스위치는 현재 상태로 유지됩니다.",
            onDismissRequest = { showClearDialog = false },
        ) {
            MiuixDialogActions(
                confirmText = "분명한",
                destructive = true,
                confirmEnabled = !state.isSaving,
                onCancel = { showClearDialog = false },
                onConfirm = {
                    showClearDialog = false
                    onAction(AgentMemoryAction.Clear)
                },
            )
        }
    }

    state.notice?.let { notice ->
        WindowDialog(
            show = true,
            title = "메모리",
            summary = notice,
            onDismissRequest = { onAction(AgentMemoryAction.DismissNotice) },
        ) {
            TextButton(
                text = "확인",
                onClick = { onAction(AgentMemoryAction.DismissNotice) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun formatBytes(bytes: Int): String = when {
    bytes < 1_024 -> "$bytes B"
    bytes < 1_024 * 1_024 -> "%.1f KiB".format(bytes / 1_024.0)
    else -> "%.2f MiB".format(bytes / (1_024.0 * 1_024.0))
}

private fun formatNumber(value: Int): String = "%,d".format(value)
