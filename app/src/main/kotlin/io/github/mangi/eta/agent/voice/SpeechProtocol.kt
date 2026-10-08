package io.github.mangi.eta.agent.voice

import io.github.mangi.eta.data.model.AsrProvider
import io.github.mangi.eta.data.model.SpeechCredentials
import io.github.mangi.eta.data.model.SpeechSettings
import io.github.mangi.eta.data.model.TtsProvider
import io.github.mangi.eta.data.repository.SpeechCredentialsUnavailable
import io.github.mangi.eta.i18n.ko
import java.io.IOException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal enum class SpeechErrorCode {
    CONFIGURATION, CREDENTIALS, PERMISSION, AUDIO, NETWORK, TIMEOUT, AUTHENTICATION,
    RATE_LIMITED, SERVER, PROTOCOL, NO_SPEECH, BACKPRESSURE, STORAGE,
}

internal class SpeechFailure(val code: SpeechErrorCode, val userMessage: String) : IOException(code.name)

internal fun Throwable.speechFailure(): SpeechFailure = when (this) {
    is SpeechFailure -> this
    is SpeechCredentialsUnavailable -> SpeechFailure(SpeechErrorCode.CREDENTIALS, ko("语音凭据无法解密，请重新填写并保存", "음성 자격 증명을 복호화할 수 없습니다. 다시 입력하고 저장하세요."))
    is SecurityException -> SpeechFailure(SpeechErrorCode.PERMISSION, ko("请允许 Eta 使用麦克风", "Eta의 마이크 사용을 허용하세요."))
    is kotlinx.coroutines.TimeoutCancellationException -> SpeechFailure(SpeechErrorCode.TIMEOUT, ko("语音服务响应超时，请重试", "음성 서비스 응답 시간이 초과되었습니다. 다시 시도하세요."))
    is IOException -> SpeechFailure(SpeechErrorCode.NETWORK, ko("无法连接语音服务，请检查网络", "음성 서비스에 연결할 수 없습니다. 네트워크를 확인하세요."))
    else -> SpeechFailure(SpeechErrorCode.PROTOCOL, ko("语音服务返回了无法处理的数据", "음성 서비스가 처리할 수 없는 데이터를 반환했습니다."))
}

internal fun validateSpeechSettings(settings: SpeechSettings, secrets: SpeechCredentials, synthesis: Boolean) {
    fun required(value: String, name: String) {
        if (value.isBlank()) throw SpeechFailure(SpeechErrorCode.CONFIGURATION, ko("请先配置$name", "먼저 $name 항목을 설정하세요."))
    }
    if (synthesis) {
        when (settings.tts) {
            TtsProvider.NONE -> throw SpeechFailure(SpeechErrorCode.CONFIGURATION, ko("请先在设置中配置语音播报", "먼저 설정에서 음성 출력을 설정하세요."))
            TtsProvider.QWEN -> {
                required(secrets.qwenTts, ko("千问播报 API Key", "Qwen 음성 출력 API Key"))
                required(settings.qwenVoice, ko("音色", "음색"))
                speechBaseUrl(settings.qwenTts.endpoint())
            }
            TtsProvider.DOUBAO -> {
                required(secrets.doubaoTts, ko("豆包播报凭据", "Doubao 음성 출력 자격 증명"))
                required(settings.doubaoVoice, ko("音色", "음색"))
                speechBaseUrl(settings.doubaoTts.baseUrl)
                if (settings.doubaoTts.legacyAuth) required(settings.doubaoTts.appId, ko("豆包 App ID", "Doubao App ID"))
            }
        }
    } else when (settings.asr) {
        AsrProvider.SYSTEM -> Unit
        AsrProvider.DOUBAO -> {
            required(secrets.doubaoAsr, ko("豆包识别凭据", "Doubao 인식 자격 증명"))
            speechBaseUrl(settings.doubaoAsr.baseUrl)
            if (settings.doubaoAsr.legacyAuth) required(settings.doubaoAsr.appId, ko("豆包 App ID", "Doubao App ID"))
        }
        else -> {
            required(secrets.qwenAsr, ko("千问识别 API Key", "Qwen 인식 API Key"))
            speechBaseUrl(settings.qwenAsr.endpoint())
            if (settings.asr == AsrProvider.QWEN_FILE) {
                required(settings.oss.bucket, "OSS Bucket")
                required(settings.oss.region, ko("OSS 地域", "OSS 리전"))
                required(secrets.ossAccessKeyId, "OSS AccessKey ID")
                required(secrets.ossAccessKeySecret, "OSS AccessKey Secret")
                speechBaseUrl(settings.oss.endpoint)
                if (!Regex("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]").matches(settings.oss.bucket) ||
                    settings.oss.prefix.split('/').any { it == ".." || it == "." }
                ) throw SpeechFailure(SpeechErrorCode.CONFIGURATION, ko("请检查 OSS Bucket 和目录前缀", "OSS Bucket과 디렉터리 접두사를 확인하세요."))
            }
        }
    }
}

internal fun speechBaseUrl(value: String): String {
    val url = value.trim().toHttpUrlOrNull()
    if (url == null || !url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() ||
        url.query != null || url.fragment != null
    ) throw SpeechFailure(SpeechErrorCode.CONFIGURATION, ko("服务地址须为不含认证信息和查询参数的 HTTPS 地址", "서비스 주소는 인증 정보와 쿼리 매개변수가 없는 HTTPS 주소여야 합니다."))
    return url.toString().trimEnd('/')
}
