package fuck.andes.agent.skill

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.UUID

internal data class SkillRecoveryRecord(
    val id: String,
    val originalTargetExisted: Boolean,
    val backupCompleted: Boolean = false,
    val newTargetCommitted: Boolean = false,
    val registrySnapshot: SkillRegistryRecoverySnapshot = SkillRegistryRecoverySnapshot(
        skillId = id,
        entryExisted = false,
    ),
)

internal data class SkillRegistryRecoverySnapshot(
    val skillId: String,
    val entryExisted: Boolean,
    val enabled: Boolean = false,
    val source: String = "",
    val installState: String = "",
)

internal data class RecoveredSkillOperation(
    val operationDirectory: File,
    val records: List<SkillRecoveryRecord>,
)

internal class PendingSkillRecoveryJournal private constructor(
    private val operationDirectory: File,
    records: List<SkillRecoveryRecord>,
) {
    private var records = records

    fun markBackupCompleted(skillId: String) {
        update(skillId) { it.copy(backupCompleted = true) }
    }

    fun markNewTargetCommitted(skillId: String) {
        update(skillId) { it.copy(newTargetCommitted = true) }
    }

    fun clear() {
        Files.deleteIfExists(File(operationDirectory, JOURNAL_FILE_NAME).toPath())
    }

    private fun update(skillId: String, transform: (SkillRecoveryRecord) -> SkillRecoveryRecord) {
        var found = false
        records = records.map { record ->
            if (record.id == skillId) {
                found = true
                transform(record)
            } else {
                record
            }
        }
        check(found) { "복구 로그에 스킬이 없습니다: $skillId" }
        writeJournalAtomically(operationDirectory, records)
    }

    companion object {
        fun begin(
            skillsRoot: File,
            operationDirectory: File,
            records: List<SkillRecoveryRecord>,
        ): PendingSkillRecoveryJournal {
            validateOperationDirectory(skillsRoot, operationDirectory)
            require(records.isNotEmpty()) { "복구 로그에는 최소 한 개의 스킬이 필요합니다." }
            validateRecords(records)
            writeJournalAtomically(operationDirectory, records)
            return PendingSkillRecoveryJournal(operationDirectory, records)
        }
    }
}

internal class SkillRecoveryRequiredException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/** 必须在持有 [SkillMutationLock] 的跨进程锁时调用。 */
internal fun recoverPendingSkillOperations(
    skillsRoot: File,
    directoryMover: SkillDirectoryMover = AtomicSkillDirectoryMover,
): List<RecoveredSkillOperation> {
    val workRoot = skillInstallerWorkRoot(skillsRoot)
    if (!Files.exists(workRoot.toPath(), LinkOption.NOFOLLOW_LINKS)) return emptyList()
    if (!workRoot.isDirectory || Files.isSymbolicLink(workRoot.toPath())) {
        throw SkillRecoveryRequiredException("스킬 복구 디렉터리가 안전하지 않습니다.")
    }
    val operations = workRoot.listFiles()
        ?.filter { it.name.startsWith(OPERATION_PREFIX) }
        ?.sortedBy { it.name }
        ?: throw SkillRecoveryRequiredException("스킬 복구 디렉터리를 읽을 수 없습니다.")
    val recovered = operations.mapNotNull { operation ->
        val journalFile = File(operation, JOURNAL_FILE_NAME)
        if (!Files.exists(journalFile.toPath(), LinkOption.NOFOLLOW_LINKS)) return@mapNotNull null
        recoverOperation(skillsRoot, operation, journalFile, directoryMover)
    }
    val duplicateIds = recovered
        .flatMap { it.records }
        .groupBy { it.id }
        .filterValues { it.size > 1 }
        .keys
    if (duplicateIds.isNotEmpty()) {
        recoveryFailure("여러 복구 작업에 동일한 스킬이 포함되어 있습니다.")
    }
    return recovered
}

