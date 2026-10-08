package io.github.mangi.eta.agent.model

import io.github.mangi.eta.CodexOAuthManager
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.i18n.ko

/**
 * Adapter that obtains a short-lived access token immediately before a
 * request, then delegates the complete Responses/tool-loop implementation to
 * the ordinary Responses provider.
 */
internal object CodexResponsesProvider : AgentProviderClient {
    override val id: String = "openai_codex_responses"
    override val capabilities: ProviderCapabilities = OpenAiResponsesProvider.capabilities

    override fun complete(
        request: ProviderRequest,
        runController: AgentRunController,
        onEvent: (ProviderEvent) -> Unit,
    ): ProviderResponse {
        val sourceConfig = request.effectiveConfig
        require(sourceConfig.openAiEndpointMode == OpenAiEndpointMode.RESPONSES) {
            ko("当前 OpenAI Codex Provider 未配置为 Responses API", "현재 OpenAI Codex Provider가 Responses API로 설정되어 있지 않습니다.")
        }
        val credentials = CodexOAuthManager.requireCredentials()
        return try {
            CodexCompatibilityProfile.withUnauthorizedRetry(
                initialAccessToken = credentials.accessToken,
                execute = { accessToken ->
                    val attemptClaims = CodexCompatibilityProfile.claimsForCredentials(
                        credentials.copy(accessToken = accessToken),
                    )
                    val ephemeralConfig = buildRequestConfig(
                        sourceConfig = sourceConfig,
                        accessToken = accessToken,
                        claims = attemptClaims,
                        sessionId = request.sessionId,
                    )
                    OpenAiResponsesProvider.complete(
                        request.copy(config = ephemeralConfig),
                        runController,
                        onEvent,
                    )
                },
                refresh = { failedAccessToken ->
                    CodexOAuthManager.forceRefreshAfterUnauthorized(failedAccessToken).accessToken
                },
            )
        } catch (failure: AgentModelFailure) {
            if (failure.code != "HTTP_401") throw failure
            throw AgentModelFailure(
                code = "CODEX_SUBSCRIPTION_UNAUTHORIZED",
                retryable = false,
                message = ko("OpenAI Codex 对话接口认证失败（HTTP 401）。请重新登录订阅账号后重试；模型目录能刷新不代表对话请求已通过认证。", "OpenAI Codex 대화 API 인증에 실패했습니다(HTTP 401). 구독 계정으로 다시 로그인한 후 시도하세요. 모델 목록이 새로 고쳐져도 대화 요청 인증이 통과된 것은 아닙니다."),
                cause = failure,
            )
        }
    }

    internal fun buildRequestConfig(
        sourceConfig: AgentModelClient.ModelConfig,
        accessToken: String,
        claims: CodexCompatibilityProfile.JwtClaims?,
        sessionId: String,
    ): AgentModelClient.ModelConfig = sourceConfig.copy(
        baseUrl = CodexCompatibilityProfile.CODEX_RESPONSES_BASE_URL,
        // This value lives only in the request-local copy and is never
        // serialized to Provider settings, runtime JSON or a Bundle.
        apiKey = accessToken,
        authMode = CodexCompatibilityProfile.AUTH_MODE,
        // Codex always targets the fixed official host. Do not forward
        // provider/model custom headers to that host.
        customHeaders = CodexCompatibilityProfile.requestHeaders(claims, sessionId),
    )
}
