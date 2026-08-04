package fuck.andes.ui.model

import androidx.compose.runtime.Immutable

@Immutable
data class ConversationPaneUiState(
    val conversations: List<ConversationSummaryUi>,
    val selectedConversationId: String?,
    val searchQuery: String,
)

@Immutable
data class ConversationSummaryUi(
    val id: String,
    val title: String,
    val preview: String,
    val timeLabel: String,
    val mode: ConversationModeUi,
    val isPinned: Boolean = false,
    val isActiveRun: Boolean = false,
)

@Immutable
enum class ConversationModeUi(
    val label: String,
) {
    Chat("채팅"),
    PhoneAgent("휴대폰"),
    Terminal("터미널"),
    Automation("자동화"),
}
