package fuck.andes.agent.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTraceFormatterTest {
    private val formatter = AgentTraceFormatter()

    @Test
    fun sensitiveToolArgumentsAreSummarizedWithoutRawValues() {
        val cases = listOf(
            RedactionCase(
                toolName = "terminal",
                argumentsJson =
                    """{"action":"open_and_exec","identity":"root","command":"echo bearer-secret"}""",
                expectedParts = listOf("터미널", "open_and_exec", "root", "command_chars="),
                sensitiveParts = listOf("echo bearer-secret", "bearer-secret"),
            ),
            RedactionCase(
                toolName = "run_command",
                argumentsJson = """{"command":"cat /data/local/tmp/private-token"}""",
                expectedParts = listOf("명령 실행", "chars="),
                sensitiveParts = listOf("cat ", "/data/local/tmp/private-token", "private-token"),
            ),
            RedactionCase(
                toolName = "write_file",
                argumentsJson =
                    """{"path":"/data/local/tmp/secret.txt","content":"api-key-value"}""",
                expectedParts = listOf("파일 쓰기", "chars="),
                sensitiveParts = listOf("/data/local/tmp/secret.txt", "api-key-value"),
            ),
            RedactionCase(
                toolName = "input_text",
                argumentsJson = """{"text":"one-time-password-123456"}""",
                expectedParts = listOf("텍스트 입력", "chars="),
                sensitiveParts = listOf("one-time-password-123456", "123456"),
            ),
            RedactionCase(
                toolName = "read_file",
                argumentsJson = """{"path":"/data/user/0/example/private.xml"}""",
                expectedParts = listOf("파일 읽기"),
                sensitiveParts = listOf("/data/user/0/example/private.xml", "private.xml"),
            ),
            RedactionCase(
                toolName = "list_directory",
                argumentsJson = """{"path":"/storage/emulated/0/Private"}""",
                expectedParts = listOf("디렉터리 목록 보기"),
                sensitiveParts = listOf("/storage/emulated/0/Private"),
            ),
            RedactionCase(
                toolName = "search_apps",
                argumentsJson = """{"query":"confidential-app-name"}""",
                expectedParts = listOf("앱 검색", "chars="),
                sensitiveParts = listOf("confidential-app-name"),
            ),
        )

        cases.forEach { case ->
            val summary = formatter.summarizeArguments(
                AgentModelClient.ToolCall(
                    id = "call-test",
                    name = case.toolName,
                    argumentsJson = case.argumentsJson,
                )
            )

            case.expectedParts.forEach { expected ->
                assertTrue(
                    "${case.toolName} summary must contain '$expected': $summary",
                    summary.contains(expected),
                )
            }
            case.sensitiveParts.forEach { sensitive ->
                assertFalse(
                    "${case.toolName} summary leaked '$sensitive': $summary",
                    summary.contains(sensitive),
                )
            }
        }
    }

    @Test
    fun malformedSensitiveArgumentsUseSafeFallback() {
        val summary = formatter.summarizeArguments(
            AgentModelClient.ToolCall(
                id = "call-test",
                name = "terminal",
                argumentsJson = "{secret-command",
            )
        )

        assertTrue(summary.contains("터미널"))
        assertFalse(summary.contains("secret-command"))
    }

    private data class RedactionCase(
        val toolName: String,
        val argumentsJson: String,
        val expectedParts: List<String>,
        val sensitiveParts: List<String>,
    )
}
