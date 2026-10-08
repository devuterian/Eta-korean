package io.github.mangi.eta.ui.screens.characters

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.i18n.ko
import io.github.mangi.eta.ui.app.CharacterLibraryStore
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaCard
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.navigation.AppRoute
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

internal val CharacterCardPadding = 16.dp

@Composable
internal fun CharacterLibraryScreen(
    store: CharacterLibraryStore,
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) store.importCard(uri) { onNavigate(AppRoute.CharacterDetail(it)) }
    }
    val importCard = {
        importer.launch(arrayOf("image/png", "application/json", "text/plain", "application/octet-stream"))
    }
    val createCharacter = {
        store.discardEditor()
        onNavigate(AppRoute.CharacterEditor())
    }
    MiuixScaffoldPage(
        title = ko("角色", "캐릭터"),
        onBack = onBack,
        actions = {
            IconButton(onClick = importCard, enabled = !store.busy) {
                Icon(Icons.Rounded.FileUpload, contentDescription = ko("导入角色卡", "캐릭터 카드 가져오기"))
            }
            IconButton(onClick = createCharacter, enabled = !store.busy) {
                Icon(Icons.Rounded.Add, contentDescription = ko("创建角色", "캐릭터 만들기"))
            }
        },
    ) {
        item(key = "search") {
            SearchBar(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CharacterCardPadding, vertical = 4.dp),
                expanded = false,
                onExpandedChange = {},
                inputField = {
                    InputField(
                        query = store.query,
                        onQueryChange = { store.query = it },
                        onSearch = { store.query = it },
                        expanded = false,
                        onExpandedChange = {},
                        label = ko("搜索名称或标签", "이름 또는 태그 검색"),
                    )
                },
                content = {},
            )
        }
        when {
            store.busy && store.characters.isEmpty() -> {
                item(key = "loading") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        InfiniteProgressIndicator(size = 28.dp)
                    }
                }
            }
            store.characters.isEmpty() -> {
                item(key = "empty-library") {
                    ListEmptyState(
                        title = ko("还没有角色", "캐릭터가 없습니다"),
                        summary = ko("创建一个角色，或导入 PNG、JSON 角色卡开始对话", "캐릭터를 만들거나 PNG, JSON 캐릭터 카드를 가져와 대화를 시작하세요"),
                        action = {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                EtaTextButton(
                                    text = ko("恢复默认角色", "기본 캐릭터 복원"),
                                    onClick = { store.restoreDefaultCharacter() },
                                    enabled = !store.busy,
                                )
                                EtaTextButton(
                                    text = ko("创建角色", "캐릭터 만들기"),
                                    onClick = createCharacter,
                                    colors = ButtonDefaults.textButtonColorsPrimary(),
                                )
                            }
                        },
                    )
                }
            }
            store.filteredCharacters.isEmpty() -> {
                item(key = "empty-search") {
                    ListEmptyState(
                        title = ko("没有找到匹配的角色", "일치하는 캐릭터 없음"),
                        summary = ko("换个关键词试试，搜索会匹配名称与标签", "다른 키워드로 검색해 보세요. 이름과 태그를 검색합니다."),
                    )
                }
            }
        }
        items(store.filteredCharacters, key = { it.id }) { profile ->
            EtaCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CharacterCardPadding, vertical = 8.dp),
                insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                pressFeedbackType = PressFeedbackType.Sink,
                onClick = { if (!store.busy) onNavigate(AppRoute.CharacterDetail(profile.id)) },
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = profile.card.name,
                        style = MiuixTheme.textStyles.body1,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = profile.card.description.ifBlank {
                            profile.card.tags.joinToString(" · ")
                        },
                        style = MiuixTheme.textStyles.body2,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
        item(key = "persona-title") { EtaPreferenceGroupTitle(ko("我的", "나")) }
        item(key = "persona") {
            EtaPreferenceGroup(
                modifier = Modifier
                    .padding(horizontal = CharacterCardPadding)
                    .padding(bottom = 16.dp),
            ) {
                EtaArrowPreference(
                    title = ko("我的人设", "내 페르소나"),
                    summary = ko("设置角色如何称呼你，以及你在故事中的身份", "캐릭터가 나를 부르는 호칭과 이야기 속 내 정체성을 설정합니다"),
                    onClick = { onNavigate(AppRoute.CharacterPersona) },
                )
            }
        }
    }
}

@Composable
internal fun CharacterPageMessage(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.body2,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
}
