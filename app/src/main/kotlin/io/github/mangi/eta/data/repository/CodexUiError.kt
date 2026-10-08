package io.github.mangi.eta.data.repository

import io.github.mangi.eta.CodexOAuthException
import io.github.mangi.eta.CodexOAuthHttpException
import io.github.mangi.eta.CodexOAuthStage
import io.github.mangi.eta.CodexRateLimitedException
import io.github.mangi.eta.CodexReauthenticationRequiredException
import io.github.mangi.eta.agent.model.CodexCredentialStoreException
import io.github.mangi.eta.i18n.ko

/**
 * Fixed, actionable copy for OAuth-facing surfaces.
 *
 * Throwable messages and HTTP response bodies are deliberately not returned
 * here: an OAuth response can contain token-shaped data or provider internals.
 */
internal object CodexUiError {
    fun login(error: Throwable): String = when (error) {
        is CodexCredentialStoreException -> ko("OpenAI Codex 登录未完成，本地凭据保存失败，请检查存储空间后重试", "OpenAI Codex 로그인을 완료하지 못했습니다. 로컬 자격 증명 저장에 실패했습니다. 저장 공간을 확인한 후 다시 시도하세요.")
        is CodexRateLimitedException -> ko("OpenAI Codex 登录请求受到限流，请稍后重试", "OpenAI Codex 로그인 요청이 제한되었습니다. 잠시 후 다시 시도하세요.")
        is CodexReauthenticationRequiredException -> ko("OpenAI Codex 登录已失效，请重新登录", "OpenAI Codex 로그인이 만료되었습니다. 다시 로그인하세요.")
        is CodexOAuthHttpException -> loginHttp(error)
        is CodexOAuthException -> ko("OpenAI Codex 登录失败，请稍后重试", "OpenAI Codex 로그인에 실패했습니다. 잠시 후 다시 시도하세요.")
        else -> ko("OpenAI Codex 登录失败，请检查网络或稍后重试", "OpenAI Codex 로그인에 실패했습니다. 네트워크를 확인하거나 잠시 후 다시 시도하세요.")
    }

    fun models(error: Throwable): String = when (error) {
        is CodexRateLimitedException -> ko("OpenAI Codex 模型列表请求受到限流，请稍后重试", "OpenAI Codex 모델 목록 요청이 제한되었습니다. 잠시 후 다시 시도하세요.")
        is CodexReauthenticationRequiredException -> ko("OpenAI Codex 登录已失效，请重新登录", "OpenAI Codex 로그인이 만료되었습니다. 다시 로그인하세요.")
        is CodexModelCatalogException -> modelCatalog(error)
        is CodexCredentialStoreException -> ko("OpenAI Codex 本地凭据不可用，请重新登录", "OpenAI Codex 로컬 자격 증명을 사용할 수 없습니다. 다시 로그인하세요.")
        is CodexOAuthException -> ko("OpenAI Codex 模型列表暂时不可用，请稍后重试", "OpenAI Codex 모델 목록을 일시적으로 사용할 수 없습니다. 잠시 후 다시 시도하세요.")
        else -> ko("OpenAI Codex 模型列表暂时不可用，请稍后重试", "OpenAI Codex 모델 목록을 일시적으로 사용할 수 없습니다. 잠시 후 다시 시도하세요.")
    }

    private fun modelCatalog(error: CodexModelCatalogException): String = when (error.kind) {
        CodexModelCatalogFailureKind.HTTP -> when (error.statusCode) {
            401 -> ko("OpenAI Codex 模型目录返回 HTTP 401；已保留离线兜底列表。登录与对话可正常时，这不等于账号缺少模型权限", "OpenAI Codex 모델 목록이 HTTP 401을 반환했습니다. 오프라인 기본 목록을 유지합니다. 로그인과 대화가 정상이라면 계정에 모델 권한이 없다는 뜻은 아닙니다.")
            403 -> ko("OpenAI Codex 模型目录未获授权（HTTP 403）；已保留离线兜底列表", "OpenAI Codex 모델 목록 접근이 거부되었습니다(HTTP 403). 오프라인 기본 목록을 유지합니다.")
            429 -> ko("OpenAI Codex 模型列表受到限流（HTTP 429），请稍后重试", "OpenAI Codex 모델 목록 요청이 제한되었습니다(HTTP 429). 잠시 후 다시 시도하세요.")
            else -> ko("OpenAI Codex 模型列表请求失败（HTTP ${error.statusCode ?: "未知"}），请稍后重试", "OpenAI Codex 모델 목록 요청에 실패했습니다(HTTP ${error.statusCode ?: "알 수 없음"}). 잠시 후 다시 시도하세요.")
        }
        CodexModelCatalogFailureKind.EMPTY ->
            ko("OpenAI Codex 模型列表为空（HTTP 200），请确认此账号有可用模型", "OpenAI Codex 모델 목록이 비어 있습니다(HTTP 200). 이 계정에 사용 가능한 모델이 있는지 확인하세요.")
        CodexModelCatalogFailureKind.JWT_MISSING_ACCOUNT_ID ->
            ko("OpenAI Codex 登录令牌缺少账户标识，请重新登录", "OpenAI Codex 로그인 토큰에 계정 식별자가 없습니다. 다시 로그인하세요.")
        CodexModelCatalogFailureKind.NETWORK ->
            ko("无法连接 OpenAI Codex 模型目录；已保留离线兜底列表", "OpenAI Codex 모델 목록에 연결할 수 없습니다. 오프라인 기본 목록을 유지합니다.")
        CodexModelCatalogFailureKind.RESPONSE_TOO_LARGE ->
            ko("OpenAI Codex 模型目录响应超出安全大小限制；已保留离线兜底列表", "OpenAI Codex 모델 목록 응답이 안전 크기 제한을 초과했습니다. 오프라인 기본 목록을 유지합니다.")
        CodexModelCatalogFailureKind.PARSE ->
            ko("OpenAI Codex 模型列表响应无法解析，请稍后重试", "OpenAI Codex 모델 목록 응답을 해석할 수 없습니다. 잠시 후 다시 시도하세요.")
    }