private fun recoverOperation(
    skillsRoot: File,
    operation: File,
    journalFile: File,
    directoryMover: SkillDirectoryMover,
): RecoveredSkillOperation {
    try {
        validateOperationDirectory(skillsRoot, operation)
        if (Files.isSymbolicLink(journalFile.toPath()) || !journalFile.isFile) {
            recoveryFailure("스킬 복구 로그가 일반 파일이 아닙니다.")
        }
        if (journalFile.length() !in 1..MAX_JOURNAL_BYTES) {
            recoveryFailure("스킬 복구 로그 크기가 올바르지 않습니다.")
        }
        val records = parseJournal(journalFile)
        val backupRoot = File(operation, BACKUP_DIRECTORY_NAME)
        records.forEach { record ->
            val target = File(skillsRoot, record.id)
            val backup = File(backupRoot, record.id)
            val backupExists = Files.exists(backup.toPath(), LinkOption.NOFOLLOW_LINKS)
            if (record.originalTargetExisted) {
                when {
                    backupExists -> restoreBackup(
                        skillsRoot,
                        operation,
                        backupRoot,
                        record.id,
                        directoryMover,
                    )
                    record.backupCompleted -> recoveryFailure("이전 스킬 백업이 없습니다: ${record.id}")
                    !isSafeExistingTarget(skillsRoot, target) ->
                        recoveryFailure("이전 스킬 대상과 복구 로그가 일치하지 않습니다: ${record.id}")
                }
            } else {
                if (backupExists) recoveryFailure("새 스킬에 이전 백업이 존재하면 안 됩니다: ${record.id}")
                if (!deleteSkillPathWithoutFollowingLinks(skillsRoot, target)) {
                    recoveryFailure("설치가 완료되지 않은 스킬을 제거할 수 없습니다: ${record.id}")
                }
            }
        }
        return RecoveredSkillOperation(operationDirectory = operation, records = records)
    } catch (error: SkillRecoveryRequiredException) {
        throw error
    } catch (error: Exception) {
        throw SkillRecoveryRequiredException("스킬 자동 복구에 실패했습니다.", error)
    }
}

internal fun completeRecoveredSkillOperations(
    skillsRoot: File,
    recovered: List<RecoveredSkillOperation>,
) {
    val workRoot = skillInstallerWorkRoot(skillsRoot)
    recovered.forEach { recovery ->
        val operation = recovery.operationDirectory
        validateOperationDirectory(skillsRoot, operation)
        Files.deleteIfExists(File(operation, JOURNAL_FILE_NAME).toPath())
        // journal 删除即表示文件与 registry 已共同恢复完成；残留的无 journal 暂存目录
        // 不再影响索引，清理失败也不能把已经完成的恢复重新标记为待处理。
        deleteSkillPathWithoutFollowingLinks(workRoot, operation)
    }
}

internal fun createSkillRecoveryOperationDirectory(skillsRoot: File): File {
    val workRoot = prepareSkillInstallerWorkRoot(skillsRoot)
    repeat(8) {
        val operation = File(workRoot, "$OPERATION_PREFIX${UUID.randomUUID()}")
        if (operation.mkdir()) return operation
    }
    throw IOException("스킬 설치 트랜잭션을 생성할 수 없습니다.")
}

private fun restoreBackup(
    skillsRoot: File,
    operation: File,
    backupRoot: File,
    skillId: String,
    directoryMover: SkillDirectoryMover,
) {
    if (
        Files.isSymbolicLink(backupRoot.toPath()) || !backupRoot.isDirectory ||
        !isStrictChild(operation, backupRoot)
    ) {
        recoveryFailure("스킬 백업 루트 디렉터리가 안전하지 않습니다.")
    }
    val backup = File(backupRoot, skillId)
    if (
        Files.isSymbolicLink(backup.toPath()) || !backup.isDirectory ||
        !isStrictChild(backupRoot, backup)
    ) {
        recoveryFailure("이전 스킬 백업이 안전하지 않습니다: $skillId")
    }
    val recoveryRoot = File(operation, RECOVERY_DIRECTORY_NAME)
    if (!recoveryRoot.mkdirs() && !recoveryRoot.isDirectory) {
        recoveryFailure("스킬 복구 임시 디렉터리를 생성할 수 없습니다.")
    }
    if (Files.isSymbolicLink(recoveryRoot.toPath()) || !isStrictChild(operation, recoveryRoot)) {
        recoveryFailure("스킬 복구 임시 디렉터리가 안전하지 않습니다.")
    }
    val staging = File(recoveryRoot, skillId)
    if (!deleteSkillPathWithoutFollowingLinks(operation, staging)) {
        recoveryFailure("스킬 복구 임시 디렉터리를 정리할 수 없습니다.")
    }
    copyDirectoryWithoutFollowingLinks(backup, staging)

    val target = File(skillsRoot, skillId)
    if (!deleteSkillPathWithoutFollowingLinks(skillsRoot, target)) {
        recoveryFailure("제출이 완료되지 않은 스킬을 정리할 수 없습니다: $skillId")
    }
    directoryMover.move(staging, target)
}

