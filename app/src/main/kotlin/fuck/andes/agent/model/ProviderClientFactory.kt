package fuck.andes.agent.model

import fuck.andes.data.model.ProviderTypes

internal object ProviderClientFactory {

    fun getClient(config: AgentModelClient.ModelConfig): AgentProviderClient =
        when (config.providerType) {
            ProviderTypes.OPENAI_COMPATIBLE -> OpenAiChatCompletionsProvider
            ProviderTypes.ANTHROPIC -> AnthropicMessagesProvider
            else -> error("지원하지 않는 제공자 프로토콜 타입: ${config.providerType}")
        }
}
