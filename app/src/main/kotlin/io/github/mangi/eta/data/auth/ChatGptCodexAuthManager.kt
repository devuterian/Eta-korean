package io.github.mangi.eta.data.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * ChatGPT 구독의 Codex 사용량을 쓰기 위한 공식 Codex device-code OAuth 흐름.
 *
 * 엔드포인트와 client id는 OpenAI Codex CLI 공개 구현과 동일하다. 액세스/리프레시 토큰은
 * Provider 설정이나 RemotePreferences에 기록하지 않고 Android Keystore로 암호화한 뒤
 * 앱 전용 SharedPreferences에만 보관한다.
 */
internal object ChatGptCodexAuthManager {
    const val CHATGPT_CODEX_BASE_URL = "https://chatgpt.com/backend-api/codex"
    const val CHATGPT_CODEX_MODELS_URL = "$CHATGPT_CODEX_BASE_URL/models"
    const val VERIFICATION_URL = "https://auth.openai.com/codex/device"

    private const val AUTH_BASE_URL = "https://auth.openai.com"
    private const val USER_CODE_URL = "$AUTH_BASE_URL/api/accounts/deviceauth/usercode"
    private const val DEVICE_TOKEN_URL = "$AUTH_BASE_URL/api/accounts/deviceauth/token"
    private const val OAUTH_TOKEN_URL = "$AUTH_BASE_URL/oauth/token"
    private const val DEVICE_REDIRECT_URI = "$AUTH_BASE_URL/deviceauth/callback"
    private const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    private const val MAX_LOGIN_MILLIS = 15 * 60 * 1000L
    private const val REFRESH_SKEW_MILLIS = 90_000L
    private const val PREFS_NAME = "chatgpt_codex_auth"
    private const val PREFS_CREDENTIAL = "credential_v1"
    private const val KEYSTORE_ALIAS = "eta_chatgpt_codex_auth_v1"
    private const val AAD = "eta-chatgpt-codex-v1"

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val lock = Any()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile
    private lateinit var applicationContext: Context

    data class DeviceAuthorization(
        val userCode: String,
        val deviceAuthId: String,
        val intervalSeconds: Long,
        val startedAtMillis: Long = System.currentTimeMillis(),
        val verificationUrl: String = VERIFICATION_URL,
    )

    data class Credential(
        val accessToken: String,
        val refreshToken: String,
        val expiresAtMillis: Long,
        val accountId: String,
        val email: String? = null,
    )

    data class AccountSummary(
        val accountId: String,
        val email: String?,
    )

    fun init(context: Context) {
        if (!::applicationContext.isInitialized) {
            synchronized(lock) {
                if (!::applicationContext.isInitialized) {
                    applicationContext = context.applicationContext
                }
            }
        }
    }

    fun accountSummary(): AccountSummary? =
        loadCredential()?.let { AccountSummary(it.accountId, it.email) }

    fun isSignedIn(): Boolean = loadCredential() != null

