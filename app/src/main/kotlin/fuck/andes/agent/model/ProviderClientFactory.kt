package fuck.andes.agent.model

import fuck.andes.data.model.ProviderTypes

internal object ProviderClientFactory {

    fun getClient(config: AgentModelClient.ModelConfig): AgentProviderClient =
        when (config.providerType) {
            ProviderTypes.OPENAI_COMPATIBLE -> OpenAiChatCompletionsProvider
            ProviderTypes.ANTHROPIC -> AnthropicMessagesProvider
            else -> error("不支持的 Provider 协议类型：${config.providerType}")
        }
}
