package fuck.andes.agent.skill

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Skills 文件树与注册表的跨进程互斥锁。
 *
 * UI 与 Agent Runtime 可能位于不同进程；仅使用 JVM monitor 无法避免两个安装事务同时通过
 * 冲突检查。锁文件位于索引扫描目录之外，线程内同一根目录允许重入。
 */
internal object SkillMutationLock {
    private val processLock = Any()
    private val heldRoots = ThreadLocal<MutableSet<String>>()

    fun <T> withLock(
        skillsRoot: File,
        recoveryHandler: ((List<RecoveredSkillOperation>) -> Unit)? = null,
        block: () -> T,
    ): T {
        val canonicalRoot = skillsRoot.canonicalFile
        val key = canonicalRoot.absolutePath
        val held = heldRoots.get() ?: linkedSetOf<String>().also(heldRoots::set)
        if (key in held) return block()

        return synchronized(processLock) {
            val lockDirectory = prepareSkillInstallerWorkRoot(canonicalRoot)
            val lockPath = File(lockDirectory, "install.lock")
            if (
                Files.exists(lockPath.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                (Files.isSymbolicLink(lockPath.toPath()) ||
                    !Files.isRegularFile(lockPath.toPath(), LinkOption.NOFOLLOW_LINKS))
            ) {
                throw IOException("스킬 설치 잠금 파일이 안전하지 않습니다.")
            }
            RandomAccessFile(lockPath, "rw").use { lockFile ->
                if (Files.isSymbolicLink(lockPath.toPath())) {
                    throw IOException("스킬 설치 잠금 파일이 안전하지 않습니다.")
                }
                lockFile.channel.use { channel ->
                    channel.lock().use {
                        held += key
                        try {
                            val recovered = recoverPendingSkillOperations(canonicalRoot)
                            if (recovered.isNotEmpty()) {
                                val handler = recoveryHandler
                                    ?: throw SkillRecoveryRequiredException(
                                        "스킬 파일이 복구되었습니다. registry 복구를 기다립니다."
                                    )
                                try {
                                    handler(recovered)
                                    completeRecoveredSkillOperations(canonicalRoot, recovered)
                                } catch (error: SkillRecoveryRequiredException) {
                                    throw error
                                } catch (error: Exception) {
                                    throw SkillRecoveryRequiredException(
                                        "스킬 registry 자동 복구에 실패했습니다.",
                                        error,
                                    )
                                }
                            }
                            block()
                        } finally {
                            held -= key
                            if (held.isEmpty()) heldRoots.remove()
                        }
                    }
                }
            }
        }
    }
}

/** 创建或验证只位于 Skills 同级私有目录中的安装工作区，拒绝 symlink 与特殊文件。 */
internal fun prepareSkillInstallerWorkRoot(skillsRoot: File): File {
    val canonicalRoot = skillsRoot.canonicalFile
    val parent = requireNotNull(canonicalRoot.parentFile) { "Skills 디렉터리에는 상위 디렉터리가 필요합니다." }
    if (!Files.isDirectory(parent.toPath(), LinkOption.NOFOLLOW_LINKS)) {
        throw IOException("Skills 상위 디렉터리를 사용할 수 없습니다.")
    }
    val workRoot = File(parent, ".eta-skill-installer")
    val path = workRoot.toPath()
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
        try {
            Files.createDirectory(path)
        } catch (error: java.nio.file.FileAlreadyExistsException) {
            // 与其他进程竞争创建时，交给下面的 NOFOLLOW 校验决定是否可用。
        }
    }
    if (
        Files.isSymbolicLink(path) ||
        !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) ||
        workRoot.canonicalFile.parentFile != parent.canonicalFile ||
        workRoot.canonicalFile != workRoot.absoluteFile
    ) {
        throw IOException("스킬 설치 작업 디렉터리가 안전하지 않습니다.")
    }
    return workRoot
}

/** 替换事务只能接收可完整复制恢复的普通目录树。 */
internal fun isRecoverableSkillDirectoryTree(skillsRoot: File, target: File): Boolean {
    val canonicalRoot = runCatching { skillsRoot.canonicalFile.toPath() }.getOrNull() ?: return false
    if (Files.isSymbolicLink(target.toPath())) return false
    val canonicalTarget = runCatching { target.canonicalFile.toPath() }.getOrNull() ?: return false
    if (!canonicalTarget.startsWith(canonicalRoot) || canonicalTarget == canonicalRoot) return false
    return isRegularDirectoryTreeWithoutLinks(target.toPath())
}

private fun isRegularDirectoryTreeWithoutLinks(path: Path): Boolean {
    if (Files.isSymbolicLink(path)) return false
    if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return true
    if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return false
    return runCatching {
        Files.newDirectoryStream(path).use { children ->
            children.all(::isRegularDirectoryTreeWithoutLinks)
        }
    }.getOrDefault(false)
}

internal fun moveSkillDirectoryAtomically(source: File, target: File) {
    target.parentFile?.let { parent ->
        if (!parent.mkdirs() && !parent.isDirectory) throw IOException("대상 상위 디렉터리를 생성할 수 없습니다.")
    }
    try {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source.toPath(), target.toPath())
    }
}

/** 删除 Skills 根目录内的路径，但遇到任意符号链接时只删除链接本身。 */
internal fun deleteSkillPathWithoutFollowingLinks(skillsRoot: File, target: File): Boolean {
    val lexicalRoot = skillsRoot.absoluteFile.toPath().normalize()
    val lexicalTarget = target.absoluteFile.toPath().normalize()
    if (!lexicalTarget.startsWith(lexicalRoot) || lexicalTarget == lexicalRoot) return false
    if (!Files.exists(lexicalTarget, LinkOption.NOFOLLOW_LINKS)) return true
    if (!Files.isSymbolicLink(lexicalTarget)) {
        val canonicalRoot = skillsRoot.canonicalFile.toPath()
        val canonicalTarget = target.canonicalFile.toPath()
        if (!canonicalTarget.startsWith(canonicalRoot) || canonicalTarget == canonicalRoot) return false
    }
    return runCatching {
        deletePathTreeWithoutFollowingLinks(lexicalTarget)
        true
    }.getOrDefault(false)
}

private fun deletePathTreeWithoutFollowingLinks(path: Path) {
    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
        Files.newDirectoryStream(path).use { children ->
            children.forEach(::deletePathTreeWithoutFollowingLinks)
        }
    }
    Files.deleteIfExists(path)
}
