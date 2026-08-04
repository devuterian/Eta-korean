package fuck.andes.data.provider

import fuck.andes.data.model.Model
import fuck.andes.data.model.ModelReasoningCapabilities
import fuck.andes.data.model.ProviderSourceTypes
import fuck.andes.data.model.ReasoningEffort
import org.junit.Assert.assertEquals
import org.junit.Test

class ReasoningCapabilityResolverTest {
    @Test
    fun userFacingEffortLabelsAreStableEnglishValues() {
        assertEquals(
            listOf("Off", "Default", "Low", "Medium", "High", "XHigh", "Max"),
            ReasoningEffort.entries.map(ReasoningEffort::displayName),
        )
    }

    @Test
    fun deepSeekCatalogExposesOnlyMeaningfulLevels() {
        val flash = resolve(ProviderSourceTypes.DEEPSEEK, "deepseek-v4-flash")
        val pro = resolve(ProviderSourceTypes.DEEPSEEK, "deepseek-v4-pro")

        assertEquals(
            listOf(
                ReasoningEffort.OFF,
                ReasoningEffort.DEFAULT,
                ReasoningEffort.LOW,
                ReasoningEffort.HIGH,
                ReasoningEffort.MAX,
            ),
            flash.selectableEfforts,
        )
        assertEquals(
            listOf(
                ReasoningEffort.OFF,
                ReasoningEffort.DEFAULT,
                ReasoningEffort.HIGH,
                ReasoningEffort.MAX,
            ),
            pro.selectableEfforts,
        )
        assertEquals(ReasoningEffort.HIGH, pro.normalize(ReasoningEffort.XHIGH))
    }

    @Test
    fun mandatoryKimiModelsNeverExposeOff() {
        assertEquals(
            listOf(
                ReasoningEffort.DEFAULT,
                ReasoningEffort.LOW,
                ReasoningEffort.HIGH,
                ReasoningEffort.MAX,
            ),
            resolve(ProviderSourceTypes.MOONSHOT, "kimi-k3").selectableEfforts,
        )
        assertEquals(
            listOf(ReasoningEffort.DEFAULT),
            resolve(ProviderSourceTypes.MOONSHOT, "kimi-k2.7-code").selectableEfforts,
        )
    }

    @Test
    fun unverifiedModelsDegradeToSafeDefault() {
        assertEquals(
            listOf(ReasoningEffort.DEFAULT),
            resolve(ProviderSourceTypes.MINIMAX, "MiniMax-M3").selectableEfforts,
        )
        assertEquals(
            listOf(ReasoningEffort.DEFAULT),
            resolve(ProviderSourceTypes.STEPFUN, "step-3.7-flash").selectableEfforts,
        )
        assertEquals(
            listOf(ReasoningEffort.DEFAULT),
            resolve(ProviderSourceTypes.CUSTOM, "unknown-thinking-model").selectableEfforts,
        )
    }

    @Test
    fun exactRemoteMetadataWinsOverProviderFamilyRules() {
        val remote = ModelReasoningCapabilities(
            supportedEfforts = listOf(ReasoningEffort.MEDIUM),
            defaultEffort = ReasoningEffort.MEDIUM,
            mandatory = true,
        )
        val resolved = ReasoningCapabilityResolver.resolve(
            sourceType = ProviderSourceTypes.DEEPSEEK,
            model = Model(
                id = "id",
                modelId = "deepseek-v4-flash",
                displayName = "DeepSeek",
                reasoning = true,
                reasoningCapabilities = remote,
            ),
        )

        assertEquals(remote, resolved)
        assertEquals(
            listOf(ReasoningEffort.DEFAULT, ReasoningEffort.MEDIUM),
            resolved?.selectableEfforts,
        )
    }

    private fun resolve(source: String, modelId: String): ModelReasoningCapabilities =
        requireNotNull(
            ReasoningCapabilityResolver.resolve(
                sourceType = source,
                model = Model(
                    id = "id-$modelId",
                    modelId = modelId,
                    displayName = modelId,
                    reasoning = true,
                ),
            )
        )
}