    fun configuration(error: Throwable): String = when (error) {
        is CodexCredentialStoreException -> ko("OpenAI Codex 本地凭据保存失败，请检查存储空间后重试", "OpenAI Codex 로컬 자격 증명 저장에 실패했습니다. 저장 공간을 확인한 후 다시 시도하세요.")
        is CodexRateLimitedException -> ko("OpenAI Codex 请求受到限流，请稍后重试", "OpenAI Codex 요청이 제한되었습니다. 잠시 후 다시 시도하세요.")
        is CodexReauthenticationRequiredException -> ko("OpenAI Codex 登录已失效，请重新登录", "OpenAI Codex 로그인이 만료되었습니다. 다시 로그인하세요.")
        is CodexOAuthException -> ko("OpenAI Codex 配置同步失败，请稍后重试", "OpenAI Codex 설정 동기화에 실패했습니다. 잠시 후 다시 시도하세요.")
        else -> ko("OpenAI Codex 配置同步失败，请稍后重试", "OpenAI Codex 설정 동기화에 실패했습니다. 잠시 후 다시 시도하세요.")
    }

    private fun loginHttp(error: CodexOAuthHttpException): String = when {
        error.errorCode == "device_code_expired" -> ko("OpenAI 设备码已过期，请重新获取", "OpenAI 기기 코드가 만료되었습니다. 다시 발급받으세요.")
        error.stage == CodexOAuthStage.DEVICE_REQUEST && error.httpStatus in setOf(403, 404) ->
            ko("OpenAI 设备码功能可能未开启，请稍后重试或使用其他登录方式", "OpenAI 기기 코드 기능이 꺼져 있을 수 있습니다. 잠시 후 다시 시도하거나 다른 로그인 방식을 사용하세요.")
        error.errorCode.endsWith("network_error") ->
            "Cannot connect to OpenAI (${error.stage.code}: ${networkFailureKind(error.cause)}). Check Eta's network/VPN access and retry."
        error.errorCode == "response_too_large" ->
            ko("OpenAI 登录响应过大，请稍后重试", "OpenAI 로그인 응답이 너무 큽니다. 잠시 후 다시 시도하세요.")
        error.errorCode.endsWith("body_read_error") ->
            ko("OpenAI 登录响应读取失败，请稍后重试", "OpenAI 로그인 응답을 읽지 못했습니다. 잠시 후 다시 시도하세요.")
        error.stage == CodexOAuthStage.TOKEN_EXCHANGE &&
            error.errorCode == "token_exchange_http" ->
            ko("OpenAI 登录交换失败（HTTP ${error.httpStatus ?: "未知"}），请确认设备登录已完成后重试", "OpenAI 로그인 교환에 실패했습니다(HTTP ${error.httpStatus ?: "알 수 없음"}). 기기 로그인을 완료했는지 확인한 후 다시 시도하세요.")
        error.stage == CodexOAuthStage.TOKEN_EXCHANGE &&
            error.errorCode == "token_exchange_invalid_response" ->
            ko("OpenAI 登录交换响应无效，请稍后重试", "OpenAI 로그인 교환 응답이 올바르지 않습니다. 잠시 후 다시 시도하세요.")
        error.stage == CodexOAuthStage.TOKEN_EXCHANGE &&
            error.errorCode == "token_exchange_missing_access_token" ->
            ko("OpenAI 登录交换未返回访问令牌，请重新获取设备码", "OpenAI 로그인 교환에서 액세스 토큰을 받지 못했습니다. 기기 코드를 다시 발급받으세요.")
        error.stage == CodexOAuthStage.TOKEN_EXCHANGE ->
            ko("OpenAI 登录交换失败，请确认设备登录已完成后重试", "OpenAI 로그인 교환에 실패했습니다. 기기 로그인을 완료했는지 확인한 후 다시 시도하세요.")
        error.stage == CodexOAuthStage.DEVICE_POLL ->
            ko("OpenAI 设备登录暂未完成或已失效，请重新获取设备码", "OpenAI 기기 로그인이 아직 완료되지 않았거나 만료되었습니다. 기기 코드를 다시 발급받으세요.")
        else -> ko("OpenAI Codex 登录服务暂时不可用，请稍后重试", "OpenAI Codex 로그인 서비스를 일시적으로 사용할 수 없습니다. 잠시 후 다시 시도하세요.")
    }

    private fun networkFailureKind(cause: Throwable?): String = when (cause) {
        is java.net.UnknownHostException -> "DNS lookup failed"
        is java.net.SocketTimeoutException -> "connection timed out"
        is javax.net.ssl.SSLException -> "TLS connection failed"
        is java.net.ConnectException -> "connection refused or unreachable"
        is java.net.SocketException -> "connection interrupted"
        else -> "network I/O failed"
    }
}
