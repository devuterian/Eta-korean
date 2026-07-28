package fuck.andes.ui

import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import com.composables.icons.lucide.R as LucideR
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import fuck.andes.FuckAndesApp
import fuck.andes.agent.accessibility.AgentAccessibilityService
import fuck.andes.config.Prefs
import fuck.andes.data.repository.ProviderRepository
import fuck.andes.data.repository.RuntimeConfigRepository
import fuck.andes.systemizer.GoogleAppSystemizerInstaller
import fuck.andes.ui.components.MiuixBackButton
import fuck.andes.ui.navigation.AppRoute
import fuck.andes.systemizer.RootManager
import fuck.andes.systemizer.SystemizerInstallResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

// ── ColorOS / COUI 主色（ColorOS 16.1 Settings.apk: coui_color_*） ────────────────
// 约定：设置页圆形图标/按钮底色只使用 ColorOS 设置主色。
// 不要用 coui_color_*_variant、截图平均取样色或 Material/iOS 近似色替代，否则实心圆底会发灰或偏色。
private val ColorOSOrangeRed = Color(0xFFFF7700)
private val ColorOSRoyalBlue = Color(0xFF0066FF)
private val ColorOSVividGreen = Color(0xFF00BD13)
private val ColorOSAmberYellow = Color(0xFFFFB200)
private val ColorOSLightBlue = Color(0xFF0066FF)
private val ColorOSRed = Color(0xFFEB3B2F)
private val ColorOSPurple = Color(0xFF0066FF)
private val ColorOSSlateGray = Color(0xFF0066FF)
private val ColorOSOrange = Color(0xFFFF7700)

/**
 * 模块配置界面。
 *
 * 开关默认开启（与历史硬编码行为一致）。切换时同步提交（RemotePreferences.commit
 * 会同步等待 binder 提交到 LSPosed 数据库，失败返回 false）；XposedService 未就绪时
 * 不允许写入，避免保存到 hook 进程不可见的本地配置。
 */
