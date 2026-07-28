package fuck.andes.ui.app

import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import fuck.andes.FuckAndesApp
import fuck.andes.agent.accessibility.AgentAccessibilityService
import fuck.andes.agent.device.DeviceLocationProvider
import fuck.andes.agent.media.AgentImageCodec
import fuck.andes.agent.model.AgentModelClient
import fuck.andes.agent.runtime.AgentEvent
import fuck.andes.agent.runtime.AgentExternalArchivePayload
import fuck.andes.agent.runtime.AgentRunArchiveStore
import fuck.andes.agent.runtime.AgentRuntimeClient
import fuck.andes.agent.runtime.AgentRuntimeWire
import fuck.andes.agent.runtime.AgentTokenUsage
import fuck.andes.agent.runtime.AgentUiHandoffPayload
import fuck.andes.agent.skill.SkillRuntime
import fuck.andes.config.Prefs
import fuck.andes.core.AndroidAgentLogger
import fuck.andes.core.safeLogType
import fuck.andes.data.repository.RuntimeConfigRepository
import fuck.andes.ui.model.AgentChatHomeUiState
import fuck.andes.ui.model.AgentChatMessageUi
import fuck.andes.ui.model.AgentMessageUi
import fuck.andes.ui.model.AgentSkillsUiState
import fuck.andes.ui.model.AgentSystemEnhanceUiState
import fuck.andes.ui.model.AgentToolsUiState
import fuck.andes.ui.model.ConversationModeUi
import fuck.andes.ui.model.ConversationPaneUiState
import fuck.andes.ui.model.ConversationSummaryUi
import fuck.andes.ui.model.PermissionHealthItemUi
import fuck.andes.ui.model.PermissionHealthUiState
import fuck.andes.ui.model.PermissionStatusUi
import fuck.andes.ui.model.PendingImageUi
import fuck.andes.ui.model.SkillItemUi
import fuck.andes.ui.model.SkillNoticeUi
import fuck.andes.ui.model.SkillReplacementUi
import fuck.andes.ui.model.canDeleteUserSkill
import fuck.andes.ui.model.SystemEnhanceItemUi
import fuck.andes.ui.model.SystemEnhanceSectionUi
import fuck.andes.ui.model.SystemEnhanceStatusUi
import fuck.andes.ui.model.ThinkingMessageUi
import fuck.andes.ui.model.TokenUsageUi
import fuck.andes.ui.model.ToolActivityMessageUi
import fuck.andes.ui.model.ToolGroupUi
import fuck.andes.ui.model.ToolItemUi
import fuck.andes.ui.model.UserMessageUi
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class AgentAppState(
    context: Context,
    private val scope: CoroutineScope,
    skillZipImportGateway: SkillZipImportGateway? = null,
) {
    private val appContext = context.applicationContext
    private val skillZipImportGateway = skillZipImportGateway ?: CoreSkillZipImportGateway(appContext)
    private val runConversationIds = mutableMapOf<String, String>()
    private val runMessageProjector = AgentRunMessageProjector()
    private val runEventCoalescer = AgentRunEventCoalescer()
    private val runEventFlushJobs = mutableMapOf<String, Job>()
    private var currentRunId: String? = null
    private var currentRunJob: Job? = null
    private val persistenceLock = Any()
    private var persistenceJob: Job? = null
    private val runtimeRecoveryInProgress = AtomicBoolean(false)
    private val defaultThinkingEnabled = remoteBooleanForUi(Prefs.Keys.AGENT_THINKING_ENABLED)
    private val initialConversations = AgentConversationStore.load(appContext)
    private var skillNoticeSequence = 0L
    private var pendingSkillZipUri: Uri? = null
    private var pendingSkillZipSha256: String? = null

    private var selectedConversationId: String? = initialConversations.selectedConversationId
    private var conversationsById: Map<String, AgentChatHomeUiState> = initialConversations.conversationsById
    private var conversationTitles: Map<String, String> = initialConversations.titles
    private var conversationUpdatedAt: Map<String, Long> = initialConversations.updatedAt

    var homeState by mutableStateOf(
        selectedConversationId?.let(conversationsById::get) ?: emptyChatState(defaultThinkingEnabled)
    )
        private set

    var conversationPaneState by mutableStateOf(
        ConversationPaneUiState(
            conversations = emptyList(),
            selectedConversationId = selectedConversationId,
            searchQuery = "",
        )
    )
        private set

    var toolsState by mutableStateOf(buildToolsState())
        private set

    var skillsState by mutableStateOf(AgentSkillsUiState(isLoading = true))
        private set

    var permissionHealthState by mutableStateOf(buildPermissionHealthState(appContext))
        private set

    var systemEnhanceState by mutableStateOf(buildSystemEnhanceState())
        private set

    init {
        refreshConversationSummaries()
        runtimeRecoveryInProgress.set(true)
        scope.launch(Dispatchers.IO) {
            try {
                recoverOrphanedRuns()
                importArchivedExternalRuns()
            } finally {
                runtimeRecoveryInProgress.set(false)
            }
        }
    }

    fun refreshRuntimeResults() {
        if (!runtimeRecoveryInProgress.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try {
                recoverOrphanedRuns()
            } finally {
                runtimeRecoveryInProgress.set(false)
            }
        }
    }

    /**
     * App 进程可能在 Agent 操作手机期间被系统杀死，导致最终结果未能更新到会话。
     * 从 Runtime 进程拉取未交付的结果，补回对应会话的 assistant 消息。
     */
    private suspend fun recoverOrphanedRuns() {
        val client = AgentRuntimeClient(appContext, AndroidAgentLogger)
        val completedRuns = runCatching {
            client.drainCompletedRuns()
        }.getOrElse { throwable ->
            AndroidAgentLogger.warnThrottled("agent_ui_drain_results_failed") {
                "Agent UI pending result recovery failed: type=${throwable.safeLogType()}"
            }
            emptyList()
        }
        if (completedRuns.isEmpty()) return
        val ours = completedRuns.filter { it.handoff.source == HANDOFF_SOURCE }
        if (ours.isEmpty()) return
        withContext(Dispatchers.Main) {
            val acknowledgeAfterSave = mutableListOf<String>()
            ours.forEach { completedRun ->
                val runId = completedRun.result.runId.ifBlank { completedRun.handoff.id }
                val payload = AgentUiHandoffPayload.from(completedRun.handoff.payload)
                val conversationId = payload.conversationId
                val state = conversationsById[conversationId] ?: return@forEach
                val result = completedRun.result
                val recovery = AgentPendingResultRecovery.apply(
                    state = state,
                    runId = runId,
                    result = result,
                    promptSupplement = payload.promptSupplement,
                    supplements = payload.supplements,
                )
                if (recovery.alreadyApplied) {
                    acknowledgeAfterSave += runId
                    return@forEach
                }
                updateConversation(conversationId, recovery.state)
                acknowledgeAfterSave += runId
            }
            refreshConversationSummaries()
            persistConversations {
                acknowledgeAfterSave.forEach(client::ackResult)
            }
        }
    }

    private suspend fun importArchivedExternalRuns() {
        val archivedRuns = AgentRunArchiveStore.list(appContext)
            .filter { AgentExternalArchivePayload.from(it.handoff.payload) != null }
        if (archivedRuns.isEmpty()) return

        withContext(Dispatchers.Main) {
            val importedRunIds = archivedRuns.mapNotNull { archivedRun ->
                importExternalRun(archivedRun)
            }
            refreshConversationSummaries()
            persistConversations {
                importedRunIds.forEach { runId ->
                    AgentRunArchiveStore.remove(appContext, runId)
                }
            }
        }
    }

    private fun importExternalRun(archivedRun: AgentRunArchiveStore.ArchivedRun): String? {
        val runId = archivedRun.result.runId.ifBlank { archivedRun.handoff.id }
        if (runId.isBlank()) return null
        val payload = AgentExternalArchivePayload.from(archivedRun.handoff.payload) ?: return null
        val conversationId = archiveConversationId(
            source = archivedRun.handoff.source,
            conversationKey = payload.conversationKey,
        )
        val existingState = conversationsById[conversationId] ?: emptyChatState(
            payload.thinkingEnabled ?: defaultThinkingEnabled
        )
        val alreadyImported = AgentRuntimeHistoryReducer.wasApplied(existingState, runId) ||
            existingState.messages.any {
                it is AgentMessageUi &&
                    (it.id == "assistant-$runId" || it.id.startsWith("assistant-$runId-")) &&
                    !it.isStreaming
            }
        if (alreadyImported) return runId

        if (conversationTitles[conversationId].isNullOrBlank() || conversationTitles[conversationId] == "새 대화") {
            conversationTitles = conversationTitles + (conversationId to payload.title.ifBlank { "외부 기록" })
        }
        runConversationIds[runId] = conversationId
        updateConversation(
            conversationId,
            existingState.copy(
                input = "",
                isStreaming = true,
                thinkingEnabled = payload.thinkingEnabled ?: existingState.thinkingEnabled,
                pendingImages = emptyList(),
                messages = existingState.messages +
                    UserMessageUi(id = "user-$runId", content = payload.userText) +
                    AgentMessageUi(
                        id = "assistant-$runId",
                        content = "",
                        isStreaming = true,
                        renderMarkdown = false,
                    ),
            )
        )
        archivedRun.events.forEach { event -> applyRunEvent(runId, event) }
        applyRunResult(runId, archivedRun.result)
        conversationUpdatedAt = conversationUpdatedAt + (conversationId to archivedRun.createdAt)
        return runId
    }

    fun updateInput(text: String) {
        updateCurrentConversation(homeState.copy(input = text))
    }

    fun updateThinkingEnabled(enabled: Boolean) {
        updateCurrentConversation(homeState.copy(thinkingEnabled = enabled))
        if (selectedConversationId != null) persistConversations()
    }

    fun updateSearchQuery(query: String) {
        conversationPaneState = conversationPaneState.copy(searchQuery = query)
    }

    fun selectConversation(conversationId: String) {
        val state = conversationsById[conversationId] ?: return
        selectedConversationId = conversationId
        homeState = state
        conversationPaneState = conversationPaneState.copy(selectedConversationId = conversationId)
        persistConversations()
    }

    fun createConversation() {
        selectedConversationId = null
        homeState = emptyChatState(defaultThinkingEnabled)
        conversationPaneState = conversationPaneState.copy(
            selectedConversationId = null,
            searchQuery = "",
        )
        refreshConversationSummaries()
    }

    fun deleteConversation(conversationId: String) {
        val wasSelected = selectedConversationId == conversationId
        conversationsById = conversationsById - conversationId
        conversationTitles = conversationTitles - conversationId
        conversationUpdatedAt = conversationUpdatedAt - conversationId
        if (wasSelected) {
            val nextId = conversationsById.keys.firstOrNull()
            if (nextId != null) {
                selectedConversationId = nextId
                homeState = conversationsById.getValue(nextId)
            } else {
                selectedConversationId = null
                homeState = emptyChatState(defaultThinkingEnabled)
            }
        }
        conversationPaneState = conversationPaneState.copy(selectedConversationId = selectedConversationId)
        refreshConversationSummaries()
        persistConversations()
    }

    fun renameConversation(conversationId: String, title: String) {
        val trimmed = title.trim()
        if (trimmed.isBlank()) return
        conversationTitles = conversationTitles + (conversationId to trimmed)
        conversationUpdatedAt = conversationUpdatedAt + (conversationId to System.currentTimeMillis())
        refreshConversationSummaries()
        persistConversations()
    }

    fun sendCurrentMessage() {
        val prompt = homeState.input.trim()
        val pendingImages = homeState.pendingImages
        if ((prompt.isBlank() && pendingImages.isEmpty()) || homeState.isStreaming) return

        if (selectedConversationId?.isExternalArchiveConversation() == true) {
            moveCurrentDraftToNewConversation()
        }

        val conversationId = selectedConversationId ?: newConversationId().also {
            selectedConversationId = it
        }
        val history = homeState.history
        val thinkingEnabled = homeState.thinkingEnabled
        val runId = "run-${UUID.randomUUID()}"
        val imageDataUrls = pendingImages.map { it.dataUrl }
        val userMessage = UserMessageUi(id = "user-$runId", content = prompt, images = imageDataUrls)
        val userHistoryMessage = AgentModelClient.buildUserHistoryMessage(
            text = prompt,
            images = pendingImages.map { image ->
                AgentModelClient.ModelImage(
                    reference = image.dataUrl,
                    mimeType = image.mimeType,
                    bytes = image.dataUrl.length,
                    source = image.uri,
                )
            },
        )

        val title = conversationTitles[conversationId]
            ?.takeUnless { it == "새 대화" }
            ?: prompt.lineSequence().firstOrNull().orEmpty().trim().take(MAX_TITLE_CHARS).ifBlank { "새 대화" }

        conversationTitles = conversationTitles + (conversationId to title)
        conversationPaneState = conversationPaneState.copy(selectedConversationId = conversationId)
        runConversationIds[runId] = conversationId
        currentRunId = runId

        updateConversation(
            conversationId,
            homeState.copy(
                input = "",
                isStreaming = true,
                pendingImages = emptyList(),
                history = homeState.history + userHistoryMessage,
                messages = homeState.messages + userMessage,
            )
        )
        refreshConversationSummaries()
        persistConversations()

        currentRunJob = scope.launch(Dispatchers.IO) {
            val config = RuntimeConfigRepository.currentRuntimeConfig()?.copy(
                terminalTools = remoteBooleanForUi(Prefs.Keys.AGENT_TERMINAL_TOOLS),
                browserTools = remoteBooleanForUi(Prefs.Keys.AGENT_BROWSER_TOOLS),
                deviceDirectTools = remoteBooleanForUi(Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS),
                deviceSensitiveReadTools =
                    remoteBooleanForUi(Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS),
                deviceSensitiveActionTools =
                    remoteBooleanForUi(Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS),
                thinkingEnabled = thinkingEnabled,
            )
            if (config == null) {
                withContext(Dispatchers.Main) {
                    applyRunResult(
                        runId,
                        AgentRuntimeWire.RunResult(
                            runId = runId,
                            ok = false,
                            content = "",
                            error = "모델 제공자와 모델을 먼저 설정하세요.",
                        )
                    )
                }
                return@launch
            }
            val modelImages = pendingImages.map { p ->
                AgentModelClient.ModelImage(
                    reference = p.uri,
                    mimeType = p.mimeType,
                    bytes = 0,
                    source = "user_attach",
                )
            }
            val result = AgentRuntimeClient(appContext, AndroidAgentLogger).run(
                request = AgentRuntimeWire.RunRequest(
                    runId = runId,
                    prompt = prompt,
                    config = config,
                    images = modelImages,
                    history = history,
                    handoff = AgentRuntimeWire.EntryHandoff(
                        id = runId,
                        source = HANDOFF_SOURCE,
                        payload = conversationId,
                    ),
                ),
                onEvent = { event -> enqueueRunEvent(runId, event) },
            )
            withContext(Dispatchers.Main) {
                applyRunResult(runId, result, acknowledgeRuntimeResult = true)
            }
        }
    }

    fun attachImage(uri: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val image = AgentImageCodec.fromReference(
                    context = appContext,
                    value = uri,
                    source = "user_attach",
                )
                if (image == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            appContext,
                            "이미지를 읽을 수 없습니다. 다시 시도하거나 다른 이미지를 사용하세요.",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    return@launch
                }
                val preview = AgentImageCodec.previewFromReference(appContext, image) ?: image
                val pending = PendingImageUi(
                    id = "img-${UUID.randomUUID()}",
                    // 后续发送使用首次读取后的稳定引用，不再依赖 ROM Photo Picker URI 的授权生命周期。
                    uri = image.reference,
                    dataUrl = preview.reference,
                    mimeType = image.mimeType,
                )
                withContext(Dispatchers.Main) {
                    updateCurrentConversation(homeState.copy(pendingImages = homeState.pendingImages + pending))
                }
            } finally {
                val selectedUri = Uri.parse(uri)
                if (selectedUri.scheme == ContentResolver.SCHEME_CONTENT) {
                    runCatching {
                        appContext.contentResolver.releasePersistableUriPermission(
                            selectedUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                }
            }
        }
    }

    fun removePendingImage(id: String) {
        updateCurrentConversation(homeState.copy(pendingImages = homeState.pendingImages.filterNot { it.id == id }))
    }

    fun stopCurrentRun() {
        val runId = currentRunId ?: return
        currentRunJob?.cancel()
        currentRunJob = null
        currentRunId = null
        flushPendingRunDelta(runId)
        scope.launch(Dispatchers.IO) {
            AgentRuntimeClient(appContext, AndroidAgentLogger).cancelRun(runId)
        }
        updateRunTrace(runId) { messages ->
            val finalizedThinking = runMessageProjector.finalizeThinking(runId, messages)
            val finalizedText = runMessageProjector.finalizeText(runId, finalizedThinking)
            runMessageProjector.failRunningTools("중지됨", finalizedText)
        }
        replaceLatestAssistantMessage(runId, content = "중지됨", isStreaming = false, renderMarkdown = false)
        setConversationStreaming(runId, false)
        runMessageProjector.clearRun(runId)
        runConversationIds.remove(runId)
        refreshConversationSummaries()
        persistConversations()
    }

    fun refreshPermissionHealth() {
        permissionHealthState = buildPermissionHealthState(appContext)
    }

    fun refreshSkills() {
        scope.launch(Dispatchers.IO) {
            val entries = runCatching {
                SkillRuntime.createIndexService(appContext)
                    .listSkillsForManagement(forceRefresh = true)
            }.getOrElse {
                withContext(Dispatchers.Main) {
                    skillsState = skillsState.copy(
                        isLoading = false,
                        notice = skillsState.notice ?: newSkillNotice(
                            title = "스킬을 읽을 수 없음",
                            message = "스킬 목록을 불러올 수 없습니다. 잠시 후 다시 시도하세요.",
                            isError = true,
                        ),
                    )
                }
                return@launch
            }
            val items = entries.map { entry ->
                val capabilities = buildList {
                    if (entry.hasScripts) add("scripts")
                    if (entry.hasReferences) add("references")
                    if (entry.hasAssets) add("assets")
                    if (entry.hasEvals) add("evals")
                }
                SkillItemUi(
                    id = entry.id,
                    name = entry.name,
                    description = entry.description,
                    source = entry.source,
                    enabled = entry.enabled,
                    installed = entry.installed,
                    capabilities = capabilities,
                )
            }
            withContext(Dispatchers.Main) {
                skillsState = skillsState.copy(skills = items, isLoading = false)
            }
        }
    }

    fun toggleSkill(skillId: String, enabled: Boolean) {
        if (skillsState.isImporting || skillsState.busySkillId != null) return
        skillsState = skillsState.copy(busySkillId = skillId)
        scope.launch(Dispatchers.IO) {
            val succeeded = runCatching {
                SkillRuntime.createIndexService(appContext).setSkillEnabled(skillId, enabled)
            }.isSuccess
            withContext(Dispatchers.Main) {
                skillsState = skillsState.copy(
                    busySkillId = null,
                    notice = if (succeeded) {
                        skillsState.notice
                    } else {
                        newSkillNotice(
                            title = "스킬을 업데이트할 수 없음",
                            message = "스킬 상태가 변경되지 않았습니다. 잠시 후 다시 시도하세요.",
                            isError = true,
                        )
                    },
                )
            }
            refreshSkills()
        }
    }

    fun deleteSkill(skillId: String) {
        if (skillsState.isImporting || skillsState.busySkillId != null) return
        val skill = skillsState.skills.firstOrNull { it.id == skillId }
            ?.takeIf { it.canDeleteUserSkill }
            ?: return
        val skillName = skill.name.safeSkillDisplayName()
        skillsState = skillsState.copy(busySkillId = skillId, notice = null)
        scope.launch(Dispatchers.IO) {
            val succeeded = runCatching {
                SkillRuntime.createIndexService(appContext).deleteSkill(skillId)
            }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                skillsState = skillsState.copy(
                    busySkillId = null,
                    notice = if (succeeded) {
                        newSkillNotice(
                            title = "스킬이 삭제됨",
                            message = "「$skillName」 스킬을 Eta에서 삭제했습니다.",
                            isError = false,
                        )
                    } else {
                        newSkillNotice(
                            title = "스킬을 삭제할 수 없음",
                            message = "삭제가 완료되지 않았습니다. Eta가 스킬 목록을 새로고침할 때 복구를 시도합니다. 상태를 확인한 후 다시 시도하세요.",
                            isError = true,
                        )
                    },
                )
            }
            refreshSkills()
        }
    }

    fun importSkillZip(uriValue: String) {
        if (skillsState.isImporting || skillsState.busySkillId != null) return
        val uri = runCatching { Uri.parse(uriValue) }.getOrNull()
            ?.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT }
        if (uri == null) {
            skillsState = skillsState.copy(
                notice = newSkillNotice(
                    title = "스킬 패키지를 읽을 수 없음",
                    message = "시스템 파일 선택기에서 ZIP 파일을 선택하세요.",
                    isError = true,
                ),
            )
            return
        }
        pendingSkillZipUri = uri
        pendingSkillZipSha256 = null
        launchSkillZipImport(
            uri = uri,
            replaceUserSkill = false,
            expectedReplacementId = null,
            expectedArchiveSha256 = null,
        )
    }

    fun confirmSkillZipReplacement() {
        if (skillsState.isImporting || skillsState.busySkillId != null) return
        val uri = pendingSkillZipUri
        if (uri == null) {
            pendingSkillZipSha256 = null
            skillsState = skillsState.copy(
                replacement = null,
                notice = newSkillNotice(
                    title = "설치를 계속할 수 없음",
                    message = "스킬 패키지를 사용할 수 없습니다. ZIP 파일을 다시 선택하세요.",
                    isError = true,
                ),
            )
            return
        }
        val replacementId = skillsState.replacement?.id
        val archiveSha256 = pendingSkillZipSha256
        if (replacementId == null || archiveSha256 == null) {
            pendingSkillZipUri = null
            pendingSkillZipSha256 = null
            skillsState = skillsState.copy(
                replacement = null,
                notice = newSkillNotice(
                    title = "설치를 계속할 수 없음",
                    message = "교체 확인이 만료되었습니다. ZIP 파일을 다시 선택하세요.",
                    isError = true,
                ),
            )
            return
        }
        launchSkillZipImport(
            uri = uri,
            replaceUserSkill = true,
            expectedReplacementId = replacementId,
            expectedArchiveSha256 = archiveSha256,
        )
    }

    fun cancelSkillZipReplacement() {
        if (skillsState.isImporting) return
        pendingSkillZipUri = null
        pendingSkillZipSha256 = null
        skillsState = skillsState.copy(replacement = null)
    }

    private fun launchSkillZipImport(
        uri: Uri,
        replaceUserSkill: Boolean,
        expectedReplacementId: String?,
        expectedArchiveSha256: String?,
    ) {
        skillsState = skillsState.copy(
            isImporting = true,
            replacement = null,
            notice = null,
        )
        scope.launch(Dispatchers.IO) {
            val outcome = runCatching {
                skillZipImportGateway.installLocalZip(
                    openStream = {
                        appContext.contentResolver.openInputStream(uri)
                            ?: error("선택한 항목을 열 수 없음")
                    },
                    replaceUserSkill = replaceUserSkill,
                    expectedReplacementId = expectedReplacementId,
                    expectedArchiveSha256 = expectedArchiveSha256,
                )
            }.getOrElse {
                SkillZipImportOutcome.Failure(SkillZipImportOutcome.FailureCode.READ_FAILED)
            }
            withContext(Dispatchers.Main) {
                applySkillZipImportOutcome(outcome)
            }
        }
    }

    private fun enqueueRunEvent(runId: String, event: AgentEvent) {
        if (event is AgentEvent.AssistantBlockDelta) {
            if (event.kind == AgentEvent.AssistantBlockKind.TOOL_CALL || event.delta.isEmpty()) return

            runEventCoalescer.append(runId, event)?.let { ready ->
                applyRunEvent(runId, ready)
            }
            scheduleRunDeltaFlush(runId)
            return
        }

        flushPendingRunDelta(runId)
        applyRunEvent(runId, event)
    }

    private fun scheduleRunDeltaFlush(runId: String) {
        if (runEventFlushJobs[runId]?.isActive == true) return
        runEventFlushJobs[runId] = scope.launch {
            delay(STREAM_UI_UPDATE_INTERVAL_MS)
            runEventFlushJobs.remove(runId)
            flushPendingRunDelta(runId)
        }
    }

    private fun flushPendingRunDelta(runId: String) {
        runEventFlushJobs.remove(runId)?.cancel()
        runEventCoalescer.flush(runId)?.let { event ->
            applyRunEvent(runId, event)
        }
    }

    private fun applySkillZipImportOutcome(outcome: SkillZipImportOutcome) {
        when (outcome) {
            is SkillZipImportOutcome.Success -> {
                val installed = outcome.skills.singleOrNull()
                pendingSkillZipUri = null
                pendingSkillZipSha256 = null
                skillsState = skillsState.copy(
                    isImporting = false,
                    replacement = null,
                    notice = if (installed == null) {
                        skillZipFailureNotice(SkillZipImportOutcome.FailureCode.MULTIPLE_SKILLS)
                    } else {
                        newSkillNotice(
                            title = "스킬이 설치됨",
                            message = "「${installed.name.safeSkillDisplayName()}」 스킬을 사용 설정했습니다. 다음 대화부터 사용할 수 있습니다.",
                            isError = false,
                        )
                    },
                )
                if (installed != null) refreshSkills()
            }

            is SkillZipImportOutcome.Conflict -> {
                val conflict = outcome.skills.singleOrNull()
                val archiveSha256 = outcome.archiveSha256
                if (
                    conflict != null &&
                    conflict.source == "user" &&
                    conflict.replaceAllowed &&
                    archiveSha256 != null
                ) {
                    val existingName = skillsState.skills
                        .firstOrNull { it.id == conflict.id && it.installed }
                        ?.name
                        .orEmpty()
                        .ifBlank { conflict.name }
                    pendingSkillZipSha256 = archiveSha256
                    skillsState = skillsState.copy(
                        isImporting = false,
                        replacement = SkillReplacementUi(
                            id = conflict.id,
                            name = existingName.safeSkillDisplayName(),
                        ),
                        notice = null,
                    )
                } else {
                    pendingSkillZipUri = null
                    pendingSkillZipSha256 = null
                    skillsState = skillsState.copy(
                        isImporting = false,
                        replacement = null,
                        notice = skillZipFailureNotice(
                            if (conflict?.source == "builtin") {
                                SkillZipImportOutcome.FailureCode.BUILTIN_CONFLICT
                            } else if (conflict != null && conflict.replaceAllowed) {
                                SkillZipImportOutcome.FailureCode.PACKAGE_CHANGED
                            } else if (conflict != null && !conflict.replaceAllowed) {
                                SkillZipImportOutcome.FailureCode.TARGET_NOT_REPLACEABLE
                            } else {
                                SkillZipImportOutcome.FailureCode.MULTIPLE_SKILLS
                            },
                        ),
                    )
                }
            }

            is SkillZipImportOutcome.Failure -> {
                pendingSkillZipUri = null
                pendingSkillZipSha256 = null
                skillsState = skillsState.copy(
                    isImporting = false,
                    replacement = null,
                    notice = skillZipFailureNotice(outcome.code),
                )
                if (outcome.code == SkillZipImportOutcome.FailureCode.RECOVERY_REQUIRED) {
                    refreshSkills()
                }
            }
        }
    }

    private fun skillZipFailureNotice(code: SkillZipImportOutcome.FailureCode): SkillNoticeUi {
        val message = when (code) {
            SkillZipImportOutcome.FailureCode.INVALID_ARCHIVE -> "선택한 파일은 올바른 ZIP 스킬 패키지가 아닙니다."
            SkillZipImportOutcome.FailureCode.ARCHIVE_LIMIT_EXCEEDED -> "스킬 패키지가 안전한 크기 또는 파일 수 제한을 초과했습니다."
            SkillZipImportOutcome.FailureCode.UNSAFE_ARCHIVE -> "스킬 패키지에 안전하지 않은 파일 경로가 있어 설치하지 않았습니다."
            SkillZipImportOutcome.FailureCode.NO_SKILL -> "ZIP에서 SKILL.md를 찾지 못했습니다."
            SkillZipImportOutcome.FailureCode.MULTIPLE_SKILLS -> "로컬 ZIP에는 스킬 하나만 포함되어야 합니다."
            SkillZipImportOutcome.FailureCode.INVALID_SKILL -> "SKILL.md에 필수 정보가 없거나 형식이 올바르지 않습니다."
            SkillZipImportOutcome.FailureCode.PACKAGE_CHANGED -> "ZIP 내용이 변경되었습니다. 다시 선택하고 교체할 스킬을 확인하세요."
            SkillZipImportOutcome.FailureCode.BUILTIN_CONFLICT -> "같은 이름의 내장 스킬은 보호되므로 ZIP으로 교체할 수 없습니다."
            SkillZipImportOutcome.FailureCode.TARGET_NOT_REPLACEABLE ->
                "같은 이름의 대상이 안전하게 교체할 수 있는 사용자 스킬이 아니어서 기존 파일을 덮어쓰지 않았습니다."
            SkillZipImportOutcome.FailureCode.READ_FAILED -> "선택한 파일을 읽을 수 없습니다. 다시 선택하세요."
            SkillZipImportOutcome.FailureCode.STORAGE_FAILED -> "스킬을 저장하지 못해 기존 스킬을 자동으로 복구했습니다."
            SkillZipImportOutcome.FailureCode.RECOVERY_REQUIRED ->
                "설치에 실패했고 자동 복구도 완료되지 않았습니다. Eta가 앱 전용 디렉터리에 복구 백업을 보관했습니다. 스킬 목록을 확인하고 추가 설치를 중지하세요."
        }
        return newSkillNotice(
            title = "스킬을 설치할 수 없음",
            message = message,
            isError = true,
        )
    }

    fun reinstallBuiltin(skillId: String) {
        if (skillsState.isImporting || skillsState.busySkillId != null) return
        skillsState = skillsState.copy(busySkillId = skillId)
        scope.launch(Dispatchers.IO) {
            val succeeded = runCatching {
                SkillRuntime.createIndexService(appContext).installBuiltinSkill(skillId)
            }.isSuccess
            withContext(Dispatchers.Main) {
                skillsState = skillsState.copy(
                    busySkillId = null,
                    notice = if (succeeded) {
                        skillsState.notice
                    } else {
                        newSkillNotice(
                            title = "스킬을 복구할 수 없음",
                            message = "내장 스킬이 변경되지 않았습니다. 잠시 후 다시 시도하세요.",
                            isError = true,
                        )
                    },
                )
            }
            if (succeeded) refreshSkills()
        }
    }

    fun dismissSkillNotice() {
        skillsState = skillsState.copy(notice = null)
    }

    private fun newSkillNotice(
        title: String,
        message: String,
        isError: Boolean,
    ): SkillNoticeUi = SkillNoticeUi(
        id = ++skillNoticeSequence,
        title = title,
        message = message,
        isError = isError,
    )

    private fun String.safeSkillDisplayName(): String =
        lineSequence().firstOrNull().orEmpty().trim().ifBlank { "이름 없는 스킬" }.take(80)

    private fun applyRunEvent(runId: String, event: AgentEvent) {
        when (event) {
            is AgentEvent.AssistantBlockStart -> {
                if (event.kind == AgentEvent.AssistantBlockKind.TOOL_CALL) {
                    updateRunTrace(runId) { messages ->
                        runMessageProjector.finalizeTextRound(runId, event.round, messages)
                    }
                }
            }

            is AgentEvent.AssistantBlockDelta -> {
                updateMessages(runId, updateTimestamp = false) { messages ->
                    when (event.kind) {
                        AgentEvent.AssistantBlockKind.TEXT ->
                            runMessageProjector.appendTextDelta(runId, event.round, event.delta, messages)

                        AgentEvent.AssistantBlockKind.THINKING ->
                            runMessageProjector.appendReasoningDelta(runId, event.round, event.delta, messages)

                        AgentEvent.AssistantBlockKind.TOOL_CALL -> messages
                    }
                }
            }

            is AgentEvent.AssistantBlockEnd -> {
                updateRunTrace(runId) { messages ->
                    when (event.kind) {
                        AgentEvent.AssistantBlockKind.TEXT ->
                            runMessageProjector.finalizeTextRound(runId, event.round, messages)

                        AgentEvent.AssistantBlockKind.THINKING ->
                            runMessageProjector.finalizeThinkingRound(runId, event.round, messages)

                        AgentEvent.AssistantBlockKind.TOOL_CALL -> messages
                    }
                }
            }

            is AgentEvent.UsageReceived -> {
                updateAssistantUsage(runId, event.round, event.usage.toUi())
            }

            is AgentEvent.UserSupplementReceived -> {
                insertSupplementMessage(runId, event.index, event.text)
            }

            is AgentEvent.ToolStarted -> {
                updateRunTrace(runId) { messages ->
                    val finalizedThinking = runMessageProjector.finalizeThinking(runId, messages)
                    val finalizedText = runMessageProjector.finalizeTextRound(runId, event.round, finalizedThinking)
                    runMessageProjector.startTool(runId, event, finalizedText)
                }
            }

            is AgentEvent.ToolFinished -> {
                updateRunTrace(runId) { messages ->
                    runMessageProjector.finishTool(runId, event, messages)
                }
            }

            is AgentEvent.RunFailed -> {
                updateRunTrace(runId) { messages ->
                    val finalizedThinking = runMessageProjector.finalizeThinking(runId, messages)
                    val finalizedText = runMessageProjector.finalizeText(runId, finalizedThinking)
                    runMessageProjector.failRunningTools(event.reason, finalizedText)
                }
            }

            is AgentEvent.AssistantReceived -> {
                if (event.reasoningContent.isNotBlank()) {
                    updateRunTrace(runId) { messages ->
                        runMessageProjector.ensureCompletedThinking(
                            runId = runId,
                            round = event.round,
                            content = event.reasoningContent,
                            messages = messages,
                        )
                    }
                }
            }

            is AgentEvent.RunFinished -> {
                updateRunTrace(runId) { messages ->
                    val finalizedThinking = runMessageProjector.finalizeThinking(runId, messages)
                    runMessageProjector.finalizeText(runId, finalizedThinking)
                }
            }

            is AgentEvent.RunStarted,
            is AgentEvent.ProviderRequestStarted,
            is AgentEvent.ProviderResponseStarted,
            is AgentEvent.ToolImagesAttached,
            is AgentEvent.RoundStarted,
            -> Unit
        }
    }

    private fun applyRunResult(
        runId: String,
        result: AgentRuntimeWire.RunResult,
        acknowledgeRuntimeResult: Boolean = false,
    ) {
        flushPendingRunDelta(runId)
        if (runId == currentRunId) {
            currentRunId = null
            currentRunJob = null
        }
        val content = if (result.ok) {
            result.content.ifBlank { "완료되었습니다." }
        } else {
            result.error ?: "에이전트 런타임 호출 실패"
        }

        applyConversationHistoryResult(runId, result.transcript)
        replaceLatestAssistantMessage(runId, content, isStreaming = false, renderMarkdown = result.ok)
        setConversationStreaming(runId, false)
        runMessageProjector.clearRun(runId)
        runConversationIds.remove(runId)
        refreshConversationSummaries()
        persistConversations(
            onSaved = if (acknowledgeRuntimeResult) {
                {
                    AgentRuntimeClient(appContext, AndroidAgentLogger).ackResult(runId)
                }
            } else {
                null
            }
        )
    }

    private fun updateRunTrace(
        runId: String,
        transform: (List<AgentChatMessageUi>) -> List<AgentChatMessageUi>,
    ) {
        updateMessages(runId, transform = transform)
        refreshConversationSummaries()
    }

    private fun updateAssistantUsage(runId: String, round: Int, usage: TokenUsageUi) {
        if (usage.isEmpty) return
        // 只补充 token 用量。不能触碰 isStreaming：Usage 事件紧跟在文本块结束之后，
        // 若把 isStreaming 改回 true，流式渲染会在流式/静态两种视图间反复切换，整段重渲染。
        updateMessages(runId) { messages ->
            val assistantId = assistantMessageId(runId, round)
            messages.map { message ->
                if (message is AgentMessageUi && message.id == assistantId) {
                    message.copy(usage = usage)
                } else {
                    message
                }
            }
        }
    }

    private fun insertSupplementMessage(runId: String, index: Int, text: String) {
        updateMessages(runId) { messages ->
            AgentPendingResultRecovery.mergeSupplements(
                runId = runId,
                supplements = listOf(
                    AgentUiHandoffPayload.Supplement(
                        index = index,
                        text = text,
                        createdAt = System.currentTimeMillis(),
                    )
                ),
                messages = messages,
            )
        }
        refreshConversationSummaries()
        persistConversations()
    }

    private fun replaceAssistantMessage(
        runId: String,
        round: Int,
        content: String,
        isStreaming: Boolean,
        renderMarkdown: Boolean? = null,
        usage: TokenUsageUi? = null,
    ) {
        updateMessages(runId) { messages ->
            val assistantId = assistantMessageId(runId, round)
            var replaced = false
            val updated = messages.map { message ->
                if (message is AgentMessageUi && message.id == assistantId) {
                    replaced = true
                    message.copy(
                        content = content,
                        isStreaming = isStreaming,
                        renderMarkdown = renderMarkdown ?: message.renderMarkdown,
                        usage = usage ?: message.usage,
                    )
                } else {
                    message
                }
            }
            if (replaced) {
                updated
            } else {
                updated + AgentMessageUi(
                    id = assistantId,
                    content = content,
                    isStreaming = isStreaming,
                    renderMarkdown = renderMarkdown ?: false,
                    usage = usage,
                )
            }
        }
    }

    private fun replaceLatestAssistantMessage(
        runId: String,
        content: String,
        isStreaming: Boolean,
        renderMarkdown: Boolean? = null,
        usage: TokenUsageUi? = null,
    ) {
        replaceAssistantMessage(
            runId = runId,
            round = latestAssistantRound(runId) ?: 1,
            content = content,
            isStreaming = isStreaming,
            renderMarkdown = renderMarkdown,
            usage = usage,
        )
    }

    private fun latestAssistantRound(runId: String): Int? =
        conversationStateForRun(runId).messages
            .filterIsInstance<AgentMessageUi>()
            .mapNotNull { assistantRound(runId, it.id) }
            .maxOrNull()

    private fun assistantMessageId(runId: String, round: Int): String =
        "${assistantMessagePrefix(runId)}$round"

    private fun assistantMessagePrefix(runId: String): String =
        "assistant-$runId-"

    private fun assistantRound(runId: String, messageId: String): Int? =
        messageId.removePrefix(assistantMessagePrefix(runId))
            .takeIf { it != messageId }
            ?.toIntOrNull()

    private fun updateMessages(
        runId: String,
        updateTimestamp: Boolean = true,
        transform: (List<AgentChatMessageUi>) -> List<AgentChatMessageUi>,
    ) {
        val conversationId = conversationIdForRun(runId) ?: return
        val state = conversationsById[conversationId] ?: return
        updateConversation(
            conversationId = conversationId,
            state = state.copy(messages = transform(state.messages)),
            updateTimestamp = updateTimestamp,
        )
    }

    private fun applyConversationHistoryResult(
        runId: String,
        additions: List<AgentModelClient.ConversationMessage>,
    ) {
        val conversationId = conversationIdForRun(runId) ?: return
        val state = conversationsById[conversationId] ?: return
        val outcome = AgentRuntimeHistoryReducer.apply(state, runId, additions)
        if (!outcome.alreadyApplied) updateConversation(conversationId, outcome.state)
    }

    private fun updateCurrentConversation(state: AgentChatHomeUiState) {
        val conversationId = selectedConversationId
        if (conversationId == null) {
            homeState = state
        } else {
            updateConversation(conversationId, state)
        }
    }

    private fun moveCurrentDraftToNewConversation() {
        val draft = homeState
        selectedConversationId = null
        homeState = emptyChatState(defaultThinkingEnabled).copy(
            input = draft.input,
            thinkingEnabled = draft.thinkingEnabled,
            pendingImages = draft.pendingImages,
        )
        conversationPaneState = conversationPaneState.copy(selectedConversationId = null)
    }

    private fun updateConversation(
        conversationId: String,
        state: AgentChatHomeUiState,
        updateTimestamp: Boolean = true,
    ) {
        conversationsById = conversationsById + (conversationId to state)
        if (updateTimestamp) {
            conversationUpdatedAt = conversationUpdatedAt + (conversationId to System.currentTimeMillis())
        }
        if (conversationId == selectedConversationId) {
            homeState = state
        }
    }

    private fun setConversationStreaming(runId: String, isStreaming: Boolean) {
        val conversationId = conversationIdForRun(runId) ?: return
        val state = conversationsById[conversationId] ?: return
        updateConversation(conversationId, state.copy(isStreaming = isStreaming))
    }

    private fun conversationIdForRun(runId: String): String? = runConversationIds[runId]

    private fun conversationStateForRun(runId: String): AgentChatHomeUiState {
        val conversationId = conversationIdForRun(runId) ?: return emptyChatState(defaultThinkingEnabled)
        return conversationsById[conversationId] ?: emptyChatState(defaultThinkingEnabled)
    }

    private fun refreshConversationSummaries() {
        val summaries = conversationsById.entries
            .sortedByDescending { (id, _) ->
                conversationUpdatedAt[id] ?: 0L
            }
            .map { (id, state) ->
                val lastMessage = state.messages.lastOrNull()
                ConversationSummaryUi(
                    id = id,
                    title = conversationTitles[id] ?: "새 대화",
                    preview = when (lastMessage) {
                        is UserMessageUi -> lastMessage.content
                        is AgentMessageUi -> lastMessage.content.ifBlank { "에이전트가 생각 중" }
                        is ThinkingMessageUi -> "에이전트가 생각 중"
                        is ToolActivityMessageUi -> "도구 호출: ${lastMessage.toolName}"
                        else -> "질문을 입력하세요. 필요하면 에이전트가 휴대폰을 조작합니다."
                    }.take(MAX_PREVIEW_CHARS),
                    timeLabel = if (state.isStreaming) {
                        "지금"
                    } else {
                        conversationUpdatedAt[id]?.let(ConversationTimeLabels::label) ?: "최근"
                    },
                    mode = ConversationModeUi.Chat,
                    isActiveRun = state.isStreaming,
                )
            }
        val query = conversationPaneState.searchQuery.trim()
        conversationPaneState = conversationPaneState.copy(
            selectedConversationId = selectedConversationId,
            conversations = if (query.isBlank()) {
                summaries
            } else {
                summaries.filter {
                    it.title.contains(query, ignoreCase = true) ||
                        it.preview.contains(query, ignoreCase = true)
                }
            },
        )
    }

    private fun persistConversations(onSaved: (() -> Unit)? = null) {
        val selected = selectedConversationId
        val conversations = conversationsById
        val titles = conversationTitles
        val timestamps = conversationUpdatedAt
        synchronized(persistenceLock) {
            val previous = persistenceJob
            persistenceJob = scope.launch(Dispatchers.IO) {
                try {
                    previous?.join()
                    AgentConversationStore.save(
                        context = appContext,
                        selectedConversationId = selected,
                        conversationsById = conversations,
                        titles = titles,
                        updatedAt = timestamps,
                    )
                    onSaved?.invoke()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (throwable: Throwable) {
                    AndroidAgentLogger.error(
                        "Agent conversation persistence failed: type=${throwable.safeLogType()}"
                    )
                }
            }
        }
    }

    private companion object {
        const val HANDOFF_SOURCE = "agent_ui"
        const val MAX_TITLE_CHARS = 24
        const val MAX_PREVIEW_CHARS = 48
        // 数据状态以较粗粒度发布，文字显现由独立的帧时钟连续推进。
        // 这与 Kimi 将流式数据和视觉动画分层的做法一致。
        const val STREAM_UI_UPDATE_INTERVAL_MS = 80L

        fun emptyChatState(thinkingEnabled: Boolean): AgentChatHomeUiState =
            AgentChatHomeUiState(
                messages = emptyList(),
                history = emptyList(),
                input = "",
                isStreaming = false,
                thinkingEnabled = thinkingEnabled,
            )

        fun newConversationId(): String = "conv-${UUID.randomUUID()}"
    }
}

