package io.github.mangi.eta.ui.app

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.mangi.eta.agent.roleplay.CharacterCard
import io.github.mangi.eta.agent.roleplay.CharacterCardCodec
import io.github.mangi.eta.agent.roleplay.CharacterCardFormat
import io.github.mangi.eta.agent.roleplay.CharacterCardException
import io.github.mangi.eta.agent.roleplay.CharacterCardCompatibility
import io.github.mangi.eta.agent.roleplay.CharacterBookDraft
import io.github.mangi.eta.agent.roleplay.CharacterProfile
import io.github.mangi.eta.agent.roleplay.RoleplayBinding
import io.github.mangi.eta.agent.roleplay.UserPersona
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.repository.AgentMemorySnapshot
import io.github.mangi.eta.data.repository.AgentMemoryWriteResult
import io.github.mangi.eta.data.repository.CharacterMemoryRepository
import io.github.mangi.eta.data.repository.CharacterRepository
import io.github.mangi.eta.i18n.ko
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class CharacterLibraryViewModel(application: Application) : AndroidViewModel(application) {
    val store = CharacterLibraryStore(application, viewModelScope)
}

/** 角色页面才加载资料；配置变更保留编辑草稿，文件操作均在后台完成。 */
internal class CharacterLibraryStore(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    var characters by mutableStateOf<List<CharacterProfile>>(emptyList())
        private set
    var query by mutableStateOf("")
    var selected by mutableStateOf<CharacterProfile?>(null)
        private set
    var compatibilityWarnings by mutableStateOf<List<String>>(emptyList())
        private set
    var draft by mutableStateOf<CharacterCard?>(null)
        private set
    var draftName by mutableStateOf("")
        private set
    var persona by mutableStateOf(UserPersona())
        private set
    var personaDraft by mutableStateOf(UserPersona())
        private set
    var usePersona by mutableStateOf(true)
    var greetingIndex by mutableStateOf(0)
    var memoryDraft by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set
    var notice by mutableStateOf<String?>(null)
        private set
    private var operation: Job? = null
    private var pendingLoad: (() -> Unit)? = null
    private var editorKey: String? = null
    private var editorLoaded = false
    private var personaLoaded = false
    private var memoryCharacterId: String? = null
    private var memorySnapshot: AgentMemorySnapshot? = null

    val filteredCharacters: List<CharacterProfile>
        get() {
            val term = query.trim()
            return characters.filter {
                term.isEmpty() ||
                    it.card.name.contains(term, ignoreCase = true) ||
                    it.card.tags.any { tag -> tag.contains(term, ignoreCase = true) }
            }
        }

    fun loadLibrary() = runOperation(ko("角色库读取失败，请重试", "캐릭터 목록을 불러오지 못했습니다. 다시 시도하세요."), queueIfBusy = true) {
        io { CharacterRepository.ensureDefaultCharacter() }
        characters = io { CharacterRepository.list() }
    }

    fun dismissNotice() { notice = null }

    fun loadDetail(id: String) = runOperation(ko("角色读取失败，请返回角色库重试", "캐릭터를 불러오지 못했습니다. 캐릭터 목록으로 돌아가 다시 시도하세요."), queueIfBusy = true) {
        val profile = io { CharacterRepository.get(id) } ?: error("CHARACTER_NOT_FOUND")
        val warnings = io { CharacterCardCompatibility.warnings(profile.card) }
        if (selected?.id != id) greetingIndex = 0
        selected = profile
        compatibilityWarnings = warnings
        persona = io { CharacterRepository.persona() }
    }

    fun loadEditor(id: String?) {
        if (editorLoaded && editorKey == id) return
        runOperation(ko("角色读取失败，请重试", "캐릭터를 불러오지 못했습니다. 다시 시도하세요."), queueIfBusy = true) {
            val profile = id?.let { io { CharacterRepository.get(it) } ?: error("CHARACTER_NOT_FOUND") }
            selected = profile
            draft = profile?.card ?: CharacterCardCodec.create(ko("新角色", "새 캐릭터"))
            draftName = profile?.card?.name.orEmpty()
            editorKey = id
            editorLoaded = true
        }
    }

    fun discardEditor() {
        editorLoaded = false
        editorKey = null
        draft = null
    }

    fun updateDraft(update: (CharacterCard) -> CharacterCard) {
        if (!busy) draft = draft?.let(update)
    }

    fun updateName(name: String) { if (!busy) draftName = name }

    fun updateWorldbook(update: (CharacterBookDraft) -> CharacterBookDraft) {
        if (busy) return
        try {
            draft = draft?.let { it.withWorldbook(update(it.worldbookDraft())) }
        } catch (_: IllegalArgumentException) {
            notice = ko("世界书设置无效，请检查扫描深度、预算与条目位置", "월드북 설정이 올바르지 않습니다. 스캔 깊이, 예산, 항목 위치를 확인하세요.")
        }
    }

    fun importCard(uri: Uri, onImported: (String) -> Unit) = runOperation(
        ko("导入失败，请确认文件是完整的 PNG 或 JSON 角色卡，且未超过大小限制", "가져오지 못했습니다. 온전한 PNG 또는 JSON 캐릭터 카드인지, 크기 제한을 넘지 않는지 확인하세요."),
        diagnoseCardImport = true,
    ) {
        val profile = io {
            context.contentResolver.openInputStream(uri)?.use { CharacterRepository.import(it) }
                ?: error("CHARACTER_INPUT_UNAVAILABLE")
        }
        selected = profile
        compatibilityWarnings = emptyList()
        characters = io { CharacterRepository.list() }
        onImported(profile.id)
    }

    fun saveEditor(onSaved: (String) -> Unit) {
        val originalDraft = draft ?: return
        if (draftName.isBlank()) {
            notice = ko("请填写角色名称", "캐릭터 이름을 입력하세요")
            return
        }
        val card = originalDraft.withEdits(name = draftName)
        val original = selected
        runOperation(ko("角色保存失败，请重试", "캐릭터를 저장하지 못했습니다. 다시 시도하세요.")) {
            val profile = io {
                if (original == null) CharacterRepository.create(card)
                else CharacterRepository.save(original.copy(card = card))
            }
            selected = profile
            compatibilityWarnings = emptyList()
            discardEditor()
            characters = io { CharacterRepository.list() }
            onSaved(profile.id)
        }
    }

    fun duplicate(id: String, onDuplicated: (String) -> Unit) = runOperation(ko("角色复制失败，请重试", "캐릭터를 복제하지 못했습니다. 다시 시도하세요.")) {
        val profile = io { CharacterRepository.duplicate(id) }
        characters = io { CharacterRepository.list() }
        onDuplicated(profile.id)
    }

    fun restoreDefaultCharacter() = runOperation(ko("默认角色恢复失败，请重试", "기본 캐릭터를 복원하지 못했습니다. 다시 시도하세요.")) {
        io { CharacterRepository.createDefaultCharacter() }
        characters = io { CharacterRepository.list() }
    }

    fun delete(id: String, onDeleted: () -> Unit) = runOperation(ko("角色删除失败，请重试", "캐릭터를 삭제하지 못했습니다. 다시 시도하세요.")) {
        io { CharacterRepository.delete(id) }
        if (selected?.id == id) selected = null
        characters = io { CharacterRepository.list() }
        onDeleted()
    }

    fun export(id: String, format: CharacterCardFormat, uri: Uri) = runOperation(ko("角色卡导出失败，请重试", "캐릭터 카드를 내보내지 못했습니다. 다시 시도하세요.")) {
        io {
            context.contentResolver.openOutputStream(uri, "wt")?.use {
                CharacterRepository.export(id, format, it)
            } ?: error("CHARACTER_OUTPUT_UNAVAILABLE")
        }
        notice = ko("角色卡已导出", "캐릭터 카드를 내보냈습니다")
    }

    fun startConversation(id: String, onReady: (RoleplayBinding, String) -> Unit) = runOperation(
        ko("新对话创建失败，请重试", "새 대화를 만들지 못했습니다. 다시 시도하세요."),
    ) {
        val profile = io { CharacterRepository.get(id) } ?: error("CHARACTER_NOT_FOUND")
        val storedBinding = io { CharacterRepository.binding(id) }
        val binding = if (usePersona) storedBinding else storedBinding.copy(userName = ko("用户", "사용자"), userDescription = "")
        val greetings = listOf(profile.card.firstMessage) + profile.card.alternateGreetings
        onReady(binding, greetings.getOrElse(greetingIndex) { profile.card.firstMessage })
    }

    fun loadPersona() {
        if (personaLoaded && personaDraft != persona) return
        runOperation(ko("用户人设读取失败，请重试", "내 페르소나를 불러오지 못했습니다. 다시 시도하세요."), queueIfBusy = true) {
            persona = io { CharacterRepository.persona() }
            personaDraft = persona
            personaLoaded = true
        }
    }

    fun updatePersona(name: String = personaDraft.name, description: String = personaDraft.description) {
        if (!busy) personaDraft = UserPersona(name, description)
    }

    fun savePersona(onSaved: () -> Unit) = runOperation(ko("用户人设保存失败，请重试", "내 페르소나를 저장하지 못했습니다. 다시 시도하세요.")) {
        val normalized = personaDraft.copy(name = personaDraft.name.trim().ifBlank { ko("用户", "사용자") })
        io { CharacterRepository.savePersona(normalized) }
        persona = normalized
        personaDraft = normalized
        onSaved()
    }

    fun loadMemory(id: String, force: Boolean = false) {
        if (!force && memoryCharacterId == id && memorySnapshot != null && memoryDraft != memorySnapshot?.content) return
        runOperation(ko("剧情记忆读取失败，请重试", "스토리 메모리를 불러오지 못했습니다. 다시 시도하세요."), queueIfBusy = true) {
            val snapshot = io { CharacterMemoryRepository.snapshot(context, id) }
            memoryCharacterId = id
            memorySnapshot = snapshot
            memoryDraft = snapshot.content
        }
    }

    fun updateMemory(content: String) { if (!busy) memoryDraft = content }

    fun saveMemory(id: String) {
        val original = memorySnapshot ?: return
        val content = memoryDraft
        runOperation(ko("剧情记忆保存失败，请检查内容长度后重试", "스토리 메모리를 저장하지 못했습니다. 내용 길이를 확인한 후 다시 시도하세요.")) {
            when (val result = io {
                CharacterMemoryRepository.replaceAllIfRevision(context, id, original.revision, content)
            }) {
                is AgentMemoryWriteResult.Success -> {
                    memorySnapshot = result.snapshot
                    notice = ko("剧情记忆已保存", "스토리 메모리를 저장했습니다")
                }
                is AgentMemoryWriteResult.Conflict -> {
                    notice = ko("剧情记忆已被对话更新。当前草稿仍保留，请复制需要的内容后重新载入，再合并保存。", "대화에서 스토리 메모리가 업데이트되었습니다. 현재 초안은 유지됩니다. 필요한 내용을 복사한 후 다시 불러와 병합해 저장하세요.")
                }
            }
        }
    }

    private fun runOperation(
        failure: String,
        queueIfBusy: Boolean = false,
        diagnoseCardImport: Boolean = false,
        block: suspend () -> Unit,
    ) {
        if (operation?.isActive == true) {
            if (queueIfBusy) pendingLoad = { runOperation(failure, queueIfBusy = true, block = block) }
            return
        }
        operation = scope.launch {
            busy = true
            try {
                io { CharacterRepository.initialize(context) }
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AndroidAgentLogger.warn("CharacterLibrary operation_failed type=${error.javaClass.simpleName}")
                notice = if (diagnoseCardImport) {
                    characterCardImportMessage((error as? CharacterCardException)?.code) ?: failure
                } else failure
            } finally {
                busy = false
                operation = null
                val next = pendingLoad
                pendingLoad = null
                next?.invoke()
            }
        }
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }
}