@Composable
internal fun SettingsScreen(
    context: Context,
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val coroutineScope = rememberCoroutineScope()
    var showSystemizerDialog by remember { mutableStateOf(false) }
    var installingSystemizer by remember { mutableStateOf(false) }

    // 悬浮窗权限状态：授权后从系统设置返回时（ON_RESUME）刷新。
    var overlayGranted by remember {
        mutableStateOf(android.provider.Settings.canDrawOverlays(context))
    }
    var accessibilityGranted by remember {
        mutableStateOf(isAgentAccessibilityEnabled(context))
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayGranted = android.provider.Settings.canDrawOverlays(context)
                accessibilityGranted = isAgentAccessibilityEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Provider / Model 选中状态展示
    val providers by ProviderRepository.providersFlow().collectAsState(initial = emptyList())
    val selectedProviderId by RuntimeConfigRepository.selectedProviderIdFlow()
        .collectAsState(initial = null)
    val selectedModelId by RuntimeConfigRepository.selectedModelIdFlow()
        .collectAsState(initial = null)
    val selectedProvider = remember(providers, selectedProviderId) {
        providers.find { it.id == selectedProviderId }
    }
    val selectedModel = remember(selectedProvider, selectedModelId) {
        selectedProvider?.models?.find { it.id == selectedModelId }
    }
    val providerSummary = selectedProvider?.let { provider ->
        "${provider.name} / ${selectedModel?.displayName ?: "모델을 선택하지 않음"}"
    } ?: "설정되지 않음"

    // prefs 绑定到 XposedService：service 到达时切换到 RemotePreferences（跨进程提交到
    // LSPosed 数据库）；未就绪时保持 null，UI 禁止修改。
    var prefs by remember { mutableStateOf(Prefs.remotePreferencesForUi(FuckAndesApp.serviceInstance)) }
    DisposableEffect(Unit) {
        val listener = object : FuckAndesApp.ServiceStateListener {
            override fun onServiceStateChanged(service: io.github.libxposed.service.XposedService?) {
                prefs = Prefs.remotePreferencesForUi(service)
                coroutineScope.launch {
                    RuntimeConfigRepository.ensureDefaults(service)
                }
            }
        }
        FuckAndesApp.addServiceStateListener(listener, notifyImmediately = true)
        onDispose { FuckAndesApp.removeServiceStateListener(listener) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "설정",
                largeTitle = "설정",
                navigationIcon = { MiuixBackButton(onClick = onBack) },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = innerPadding,
        ) {
            // ── LSPosed 未连接提示 ──────────────────────────────────────
            if (prefs == null) {
                item(key = "service_warning") {
                    Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                        BasicComponent(
                            title = "LSPosed 서비스가 연결되지 않음",
                        )
                    }
                }
            }

            // ── Agent ──────────────────────────────────────────────────
            item(key = "section_agent") {
                SmallTitle("Agent")
                Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    SwitchPref(
                        context = context,
                        prefs = prefs,
                        title = "기본으로 심층 사고 사용",
                        key = Prefs.Keys.AGENT_THINKING_ENABLED,
                        icon = LucideR.drawable.lucide_ic_brain,
                        iconTint = ColorOSRoyalBlue,
                    )
                    PrefDivider()
                    SwitchPref(
                        context = context,
                        prefs = prefs,
                        title = "웹 탐색 도구 사용",
                        summary = "백그라운드 브라우저에서 웹페이지를 읽고 조작합니다. 자동으로 전면 전환하지 않습니다.",
                        key = Prefs.Keys.AGENT_BROWSER_TOOLS,
                        icon = LucideR.drawable.lucide_ic_globe,
                        iconTint = ColorOSVividGreen,
                    )
                    PrefDivider()
                    SwitchPref(
                        context = context,
                        prefs = prefs,
                        title = "기기 직접 제어 도구 사용",
                        summary = "알람, 타이머, 미디어, 음량, 기기 상태를 화면 조작 없이 직접 제어합니다.",
                        key = Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS,
                        icon = LucideR.drawable.lucide_ic_smartphone,
                        iconTint = ColorOSVividGreen,
                    )
                    PrefDivider()
                    SwitchPref(
                        context = context,
                        prefs = prefs,
                        title = "민감한 기기 정보 읽기 허용",
                        summary = "알림, SMS 인증번호, 저장된 Wi‑Fi 비밀번호, 시스템 설정, 로그를 포함합니다. 원본 결과는 보관하지 않습니다.",
                        key = Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS,
                        icon = LucideR.drawable.lucide_ic_eye,
                        iconTint = ColorOSAmberYellow,
                    )
                    PrefDivider()
                    SwitchPref(
                        context = context,
                        prefs = prefs,
                        title = "민감한 기기 작업 허용",
                        summary = "WeChat 메시지 전송, 앱 정지, 시스템 설정 및 네트워크 스위치 변경을 포함합니다. 사용 시 모델이 직접 호출할 수 있습니다.",
                        key = Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS,
                        icon = LucideR.drawable.lucide_ic_shield_alert,
                        iconTint = ColorOSAmberYellow,
                    )
                    PrefDivider()
                    SwitchPref(
                        context = context,
                        prefs = prefs,
                        title = "터미널/파일 도구 사용",
                        summary = "에이전트가 Android user/root Shell을 사용하고 휴대폰 파일을 읽거나 쓸 수 있습니다.",
                        key = Prefs.Keys.AGENT_TERMINAL_TOOLS,
                        icon = LucideR.drawable.lucide_ic_square_terminal,
                        iconTint = ColorOSAmberYellow,
                    )
                    PrefDivider()
                    ArrowPreference(
                        title = "Linux 도구 환경",
                        summary = "Python, Git, jq, zip 등 범용 명령어를 설치합니다. 현재 약 120MB를 사용합니다.",
                        startAction = {
                            TintedIcon(
                                icon = LucideR.drawable.lucide_ic_square_terminal,
                                tint = ColorOSVividGreen,
                            )
                        },
                        onClick = { onNavigate(AppRoute.LinuxEnvironment) },
                    )
                    PrefDivider()
                    ArrowPreference(
                        title = "모델 제공자",
                        summary = providerSummary,
                        startAction = {
                            TintedIcon(
                                icon = LucideR.drawable.lucide_ic_cpu,
                                tint = ColorOSPurple,
                            )
                        },
                        onClick = { onNavigate(AppRoute.ModelProviders) },
                    )
                }
            }

            // ── 系统助手接管 ──────────────────────────────────────────────
            item(key = "section_assistant_takeover") {
                SmallTitle("시스템 어시스턴트 연동")
                Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    SwitchPref(
                        context = context,
                        prefs = prefs,
                        title = "시스템 어시스턴트에서 사용자 지정 모델 사용",
                        summary = "Breeno와 슈퍼 샤오아이가 이 설정을 함께 사용합니다",
                        key = Prefs.Keys.AGENT_CUSTOM_MODEL,
                        icon = LucideR.drawable.lucide_ic_cpu,
                        iconTint = ColorOSOrangeRed,
                    )
                    PrefDivider()
                    SwitchPref(
                        context = context,
                        prefs = prefs,
                        title = "/agent 접두사에서만 연동",
                        summary = "Breeno와 슈퍼 샤오아이에 모두 적용됩니다",
                        key = Prefs.Keys.AGENT_REQUIRE_PREFIX,
                        icon = LucideR.drawable.lucide_ic_message_square,
                        iconTint = ColorOSAmberYellow,
                    )
                }
            }

            // ── Gemini ─────────────────────────────────────────────────
            item(key = "section_gemini") {
                SmallTitle("Gemini")
                Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    SwitchPref(
                        context = context,
                        prefs = prefs,
                        title = "전원 버튼을 길게 눌러 Gemini 실행",
                        key = Prefs.Keys.POWER_KEY_TAKEOVER,
                        icon = LucideR.drawable.lucide_ic_power,
                        iconTint = ColorOSOrangeRed,
                    )
                    PrefDivider()
                    SwitchPref(
                        context = context,
                        prefs = prefs,
                        title = "Google을 기본 어시스턴트로 자동 설정",
                        key = Prefs.Keys.ASSISTANT_AUTO_CONFIG,
                        icon = LucideR.drawable.lucide_ic_sparkles,
                        iconTint = ColorOSVividGreen,
                    )
                    PrefDivider()
                    SwitchPref(
                        context = context,
                        prefs = prefs,
                        title = "화면이 꺼져도 Hey Google 감지 유지",
                        key = Prefs.Keys.HOTWORD_SELF_HEAL,
                        icon = LucideR.drawable.lucide_ic_mic,
                        iconTint = ColorOSAmberYellow,
                    )
                    PrefDivider()
                    SwitchPref(
                        context = context,
                        prefs = prefs,
                        title = "잠금 화면에서 실행 시 자동 음성 입력",
                        key = Prefs.Keys.LOCKSCREEN_VOICE_COMMAND,
                        icon = LucideR.drawable.lucide_ic_lock,
                        iconTint = ColorOSRed,
                    )
                    PrefDivider()
                    SwitchPref(
                        context = context,
                        prefs = prefs,
                        title = "화면이 켜진 상태에서 실행 시 자동 음성 입력",
                        key = Prefs.Keys.SCREEN_ON_VOICE_COMMAND,
                        icon = LucideR.drawable.lucide_ic_mic,
                        iconTint = ColorOSLightBlue,
                    )
                    PrefDivider()
                    ArrowPreference(
                        title = "Google 앱을 시스템 앱으로 전환",
                        startAction = {
                            TintedIcon(
                                icon = LucideR.drawable.lucide_ic_shield,
                                tint = ColorOSVividGreen,
                            )
                        },
                        enabled = !installingSystemizer,
                        holdDownState = showSystemizerDialog,
                        onClick = {
                            if (!installingSystemizer) {
                                showSystemizerDialog = true
                            }
                        },
                    )
                }
            }

            // ── 一圈即搜 ────────────────────────────────────────────────
            item(key = "section_circle_to_search") {
                SmallTitle("서클 투 서치")
                Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    SwitchPref(
                        context = context,
                        prefs = prefs,
                        title = "제스처 바를 길게 눌러 서클 투 서치 실행",
                        key = Prefs.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH,
                        icon = LucideR.drawable.lucide_ic_search,
                        iconTint = ColorOSRoyalBlue,
                    )
                    PrefDivider()
                    SwitchPref(
                        context = context,
                        prefs = prefs,
                        title = "두 손가락을 길게 눌러 서클 투 서치 실행",
                        key = Prefs.Keys.DOUBLE_FINGER_CIRCLE_TO_SEARCH,
                        icon = LucideR.drawable.lucide_ic_mouse_pointer_click,
                        iconTint = ColorOSLightBlue,
                    )
                }
            }

            // ── 权限 ────────────────────────────────────────────────────
            item(key = "section_permissions") {
                SmallTitle("권한")
                Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    ArrowPreference(
                        title = "다른 앱 위에 표시 권한",
                        startAction = {
                            TintedIcon(
                                icon = LucideR.drawable.lucide_ic_layers,
                                tint = ColorOSOrangeRed,
                            )
                        },
                        endActions = {
                            Text(
                                text = if (overlayGranted) "허용됨" else "허용되지 않음",
                                fontSize = MiuixTheme.textStyles.body2.fontSize,
                                color = if (overlayGranted) {
                                    MiuixTheme.colorScheme.onSurfaceVariantActions
                                } else {
                                    ColorOSOrangeRed
                                },
                            )
                        },
                        onClick = {
                            if (!overlayGranted) {
                                runCatching {
                                    context.startActivity(
                                        android.content.Intent(
                                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            android.net.Uri.parse("package:${context.packageName}"),
                                        ),
                                    )
                                }
                            }
                        },
                    )
                    PrefDivider()
                    ArrowPreference(
                        title = "접근성 강화 도구",
                        startAction = {
                            TintedIcon(
                                icon = LucideR.drawable.lucide_ic_accessibility,
                                tint = ColorOSRoyalBlue,
                            )
                        },
                        endActions = {
                            val enabled = accessibilityGranted || AgentAccessibilityService.isAvailable()
                            Text(
                                text = if (enabled) "사용 중" else "사용 안 함",
                                fontSize = MiuixTheme.textStyles.body2.fontSize,
                                color = if (enabled) {
                                    MiuixTheme.colorScheme.onSurfaceVariantActions
                                } else {
                                    ColorOSRoyalBlue
                                },
                            )
                        },
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS),
                                )
                            }
                        },
                    )
                }
            }

            // ── 关于 ────────────────────────────────────────────────────
            item(key = "section_about") {
                SmallTitle("정보")
                Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    ArrowPreference(
                        title = "소스 코드",
                        startAction = {
                            TintedIcon(
                                icon = LucideR.drawable.lucide_ic_github,
                                tint = ColorOSPurple,
                            )
                        },
                        endActions = {
                            Text(
                                text = "GitHub",
                                fontSize = MiuixTheme.textStyles.body2.fontSize,
                                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                            )
                        },
                        onClick = {
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://github.com/Mangi-11/Eta"),
                            )
                            context.startActivity(intent)
                        },
                    )
                }
            }
        }

        SystemizerConfirmDialog(
            show = showSystemizerDialog,
            installing = installingSystemizer,
            onDismissRequest = {
                if (!installingSystemizer) {
                    showSystemizerDialog = false
                }
            },
            onConfirm = {
                if (installingSystemizer) return@SystemizerConfirmDialog
                showSystemizerDialog = false
                installingSystemizer = true
                coroutineScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        GoogleAppSystemizerInstaller(context.applicationContext).install()
                    }
                    installingSystemizer = false
                    Toast.makeText(
                        context.applicationContext,
                        result.toToastMessage(),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            },
        )
    }
}