private fun copyDirectoryWithoutFollowingLinks(source: File, target: File) {
    if (Files.isSymbolicLink(source.toPath()) || !source.isDirectory) {
        recoveryFailure("스킬 백업 디렉터리가 안전하지 않습니다.")
    }
    if (!target.mkdir()) recoveryFailure("스킬 복구 복사본을 생성할 수 없습니다.")
    val children = source.listFiles() ?: recoveryFailure("스킬 백업 디렉터리를 읽을 수 없습니다.")
    children.forEach { child ->
        if (Files.isSymbolicLink(child.toPath())) {
            recoveryFailure("스킬 백업에 심볼릭 링크가 포함되어 있습니다.")
        }
        val destination = File(target, child.name)
        when {
            child.isDirectory -> copyDirectoryWithoutFollowingLinks(child, destination)
            child.isFile -> Files.copy(child.toPath(), destination.toPath())
            else -> recoveryFailure("스킬 백업에 지원하지 않는 파일 유형이 포함되어 있습니다.")
        }
    }
}

private fun parseJournal(journalFile: File): List<SkillRecoveryRecord> {
    val json = runCatching { JSONObject(journalFile.readText(Charsets.UTF_8)) }
        .getOrElse { recoveryFailure("스킬 복구 로그 형식이 올바르지 않습니다.") }
    if (json.optInt("version", -1) != JOURNAL_VERSION) {
        recoveryFailure("스킬 복구 로그 버전이 지원되지 않습니다.")
    }
    val entries = json.optJSONArray("skills") ?: recoveryFailure("스킬 복구 로그에 skills 항목이 없습니다.")
    if (entries.length() !in 1..MAX_JOURNAL_SKILLS) {
        recoveryFailure("스킬 복구 로그 항목 수가 올바르지 않습니다.")
    }
    val records = (0 until entries.length()).map { index ->
        val entry = entries.optJSONObject(index) ?: recoveryFailure("스킬 복구 로그 항목이 올바르지 않습니다.")
        val id = entry.optString("id")
        if (!entry.has("originalTargetExisted") || entry.opt("originalTargetExisted") !is Boolean) {
            recoveryFailure("스킬 복구 로그에 원래 대상 상태가 없습니다.")
        }
        if (!entry.has("backupCompleted") || entry.opt("backupCompleted") !is Boolean) {
            recoveryFailure("스킬 복구 로그에 백업 상태가 없습니다.")
        }
        if (!entry.has("newTargetCommitted") || entry.opt("newTargetCommitted") !is Boolean) {
            recoveryFailure("스킬 복구 로그에 제출 상태가 없습니다.")
        }
        SkillRecoveryRecord(
            id = id,
            originalTargetExisted = entry.getBoolean("originalTargetExisted"),
            backupCompleted = entry.getBoolean("backupCompleted"),
            newTargetCommitted = entry.getBoolean("newTargetCommitted"),
            registrySnapshot = parseRegistrySnapshot(entry, id),
        )
    }
    validateRecords(records)
    return records
}

