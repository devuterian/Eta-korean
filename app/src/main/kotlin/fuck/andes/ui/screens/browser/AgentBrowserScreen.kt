package fuck.andes.ui.screens.browser

import android.content.Intent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import com.composables.icons.lucide.R as LucideR
import fuck.andes.agent.browser.AgentBrowserSession
import fuck.andes.agent.browser.BrowserSessionSnapshot
import fuck.andes.ui.components.StatusError
import fuck.andes.ui.components.StatusWarning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * Agent 与用户共享的浏览器会话。
 *
 * 浏览器通常在后台由模型驱动；进入本页后挂载的是同一个 WebView，用户可以直接接管，
 * 不会新建一份与 Agent 状态脱节的预览。
 */
@Composable
internal fun AgentBrowserScreen(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val snapshot by AgentBrowserSession.snapshots.collectAsState()
    var address by remember { mutableStateOf("") }
    var addressFocused by remember { mutableStateOf(false) }
    var actionPending by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }

    LaunchedEffect(context.applicationContext) {
        AgentBrowserSession.initialize(context.applicationContext)
    }
    LaunchedEffect(snapshot.displayUrl, addressFocused) {
        if (!addressFocused) {
            address = snapshot.displayUrl
        }
    }

    fun launchBrowserAction(action: () -> Unit) {
        if (actionPending) return
        actionPending = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { action() }
            } finally {
                actionPending = false
            }
        }
    }

    fun navigate() {
        if (actionPending) return
        val target = if (address == snapshot.displayUrl) {
            snapshot.url
        } else {
            address.trim()
        }
        if (target.isBlank()) return
        focusManager.clearFocus()
        keyboard?.hide()
        launchBrowserAction {
            AgentBrowserSession.navigateFromUser(context.applicationContext, target)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.surface)
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp)
            .imePadding()
            .navigationBarsPadding(),
    ) {
        TextField(
            value = address,
            onValueChange = {
                address = it
            },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { state -> addressFocused = state.isFocused },
            label = "URL 또는 도메인",
            useLabelAsPlaceholder = true,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(onGo = { navigate() }),
            leadingIcon = {
                Icon(
                    painter = painterResource(
                        if (snapshot.url.startsWith("https://")) {
                            LucideR.drawable.lucide_ic_lock
                        } else {
                            LucideR.drawable.lucide_ic_globe
                        }
                    ),
                    contentDescription = null,
                    modifier = Modifier.padding(start = 12.dp).size(18.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            },
            trailingIcon = {
                IconButton(
                    onClick = ::navigate,
                    enabled = address.isNotBlank() && !actionPending,
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .alpha(if (address.isNotBlank() && !actionPending) 1f else 0.34f),
                ) {
                    Icon(
                        painter = painterResource(LucideR.drawable.lucide_ic_arrow_right),
                        contentDescription = "이동",
                        modifier = Modifier.size(19.dp),
                        tint = MiuixTheme.colorScheme.onSurface,
                    )
                }
            },
        )

        Spacer(modifier = Modifier.height(10.dp))
        BrowserControlBar(
            snapshot = snapshot,
            actionPending = actionPending,
            onBack = { launchBrowserAction { AgentBrowserSession.goBackFromUser() } },
            onForward = { launchBrowserAction { AgentBrowserSession.goForwardFromUser() } },
            onRefresh = {
                if (snapshot.isLoading) {
                    scope.launch(Dispatchers.IO) {
                        AgentBrowserSession.stopFromUser()
                    }
                } else {
                    launchBrowserAction {
                        AgentBrowserSession.reloadFromUser()
                    }
                }
            },
            onOpenExternal = {
                val currentUrl = snapshot.url.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                if (currentUrl != null) {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, currentUrl.toUri()))
                    }.onFailure {
                        Toast.makeText(context, "현재 웹페이지를 열 수 있는 앱이 없습니다.", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onReset = { showResetDialog = true },
        )

        if (snapshot.isLoading) {
            LinearProgressIndicator(
                progress = snapshot.progress.coerceIn(0, 100) / 100f,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                height = 3.dp,
            )
        } else {
            Spacer(modifier = Modifier.height(8.dp))
        }

        BrowserStatusBanner(snapshot)

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            insideMargin = PaddingValues(0.dp),
            colors = CardDefaults.defaultColors(
                color = MiuixTheme.colorScheme.surfaceContainer,
                contentColor = MiuixTheme.colorScheme.onSurface,
            ),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                BrowserWebViewHost(modifier = Modifier.fillMaxSize())
                if (!snapshot.available) {
                    BrowserEmptyState(modifier = Modifier.fillMaxSize())
                } else if (snapshot.isLoading || (!snapshot.isPageVisible && snapshot.error == null)) {
                    BrowserLoadingState(
                        host = snapshot.host,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (!snapshot.isPageVisible) {
                    BrowserFailedState(modifier = Modifier.fillMaxSize())
                }
            }
        }
    }

    if (showResetDialog) {
        WindowDialog(
            show = true,
            title = "브라우저 세션 초기화",
            onDismissRequest = { showResetDialog = false },
        ) {
            Text(
                text = "현재 페이지를 닫고 Eta 브라우저의 쿠키와 사이트 데이터를 삭제합니다. 외부 브라우저에는 영향을 주지 않습니다.",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    text = "취소",
                    onClick = { showResetDialog = false },
                )
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(
                    text = "초기화",
                    onClick = {
                        showResetDialog = false
                        address = ""
                        launchBrowserAction { AgentBrowserSession.resetFromUser() }
                    },
                    enabled = !actionPending,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

@Composable
private fun BrowserControlBar(
    snapshot: BrowserSessionSnapshot,
    actionPending: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onRefresh: () -> Unit,
    onOpenExternal: () -> Unit,
    onReset: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        colors = CardDefaults.defaultColors(
            color = MiuixTheme.colorScheme.surfaceContainer,
            contentColor = MiuixTheme.colorScheme.onSurface,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BrowserControlButton(
                icon = LucideR.drawable.lucide_ic_arrow_left,
                description = "뒤로",
                enabled = snapshot.canGoBack && !actionPending,
                onClick = onBack,
            )
            BrowserControlButton(
                icon = LucideR.drawable.lucide_ic_arrow_right,
                description = "앞으로",
                enabled = snapshot.canGoForward && !actionPending,
                onClick = onForward,
            )
            BrowserControlButton(
                icon = if (snapshot.isLoading) {
                    LucideR.drawable.lucide_ic_x
                } else {
                    LucideR.drawable.lucide_ic_refresh_cw
                },
                description = if (snapshot.isLoading) "로딩 중지" else "새로고침",
                enabled = snapshot.available && (snapshot.isLoading || !actionPending),
                onClick = onRefresh,
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            ) {
                Text(
                    text = snapshot.title.ifBlank { "에이전트 브라우저" },
                    style = MiuixTheme.textStyles.body2,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                if (snapshot.host.isNotBlank()) {
                    Text(
                        text = snapshot.host,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                    )
                }
            }

            BrowserControlButton(
                icon = LucideR.drawable.lucide_ic_external_link,
                description = "외부 앱으로 열기",
                enabled = snapshot.available,
                onClick = onOpenExternal,
            )
            BrowserControlButton(
                icon = LucideR.drawable.lucide_ic_trash_2,
                description = "세션 초기화",
                enabled = snapshot.available && !actionPending,
                onClick = onReset,
            )
        }
    }
}

@Composable
private fun BrowserControlButton(
    icon: Int,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .alpha(if (enabled) 1f else 0.34f)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                if (!enabled) disabled()
            },
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MiuixTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun BrowserStatusBanner(snapshot: BrowserSessionSnapshot) {
    val risk = snapshot.riskChallengeKind
    val message = when {
        risk != null -> "페이지에 사람의 확인이 필요해 에이전트가 자동 탭과 입력을 중지했습니다. 아래에서 직접 조작할 수 있습니다."
        snapshot.error != null -> snapshot.error
        snapshot.isUserControlling && snapshot.available ->
            "현재 세션을 직접 조작하고 있습니다. 에이전트의 웹 작업은 중지되었으며, 뒤로 돌아가면 계속하도록 할 수 있습니다."
        else -> null
    } ?: return
    val color = when {
        risk != null -> StatusWarning
        snapshot.error != null -> StatusError
        else -> MiuixTheme.colorScheme.primary
    }
    val icon = if (risk != null || snapshot.error != null) {
        LucideR.drawable.lucide_ic_shield_alert
    } else {
        LucideR.drawable.lucide_ic_mouse_pointer_click
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        colors = CardDefaults.defaultColors(
            color = color.copy(alpha = 0.10f),
            contentColor = MiuixTheme.colorScheme.onSurface,
        ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(17.dp),
                tint = color,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun BrowserEmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial).changes.forEach { change ->
                            change.consume()
                        }
                    }
                }
            }
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(LucideR.drawable.lucide_ic_globe),
            contentDescription = null,
            modifier = Modifier.size(38.dp),
            tint = MiuixTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = "브라우저에 열린 웹페이지가 없습니다.",
            style = MiuixTheme.textStyles.body1,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "주소창에 URL을 입력하거나 에이전트에게 웹페이지를 찾아보도록 요청하세요. 세션은 Eta 안에서만 사용됩니다.",
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun BrowserLoadingState(
    host: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial).changes.forEach { change ->
                            change.consume()
                        }
                    }
                }
            }
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(LucideR.drawable.lucide_ic_globe),
            contentDescription = null,
            modifier = Modifier.size(30.dp),
            tint = MiuixTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = if (host.isBlank()) "웹페이지 여는 중" else "$host 여는 중",
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
        )
    }
}

@Composable
private fun BrowserFailedState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial).changes.forEach { change ->
                            change.consume()
                        }
                    }
                }
            }
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(LucideR.drawable.lucide_ic_shield_alert),
            contentDescription = null,
            modifier = Modifier.size(30.dp),
            tint = StatusError,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "웹페이지를 열지 못했습니다.",
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun BrowserWebViewHost(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val backgroundColor = MiuixTheme.colorScheme.surfaceContainer.toArgb()
    val container = remember(context) {
        FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(backgroundColor)
        }
    }
    DisposableEffect(container, context) {
        AgentBrowserSession.attachTo(container, context)
        onDispose { AgentBrowserSession.detachFrom(container) }
    }
    AndroidView(
        factory = { container },
        update = { view -> view.setBackgroundColor(backgroundColor) },
        modifier = modifier,
    )
}
