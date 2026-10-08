package io.github.mangi.eta.ui.pages.providers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.data.provider.CommunityCatalog
import io.github.mangi.eta.data.provider.CommunityCatalogProvider
import io.github.mangi.eta.data.provider.CommunityCatalogSource
import io.github.mangi.eta.i18n.ko
import io.github.mangi.eta.ui.app.CommunityCatalogStore
import io.github.mangi.eta.ui.components.EtaCard
import io.github.mangi.eta.ui.components.EtaCheckboxPreference
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.navigation.AppRoute
import io.github.mangi.eta.ui.model.formatCompactTokenCount
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

@Composable
internal fun CommunityCatalogScreen(
    store: CommunityCatalogStore,
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val catalog = store.catalog
    val providers = remember(catalog, query) {
        val term = query.trim()
        catalog?.providers.orEmpty().filter { provider ->
            term.isEmpty() ||
                provider.name.contains(term, ignoreCase = true) ||
                provider.id.contains(term, ignoreCase = true) ||
                provider.baseUrl.contains(term, ignoreCase = true)
        }
    }

    LaunchedEffect(Unit) { store.openCatalog() }

    MiuixScaffoldPage(
        title = ko("从目录添加", "카탈로그에서 추가"),
        onBack = onBack,
        actions = {
            IconButton(
                onClick = store::refresh,
                enabled = !store.loading && !store.refreshing,
            ) {
                Icon(Icons.Rounded.Refresh, contentDescription = ko("刷新模型目录", "모델 카탈로그 새로고침"))
            }
        },
    ) {
        item(key = "search") {
            InputField(
                query = query,
                onQueryChange = { query = it },
                onSearch = {},
                expanded = false,
                onExpandedChange = {},
                label = ko("搜索提供商", "제공업체 검색"),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        item(key = "catalog_status") {
            ProviderSection(title = ko("目录状态", "카탈로그 상태")) {
                EtaPreference(
                    title = ko("models.dev 社区目录", "models.dev 커뮤니티 카탈로그"),
                    summary = ko("读取目录不会上传 Eta 的 API Key", "카탈로그를 불러와도 Eta의 API 키는 업로드되지 않습니다"),
                )
                EtaPreferenceDivider(hasLeading = false)
                EtaPreference(
                    title = ko("模型是社区候选条目", "모델 목록은 커뮤니티 제공 후보입니다"),
                    summary = ko("能否调用以服务商账号权限和实际接口为准", "실제 사용 가능 여부는 제공업체 계정 권한과 API에 따라 다릅니다"),
                )
                EtaPreferenceDivider(hasLeading = false)
                EtaPreference(
                    title = catalog?.sourceLabel() ?: if (store.loading) ko("正在读取本地目录", "로컬 카탈로그 불러오는 중") else ko("目录暂不可用", "카탈로그를 사용할 수 없음"),
                    summary = catalog?.let(::catalogSummary),
                )
                if (store.refreshing) {
                    EtaPreferenceDivider(hasLeading = false)
                    EtaPreference(title = ko("正在更新在线目录", "온라인 카탈로그 업데이트 중"))
                }
                store.refreshError?.let { error ->
                    EtaPreferenceDivider(hasLeading = false)
                    EtaPreference(
                        title = ko("在线更新失败", "온라인 업데이트 실패"),
                        summary = if (catalog != null) ko("$error；仍可使用当前目录", "$error. 현재 카탈로그는 계속 사용할 수 있습니다") else error,
                    )
                }
                if (store.loadError && catalog == null) {
                    EtaPreferenceDivider(hasLeading = false)
                    EtaPreference(title = ko("本地目录读取失败", "로컬 카탈로그를 불러오지 못함"), summary = ko("请重试或检查应用安装包", "다시 시도하거나 앱 설치 파일을 확인하세요"))
                }
            }
        }

        item(key = "results_title") {
            EtaPreferenceGroupTitle(ko("可导入提供商（${providers.size}）", "가져올 수 있는 제공업체(${providers.size})"))
        }
        if (catalog == null && store.loading) {
            item(key = "loading") { CatalogLoadingState() }
        } else if (providers.isEmpty()) {
            item(key = "empty") {
                ListEmptyState(
                    title = if (catalog == null) ko("没有可用的目录", "사용 가능한 카탈로그 없음") else ko("没有找到匹配的提供商", "일치하는 제공업체 없음"),
                    summary = if (catalog == null) ko("点击右上角刷新后重试", "오른쪽 위의 새로고침을 누른 후 다시 시도하세요") else ko("试试其他名称或地址", "다른 이름이나 주소로 검색해 보세요"),
                )
            }
        } else {
            items(providers, key = { "provider:${it.id}" }, contentType = { "provider" }) { provider ->
                EtaCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 5.dp),
                    insideMargin = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                    pressFeedbackType = PressFeedbackType.Sink,
                    onClick = { onNavigate(AppRoute.CommunityCatalogProvider(provider.id)) },
                ) {
                    Text(
                        text = provider.name,
                        style = MiuixTheme.textStyles.body1,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = provider.baseUrl,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = ko("${provider.models.size} 个可导入模型", "가져올 수 있는 모델 ${provider.models.size}개"),
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}

@Composable
internal fun CommunityCatalogProviderScreen(
    catalogId: String,
    store: CommunityCatalogStore,
    onImported: (String) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var query by rememberSaveable(catalogId) { mutableStateOf("") }
    var selectedIds by rememberSaveable(catalogId) { mutableStateOf(arrayListOf<String>()) }
    var importError by remember { mutableStateOf<String?>(null) }
    var importInFlight by remember { mutableStateOf(false) }
    var selection by remember(catalogId) { mutableStateOf<CommunityCatalogProvider?>(null) }
    val provider = selection ?: store.catalog?.providers?.firstOrNull { it.id == catalogId }
    val filteredModels = remember(provider, query) {
        val term = query.trim()
        provider?.models.orEmpty().filter { model ->
            term.isEmpty() ||
                model.displayName.contains(term, ignoreCase = true) ||
                model.modelId.contains(term, ignoreCase = true)
        }
    }

    LaunchedEffect(catalogId) { store.ensureLoaded() }
    LaunchedEffect(provider) {
        if (selection == null && provider != null) selection = provider
    }

    MiuixScaffoldPage(
        title = provider?.name ?: ko("选择模型", "모델 선택"),
        onBack = onBack,
        actions = {
            EtaTextButton(
                text = ko("导入（${selectedIds.size}）", "가져오기(${selectedIds.size})"),
                enabled = provider != null && selectedIds.isNotEmpty() && !importInFlight && !store.importing,
                onClick = {
                    val chosen = provider ?: return@EtaTextButton
                    if (importInFlight) return@EtaTextButton
                    importInFlight = true
                    importError = null
                    scope.launch {
                        try {
                            val addedId = store.importProvider(chosen, selectedIds.toSet())
                            onImported(addedId)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: IllegalArgumentException) {
                            importError = ko("模型选择已失效，请重新选择", "모델 선택이 만료되었습니다. 다시 선택하세요.")
                        } catch (_: Throwable) {
                            importError = ko("保存提供商失败，请重试", "제공업체를 저장하지 못했습니다. 다시 시도하세요.")
                        } finally {
                            importInFlight = false
                        }
                    }
                },
                insideMargin = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            )
        },
    ) {
        if (provider == null) {
            item(key = "missing") {
                if (store.loading) {
                    CatalogLoadingState()
                } else {
                    ListEmptyState(
                        title = ko("目录中没有这个提供商", "카탈로그에 이 제공업체가 없습니다"),
                        summary = ko("目录可能已更新，请返回列表重新选择", "카탈로그가 업데이트되었을 수 있습니다. 목록으로 돌아가 다시 선택하세요."),
                    )
                }
            }
            return@MiuixScaffoldPage
        }

        item(key = "provider") {
            ProviderSection(title = ko("提供商", "제공업체")) {
                EtaPreference(title = ko("名称", "이름"), summary = provider.name)
                EtaPreferenceDivider(hasLeading = false)
                EtaPreference(title = null) {
                    Text(
                        text = "Base URL",
                        style = MiuixTheme.textStyles.body1,
                        fontWeight = FontWeight.Medium,
                    )
                    SelectionContainer {
                        Text(
                            text = provider.baseUrl,
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            softWrap = true,
                        )
                    }
                }
            }
        }
        item(key = "selection_status") {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                Text(
                    text = ko("已选 ${selectedIds.size} / ${provider.models.size} 个模型", "모델 ${selectedIds.size} / ${provider.models.size}개 선택됨"),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Text(
                    text = ko("导入后需填写 API Key 并启用；模型请求将发送到上方 Base URL", "가져온 후 API 키를 입력하고 활성화해야 합니다. 모델 요청은 위의 Base URL로 전송됩니다."),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                importError?.let { error ->
                    Text(
                        text = error,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.error,
                    )
                }
            }
        }
        item(key = "model_search") {
            InputField(
                query = query,
                onQueryChange = { query = it },
                onSearch = {},
                expanded = false,
                onExpandedChange = {},
                label = ko("搜索模型名称或 ID", "모델 이름 또는 ID 검색"),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        item(key = "model_actions") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                EtaTextButton(
                    text = ko("全选当前结果", "현재 결과 모두 선택"),
                    enabled = filteredModels.isNotEmpty() && !store.importing,
                    onClick = {
                        selectedIds = ArrayList((selectedIds + filteredModels.map { it.modelId }).distinct())
                    },
                )
                EtaTextButton(
                    text = ko("清空已选", "선택 해제"),
                    enabled = selectedIds.isNotEmpty() && !store.importing,
                    onClick = { selectedIds = arrayListOf() },
                )
            }
        }
        item(key = "models_title") {
            EtaPreferenceGroupTitle(ko("模型（${filteredModels.size}）", "모델(${filteredModels.size})"))
        }
        if (filteredModels.isEmpty()) {
            item(key = "empty_models") {
                ListEmptyState(title = ko("没有找到匹配的模型", "일치하는 모델 없음"))
            }
        } else {
            items(filteredModels, key = { "model:${it.modelId}" }, contentType = { "model" }) { model ->
                EtaCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    insideMargin = PaddingValues(0.dp),
                ) {
                    EtaCheckboxPreference(
                        title = model.displayName,
                        summary = buildString {
                            append(model.modelId)
                            append(" · ")
                            append(formatCompactTokenCount(model.contextWindow))
                            append(ko(" tokens 上下文", " 토큰 컨텍스트"))
                            model.releaseDate?.let { append(" · "); append(it) }
                        },
                        checked = model.modelId in selectedIds,
                        enabled = !store.importing,
                        onCheckedChange = { checked ->
                            selectedIds = if (checked) {
                                ArrayList(selectedIds + model.modelId)
                            } else {
                                ArrayList(selectedIds.filterNot { it == model.modelId })
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CatalogLoadingState() {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
        contentAlignment = Alignment.Center,
    ) {
        InfiniteProgressIndicator(size = 28.dp)
    }
}

private fun CommunityCatalog.sourceLabel(): String = when (source) {
    CommunityCatalogSource.SNAPSHOT -> ko("内置目录快照", "내장 카탈로그 스냅샷")
    CommunityCatalogSource.CACHE -> ko("本地缓存目录", "로컬 캐시 카탈로그")
    CommunityCatalogSource.ONLINE -> ko("在线目录", "온라인 카탈로그")
}

private fun catalogSummary(catalog: CommunityCatalog): String {
    val updated = catalog.fetchedAt?.let { timestamp ->
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp))
    }
    return listOfNotNull(ko("${catalog.providers.size} 家可导入提供商", "가져올 수 있는 제공업체 ${catalog.providers.size}곳"), updated?.let { ko("更新于 $it", "$it 업데이트") })
        .joinToString(" · ")
}