// ── 带色彩的圆形图标（ColorOS 风格：圆形背景 + 纯白图标） ────────────────────────────────

@Composable
private fun TintedIcon(
    icon: Int,
    tint: Color,
) {
    Box(
        modifier = Modifier
            .padding(end = 12.dp)
            .size(32.dp)
            .background(tint, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = Color.White,
        )
    }
}

// ── Card 内分隔线 ───────────────────────────────────────────────────────────

@Composable
private fun PrefDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(
            // 对齐 BasicComponent 内文字起始位置：
            // insideMargin(16) + 图标 padding end(12) + 圆形宽度(32) = 60dp
            start = 60.dp,
        ),
    )
}

// ── 系统化确认对话框 ─────────────────────────────────────────────────────────

@Composable
private fun SystemizerConfirmDialog(
    show: Boolean,
    installing: Boolean,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    OverlayDialog(
        show = show,
        title = "Google 앱을 시스템 앱으로 전환",
        onDismissRequest = onDismissRequest,
    ) {
        Text(
            text = "시스템 앱은 음성 호출 권한과 완화된 자동 시작 제한을 적용받아 기본 앱에 가까운 환경을 제공합니다.",
            modifier = Modifier.fillMaxWidth(),
            fontSize = MiuixTheme.textStyles.body2.fontSize,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Magisk / KernelSU 모듈로 설치하며, 재부팅 후 적용됩니다.",
            modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
            fontSize = MiuixTheme.textStyles.footnote1.fontSize,
            color = MiuixTheme.colorScheme.onSurfaceVariantActions,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(
                text = "취소",
                onClick = onDismissRequest,
                modifier = Modifier.weight(1f),
                enabled = !installing,
            )
            TextButton(
                text = "확인",
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
                enabled = !installing,
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

// ── 带图标的布尔开关 ─────────────────────────────────────────────────────────

/**
 * 单个布尔开关：状态随 [prefs]/[key] 变化重读，切换时同步写入。
 *
 * XposedService 到达时通过 [remember(prefs, key)] 重算初始值；切换写入用
 * [putBooleanSync] 同步提交，避免 RemotePreferences.apply() 异步 binder 失败后 UI 显示
 * 与 hook 侧不一致。
 */
@Composable
private fun SwitchPref(
    context: Context,
    prefs: SharedPreferences?,
    title: String,
    summary: String? = null,
    key: String,
    icon: Int,
    iconTint: Color,
) {
    val enabled = prefs != null
    val default = Prefs.Keys.BOOLEAN_DEFAULTS[key] ?: true
    var checked by remember(prefs, key) {
        mutableStateOf(prefs?.getBoolean(key, default) ?: default)
    }
    SwitchPreference(
        title = title,
        summary = summary,
        checked = checked,
        onCheckedChange = { value ->
            // 同步提交；RemotePreferences.commit() 失败（binder 提交失败）时回滚 UI 状态，
            // 避免 UI 显示已切换而 hook 进程实际未收到。
            val targetPrefs = prefs ?: return@SwitchPreference
            if (putBooleanSync(targetPrefs, key, value)) {
                checked = value
            } else {
                Toast.makeText(context.applicationContext, "설정을 저장하지 못했습니다.", Toast.LENGTH_SHORT).show()
            }
        },
        startAction = {
            TintedIcon(icon = icon, tint = iconTint)
        },
        enabled = enabled,
    )
}

/**
 * 同步写入布尔值。RemotePreferences 的 [commit] 先更新本进程 map 再同步等待 binder 提交，
 * 失败（binder RemoteException）返回 false 但本进程 map 已被改写——此时 hook 进程收不到新值。
 * 返回是否提交成功，供调用方决定是否更新 UI。
 */
private fun putBooleanSync(
    prefs: SharedPreferences,
    key: String,
    value: Boolean
): Boolean =
    runCatching { prefs.edit().putBoolean(key, value).commit() }.getOrDefault(false)

private fun putStringSync(
    prefs: SharedPreferences,
    key: String,
    value: String
): Boolean =
    runCatching { prefs.edit().putString(key, value).commit() }.getOrDefault(false)

private fun isAgentAccessibilityEnabled(context: Context): Boolean {
    val expected = android.content.ComponentName(
        context,
        AgentAccessibilityService::class.java
    ).flattenToString()
    val enabledServices = android.provider.Settings.Secure.getString(
        context.contentResolver,
        android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ).orEmpty()
    return enabledServices.split(':').any { it.equals(expected, ignoreCase = true) }
}

private fun SystemizerInstallResult.toToastMessage(): String =
    when (this) {
        SystemizerInstallResult.AlreadySystemized -> "Google 앱이 이미 시스템 priv-app입니다."
        SystemizerInstallResult.GoogleAppMissing -> "Google 앱이 설치되어 있지 않습니다."
        SystemizerInstallResult.UnsupportedRootManager -> "Magisk 또는 KernelSU를 찾지 못했습니다."
        SystemizerInstallResult.KernelSuMetamoduleMissing -> "KernelSU에서 metamodule 지원을 먼저 사용 설정해야 합니다."
        is SystemizerInstallResult.RootPermissionUnavailable -> when (rootManager) {
            RootManager.KERNEL_SU -> "KernelSU에서 Eta에 root 권한을 허용하세요."
            RootManager.MAGISK -> "Magisk에서 Eta에 root 권한을 허용하세요."
            RootManager.UNSUPPORTED -> "root 권한을 받지 못했습니다."
        }
        is SystemizerInstallResult.InstalledRebootRequired -> "설치가 완료되었습니다. 재부팅 후 적용됩니다."
        is SystemizerInstallResult.Failed -> commandOutput
            .lineSequence()
            .map { it.trim() }
            .lastOrNull { it.isNotEmpty() }
            ?.let { "$message：$it" }
            ?: message
    }
