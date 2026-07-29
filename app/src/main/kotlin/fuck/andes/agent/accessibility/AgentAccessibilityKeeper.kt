package fuck.andes.agent.accessibility

import android.content.Context
import android.os.SystemClock
import fuck.andes.core.AndroidAgentLogger

/**
 * 在 GUI 工具执行前确认 Eta 无障碍服务已经真实连接。
 *
 * 持久保护、Secure Settings 写入与断连重绑均由 system_server 后端负责。这里不申请
 * Root，也不直接改系统设置；保护关闭或后端不可用时 fail closed。
 */
object AgentAccessibilityKeeper {
    internal fun ensureEnabledForGuiOperation(context: Context): AccessibilityEnableResult {
        val startedAt = SystemClock.elapsedRealtime()
        val result = ensureAvailable(
            serviceAvailable = AgentAccessibilityService::isAvailable,
            protectionEnabled = { AccessibilityProtectionClient.isEnabled(context) },
            requestRecovery = {
                AccessibilityProtectionClient.requestRecoveryBlocking(context) ==
                    AccessibilityProtectionClient.ControlStatus.APPLIED
            },
            awaitServiceBinding = ::awaitServiceBinding,
        )
        val elapsedMs = SystemClock.elapsedRealtime() - startedAt
        if (result.available) {
            AndroidAgentLogger.info(
                "Agent accessibility action=ensure_for_gui outcome=completed " +
                    "recoveryRequested=${result.recoveryRequested} " +
                    "elapsed_ms=$elapsedMs"
            )
        } else {
            AndroidAgentLogger.warn(
                "Agent accessibility action=ensure_for_gui outcome=failed " +
                    "code=${result.code} recoveryRequested=${result.recoveryRequested} " +
                    "elapsed_ms=$elapsedMs"
            )
        }
        return result
    }

    internal fun ensureAvailable(
        serviceAvailable: () -> Boolean,
        protectionEnabled: () -> Boolean,
        requestRecovery: () -> Boolean,
        awaitServiceBinding: () -> Boolean,
    ): AccessibilityEnableResult {
        if (serviceAvailable()) {
            return AccessibilityEnableResult.available(recoveryRequested = false)
        }
        if (!protectionEnabled()) {
            return AccessibilityEnableResult.failure(
                code = "ACCESSIBILITY_UNAVAILABLE",
                message = "Eta 접근성 서비스가 연결되지 않았습니다. 설정에서 서비스를 켜거나 ‘접근성 강제 유지’를 활성화해 주세요.",
                recoveryRequested = false,
            )
        }
        if (!requestRecovery()) {
            return AccessibilityEnableResult.failure(
                code = "ACCESSIBILITY_PROTECTION_UNAVAILABLE",
                message = "접근성 보호 백엔드를 사용할 수 없어 이번 GUI 작업을 실행하지 않았습니다.",
                recoveryRequested = true,
            )
        }
        if (!awaitServiceBinding()) {
            return AccessibilityEnableResult.failure(
                code = "ACCESSIBILITY_REPAIR_TIMEOUT",
                message = "복구 제한 시간 안에 Eta 접근성 서비스가 연결되지 않아 이번 GUI 작업을 실행하지 않았습니다.",
                recoveryRequested = true,
            )
        }
        return AccessibilityEnableResult.available(recoveryRequested = true)
    }

    private fun awaitServiceBinding(): Boolean {
        repeat(SERVICE_BIND_ATTEMPTS) {
            if (AgentAccessibilityService.isAvailable()) return true
            SystemClock.sleep(SERVICE_BIND_POLL_MS)
        }
        return AgentAccessibilityService.isAvailable()
    }

    private const val SERVICE_BIND_ATTEMPTS = 60
    private const val SERVICE_BIND_POLL_MS = 100L
}

internal data class AccessibilityEnableResult(
    val available: Boolean,
    val code: String = "",
    val message: String = "",
    val recoveryRequested: Boolean,
) {
    companion object {
        fun available(
            recoveryRequested: Boolean,
        ): AccessibilityEnableResult = AccessibilityEnableResult(
            available = true,
            recoveryRequested = recoveryRequested,
        )

        fun failure(
            code: String,
            message: String,
            recoveryRequested: Boolean,
        ): AccessibilityEnableResult = AccessibilityEnableResult(
            available = false,
            code = code,
            message = message,
            recoveryRequested = recoveryRequested,
        )
    }
}