    suspend fun requestDeviceAuthorization(): DeviceAuthorization = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("client_id", CLIENT_ID)
            .toString()
            .toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url(USER_CODE_URL)
            .header("Accept", "application/json")
            .post(payload)
            .build()
        val response = executeJson(request, "ChatGPT device code 요청 실패")
        val userCode = response.optString("user_code")
            .ifBlank { response.optString("usercode") }
        val deviceAuthId = response.optString("device_auth_id")
        val interval = when (val raw = response.opt("interval")) {
            is Number -> raw.toLong()
            is String -> raw.toLongOrNull()
            else -> null
        }?.coerceIn(1L, 30L) ?: 5L
        require(userCode.isNotBlank() && deviceAuthId.isNotBlank()) {
            "ChatGPT device code 응답이 올바르지 않습니다."
        }
        DeviceAuthorization(
            userCode = userCode,
            deviceAuthId = deviceAuthId,
            intervalSeconds = interval,
        )
    }

    suspend fun completeDeviceAuthorization(device: DeviceAuthorization): AccountSummary {
        while (System.currentTimeMillis() - device.startedAtMillis < MAX_LOGIN_MILLIS) {
            currentCoroutineContext().ensureActive()
            val result = withContext(Dispatchers.IO) {
                pollDeviceAuthorization(device)
            }
            if (result != null) {
                val credential = withContext(Dispatchers.IO) {
                    exchangeAuthorizationCode(result)
                }
                saveCredential(credential)
                return AccountSummary(credential.accountId, credential.email)
            }
            delay(device.intervalSeconds * 1000L)
        }
        error("ChatGPT 로그인 시간이 만료되었습니다. 다시 로그인해 주세요.")
    }

    /**
     * 모델 요청 스레드에서 호출하므로 동기 방식으로 유지한다. 토큰이 만료 임박한 경우에만
     * refresh token 교환을 수행하며, 회전된 refresh token도 즉시 다시 암호화해 저장한다.
     */
    fun validCredential(forceRefresh: Boolean = false): Credential {
        synchronized(lock) {
            val current = loadCredential()
                ?: error("ChatGPT 로그인이 필요합니다.")
            if (!forceRefresh &&
                current.accessToken.isNotBlank() &&
                current.expiresAtMillis > System.currentTimeMillis() + REFRESH_SKEW_MILLIS
            ) {
                return current
            }
            val refreshed = refreshCredential(current)
            saveCredential(refreshed)
            return refreshed
        }
    }

    fun clearCredential() {
        requireInitialized()
        applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(PREFS_CREDENTIAL)
            .apply()
    }

    private data class DeviceTokenResult(
        val authorizationCode: String,
        val codeVerifier: String,
    )

    private fun pollDeviceAuthorization(device: DeviceAuthorization): DeviceTokenResult? {
        val payload = JSONObject()
            .put("device_auth_id", device.deviceAuthId)
            .put("user_code", device.userCode)
            .toString()
            .toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url(DEVICE_TOKEN_URL)
            .header("Accept", "application/json")
            .post(payload)
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (response.code == 403 || response.code == 404) return null
            val body = response.body.string()
            if (!response.isSuccessful) {
                error("ChatGPT device 로그인 확인 실패 (HTTP ${response.code}): ${body.compactError()}")
            }
            val json = JSONObject(body)
            val authorizationCode = json.optString("authorization_code")
            val codeVerifier = json.optString("code_verifier")
            require(authorizationCode.isNotBlank() && codeVerifier.isNotBlank()) {
                "ChatGPT 로그인 응답에 인증 코드가 없습니다."
            }
            return DeviceTokenResult(authorizationCode, codeVerifier)
        }
    }

    private fun exchangeAuthorizationCode(result: DeviceTokenResult): Credential {
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("client_id", CLIENT_ID)
            .add("code", result.authorizationCode)
            .add("code_verifier", result.codeVerifier)
            .add("redirect_uri", DEVICE_REDIRECT_URI)
            .build()
        val request = Request.Builder()
            .url(OAUTH_TOKEN_URL)
            .header("Accept", "application/json")
            .post(form)
            .build()
        val payload = executeJson(request, "ChatGPT OAuth 토큰 교환 실패")
        return credentialFromTokenResponse(payload, previous = null)
    }

    private fun refreshCredential(previous: Credential): Credential {
        if (previous.refreshToken.isBlank()) {
            error("ChatGPT 로그인 갱신 정보가 없습니다. 다시 로그인해 주세요.")
        }
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("client_id", CLIENT_ID)
            .add("refresh_token", previous.refreshToken)
            .build()
        val request = Request.Builder()
            .url(OAUTH_TOKEN_URL)
            .header("Accept", "application/json")
            .post(form)
            .build()
        val payload = executeJson(request, "ChatGPT 로그인 갱신 실패")
        return credentialFromTokenResponse(payload, previous)
    }

    private fun credentialFromTokenResponse(
        payload: JSONObject,
        previous: Credential?,
    ): Credential {
        val accessToken = payload.optString("access_token")
            .ifBlank { previous?.accessToken.orEmpty() }
        val refreshToken = payload.optString("refresh_token")
            .ifBlank { previous?.refreshToken.orEmpty() }
        val idToken = payload.optString("id_token")
        val expiresIn = payload.optLong("expires_in", 3600L).coerceAtLeast(60L)
        val accountId = sequenceOf(idToken, accessToken)
            .filter { it.isNotBlank() }
            .mapNotNull(::extractAccountId)
            .firstOrNull()
            ?: previous?.accountId
            ?: error("ChatGPT 계정 ID를 확인할 수 없습니다.")
        val email = sequenceOf(idToken, accessToken)
            .filter { it.isNotBlank() }
            .mapNotNull(::extractEmail)
            .firstOrNull()
            ?: previous?.email

        require(accessToken.isNotBlank()) { "ChatGPT 액세스 토큰이 없습니다." }
        require(refreshToken.isNotBlank()) { "ChatGPT 리프레시 토큰이 없습니다." }

        return Credential(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAtMillis = System.currentTimeMillis() + expiresIn * 1000L,
            accountId = accountId,
            email = email,
        )
    }

    private fun extractAccountId(token: String): String? {
        val payload = decodeJwtPayload(token) ?: return null
        payload.optString("chatgpt_account_id").takeIf(String::isNotBlank)?.let { return it }
        payload.optJSONObject("https://api.openai.com/auth")
            ?.optString("chatgpt_account_id")
            ?.takeIf(String::isNotBlank)
            ?.let { return it }
        val organizations = payload.optJSONObject("https://api.openai.com/auth")
            ?.optJSONArray("organizations")
        if (organizations != null && organizations.length() > 0) {
            organizations.optJSONObject(0)
                ?.optString("id")
                ?.takeIf(String::isNotBlank)
                ?.let { return it }
        }
        return null
    }

    private fun extractEmail(token: String): String? =
        decodeJwtPayload(token)
            ?.optString("email")
            ?.takeIf(String::isNotBlank)

    private fun decodeJwtPayload(token: String): JSONObject? = runCatching {
        val parts = token.split('.')
        require(parts.size >= 2)
        val decoded = Base64.decode(
            parts[1],
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        JSONObject(String(decoded, StandardCharsets.UTF_8))
    }.getOrNull()

    private fun executeJson(request: Request, errorPrefix: String): JSONObject {
        httpClient.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                error("$errorPrefix (HTTP ${response.code}): ${body.compactError()}")
            }
            return JSONObject(body)
        }
    }

    private fun saveCredential(credential: Credential) {
        requireInitialized()
        val plain = JSONObject()
            .put("access_token", credential.accessToken)
            .put("refresh_token", credential.refreshToken)
            .put("expires_at", credential.expiresAtMillis)
            .put("account_id", credential.accountId)
            .put("email", credential.email ?: JSONObject.NULL)
            .toString()
        val encrypted = encrypt(plain)
        applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREFS_CREDENTIAL, encrypted)
            .commit()
    }

    private fun loadCredential(): Credential? {
        requireInitialized()
        val encrypted = applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREFS_CREDENTIAL, null)
            ?: return null
        return runCatching {
            val payload = JSONObject(decrypt(encrypted))
            Credential(
                accessToken = payload.getString("access_token"),
                refreshToken = payload.getString("refresh_token"),
                expiresAtMillis = payload.getLong("expires_at"),
                accountId = payload.getString("account_id"),
                email = payload.optString("email").takeIf { it.isNotBlank() && it != "null" },
            )
        }.getOrNull()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        cipher.updateAAD(AAD.toByteArray(StandardCharsets.UTF_8))
        val encrypted = cipher.doFinal(plain.toByteArray(StandardCharsets.UTF_8))
        return JSONObject()
            .put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .toString()
    }

    private fun decrypt(encoded: String): String {
        val payload = JSONObject(encoded)
        val iv = Base64.decode(payload.getString("iv"), Base64.NO_WRAP)
        val encrypted = Base64.decode(payload.getString("ciphertext"), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        cipher.updateAAD(AAD.toByteArray(StandardCharsets.UTF_8))
        return String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore",
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private fun requireInitialized() {
        check(::applicationContext.isInitialized) {
            "ChatGptCodexAuthManager.init(context) must be called first"
        }
    }

    private fun String.compactError(): String =
        replace('\n', ' ')
            .replace('\r', ' ')
            .let { if (it.length > 500) it.take(500) + "..." else it }
}
