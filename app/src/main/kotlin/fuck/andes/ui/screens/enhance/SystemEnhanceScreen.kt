package fuck.andes.ui.screens.enhance

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fuck.andes.ui.components.MiuixScaffoldPage
import fuck.andes.ui.model.AgentSystemEnhanceAction
import fuck.andes.ui.model.AgentSystemEnhanceUiState
import fuck.andes.ui.model.SystemEnhanceItemUi
import fuck.andes.ui.model.SystemEnhanceStatusUi
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun SystemEnhanceScreen(
    state: AgentSystemEnhanceUiState,
    onAction: (AgentSystemEnhanceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    MiuixScaffoldPage(
        title = "시스템 강화",
        onBack = { onAction(AgentSystemEnhanceAction.NavigateBack) },
        modifier = modifier,
    ) {
        items(
            items = state.sections,
            key = { it.id },
        ) { section ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                SmallTitle(
                    text = section.title,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                section.items.forEach { item ->
                    SystemEnhanceItemRow(
                        item = item,
                        onToggle = { onAction(AgentSystemEnhanceAction.ToggleItem(item.id)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SystemEnhanceItemRow(
    item: SystemEnhanceItemUi,
    onToggle: () -> Unit,
) {
    BasicComponent(
        title = item.title,
        summary = item.summary,
        endActions = {
            Text(
                text = when (item.status) {
                    SystemEnhanceStatusUi.Active -> "사용 중"
                    SystemEnhanceStatusUi.Inactive -> "사용 안 함"
                    SystemEnhanceStatusUi.Unsupported -> "지원하지 않음"
                },
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
            )
        },
        onClick = onToggle,
    )
}
