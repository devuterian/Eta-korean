package fuck.andes.ui.screens.terminal

import android.content.Context
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fuck.andes.agent.terminal.AlpineEnvironmentInstaller
import fuck.andes.agent.terminal.AlpineEnvironmentState
import fuck.andes.agent.terminal.AlpineEnvironmentStatus
import fuck.andes.agent.terminal.AlpineInstallProgress
import fuck.andes.agent.terminal.AlpineInstallResult
import fuck.andes.ui.components.MiuixScaffoldPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextButton

@Composable
internal fun LinuxEnvironmentScreen(
    context: Context,
    onBack: () -> Unit,
) {
    val installer = remember(context.applicationContext) {
        AlpineEnvironmentInstaller(context.applicationContext)
    }
    val coroutineScope = rememberCoroutineScope()
    var status by remember { mutableStateOf(installer.status()) }
    var installing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<AlpineInstallProgress?>(null) }
    var resultMessage by remember { mutableStateOf<String?>(null) }

    MiuixScaffoldPage(
        title = "Linux 도구 환경",
        onBack = onBack,
    ) {
        item(key = "status-title") { SmallTitle("환경 상태") }
        item(key = "status-card") {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                BasicComponent(
                    title = status.title(),
                    summary = progress?.summary() ?: status.summary(),
                    endActions = {
                        TextButton(
                            text = when {
                                installing -> "설치 중"
                                status.state == AlpineEnvironmentState.READY -> "준비됨"
                                status.state == AlpineEnvironmentState.BASE_READY -> "설치 계속"
                                else -> "다운로드 및 설치"
                            },
                            enabled = !installing && status.state != AlpineEnvironmentState.READY,
                            onClick = {
                                if (installing) return@TextButton
                                installing = true
                                resultMessage = null
                                coroutineScope.launch {
                                    val result = installer.install { update ->
                                        withContext(Dispatchers.Main.immediate) {
                                            progress = update
                                        }
                                    }
                                    status = installer.status()
                                    progress = null
                                    installing = false
                                    resultMessage = result.toMessage()
                                }
                            },
                        )
                    },
                )
            }
        }

        resultMessage?.let { message ->
            item(key = "result-card") {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    BasicComponent(title = message)
                }
            }
        }

        item(key = "details-title") { SmallTitle("안내") }
        item(key = "details-card") {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                BasicComponent(
                    title = "Android Root Shell과 분리됨",
                    summary = "시스템, 앱, 로그, Magisk 작업은 Android 환경을 사용하고 Python, Git, jq, zip 등의 범용 도구는 Alpine 환경을 사용합니다.",
                )
                BasicComponent(
                    title = "설치 내용",
                    summary = "약 4MB의 Alpine 3.24 기본 파일 시스템을 먼저 다운로드한 뒤 Bash, Python, Git, curl, wget, jq, zip/unzip, OpenSSL, SQLite, Vim, Nano를 설치합니다. 현재 설치 후 약 120MB를 사용합니다.",
                )
                BasicComponent(
                    title = "필요에 따라 확장",
                    summary = "컴파일 작업에는 Linux 환경에서 apk add build-base clang cmake를 실행할 수 있습니다. 추가 사용량은 선택한 패키지에 따라 달라집니다.",
                )
                BasicComponent(
                    title = "권한 범위",
                    summary = "환경은 Root chroot로 실행되며 독립 mount namespace로 마운트 누출을 방지합니다. 도구 체인일 뿐 보안 샌드박스는 아닙니다.",
                )
            }
        }
    }
}

private fun AlpineEnvironmentStatus.title(): String = when (state) {
    AlpineEnvironmentState.NOT_INSTALLED -> "설치되지 않음"
    AlpineEnvironmentState.BASE_READY -> "기본 환경 준비됨"
    AlpineEnvironmentState.READY -> "Alpine ${version ?: ""} 준비됨".trim()
}

private fun AlpineEnvironmentStatus.summary(): String = when (state) {
    AlpineEnvironmentState.NOT_INSTALLED -> "Root 권한과 Magisk, KernelSU 또는 APatch BusyBox가 필요합니다."
    AlpineEnvironmentState.BASE_READY -> "범용 도구 설치가 완료되지 않았습니다. 현재 진행 상태에서 계속할 수 있습니다."
    AlpineEnvironmentState.READY -> "에이전트는 터미널의 environment=linux를 통해 전체 도구 환경을 사용할 수 있습니다."
}

private fun AlpineInstallProgress.summary(): String {
    if (stage.displayName != "Alpine 기본 환경 다운로드" || totalBytes <= 0L) {
        return stage.displayName
    }
    val percent = (downloadedBytes * 100L / totalBytes).coerceIn(0L, 100L)
    return "${stage.displayName} · $percent%"
}

private fun AlpineInstallResult.toMessage(): String = when (this) {
    AlpineInstallResult.AlreadyReady -> "Linux 도구 환경이 준비되었습니다."
    is AlpineInstallResult.Installed -> "Alpine $version 및 범용 도구 설치가 완료되었습니다."
    is AlpineInstallResult.UnsupportedAbi -> "지원하지 않는 기기 아키텍처: $abi"
    AlpineInstallResult.RootUnavailable -> "Root 권한을 받지 못했습니다. Root 관리자에서 Eta를 허용하세요."
    AlpineInstallResult.BusyBoxUnavailable -> "Root 환경에 사용 가능한 BusyBox 또는 필수 applet이 없습니다."
    AlpineInstallResult.EnvironmentUnavailable -> "현재 Root 환경에서 격리된 mount namespace 또는 chroot를 만들 수 없습니다."
    is AlpineInstallResult.Failed -> "${stage.displayName}에 실패했습니다. 네트워크를 확인하거나 잠시 후 다시 시도하세요."
}
