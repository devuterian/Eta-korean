package io.github.mangi.eta.data.repository

import io.github.mangi.eta.CodexOAuthHttpException
import io.github.mangi.eta.CodexOAuthStage
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexUiErrorTest {
    @Test
    fun networkErrorsExposeStageAndKindWithoutProviderSecrets() {
        val failures = listOf(
            UnknownHostException("secret-token") to "DNS lookup failed",
            SocketTimeoutException("secret-token") to "connection timed out",
            SSLHandshakeException("secret-token") to "TLS connection failed",
            IOException("secret-token") to "network I/O failed",
        )
        for ((cause, expected) in failures) {
            val error = CodexOAuthHttpException(
                message = "secret-token",
                stage = CodexOAuthStage.TOKEN_EXCHANGE,
                errorCode = "token_exchange_network_error",
                cause = cause,
            )
            val text = CodexUiError.login(error)
            assertTrue(text.contains("token_exchange"))
            assertTrue(text.contains(expected))
            assertFalse(text.contains("secret-token"))
        }
    }
}
