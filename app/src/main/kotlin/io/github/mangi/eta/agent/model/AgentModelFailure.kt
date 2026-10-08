package io.github.mangi.eta.agent.model

import io.github.mangi.eta.i18n.ko
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ProtocolException
import javax.net.ssl.SSLException

/** Provider 边界只分类失败；重试预算与上下文由 Loop 持有。 */
internal class AgentModelFailure(
    val code: String,
    val retryable: Boolean,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause) {
    companion object {
        private val transientStatus = setOf(408, 429, 500, 502, 503, 504, 524, 529)
        private val permanentCodes = setOf(
            "insufficient_quota", "quota_exceeded", "billing_error", "usage_limit_reached",
        )
        private val transientCodes = setOf(
            "rate_limit_exceeded", "rate_limit_error", "overloaded_error", "server_error",
            "api_error", "internal_error", "provider_unavailable", "service_unavailable",
        )

        fun http(status: Int, body: String): AgentModelFailure {
            val error = try {
                JSONObject(body).optJSONObject("error")
            } catch (_: org.json.JSONException) {
                null
            }
            if (isContextOverflow(error)) return AgentModelFailure(
                "CONTEXT_OVERFLOW", false, ko("模型上下文超过容量限制。", "모델 컨텍스트가 용량 제한을 초과했습니다."),
            )
            val permanent = isPermanent(error, body)
            return AgentModelFailure(
                code = "HTTP_$status",
                retryable = status in transientStatus && !permanent,
                message = if (permanent) ko("模型接口额度或计费受限（HTTP $status），请检查服务商账户。", "모델 API 사용량 또는 결제가 제한되었습니다(HTTP $status). 서비스 제공자 계정을 확인하세요.")
                else when (status) {
                    400 -> ko("模型请求参数无效（HTTP 400），请检查模型配置。", "모델 요청 매개변수가 올바르지 않습니다(HTTP 400). 모델 설정을 확인하세요.")
                    401 -> ko("模型接口认证失败（HTTP 401），请检查 API Key。", "모델 API 인증에 실패했습니다(HTTP 401). API Key를 확인하세요.")
                    403 -> ko("模型接口拒绝访问（HTTP 403），请检查账户与模型权限。", "모델 API 접근이 거부되었습니다(HTTP 403). 계정과 모델 권한을 확인하세요.")
                    404 -> ko("模型接口或模型不存在（HTTP 404），请检查接口地址与模型名称。", "모델 API 또는 모델이 존재하지 않습니다(HTTP 404). API 주소와 모델 이름을 확인하세요.")
                    429 -> ko("模型接口暂时限流（HTTP 429）。", "모델 API 요청이 일시적으로 제한되었습니다(HTTP 429).")
                    else -> ko("模型接口返回 HTTP $status", "모델 API가 HTTP ${status} 오류를 반환했습니다.")
                },
            )
        }

        fun stream(error: JSONObject, message: String): AgentModelFailure {
            if (isContextOverflow(error)) return AgentModelFailure(
                "CONTEXT_OVERFLOW", false, ko("模型上下文超过容量限制。", "모델 컨텍스트가 용량 제한을 초과했습니다."),
            )
            val codes = listOf(
                error.optString("code"),
                error.optString("type"),
                error.optJSONObject("metadata")?.optString("error_type").orEmpty(),
            )
            return AgentModelFailure(
                code = "PROVIDER_STREAM_ERROR",
                retryable = !isPermanent(error, error.optString("message")) &&
                    codes.any { it in transientCodes || it.toIntOrNull() in transientStatus },
                message = message,
            )
        }

        fun incompleteStream(message: String) = AgentModelFailure("STREAM_INCOMPLETE", true, message)

        fun transport(failure: Exception): AgentModelFailure? = when (failure) {
            is AgentModelFailure -> failure
            is InterruptedIOException -> AgentModelFailure(
                "MODEL_TIMEOUT", true,
                ko("模型请求等待超时（连接或写入超时，或读取响应等待超过 ${AgentHttpClient.MODEL_READ_TIMEOUT_MS / 60_000} 分钟）。", "모델 요청 대기 시간이 초과되었습니다(연결 또는 쓰기 시간 초과, 또는 응답 대기 ${AgentHttpClient.MODEL_READ_TIMEOUT_MS / 60_000}분 초과)."),
                failure,
            )
            is SSLException, is ProtocolException -> null
            is IOException -> AgentModelFailure(
                "MODEL_CONNECTION_FAILED", true, ko("模型连接中断或暂时无法建立，请检查网络与服务商状态。", "모델 연결이 끊겼거나 일시적으로 연결할 수 없습니다. 네트워크와 서비스 제공자 상태를 확인하세요."), failure,
            )
            else -> null
        }

        private fun isContextOverflow(error: JSONObject?): Boolean {
            if (error == null) return false
            if (listOf(error.optString("code"), error.optString("type")).any {
                it in setOf("context_length_exceeded", "context_window_exceeded", "prompt_too_long", "input_too_long")
            }) return true
            val message = error.optString("message").lowercase()
            return message.contains("maximum context length") || message.contains("prompt is too long") ||
                message.contains("exceeds the context window") || message.contains("input token count exceeds")
        }

        private fun isPermanent(error: JSONObject?, body: String): Boolean =
            error?.optString("code") in permanentCodes || error?.optString("type") in permanentCodes ||
                listOf("insufficient_quota", "quota exceeded", "out of budget", "billing", "usage limit")
                    .any { body.contains(it, ignoreCase = true) }
    }
}