private const val EXTERNAL_ARCHIVE_CONVERSATION_PREFIX = "archive-"

private fun String.isExternalArchiveConversation(): Boolean =
    startsWith(EXTERNAL_ARCHIVE_CONVERSATION_PREFIX)

private fun archiveConversationId(source: String, conversationKey: String): String =
    "$EXTERNAL_ARCHIVE_CONVERSATION_PREFIX${stableArchiveId("$source:$conversationKey")}"

private fun stableArchiveId(value: String): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .take(12)
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

private fun buildToolsState(): AgentToolsUiState =
    AgentToolsUiState(
        groups = listOf(
            ToolGroupUi(
                id = "screen",
                title = "화면 및 컨트롤",
                tools = listOf(
                    ToolItemUi("observe_screen", "화면 확인", "스크린샷과 현재 접근성 노드를 읽습니다."),
                    ToolItemUi("tap_element", "요소 탭", "최근 확인한 노드를 탭합니다."),
                    ToolItemUi("tap_area", "영역 탭", "좌표 영역을 탭합니다."),
                    ToolItemUi("long_press", "길게 누르기", "좌표 또는 요소를 길게 누릅니다."),
                    ToolItemUi("swipe", "스와이프", "상하좌우 스와이프 제스처를 실행합니다."),
                    ToolItemUi("scroll", "스크롤", "페이지 또는 지정 노드를 스크롤합니다."),
                ),
            ),
            ToolGroupUi(
                id = "text",
                title = "텍스트 및 클립보드",
                tools = listOf(
                    ToolItemUi("input_text", "텍스트 입력", "현재 포커스에 텍스트를 추가하거나 붙여넣습니다."),
                    ToolItemUi("replace_text", "텍스트 바꾸기", "포커스 또는 노드의 텍스트를 바꿉니다."),
                    ToolItemUi("clear_text", "텍스트 지우기", "포커스 또는 노드의 텍스트를 지웁니다."),
                    ToolItemUi("paste_text", "텍스트 붙여넣기", "클립보드로 긴 텍스트를 안정적으로 입력합니다."),
                    ToolItemUi("wait_for_text", "텍스트 대기", "지정한 텍스트가 화면에 나타날 때까지 기다립니다."),
                ),
            ),
            ToolGroupUi(
                id = "web",
                title = "웹 탐색",
                tools = listOf(
                    ToolItemUi("browser_use", "에이전트 브라우저", "백그라운드에서 웹페이지를 열고 인계 가능한 탐색 세션을 유지합니다."),
                    ToolItemUi("browser_read", "웹페이지 읽기", "렌더링된 본문, 목록, 링크를 추출합니다."),
                    ToolItemUi("browser_interact", "웹페이지 조작", "페이지 요소를 찾고 탭하거나 입력합니다."),
                    ToolItemUi("browser_screenshot", "페이지 스크린샷", "현재 웹페이지 뷰포트를 비전 모델에 전달합니다."),
                ),
            ),
            ToolGroupUi(
                id = "app",
                title = "앱 및 시스템",
                tools = listOf(
                    ToolItemUi("search_apps", "앱 검색", "이름 또는 패키지명으로 설치된 앱을 검색합니다."),
                    ToolItemUi("get_current_context", "시간 및 위치", "시스템 시간과 최근 위치를 읽습니다."),
                    ToolItemUi("launch_app", "앱 열기", "지정한 패키지명 또는 앱 이름으로 실행합니다."),
                    ToolItemUi("open_uri", "앱으로 열기", "링크 또는 딥 링크를 외부 앱으로 명시적으로 전달합니다."),
                    ToolItemUi("press_key", "버튼", "뒤로, 홈, 최근 앱 등의 시스템 버튼을 누릅니다."),
                    ToolItemUi("open_system_panel", "시스템 패널", "알림 창, 빠른 설정 등의 패널을 엽니다."),
                ),
            ),
            ToolGroupUi(
                id = "device_direct",
                title = "기기 직접 제어",
                tools = listOf(
                    ToolItemUi("set_alarm", "알람 설정", "시스템 알람을 직접 만들고, 실패하면 시계 앱을 열어 확인합니다."),
                    ToolItemUi("set_timer", "타이머 설정", "최대 24시간의 시스템 타이머를 직접 만듭니다."),
                    ToolItemUi("device_status", "기기 상태", "배터리, 메모리, 저장공간, 시스템 버전을 읽습니다."),
                    ToolItemUi("network_info", "네트워크 상태", "연결 방식과 현재 Wi‑Fi 상태를 읽습니다."),
                    ToolItemUi("media_control", "미디어 제어", "화면 조작 없이 재생, 일시정지, 곡 넘기기를 수행합니다."),
                    ToolItemUi("set_volume", "음량 설정", "미디어, 알람, 벨소리 등의 채널별로 설정합니다."),
                    ToolItemUi("top_memory_apps", "메모리 사용 순위", "현재 메모리를 가장 많이 사용하는 프로세스를 확인합니다."),
                    ToolItemUi("top_storage_apps", "저장공간 사용 순위", "앱, 데이터, 캐시 사용량을 확인합니다."),
                ),
            ),
            ToolGroupUi(
                id = "device_sensitive",
                title = "민감한 기기 기능",
                tools = listOf(
                    ToolItemUi("send_message", "WeChat 메시지 보내기", "연락처를 정확히 일치시킨 뒤 한 번만 전송하고 결과를 확인합니다."),
                    ToolItemUi("read_sms_code", "인증번호 읽기", "최근 SMS에서 인증번호만 추출합니다."),
                    ToolItemUi("recent_notifications", "알림 읽기", "현재 알림의 제목과 본문을 읽습니다."),
                    ToolItemUi("wifi_credentials", "Wi‑Fi 비밀번호", "휴대폰에 저장된 네트워크 인증 정보를 읽습니다."),
                    ToolItemUi("get_setting", "시스템 설정 읽기", "지정한 Settings 키를 읽습니다."),
                    ToolItemUi("set_setting", "시스템 설정 변경", "보안상 중요하지 않은 Settings 키를 변경합니다."),
                    ToolItemUi("set_device_state", "네트워크 스위치", "Wi‑Fi 또는 블루투스를 직접 제어합니다."),
                    ToolItemUi("app_state_control", "앱 상태", "앱을 중지, 정지 또는 정지 해제합니다."),
                    ToolItemUi("get_logcat", "시스템 로그", "제한된 범위에서 최근 로그를 읽고 필터링합니다."),
                ),
            ),
            ToolGroupUi(
                id = "terminal",
                title = "터미널 및 파일",
                tools = listOf(
                    ToolItemUi("terminal", "세션 터미널", "user/root Shell을 세션 방식으로 실행하고 비동기 출력을 읽습니다."),
                    ToolItemUi("run_command", "명령 실행", "Shell 명령 하나를 직접 실행합니다."),
                    ToolItemUi("read_file", "파일 읽기", "휴대폰 파일 내용을 읽습니다."),
                    ToolItemUi("write_file", "파일 쓰기", "휴대폰 파일을 쓰거나 덮어씁니다."),
                    ToolItemUi("list_directory", "디렉터리 목록", "디렉터리 내용을 나열합니다."),
                ),
            ),
        )
    )