private fun writeJournalAtomically(
    operationDirectory: File,
    records: List<SkillRecoveryRecord>,
) {
    val json = JSONObject()
        .put("version", JOURNAL_VERSION)
        .put(
            "skills",
            JSONArray().apply {
                records.forEach { record ->
                    put(
                        JSONObject()
                            .put("id", record.id)
                            .put("originalTargetExisted", record.originalTargetExisted)
                            .put("backupCompleted", record.backupCompleted)
                            .put("newTargetCommitted", record.newTargetCommitted)
                            .put(
                                "registry",
                                JSONObject()
                                    .put("entryExisted", record.registrySnapshot.entryExisted)
                                    .put("enabled", record.registrySnapshot.enabled)
                                    .put("source", record.registrySnapshot.source)
                                    .put("installState", record.registrySnapshot.installState)
                            )
                    )
                }
            }
        )
    val journal = File(operationDirectory, JOURNAL_FILE_NAME)
    val temporary = File(operationDirectory, ".$JOURNAL_FILE_NAME-${UUID.randomUUID()}")
    try {
        FileOutputStream(temporary).use { output ->
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        try {
            Files.move(
                temporary.toPath(),
                journal.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temporary.toPath(),
                journal.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    } finally {
        Files.deleteIfExists(temporary.toPath())
    }
}

private fun validateOperationDirectory(skillsRoot: File, operationDirectory: File) {
    val workRoot = skillInstallerWorkRoot(skillsRoot)
    if (
        Files.isSymbolicLink(operationDirectory.toPath()) || !operationDirectory.isDirectory ||
        operationDirectory.parentFile?.canonicalFile != workRoot.canonicalFile ||
        !OPERATION_NAME_REGEX.matches(operationDirectory.name)
    ) {
        recoveryFailure("스킬 작업 디렉터리가 안전하지 않습니다.")
    }
}

private fun validateRecords(records: List<SkillRecoveryRecord>) {
    if (records.map { it.id }.distinct().size != records.size) {
        recoveryFailure("스킬 복구 로그에 중복된 ID가 포함되어 있습니다.")
    }
    records.forEach { record ->
        if (record.id.length !in 1..64 || !SKILL_ID_REGEX.matches(record.id)) {
            recoveryFailure("스킬 복구 로그에 잘못된 ID가 포함되어 있습니다.")
        }
        if (!record.originalTargetExisted && record.backupCompleted) {
            recoveryFailure("새 스킬의 복구 로그에 잘못된 백업 상태가 포함되어 있습니다.")
        }
        if (record.originalTargetExisted && record.newTargetCommitted && !record.backupCompleted) {
            recoveryFailure("스킬 교체 복구 로그 상태가 일치하지 않습니다.")
        }
        val registry = record.registrySnapshot
        if (registry.skillId != record.id) recoveryFailure("스킬 복구 로그의 registry ID가 일치하지 않습니다.")
        if (registry.entryExisted) {
            if (
                registry.source !in VALID_REGISTRY_SOURCES ||
                registry.installState !in VALID_INSTALL_STATES ||
                (registry.source == USER_SKILL_SOURCE &&
                    registry.installState != INSTALL_STATE_INSTALLED_VALUE)
            ) {
                recoveryFailure("스킬 복구 로그에 잘못된 registry 상태가 포함되어 있습니다.")
            }
        } else if (
            registry.enabled || registry.source.isNotEmpty() || registry.installState.isNotEmpty()
        ) {
            recoveryFailure("존재하지 않는 registry 스냅샷에 추가 상태가 포함되어 있습니다.")
        }
    }
}

private fun parseRegistrySnapshot(
    entry: JSONObject,
    skillId: String,
): SkillRegistryRecoverySnapshot {
    val registry = entry.optJSONObject("registry")
        ?: recoveryFailure("스킬 복구 로그에 registry 스냅샷이 없습니다.")
    val booleanKeys = listOf("entryExisted", "enabled")
    if (booleanKeys.any { key -> !registry.has(key) || registry.opt(key) !is Boolean }) {
        recoveryFailure("스킬 복구 로그의 registry 스냅샷이 유효하지 않습니다.")
    }
    if (!registry.has("source") || registry.opt("source") !is String) {
        recoveryFailure("스킬 복구 로그의 registry 소스가 유효하지 않습니다.")
    }
    if (!registry.has("installState") || registry.opt("installState") !is String) {
        recoveryFailure("스킬 복구 로그의 registry 설치 상태가 유효하지 않습니다.")
    }
    return SkillRegistryRecoverySnapshot(
        skillId = skillId,
        entryExisted = registry.getBoolean("entryExisted"),
        enabled = registry.getBoolean("enabled"),
        source = registry.getString("source"),
        installState = registry.getString("installState"),
    )
}

private fun isSafeExistingTarget(skillsRoot: File, target: File): Boolean =
    !Files.isSymbolicLink(target.toPath()) && target.isDirectory && isStrictChild(skillsRoot, target)

private fun isStrictChild(root: File, target: File): Boolean {
    val rootPath = root.canonicalFile.toPath()
    val targetPath = runCatching { target.canonicalFile.toPath() }.getOrNull() ?: return false
    return targetPath.startsWith(rootPath) && targetPath != rootPath
}

internal fun skillInstallerWorkRoot(skillsRoot: File): File = File(
    requireNotNull(skillsRoot.canonicalFile.parentFile) { "Skills 디렉터리에는 상위 디렉터리가 필요합니다." },
    ".eta-skill-installer",
)

private fun recoveryFailure(message: String): Nothing =
    throw SkillRecoveryRequiredException(message)

private const val JOURNAL_VERSION = 1
internal const val JOURNAL_FILE_NAME = "pending-install.json"
private const val BACKUP_DIRECTORY_NAME = "backup"
private const val RECOVERY_DIRECTORY_NAME = "recovery"
private const val OPERATION_PREFIX = "operation-"
private const val MAX_JOURNAL_BYTES = 256L * 1024L
private const val MAX_JOURNAL_SKILLS = 2_048
private const val INSTALL_STATE_INSTALLED_VALUE = "installed"
private const val INSTALL_STATE_REMOVED_BUILTIN_VALUE = "removed_builtin"
private val VALID_REGISTRY_SOURCES = setOf(USER_SKILL_SOURCE, BUILTIN_SKILL_SOURCE)
private val VALID_INSTALL_STATES = setOf(
    INSTALL_STATE_INSTALLED_VALUE,
    INSTALL_STATE_REMOVED_BUILTIN_VALUE,
)
private val OPERATION_NAME_REGEX = Regex("^operation-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
private val SKILL_ID_REGEX = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")
