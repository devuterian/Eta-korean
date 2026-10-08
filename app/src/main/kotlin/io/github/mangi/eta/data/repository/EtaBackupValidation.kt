package io.github.mangi.eta.data.repository

import io.github.mangi.eta.data.db.ConversationStateEntity
import io.github.mangi.eta.i18n.ko

/** 校验只保留标识与角色关联所需的消息类型，正文和检查点逐条读取后释放。 */
internal object EtaBackupValidation {
    suspend fun validate(staged: EtaBackupJsonStreams.StagedDocument): EtaBackupSummary {
        val header = staged.header
        validateHeader(header)
        val conversationIds = mutableSetOf<String>()
        val revisionConversationIds = mutableSetOf<String>()
        val bindingIds = mutableSetOf<String>()
        try {
            staged.conversations { row ->
                if (row.id.isBlank() || !conversationIds.add(row.id)) {
                    throw EtaBackupException(ko("备份中的会话存在重复或无效 ID", "백업의 대화에 중복되었거나 올바르지 않은 ID가 있습니다."))
                }
                if (row.revisionsJson.isNotBlank()) revisionConversationIds += row.id
                CharacterBackupTransfer.validateBindings(listOf(row)).forEach { bindingIds += it.characterId }
            }
            if (header.conversationState != null && header.conversationState.selectedConversationId !in conversationIds) {
                throw EtaBackupException(ko("备份中的当前会话不存在", "백업의 현재 대화가 존재하지 않습니다."))
            }
            val messageIds = mutableSetOf<String>()
            val messagePositions = mutableSetOf<Pair<String, Int>>()
            val revisionMessageTypes = mutableMapOf<String, MutableMap<String, String>>()
            var messageCount = 0
            staged.messages { row ->
                if (row.conversationId !in conversationIds) throw EtaBackupException(ko("备份中的消息缺少所属会话", "백업의 메시지에 소속 대화가 없습니다."))
                if (row.id.isBlank() || !messageIds.add(row.id)) throw EtaBackupException(ko("备份中的消息 ID 重复", "백업의 메시지 ID가 중복되었습니다."))
                if (!messagePositions.add(row.conversationId to row.sortIndex)) throw EtaBackupException(ko("备份中的消息顺序重复", "백업의 메시지 순서가 중복되었습니다."))
                if (row.conversationId in revisionConversationIds) {
                    revisionMessageTypes.getOrPut(row.conversationId) { mutableMapOf() }[row.id] = row.type
                }
                messageCount++
            }
            staged.conversations { row ->
                CharacterBackupTransfer.validateRevisions(row, revisionMessageTypes[row.id].orEmpty())
            }
            val checkpointIds = mutableSetOf<String>()
            staged.checkpoints { row ->
                if (row.conversationId !in conversationIds) throw EtaBackupException(ko("备份中的上下文检查点缺少所属会话", "백업의 컨텍스트 체크포인트에 소속 대화가 없습니다."))
                if (!checkpointIds.add(row.conversationId)) throw EtaBackupException(ko("备份中的上下文检查点重复", "백업의 컨텍스트 체크포인트가 중복되었습니다."))
            }
            header.roleplay?.let { CharacterBackupTransfer.validate(it, bindingIds) }
            return EtaBackupSummary(
                providerCount = header.providers.size,
                modelCount = header.providers.sumOf { it.models.size },
                conversationCount = conversationIds.size,
                messageCount = messageCount,
                memoryBytes = header.memoryMd.toByteArray(Charsets.UTF_8).size,
                characterCount = header.roleplay?.characters?.size ?: 0,
            )
        } catch (failure: IllegalArgumentException) {
            if (failure is EtaBackupException) throw failure
            throw EtaBackupException(ko("备份中的记录或角色数据无效", "백업의 기록 또는 캐릭터 데이터가 올바르지 않습니다."), failure)
        }
    }

    private fun validateHeader(document: EtaBackupDocument) {
        if (document.format != EtaBackupDocument.FORMAT) throw EtaBackupException(ko("这不是 Eta 备份文件", "Eta 백업 파일이 아닙니다."))
        if (document.schemaVersion !in 1..EtaBackupDocument.SCHEMA_VERSION) {
            throw EtaBackupException(ko("不支持的 Eta 备份版本：${document.schemaVersion}", "지원하지 않는 Eta 백업 버전: ${document.schemaVersion}"))
        }
        if (document.catalogRevision < 0) throw EtaBackupException(ko("备份中的模型目录版本无效", "백업의 모델 목록 버전이 올바르지 않습니다."))
        val providerIds = document.providers.map { it.provider.id }
        if (providerIds.size != providerIds.toSet().size || providerIds.any(String::isBlank)) {
            throw EtaBackupException(ko("备份中的模型提供商存在重复或无效 ID", "백업의 모델 Provider에 중복되었거나 올바르지 않은 ID가 있습니다."))
        }
        val modelIds = document.providers.flatMap { provider ->
            val ids = provider.models.map { it.id }
            if (ids.size != ids.toSet().size || ids.any(String::isBlank)) throw EtaBackupException(ko("备份中的模型存在重复或无效 ID", "백업의 모델에 중복되었거나 올바르지 않은 ID가 있습니다."))
            if (provider.models.any { it.providerId != provider.provider.id }) throw EtaBackupException(ko("备份中的模型与提供商不匹配", "백업의 모델과 Provider가 일치하지 않습니다."))
            provider.models.map { it.id to provider.provider.id }
        }
        if (modelIds.size != modelIds.map { it.first }.toSet().size) throw EtaBackupException(ko("备份中的模型 ID 重复", "백업의 모델 ID가 중복되었습니다."))
        if (document.selectedProviderId != null && document.selectedProviderId !in providerIds) {
            throw EtaBackupException(ko("备份中的当前提供商不存在", "백업의 현재 Provider가 존재하지 않습니다."))
        }
        val selectedModel = document.selectedModelId?.let { id -> modelIds.firstOrNull { it.first == id } }
        if (document.selectedModelId != null && selectedModel == null) throw EtaBackupException(ko("备份中的当前模型不存在", "백업의 현재 모델이 존재하지 않습니다."))
        if (selectedModel != null && selectedModel.second != document.selectedProviderId) {
            throw EtaBackupException(ko("备份中的当前模型与提供商不匹配", "백업의 현재 모델과 Provider가 일치하지 않습니다."))
        }
        if (document.conversationState != null && document.conversationState.id != ConversationStateEntity.SINGLETON_ID) {
            throw EtaBackupException(ko("备份中的会话状态无效", "백업의 대화 상태가 올바르지 않습니다."))
        }
        if (document.memoryMd.toByteArray(Charsets.UTF_8).size > 1024 * 1024) throw EtaBackupException(ko("MEMORY.md 超过 1 MiB 限制", "MEMORY.md가 1 MiB 제한을 초과했습니다."))
    }
}
