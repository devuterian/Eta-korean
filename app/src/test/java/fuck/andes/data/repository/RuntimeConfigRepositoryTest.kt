package fuck.andes.data.repository

import fuck.andes.agent.model.AgentModelClient
import fuck.andes.data.model.CustomHeader
import fuck.andes.data.model.Model
import fuck.andes.data.model.OpenAiCompatibleProviderSetting
import fuck.andes.data.model.ProviderTypes
import fuck.andes.data.model.ProviderSourceTypes
import fuck.andes.data.model.ReasoningEffort
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeConfigRepositoryTest {
    @Test
    fun buildsStructuredRuntimeConfigFromProviderAndModel() {
        val provider = OpenAiCompatibleProviderSetting(
            id = "p1",
            name = "Provider",
            baseUrl = "https://api.example.com/v1",
            apiKey = "key",
            sourceType = ProviderSourceTypes.OPENAI,
            customHeaders = listOf(CustomHeader("x-provider", "1"))
        )
        val model = Model(
            id = "m1",
            modelId = "gpt-5.5",
            displayName = "GPT-5.5",
            contextWindow = 1_000_000,
            reasoning = true,
            customHeaders = listOf(CustomHeader("x-model", "2"))
        )

        val config = RuntimeConfigRepository.buildRuntimeConfig(provider, model)
        val raw = RuntimeConfigRepository.runtimeConfigJson(config)
        val root = Json.parseToJsonElement(raw).jsonObject

        assertEquals(ProviderTypes.OPENAI_COMPATIBLE, root.getValue("providerType").jsonPrimitive.content)
        assertEquals("gpt-5.5", root.getValue("model").jsonPrimitive.content)
        assertEquals(1_000_000, config.contextWindow)
        assertEquals(listOf("x-provider", "x-model"), config.customHeaders.map { it.name })
        assertEquals(ReasoningEffort.DEFAULT, config.reasoningEffort)
        assertEquals(true, config.thinkingEnabled)
        assertEquals(
            ReasoningEffort.entries,
            config.reasoningCapabilities?.selectableEfforts,
        )
        assertEquals(config, Json.decodeFromString<AgentModelClient.ModelConfig>(raw))
    }
}
