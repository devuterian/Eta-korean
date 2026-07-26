package fuck.andes.agent.skill

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

data class SkillPackageLimits(
    val maxArchiveBytes: Long = 32L * 1024L * 1024L,
    val maxExtractedBytes: Long = 128L * 1024L * 1024L,
    val maxSingleFileBytes: Long = 32L * 1024L * 1024L,
    val maxSkillFileBytes: Long = 512L * 1024L,
    val maxEntries: Int = 2_048,
    val maxPathDepth: Int = 16,
)

/**
 * Skill ZIP 安装器。
 *
 * 所有内容先落入 skills 目录外的私有暂存区，完成路径、配额和 Skill 元数据验证后才会
 * 移入正式目录。批量替换会先备份旧目录；任一步失败都会删除新目录并恢复备份。
 */
class SkillPackageInstaller internal constructor(
    skillsRoot: File,
    private val indexService: SkillIndexService,
    private val limits: SkillPackageLimits = SkillPackageLimits(),
    private val directoryMover: SkillDirectoryMover = AtomicSkillDirectoryMover,
    private val builtinIdLookup: (String) -> Boolean = indexService::isBuiltinSkillId,
) {
    private val canonicalSkillsRoot = skillsRoot.canonicalFile
    private val workRoot = File(
        requireNotNull(canonicalSkillsRoot.parentFile) { "Skills 디렉터리에는 상위 디렉터리가 필요합니다." },
        ".eta-skill-installer",
    )

    fun installLocalZip(
        openStream: () -> InputStream,
        replaceUserSkill: Boolean = false,
        expectedReplacementId: String? = null,
        expectedArchiveSha256: String? = null,
        isCancelled: () -> Boolean = { false },
    ): SkillInstallResult = withArchive(openStream, isCancelled) { operation, extractedRoot ->
        val candidates = discoverCandidates(extractedRoot, extractedRoot, isCancelled)
        when {
            candidates.isEmpty() -> fail(
                SkillInstallErrorCode.NO_SKILL_FOUND,
                "ZIP 파일에서 SKILL.md를 찾을 수 없습니다.",
            )
            candidates.size > 1 -> fail(
                SkillInstallErrorCode.MULTIPLE_SKILLS_FOUND,
                "로컬 ZIP에는 스킬이 하나만 있어야 합니다. 현재 ${candidates.size}개가 발견되었습니다.",
            )
        }
        if (replaceUserSkill) {
            val expectedId = expectedReplacementId?.trim().orEmpty()
            val expectedDigest = expectedArchiveSha256?.trim().orEmpty()
            if (expectedId.isBlank()) {
                fail(
                    SkillInstallErrorCode.INVALID_SELECTION,
                    "사용자 스킬을 교체할 때 확인된 스킬 ID를 지정해야 합니다.",
                )
            }
            if (candidates.single().id != expectedId) {
                fail(
                    SkillInstallErrorCode.INVALID_SELECTION,
                    "다시 읽은 ZIP이 확인된 교체 스킬과 일치하지 않습니다.",
                )
            }
            if (!SHA_256_REGEX.matches(expectedDigest)) {
                fail(
                    SkillInstallErrorCode.INVALID_SELECTION,
                    "사용자 스킬 교체 시 유효한 소문자 SHA-256을 제공해야 합니다.",
                )
            }
            if (operation.archiveSha256 != expectedDigest) {
                fail(
                    SkillInstallErrorCode.INVALID_SELECTION,
                    "다시 읽은 ZIP 내용이 확인된 아카이브와 일치하지 않습니다.",
                )
            }
        } else if (expectedReplacementId != null || expectedArchiveSha256 != null) {
            fail(
                SkillInstallErrorCode.INVALID_SELECTION,
                "기존 사용자 스킬 교체 시에만 교체 ID와 아카이브 요약을 지정할 수 있습니다.",
            )
        }
        installCandidates(
            operation = operation,
            candidates = candidates,
            replaceUserSkills = replaceUserSkill,
            isCancelled = isCancelled,
            conflictArchiveSha256 = operation.archiveSha256,
        )
    }

    /** 检查仓库 ZIP。单一顶层目录按 GitHub codeload 的 envelope 处理。 */
    fun inspectRepositoryZip(
        openStream: () -> InputStream,
        isCancelled: () -> Boolean = { false },
    ): SkillArchiveInspectionResult = withArchiveInspection(openStream, isCancelled) { extractedRoot ->
        val repositoryRoot = repositoryContentRoot(extractedRoot)
        val candidates = discoverCandidates(repositoryRoot, repositoryRoot, isCancelled)
        if (candidates.isEmpty()) {
            fail(SkillInstallErrorCode.NO_SKILL_FOUND, "저장소 ZIP에서 SKILL.md를 찾을 수 없습니다.")
        }
        SkillArchiveInspectionResult.Success(candidates.map { it.publicModel })
    }

    /** selectedPaths 使用 [inspectRepositoryZip] 返回的仓库相对路径。 */
    fun installRepositoryZip(
        openStream: () -> InputStream,
        selectedPaths: List<String>,
        replaceUserSkills: Boolean = false,
        expectedReplacementIds: Set<String> = emptySet(),
        isCancelled: () -> Boolean = { false },
    ): SkillInstallResult {
        val selectedPathsSnapshot = selectedPaths.toList()
        val expectedIdsSnapshot = expectedReplacementIds.toSet()
        return withArchive(openStream, isCancelled) { operation, extractedRoot ->
            if (selectedPathsSnapshot.isEmpty()) {
                fail(SkillInstallErrorCode.INVALID_SELECTION, "스킬 경로를 최소 하나 선택하세요.")
            }
            val repositoryRoot = repositoryContentRoot(extractedRoot)
            val allCandidates = discoverCandidates(repositoryRoot, repositoryRoot, isCancelled)
            if (allCandidates.isEmpty()) {
                fail(SkillInstallErrorCode.NO_SKILL_FOUND, "저장소 ZIP에서 SKILL.md를 찾을 수 없습니다.")
            }
            val candidatesByPath = allCandidates.associateBy { it.relativePath }
            val normalizedSelections = selectedPathsSnapshot.map(::normalizeSelectionPath)
            if (normalizedSelections.distinct().size != normalizedSelections.size) {
                fail(SkillInstallErrorCode.INVALID_SELECTION, "선택한 스킬 경로에 중복이 있습니다.")
            }
            val selected = normalizedSelections.map { relativePath ->
                candidatesByPath[relativePath]
                    ?: fail(
                        SkillInstallErrorCode.INVALID_SELECTION,
                        "선택한 경로가 유효한 스킬 루트 디렉터리가 아닙니다: $relativePath",
                    )
            }
            rejectNestedCandidateSelections(selected, allCandidates)
            val selectedIds = selected.mapTo(linkedSetOf()) { it.id }
            if (replaceUserSkills) {
                if (expectedIdsSnapshot.isEmpty() || selectedIds != expectedIdsSnapshot) {
                    fail(
                        SkillInstallErrorCode.INVALID_SELECTION,
                        "다시 불러온 저장소 스킬과 이미 확인된 교체 스킬 집합이 일치하지 않습니다.",
                    )
                }
            } else if (expectedIdsSnapshot.isNotEmpty()) {
                fail(
                    SkillInstallErrorCode.INVALID_SELECTION,
                    "기존 사용자 스킬을 교체할 때만 expectedReplacementIds를 지정할 수 있습니다.",
                )
            }
            installCandidates(
                operation = operation,
                candidates = selected,
                replaceUserSkills = replaceUserSkills,
                isCancelled = isCancelled,
                conflictArchiveSha256 = null,
            )
        }
    }

    private fun installCandidates(
        operation: ArchiveOperation,
        candidates: List<PreparedCandidate>,
        replaceUserSkills: Boolean,
        isCancelled: () -> Boolean,
        conflictArchiveSha256: String?,
    ): SkillInstallResult = indexService.withMutationLock {
        checkCancelled(isCancelled)
        installCandidatesLocked(
            operation,
            candidates,
            replaceUserSkills,
            isCancelled,
            conflictArchiveSha256,
        )
    }

    private fun installCandidatesLocked(
        operation: ArchiveOperation,
        candidates: List<PreparedCandidate>,
        replaceUserSkills: Boolean,
        isCancelled: () -> Boolean,
        conflictArchiveSha256: String?,
    ): SkillInstallResult {
        val duplicateIds = candidates.groupBy { it.id }.filterValues { it.size > 1 }.keys
        if (duplicateIds.isNotEmpty()) {
            fail(
                SkillInstallErrorCode.DUPLICATE_SKILL_ID,
                "선택한 스킬에 중복된 이름이 있습니다: ${duplicateIds.sorted().joinToString()}",
            )
        }

        val installedEntries = indexService.listSkillsForManagement(forceRefresh = true)
            .associateBy { it.id }
        val conflicts = mutableListOf<SkillInstallConflict>()
        candidates.forEach { candidate ->
            val target = File(canonicalSkillsRoot, candidate.id)
            val existing = installedEntries[candidate.id]
            when {
                builtinIdLookup(candidate.id) -> conflicts += SkillInstallConflict(
                    id = candidate.id,
                    name = candidate.name,
                    existingSource = BUILTIN_SKILL_SOURCE,
                    replaceAllowed = false,
                )
                existing != null && !replaceUserSkills -> conflicts += SkillInstallConflict(
                    id = candidate.id,
                    name = candidate.name,
                    existingSource = existing.source,
                    replaceAllowed = existing.source == USER_SKILL_SOURCE &&
                        isReplaceableTarget(existing, target),
                )
                existing != null && !isReplaceableTarget(existing, target) -> conflicts +=
                    SkillInstallConflict(
                        id = candidate.id,
                        name = candidate.name,
                        existingSource = existing.source,
                        replaceAllowed = false,
                    )
                existing == null && target.exists() -> conflicts += SkillInstallConflict(
                    id = candidate.id,
                    name = candidate.name,
                    existingSource = "unknown",
                    replaceAllowed = false,
                )
            }
        }
        if (conflicts.isNotEmpty()) {
            return SkillInstallResult.Conflict(
                conflicts = conflicts.sortedBy { it.id },
                archiveSha256 = conflictArchiveSha256,
            )
        }

        // 从此处开始进入不可中断的短事务；取消只在任何正式文件变更发生前生效。
        checkCancelled(isCancelled)
        if (!canonicalSkillsRoot.exists() && !canonicalSkillsRoot.mkdirs()) {
            fail(SkillInstallErrorCode.IO_ERROR, "스킬 디렉터리를 생성할 수 없습니다.")
        }
        val backupRoot = File(operation.directory, "backup")
        if (!backupRoot.mkdir()) {
            fail(SkillInstallErrorCode.IO_ERROR, "설치 백업 디렉터리를 생성할 수 없습니다.")
        }
        val registrySnapshots = indexService
            .captureRegistryRecoverySnapshots(candidates.map { it.id })
            .associateBy { it.skillId }
        var recoveryJournal: PendingSkillRecoveryJournal? = null
        try {
            recoveryJournal = PendingSkillRecoveryJournal.begin(
                skillsRoot = canonicalSkillsRoot,
                operationDirectory = operation.directory,
                records = candidates.map { candidate ->
                    SkillRecoveryRecord(
                        id = candidate.id,
                        originalTargetExisted = Files.exists(
                            File(canonicalSkillsRoot, candidate.id).toPath(),
                            LinkOption.NOFOLLOW_LINKS,
                        ),
                        registrySnapshot = checkNotNull(registrySnapshots[candidate.id]),
                    )
                },
            )
            candidates.forEach { candidate ->
                val target = File(canonicalSkillsRoot, candidate.id)
                if (target.exists()) {
                    val backup = File(backupRoot, candidate.id)
                    directoryMover.move(target, backup)
                    recoveryJournal.markBackupCompleted(candidate.id)
                }
            }
            candidates.forEach { candidate ->
                val target = File(canonicalSkillsRoot, candidate.id)
                directoryMover.move(candidate.directory, target)
                recoveryJournal.markNewTargetCommitted(candidate.id)
            }
            indexService.registerInstalledUserSkills(candidates.map { it.id })
            recoveryJournal.clear()
        } catch (error: Exception) {
            val journalExists = Files.exists(
                File(operation.directory, JOURNAL_FILE_NAME).toPath(),
                LinkOption.NOFOLLOW_LINKS,
            )
            val rollbackComplete = !journalExists || runCatching {
                val recovered = recoverPendingSkillOperations(canonicalSkillsRoot, directoryMover)
                indexService.restoreRecoveredRegistry(recovered)
                completeRecoveredSkillOperations(canonicalSkillsRoot, recovered)
            }.isSuccess
            if (!rollbackComplete) operation.preserveForRecovery = true
            val suffix = if (rollbackComplete) ", 이전 스킬이 복원되었습니다." else ", 자동 복원이 완전히 완료되지 않았습니다."
            return SkillInstallResult.Failure(
                SkillInstallError(
                    code = SkillInstallErrorCode.COMMIT_FAILED,
                    message = "스킬 설치 제출에 실패했습니다$suffix",
                ),
                recoveryRequired = !rollbackComplete,
            )
        }

        return SkillInstallResult.Success(
            installed = candidates.map {
                InstalledSkill(id = it.id, name = it.name, description = it.description)
            }
        )
    }

    private fun isReplaceableTarget(entry: SkillIndexEntry, expectedTarget: File): Boolean {
        if (entry.source != USER_SKILL_SOURCE || !entry.installed) return false
        val existingRoot = runCatching { File(entry.rootPath).canonicalFile }.getOrNull() ?: return false
        if (existingRoot != expectedTarget.canonicalFile || !existingRoot.isDirectory) return false
        return isRecoverableSkillDirectoryTree(canonicalSkillsRoot, existingRoot)
    }

    private fun rejectNestedCandidateSelections(
        selected: List<PreparedCandidate>,
        allCandidates: List<PreparedCandidate>,
    ) {
        selected.forEach { parent ->
            val nested = allCandidates.firstOrNull { candidate ->
                candidate !== parent && candidate.directory.toPath().startsWith(parent.directory.toPath())
            }
            if (nested != null) {
                fail(
                    SkillInstallErrorCode.INVALID_SELECTION,
                    "스킬 ${parent.relativePath} 내에 또 다른 SKILL.md가 있습니다: ${nested.relativePath}",
                )
            }
        }
    }

    private fun discoverCandidates(
        scanRoot: File,
        relativeRoot: File,
        isCancelled: () -> Boolean,
    ): List<PreparedCandidate> {
        val skillFiles = scanRoot.walkTopDown()
            .onEnter { !Files.isSymbolicLink(it.toPath()) }
            .filter { it.isFile && it.name == SKILL_FILE_NAME }
            .toList()
        return skillFiles.map { skillFile ->
            checkCancelled(isCancelled)
            val directory = requireNotNull(skillFile.parentFile).canonicalFile
            val relativePath = directory.relativeTo(relativeRoot.canonicalFile)
                .invariantSeparatorsPath
                .ifBlank { "." }
            val metadata = parseAndValidateSkill(skillFile)
            PreparedCandidate(
                id = metadata.name,
                name = metadata.name,
                description = metadata.description,
                relativePath = relativePath,
                directory = directory,
            )
        }.sortedBy { it.relativePath }
    }

    private fun parseAndValidateSkill(skillFile: File): ValidatedSkillMetadata {
        if (skillFile.length() > limits.maxSkillFileBytes) {
            fail(
                SkillInstallErrorCode.INVALID_SKILL,
                "${skillFile.name} 파일이 ${limits.maxSkillFileBytes}바이트 제한을 초과했습니다.",
            )
        }
        val raw = readStrictUtf8(skillFile, limits.maxSkillFileBytes)
            ?: fail(SkillInstallErrorCode.INVALID_SKILL, "SKILL.md는 반드시 UTF-8 텍스트여야 합니다.")
        val frontmatter = strictFrontmatter(raw)
            ?: fail(
                SkillInstallErrorCode.INVALID_SKILL,
                "SKILL.md에는 ---로 감싼 YAML 프론트매터가 포함되어야 합니다.",
            )
        val parsed = SkillParser.parseSimpleFrontmatter(frontmatter)
        val name = parsed["name"]?.trim().orEmpty()
        val description = parsed["description"]?.trim().orEmpty()
        if (name.length !in 1..MAX_SKILL_NAME_LENGTH || !SKILL_NAME_REGEX.matches(name)) {
            fail(
                SkillInstallErrorCode.INVALID_SKILL,
                "스킬 이름은 소문자, 숫자, 단일 하이픈만 사용하며 $MAX_SKILL_NAME_LENGTH자 이하여야 합니다.",
            )
        }
        if (description.isBlank() || description.length > MAX_SKILL_DESCRIPTION_LENGTH) {
            fail(
                SkillInstallErrorCode.INVALID_SKILL,
                "스킬 설명은 필수이며 $MAX_SKILL_DESCRIPTION_LENGTH자 이하여야 합니다.",
            )
        }
        return ValidatedSkillMetadata(name = name, description = description)
    }

    private fun strictFrontmatter(raw: String): String? {
        val firstLineEnd = raw.indexOf('\n')
        if (firstLineEnd < 0 || raw.substring(0, firstLineEnd).trimEnd('\r') != "---") return null
        var lineStart = firstLineEnd + 1
        while (lineStart <= raw.length) {
            val lineEnd = raw.indexOf('\n', lineStart).let { if (it < 0) raw.length else it }
            if (raw.substring(lineStart, lineEnd).trimEnd('\r') == "---") {
                return raw.substring(firstLineEnd + 1, lineStart).trimEnd('\r', '\n')
            }
            if (lineEnd == raw.length) break
            lineStart = lineEnd + 1
        }
        return null
    }

    private fun repositoryContentRoot(extractedRoot: File): File {
        val children = extractedRoot.listFiles().orEmpty()
        return children.singleOrNull()?.takeIf { it.isDirectory } ?: extractedRoot
    }

    private fun normalizeSelectionPath(raw: String): String {
        val value = raw.trim().removeSuffix("/")
        if (value == ".") return value
        val segments = validateRelativePath(value, SkillInstallErrorCode.INVALID_SELECTION)
        return segments.joinToString("/")
    }

    private fun extractArchive(
        archiveFile: File,
        targetRoot: File,
        isCancelled: () -> Boolean,
    ) {
        if (!targetRoot.mkdir()) {
            fail(SkillInstallErrorCode.IO_ERROR, "ZIP 임시 디렉터리를 생성할 수 없습니다.")
        }
        val pathKinds = linkedMapOf<String, Boolean>()
        var entryCount = 0
        var extractedBytes = 0L
        ZipInputStream(archiveFile.inputStream().buffered()).use { zip ->
            while (true) {
                checkCancelled(isCancelled)
                val entry = zip.nextEntry ?: break
                entryCount += 1
                if (entryCount > limits.maxEntries) {
                    fail(
                        SkillInstallErrorCode.TOO_MANY_ENTRIES,
                        "ZIP 항목 수가 ${limits.maxEntries}개를 초과했습니다.",
                    )
                }
                val normalizedName = entry.name.removeSuffix("/")
                val segments = validateRelativePath(
                    normalizedName,
                    SkillInstallErrorCode.UNSAFE_ENTRY_PATH,
                )
                if (segments.size > limits.maxPathDepth) {
                    fail(
                        SkillInstallErrorCode.ENTRY_PATH_TOO_DEEP,
                        "ZIP 항목 경로 깊이가 ${limits.maxPathDepth}단계를 초과했습니다.",
                    )
                }
                val relativePath = segments.joinToString("/")
                val collisionKey = collisionKey(relativePath)
                if (pathKinds.containsKey(collisionKey)) {
                    fail(SkillInstallErrorCode.DUPLICATE_ENTRY, "ZIP에 중복된 항목이 있습니다: $relativePath")
                }
                val ancestorKeys = segments.indices.drop(1).map { index ->
                    collisionKey(segments.take(index).joinToString("/"))
                }
                if (ancestorKeys.any { pathKinds[it] == false }) {
                    fail(SkillInstallErrorCode.DUPLICATE_ENTRY, "ZIP 항목에 파일과 디렉터리 충돌이 있습니다: $relativePath")
                }
                if (!entry.isDirectory && pathKinds.keys.any { it.startsWith("$collisionKey/") }) {
                    fail(SkillInstallErrorCode.DUPLICATE_ENTRY, "ZIP 항목에 파일과 디렉터리 충돌이 있습니다: $relativePath")
                }
                pathKinds[collisionKey] = entry.isDirectory

                val target = File(targetRoot, relativePath)
                ensureWithin(targetRoot, target)
                if (entry.isDirectory) {
                    if (!target.mkdirs() && !target.isDirectory) {
                        fail(SkillInstallErrorCode.IO_ERROR, "ZIP 디렉터리를 생성할 수 없습니다.")
                    }
                    if (zip.read() != -1) {
                        fail(SkillInstallErrorCode.INVALID_ARCHIVE, "ZIP 디렉터리 항목에 데이터가 포함되어 있습니다.")
                    }
                } else {
                    if (entry.size > limits.maxSingleFileBytes) {
                        fail(
                            SkillInstallErrorCode.ENTRY_TOO_LARGE,
                            "ZIP 단일 파일이 ${limits.maxSingleFileBytes} 바이트 제한을 초과했습니다.",
                        )
                    }
                    target.parentFile?.let { parent ->
                        if (!parent.mkdirs() && !parent.isDirectory) {
                            fail(SkillInstallErrorCode.IO_ERROR, "ZIP 파일 디렉터리를 생성할 수 없습니다.")
                        }
                    }
                    var fileBytes = 0L
                    target.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            checkCancelled(isCancelled)
                            val count = zip.read(buffer)
                            if (count < 0) break
                            fileBytes += count
                            extractedBytes += count
                            if (fileBytes > limits.maxSingleFileBytes) {
                                fail(
                                    SkillInstallErrorCode.ENTRY_TOO_LARGE,
                                    "ZIP 단일 파일이 ${limits.maxSingleFileBytes} 바이트 제한을 초과했습니다.",
                                )
                            }
                            if (extractedBytes > limits.maxExtractedBytes) {
                                fail(
                                    SkillInstallErrorCode.EXTRACTED_CONTENT_TOO_LARGE,
                                    "ZIP 압축 해제 내용이 ${limits.maxExtractedBytes} 바이트 제한을 초과했습니다.",
                                )
                            }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        if (entryCount == 0) {
            fail(SkillInstallErrorCode.INVALID_ARCHIVE, "ZIP이 비어있거나 형식이 올바르지 않습니다.")
        }
    }

    private fun materializeArchive(
        openStream: () -> InputStream,
        target: File,
        isCancelled: () -> Boolean,
    ): String {
        var total = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        checkCancelled(isCancelled)
        openStream().use { input ->
            target.outputStream().buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    checkCancelled(isCancelled)
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > limits.maxArchiveBytes) {
                        fail(
                            SkillInstallErrorCode.ARCHIVE_TOO_LARGE,
                            "ZIP 압축 파일이 ${limits.maxArchiveBytes} 바이트 제한을 초과했습니다.",
                        )
                    }
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
            }
        }
        if (total == 0L) {
            fail(SkillInstallErrorCode.INVALID_ARCHIVE, "ZIP이 비어 있습니다.")
        }
        return digest.digest().joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    private fun validateRelativePath(
        raw: String,
        errorCode: SkillInstallErrorCode,
    ): List<String> {
        if (
            raw.isBlank() || raw.startsWith('/') || raw.startsWith('\\') ||
            WINDOWS_DRIVE_PREFIX.containsMatchIn(raw) || raw.contains('\\') || raw.contains('\u0000') ||
            raw.any { it.isISOControl() }
        ) {
            fail(errorCode, "경로가 안전한 상대 경로가 아닙니다.")
        }
        val segments = raw.split('/')
        if (
            segments.any { segment ->
                segment.isBlank() || segment == "." || segment == ".." ||
                    segment.length > MAX_PATH_SEGMENT_LENGTH
            }
        ) {
            fail(errorCode, "경로에 잘못된 계층이 포함되어 있습니다.")
        }
        return segments
    }

    private fun collisionKey(path: String): String = Normalizer
        .normalize(path, Normalizer.Form.NFC)
        .lowercase(Locale.ROOT)

    private fun ensureWithin(root: File, target: File) {
        val rootPath = root.canonicalFile.toPath()
        val targetPath = target.canonicalFile.toPath()
        if (!targetPath.startsWith(rootPath) || targetPath == rootPath) {
            fail(SkillInstallErrorCode.UNSAFE_ENTRY_PATH, "ZIP 항목이 임시 디렉터리 밖으로 쓰기를 시도했습니다.")
        }
    }

    private fun withArchive(
        openStream: () -> InputStream,
        isCancelled: () -> Boolean,
        block: (operation: ArchiveOperation, extractedRoot: File) -> SkillInstallResult,
    ): SkillInstallResult {
        val operationDir = createOperationDirectory()
            ?: return SkillInstallResult.Failure(
                SkillInstallError(SkillInstallErrorCode.IO_ERROR, "스킬 설치 임시 디렉터리를 생성할 수 없습니다.")
            )
        val operation = ArchiveOperation(operationDir)
        return try {
            val archiveFile = File(operationDir, "package.zip")
            operation.archiveSha256 = materializeArchive(openStream, archiveFile, isCancelled)
            val extractedRoot = File(operationDir, "extracted")
            extractArchive(archiveFile, extractedRoot, isCancelled)
            block(operation, extractedRoot)
        } catch (error: SkillInstallException) {
            SkillInstallResult.Failure(error.error)
        } catch (_: SkillRecoveryRequiredException) {
            SkillInstallResult.Failure(
                error = SkillInstallError(
                    SkillInstallErrorCode.COMMIT_FAILED,
                    "완료되지 않은 스킬 복구가 감지되어 설치가 중단되었습니다.",
                ),
                recoveryRequired = true,
            )
        } catch (_: ZipException) {
            SkillInstallResult.Failure(
                SkillInstallError(SkillInstallErrorCode.INVALID_ARCHIVE, "ZIP 형식이 올바르지 않거나 내용이 손상되었습니다.")
            )
        } catch (_: IOException) {
            SkillInstallResult.Failure(
                SkillInstallError(SkillInstallErrorCode.IO_ERROR, "ZIP을 읽거나 임시 저장에 실패했습니다.")
            )
        } catch (_: SecurityException) {
            SkillInstallResult.Failure(
                SkillInstallError(SkillInstallErrorCode.IO_ERROR, "해당 ZIP을 읽을 권한이 없습니다.")
            )
        } finally {
            if (!operation.preserveForRecovery) {
                deleteSkillPathWithoutFollowingLinks(workRoot, operationDir)
            }
        }
    }

    private fun withArchiveInspection(
        openStream: () -> InputStream,
        isCancelled: () -> Boolean,
        block: (extractedRoot: File) -> SkillArchiveInspectionResult,
    ): SkillArchiveInspectionResult {
        val operationDir = createOperationDirectory()
            ?: return SkillArchiveInspectionResult.Failure(
                SkillInstallError(SkillInstallErrorCode.IO_ERROR, "스킬 검사 임시 디렉터리를 생성할 수 없습니다.")
            )
        return try {
            val archiveFile = File(operationDir, "package.zip")
            materializeArchive(openStream, archiveFile, isCancelled)
            val extractedRoot = File(operationDir, "extracted")
            extractArchive(archiveFile, extractedRoot, isCancelled)
            block(extractedRoot)
        } catch (error: SkillInstallException) {
            SkillArchiveInspectionResult.Failure(error.error)
        } catch (_: ZipException) {
            SkillArchiveInspectionResult.Failure(
                SkillInstallError(SkillInstallErrorCode.INVALID_ARCHIVE, "ZIP 형식이 올바르지 않거나 내용이 손상되었습니다.")
            )
        } catch (_: IOException) {
            SkillArchiveInspectionResult.Failure(
                SkillInstallError(SkillInstallErrorCode.IO_ERROR, "ZIP을 읽거나 임시 저장에 실패했습니다.")
            )
        } catch (_: SecurityException) {
            SkillArchiveInspectionResult.Failure(
                SkillInstallError(SkillInstallErrorCode.IO_ERROR, "해당 ZIP을 읽을 권한이 없습니다.")
            )
        } finally {
            deleteSkillPathWithoutFollowingLinks(workRoot, operationDir)
        }
    }

    private fun createOperationDirectory(): File? {
        val safeWorkRoot = runCatching {
            prepareSkillInstallerWorkRoot(canonicalSkillsRoot)
        }.getOrNull() ?: return null
        if (safeWorkRoot != workRoot) return null
        repeat(4) {
            val candidate = File(safeWorkRoot, "operation-${UUID.randomUUID()}")
            if (candidate.mkdir()) return candidate
        }
        return null
    }

    private fun fail(code: SkillInstallErrorCode, message: String): Nothing =
        throw SkillInstallException(SkillInstallError(code, message))

    private fun checkCancelled(isCancelled: () -> Boolean) {
        if (isCancelled()) {
            fail(SkillInstallErrorCode.CANCELLED, "스킬 설치가 취소되었습니다.")
        }
    }

    private data class PreparedCandidate(
        val id: String,
        val name: String,
        val description: String,
        val relativePath: String,
        val directory: File,
    ) {
        val publicModel: SkillArchiveCandidate
            get() = SkillArchiveCandidate(
                id = id,
                name = name,
                description = description,
                relativePath = relativePath,
            )
    }

    private data class ValidatedSkillMetadata(
        val name: String,
        val description: String,
    )

    private data class ArchiveOperation(
        val directory: File,
        var archiveSha256: String = "",
        var preserveForRecovery: Boolean = false,
    )

    private class SkillInstallException(
        val error: SkillInstallError,
    ) : RuntimeException(error.message)

    private companion object {
        const val SKILL_FILE_NAME = "SKILL.md"
        const val MAX_SKILL_NAME_LENGTH = 64
        const val MAX_SKILL_DESCRIPTION_LENGTH = 1_024
        const val MAX_PATH_SEGMENT_LENGTH = 255
        val SKILL_NAME_REGEX = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")
        val WINDOWS_DRIVE_PREFIX = Regex("^[A-Za-z]:")
        val SHA_256_REGEX = Regex("^[a-f0-9]{64}$")
    }
}

internal fun interface SkillDirectoryMover {
    fun move(source: File, target: File)
}

internal object AtomicSkillDirectoryMover : SkillDirectoryMover {
    override fun move(source: File, target: File) {
        moveSkillDirectoryAtomically(source, target)
    }
}