private fun buildPermissionHealthState(context: Context): PermissionHealthUiState {
    val backgroundRunningEnabled = isIgnoringBatteryOptimizations(context)
    val overlayEnabled = Settings.canDrawOverlays(context)
    val appListEnabled = hasAppListAccess(context)
    val accessibilityEnabled = isAgentAccessibilityEnabled(context) || AgentAccessibilityService.isAvailable()
    val rootEnabled = isRootAvailable()
    val locationAccess = DeviceLocationProvider.accessState(context)

    return PermissionHealthUiState(
        items = listOf(
            PermissionHealthItemUi(
                id = "background",
                title = "백그라운드 실행 권한",
                summary = "",
                status = if (backgroundRunningEnabled) PermissionStatusUi.Available else PermissionStatusUi.Missing,
                primaryActionLabel = if (backgroundRunningEnabled) null else "설정하기",
            ),
            PermissionHealthItemUi(
                id = "overlay",
                title = "다른 앱 위에 표시 권한",
                summary = "",
                status = if (overlayEnabled) PermissionStatusUi.Available else PermissionStatusUi.Missing,
                primaryActionLabel = if (overlayEnabled) null else "허용하기",
            ),
            PermissionHealthItemUi(
                id = "app_list",
                title = "앱 목록 읽기",
                summary = "",
                status = if (appListEnabled) PermissionStatusUi.Available else PermissionStatusUi.Missing,
                primaryActionLabel = if (appListEnabled) null else "설정하기",
            ),
            PermissionHealthItemUi(
                id = "location",
                title = "위치 권한",
                summary = when (locationAccess) {
                    DeviceLocationProvider.AccessState.DENIED -> "필요할 때 휴대폰의 현재 위치를 파악하는 데 사용합니다."
                    DeviceLocationProvider.AccessState.FOREGROUND_ONLY -> "Breeno 진입점에서는 ‘항상 허용’으로 설정해야 합니다."
                    DeviceLocationProvider.AccessState.DISABLED -> "시스템 위치 서비스가 꺼져 있습니다."
                    DeviceLocationProvider.AccessState.AVAILABLE -> "에이전트가 도구를 호출할 때만 읽습니다."
                },
                status = when (locationAccess) {
                    DeviceLocationProvider.AccessState.DENIED -> PermissionStatusUi.Missing
                    DeviceLocationProvider.AccessState.FOREGROUND_ONLY -> PermissionStatusUi.Warning
                    DeviceLocationProvider.AccessState.DISABLED -> PermissionStatusUi.Disabled
                    DeviceLocationProvider.AccessState.AVAILABLE -> PermissionStatusUi.Available
                },
                primaryActionLabel = when (locationAccess) {
                    DeviceLocationProvider.AccessState.DENIED -> "허용하기"
                    DeviceLocationProvider.AccessState.FOREGROUND_ONLY -> "설정으로 이동"
                    DeviceLocationProvider.AccessState.DISABLED -> "설정하기"
                    DeviceLocationProvider.AccessState.AVAILABLE -> null
                },
            ),
            PermissionHealthItemUi(
                id = "accessibility",
                title = "접근성 권한",
                summary = "",
                status = if (accessibilityEnabled) PermissionStatusUi.Available else PermissionStatusUi.Missing,
                primaryActionLabel = if (accessibilityEnabled) null else "설정하기",
            ),
            PermissionHealthItemUi(
                id = "root",
                title = "Root 권한",
                summary = "",
                status = if (rootEnabled) PermissionStatusUi.Available else PermissionStatusUi.Missing,
                primaryActionLabel = if (rootEnabled) null else "설정하기",
            ),
        )
    )
}

