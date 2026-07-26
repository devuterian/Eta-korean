package fuck.andes.agent.skill

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files

data class SkillResourceLimits(
    val maxTextBytes: Long = 512L * 1024L,
    val maxResources: Int = 512,
    val maxPathDepth: Int = 16,
)

/** 在已安装 Skill 根目录内列出和读取有界 UTF-8 文本资源。 */
class SkillResourceReader internal constructor(
    skillsRoot: File,
    private val limits: SkillResourceLimits = SkillResourceLimits(),
) {
    private val canonicalSkillsRoot = skillsRoot.canonicalFile

    fun listResources(
        entry: SkillIndexEntry,
        relativeDirectory: String? = null,
    ): SkillResourceListResult = SkillMutationLock.withLock(canonicalSkillsRoot) {
        listResourcesAfterRecovery(entry, relativeDirectory)
    }

    private fun listResourcesAfterRecovery(
        entry: SkillIndexEntry,
        relativeDirectory: String?,
    ): SkillResourceListResult {
        val skillRoot = validateSkillRoot(entry)
            ?: return failure(
                SkillResourceErrorCode.INVALID_SKILL_ROOT,
                "스킬 루트 디렉터리가 앱의 개인 스킬 디렉터리 내에 있지 않습니다.",
            )
        val start = if (relativeDirectory.isNullOrBlank() || relativeDirectory == ".") {
            skillRoot
        } else {
            val segments = validateResourcePath(relativeDirectory)
                ?: return failure(
                    SkillResourceErrorCode.INVALID_RELATIVE_PATH,
                    "리소스 디렉터리는 스킬 내의 안전한 상대 경로여야 합니다.",
                )
            if (hasSymbolicLinkComponent(skillRoot, segments)) {
                return failure(
                    SkillResourceErrorCode.INVALID_RELATIVE_PATH,
                    "스킬 리소스 경로에 허용되지 않는 심볼릭 링크가 포함되어 있습니다.",
                )
            }
            resolveInside(skillRoot, segments)
                ?: return failure(
                    SkillResourceErrorCode.INVALID_RELATIVE_PATH,
                    "리소스 디렉터리가 스킬 루트 디렉터리를 벗어났습니다.",
                )
        }
        if (!start.isDirectory || Files.isSymbolicLink(start.toPath())) {
            return failure(SkillResourceErrorCode.RESOURCE_NOT_FOUND, "리소스 디렉터리가 존재하지 않습니다.")
        }

        val resources = mutableListOf<SkillResourceInfo>()
        val pending = ArrayDeque<File>()
        pending.add(start)
        while (pending.isNotEmpty()) {
            val directory = pending.removeFirst()
            val children = directory.listFiles()?.sortedBy { it.name }
                ?: return failure(SkillResourceErrorCode.IO_ERROR, "스킬 리소스 디렉터리를 읽을 수 없습니다.")
            for (child in children) {
                if (Files.isSymbolicLink(child.toPath())) {
                    return failure(
                        SkillResourceErrorCode.INVALID_RELATIVE_PATH,
                        "스킬 리소스에 허용되지 않는 심볼릭 링크가 포함되어 있습니다.",
                    )
                }
                val relative = child.relativeTo(skillRoot).invariantSeparatorsPath
                val depth = relative.split('/').size
                if (depth > limits.maxPathDepth) {
                    return failure(
                        SkillResourceErrorCode.INVALID_RELATIVE_PATH,
                        "스킬 리소스 경로의 깊이가 ${limits.maxPathDepth} 단계를 초과했습니다.",
                    )
                }
                when {
                    child.isDirectory -> pending.add(child)
                    child.isFile -> {
                        resources += SkillResourceInfo(relativePath = relative, sizeBytes = child.length())
                        if (resources.size > limits.maxResources) {
                            return failure(
                                SkillResourceErrorCode.TOO_MANY_RESOURCES,
                                "스킬 리소스 개수가 ${limits.maxResources}개를 초과했습니다.",
                            )
                        }
                    }
                }
            }
        }
        return SkillResourceListResult.Success(resources.sortedBy { it.relativePath })
    }

    fun readText(
        entry: SkillIndexEntry,
        relativePath: String,
    ): SkillResourceReadResult = SkillMutationLock.withLock(canonicalSkillsRoot) {
        readTextAfterRecovery(entry, relativePath)
    }

    private fun readTextAfterRecovery(
        entry: SkillIndexEntry,
        relativePath: String,
    ): SkillResourceReadResult {
        val skillRoot = validateSkillRoot(entry)
            ?: return readFailure(
                SkillResourceErrorCode.INVALID_SKILL_ROOT,
                "스킬 루트 디렉터리가 앱의 개인 스킬 디렉터리 내에 있지 않습니다.",
            )
        val segments = validateResourcePath(relativePath)
            ?: return readFailure(
                SkillResourceErrorCode.INVALID_RELATIVE_PATH,
                "리소스 경로는 스킬 내의 안전한 상대 경로여야 합니다.",
            )
        val target = resolveInside(skillRoot, segments)
            ?: return readFailure(
                SkillResourceErrorCode.INVALID_RELATIVE_PATH,
                "리소스 경로가 스킬 루트 디렉터리를 벗어났습니다.",
            )
        if (!target.isFile || Files.isSymbolicLink(target.toPath())) {
            return readFailure(SkillResourceErrorCode.RESOURCE_NOT_FOUND, "스킬 리소스가 존재하지 않습니다.")
        }
        if (hasSymbolicLinkComponent(skillRoot, segments)) {
            return readFailure(
                SkillResourceErrorCode.INVALID_RELATIVE_PATH,
                "스킬 리소스 경로에 허용되지 않는 심볼릭 링크가 포함되어 있습니다.",
            )
        }
        if (target.length() > limits.maxTextBytes) {
            return readFailure(
                SkillResourceErrorCode.RESOURCE_TOO_LARGE,
                "스킬 텍스트 리소스가 ${limits.maxTextBytes}바이트 제한을 초과했습니다.",
            )
        }
        val text = try {
            readStrictUtf8(target, limits.maxTextBytes)
        } catch (_: Exception) {
            return readFailure(SkillResourceErrorCode.IO_ERROR, "스킬 리소스 읽기에 실패했습니다.")
        } ?: return readFailure(
            SkillResourceErrorCode.BINARY_RESOURCE,
            "이 리소스는 안전하게 읽을 수 있는 UTF-8 텍스트가 아닙니다.",
        )
        return SkillResourceReadResult.Success(
            relativePath = segments.joinToString("/"),
            text = text,
        )
    }

    private fun validateSkillRoot(entry: SkillIndexEntry): File? {
        if (!entry.installed) return null
        val root = runCatching { File(entry.rootPath).canonicalFile }.getOrNull() ?: return null
        val skillsPath = canonicalSkillsRoot.toPath()
        val rootPath = root.toPath()
        if (!rootPath.startsWith(skillsPath) || rootPath == skillsPath || !root.isDirectory) return null
        return root
    }

    private fun validateResourcePath(raw: String): List<String>? {
        if (
            raw.isBlank() || raw.startsWith('/') || raw.startsWith('\\') || raw.contains('\\') ||
            raw.contains('\u0000') || raw.any { it.isISOControl() }
        ) {
            return null
        }
        val segments = raw.removeSuffix("/").split('/')
        if (
            segments.isEmpty() || segments.size > limits.maxPathDepth ||
            segments.any { it.isBlank() || it == "." || it == ".." }
        ) {
            return null
        }
        return segments
    }

    private fun resolveInside(root: File, segments: List<String>): File? {
        val target = segments.fold(root) { current, segment -> File(current, segment) }
        val canonical = runCatching { target.canonicalFile }.getOrNull() ?: return null
        return canonical.takeIf {
            it.toPath().startsWith(root.toPath()) && it.toPath() != root.toPath()
        }
    }

    private fun hasSymbolicLinkComponent(root: File, segments: List<String>): Boolean {
        var current = root
        return segments.any { segment ->
            current = File(current, segment)
            Files.isSymbolicLink(current.toPath())
        }
    }

    private fun failure(code: SkillResourceErrorCode, message: String) =
        SkillResourceListResult.Failure(SkillResourceError(code, message))

    private fun readFailure(code: SkillResourceErrorCode, message: String) =
        SkillResourceReadResult.Failure(SkillResourceError(code, message))
}

/** 严格 UTF-8 解码；超限、NUL 或除换行/回车/制表符外的控制字符均视为非文本。 */
internal fun readStrictUtf8(file: File, maxBytes: Long): String? {
    if (file.length() > maxBytes) return null
    val bytes = file.inputStream().use { input ->
        val initialCapacity = minOf(file.length(), maxBytes, Int.MAX_VALUE.toLong())
            .toInt()
            .coerceAtLeast(32)
        val output = ByteArrayOutputStream(initialCapacity)
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) return null
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }
    val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    val text = runCatching { decoder.decode(ByteBuffer.wrap(bytes)).toString() }.getOrNull() ?: return null
    if (text.any { character ->
            character == '\u0000' ||
                (character.isISOControl() && character != '\n' && character != '\r' && character != '\t')
        }
    ) {
        return null
    }
    return text
}