private fun remoteBooleanForUi(key: String): Boolean {
    val default = Prefs.Keys.BOOLEAN_DEFAULTS[key] ?: true
    return Prefs.remotePreferencesForUi(FuckAndesApp.serviceInstance)
        ?.getBoolean(key, default)
        ?: Prefs.isEnabled(key)
}

private fun AgentTokenUsage.toUi(): TokenUsageUi =
    TokenUsageUi(
        contextTokens = contextTokens,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        reasoningTokens = reasoningTokens,
        cachedTokens = cachedTokens,
    )

private fun buildSystemEnhanceState(): AgentSystemEnhanceUiState =
    AgentSystemEnhanceUiState(
        sections = listOf(
            SystemEnhanceSectionUi(
                id = "runtime",
                title = "Agent Runtime",
                items = listOf(
                    SystemEnhanceItemUi(
                        id = "streaming",
                        title = "스트리밍 이벤트",
                        summary = "모델의 스트리밍 응답, 도구 호출, 최종 결과를 현재 대화에 동기화합니다.",
                        status = SystemEnhanceStatusUi.Active,
                    ),
                    SystemEnhanceItemUi(
                        id = "overlay",
                        title = "실행 오버레이",
                        summary = "런타임 서비스가 실행되는 동안 상태 오버레이를 표시합니다.",
                        status = SystemEnhanceStatusUi.Active,
                    ),
                ),
            ),
            SystemEnhanceSectionUi(
                id = "future",
                title = "향후 기능",
                items = listOf(
                    SystemEnhanceItemUi(
                        id = "memory",
                        title = "메모리 시스템",
                        summary = "장기 메모리와 예약 트리거는 추후 지원할 예정입니다.",
                        status = SystemEnhanceStatusUi.Inactive,
                    ),
                    SystemEnhanceItemUi(
                        id = "hook",
                        title = "Hook 보조 기능",
                        summary = "시스템 강화 기능은 향후 보조 기능으로 유지합니다.",
                        status = SystemEnhanceStatusUi.Inactive,
                    ),
                ),
            ),
        )
    )

private fun isAgentAccessibilityEnabled(context: Context): Boolean {
    val expected = ComponentName(
        context,
        AgentAccessibilityService::class.java,
    ).flattenToString()
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ).orEmpty()
    return enabledServices.split(':').any { it.equals(expected, ignoreCase = true) }
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    return powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
}

private fun isRootAvailable(): Boolean {
    return try {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
        val exitCode = process.waitFor()
        exitCode == 0
    } catch (e: Exception) {
        false
    }
}

private fun hasAppListAccess(context: Context): Boolean {
    return try {
        val pm = context.packageManager
        val packages = pm.getInstalledPackages(0)
        packages.size > 10
    } catch (e: Exception) {
        false
    }
}
