package fuck.andes.hook.system

import fuck.andes.core.HookSupport
import fuck.andes.core.HookInstallation
import fuck.andes.core.HookRegistrar
import fuck.andes.core.ModuleConfig
import fuck.andes.core.ModuleLogger
import fuck.andes.core.safeLogType

import android.app.KeyguardManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import fuck.andes.config.Prefs
import io.github.libxposed.api.XposedModule
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Consumer

internal object AssistantManager {
    private const val BOOT_COMPLETED_PHASE = 1_000
    private const val SHOW_SOURCE_PUSH_TO_TALK = 1 shl 5
    private const val DEFAULT_SHOW_FLAGS = SHOW_SOURCE_PUSH_TO_TALK
    private const val CONFIG_VERIFY_COOLDOWN_MS = 15_000L
    // Android 37 RoleControllerManager 自身超时为 15 秒；本地 watchdog 只能晚于它做最终状态核验。
    private const val ROLE_OPERATION_WATCHDOG_MS = 17_000L
    private const val REFRESH_COOLDOWN_MS = 5_000L

    private val systemHandler by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Handler(Looper.getMainLooper())
    }

    private val configurationLock = Any()
    private val usersWithConfigurationInFlight = mutableSetOf<Int>()

    @Volatile
    private var lastForcedRefreshUptime = 0L

    @Volatile
    private var lastVerifiedUserId = UserHandleHidden.USER_NULL

    @Volatile
    private var lastVerifiedUptime = 0L

    @Volatile
    private var voiceInteractionManagerStub: Any? = null

    fun install(
        module: XposedModule,
        rootLogger: ModuleLogger,
        classLoader: ClassLoader
    ): HookInstallation {
        val hooks = HookRegistrar(module, rootLogger, "AssistantManager")
        val logger = hooks.logger
        return hooks.install {
            val serviceClass = HookSupport.findClassOrNull(
                classLoader,
                ModuleConfig.VOICE_INTERACTION_MANAGER_SERVICE_CLASS
            )
            val onBootPhaseMethod = serviceClass?.let {
                HookSupport.findMethod(it, "onBootPhase", Int::class.javaPrimitiveType!!)
            }
            if (serviceClass == null) {
                hooks.skipped(
                    id = "system.assistant-boot-phase",
                    description = "VoiceInteractionManagerService.onBootPhase",
                    detail = "未找到 VoiceInteractionManagerService，跳过 onBootPhase Hook"
                )
            } else if (onBootPhaseMethod == null) {
                hooks.missing(
                    id = "system.assistant-boot-phase",
                    description = "VoiceInteractionManagerService.onBootPhase",
                    detail = "未找到 VoiceInteractionManagerService.onBootPhase(int)"
                )
            } else {
                hooks.intercept(
                    id = "system.assistant-boot-phase",
                    executable = onBootPhaseMethod,
                    description = "VoiceInteractionManagerService.onBootPhase"
                ) { chain ->
                    val phase = chain.getArg(0) as Int
                    val result = chain.proceed()
                    val service = chain.getThisObject()
                    captureVoiceInteractionManagerStub(service)
                    if (phase == BOOT_COMPLETED_PHASE) {
                        // 开关关闭则不自动校正默认助理。
                        if (!Prefs.isEnabled(Prefs.Keys.ASSISTANT_AUTO_CONFIG)) {
                            logger.debug { "AssistantManager: 自动校正已关闭，跳过 boot 校正" }
                        } else {
                            val context = HookSupport.getFieldValue(service, "mContext") as? Context
                            if (context == null) {
                                logger.warnThrottled("assistant_boot_missing_context") {
                                    "AssistantManager: boot completed 时无法取得 mContext"
                                }
                            } else {
                                scheduleGoogleAssistantConfiguration(
                                    context = context,
                                    userId = null,
                                    logger = logger,
                                    handler = systemHandler,
                                    forceRefresh = false,
                                    rebuildWhenVerified = false,
                                    requiredPreferenceKey = Prefs.Keys.ASSISTANT_AUTO_CONFIG
                                )
                            }
                        }
                    }
                    result
                }
            }

            hookUserLifecycleSelfHeal(hooks, serviceClass, "onUserUnlocking", 1)
            hookUserLifecycleSelfHeal(hooks, serviceClass, "onUserSwitching", 2)
        }
    }

    fun scheduleGoogleAssistantRecovery(
        context: Context,
        logger: ModuleLogger,
        handler: Handler,
        forceRefresh: Boolean,
        requiredPreferenceKey: String
    ): Boolean = scheduleGoogleAssistantConfiguration(
        context = context,
        userId = null,
        logger = logger,
        handler = handler,
        forceRefresh = forceRefresh,
        rebuildWhenVerified = true,
        requiredPreferenceKey = requiredPreferenceKey
    )

    fun showGoogleAssistantSession(
        context: Context,
        logger: ModuleLogger,
        source: String,
        logFailures: Boolean = false
    ): Boolean {
        val service = resolveVoiceInteractionService(logger, source, logFailures) ?: return false

        if (isKeyguardLocked(context)) {
            val launchFromKeyguardMethod = service.javaClass.methods.firstOrNull {
                it.name == "launchVoiceAssistFromKeyguard" && it.parameterTypes.isEmpty()
            }
            val supportsLaunchMethod = service.javaClass.methods.firstOrNull {
                it.name == "activeServiceSupportsLaunchFromKeyguard" && it.parameterTypes.isEmpty()
            }
            val supportsLaunch = runCatching {
                supportsLaunchMethod?.invoke(service) as? Boolean ?: false
            }.getOrDefault(false)
            if (supportsLaunch && launchFromKeyguardMethod != null) {
                return runCatching {
                    launchFromKeyguardMethod.invoke(service)
                    logger.debug { "$source: 已通过 voiceinteraction 从锁屏启动 Google" }
                    true
                }.getOrElse { throwable ->
                    logShowSessionFailure(
                        logger,
                        "${source}_launch_keyguard_failed",
                        logFailures
                    ) {
                        "$source: launchVoiceAssistFromKeyguard 失败，type=${throwable.safeLogType()}"
                    }
                    false
                }
            }
        }

        val showSessionMethod = service.javaClass.methods.firstOrNull {
            it.name == "showSessionForActiveService" && it.parameterTypes.size == 5
        }
        if (showSessionMethod == null) {
            logShowSessionFailure(
                logger,
                "${source}_voice_service_missing_show",
                logFailures
            ) { "$source: voiceinteraction 缺少 showSessionForActiveService" }
            return false
        }

        return runCatching {
            showSessionMethod.invoke(
                service,
                Bundle(),
                DEFAULT_SHOW_FLAGS,
                null,
                null,
                null
            ) as? Boolean ?: false
        }.onFailure { throwable ->
            logShowSessionFailure(
                logger,
                "${source}_voice_service_failed",
                logFailures
            ) {
                "$source: 调用 showSessionForActiveService 失败，type=${throwable.safeLogType()}"
            }
        }.getOrDefault(false).also { shown ->
            if (!shown) {
                logShowSessionFailure(
                    logger,
                    "${source}_voice_service_returned_false",
                    logFailures
                ) { "$source: showSessionForActiveService 返回 false" }
            }
        }
    }

    fun rebuildVoiceInteractionImplementation(
        logger: ModuleLogger,
        userId: Int = resolveCurrentUserId(),
        force: Boolean,
        logFailures: Boolean = false
    ): Boolean {
        val stub = voiceInteractionManagerStub ?: run {
            logShowSessionFailure(
                logger,
                "assistant_stub_missing",
                logFailures
            ) { "AssistantManager: mServiceStub 尚未就绪，无法重建 voice interaction 实现" }
            return false
        }
        val initForUserMethod = stub.javaClass.methods.firstOrNull {
            it.name == "initForUser" && it.parameterTypes.size == 1
        }
        val switchImplementationMethod = stub.javaClass.methods.firstOrNull {
            it.name == "switchImplementationIfNeeded" && it.parameterTypes.size == 1
        }
        if (initForUserMethod == null || switchImplementationMethod == null) {
            logShowSessionFailure(
                logger,
                "assistant_stub_methods_missing",
                logFailures
            ) { "AssistantManager: mServiceStub 缺少 initForUser/switchImplementationIfNeeded" }
            return false
        }

        return runCatching {
            initForUserMethod.invoke(stub, userId)
            switchImplementationMethod.invoke(stub, force)
            true
        }.getOrElse { throwable ->
            logShowSessionFailure(
                logger,
                "assistant_stub_rebuild_failed",
                logFailures
            ) {
                "AssistantManager: 重建 voice interaction 实现失败，type=${throwable.safeLogType()}"
            }
            false
        }
    }

    fun resumeSoftwareHotwordDetection(
        logger: ModuleLogger,
        source: String,
        logFailures: Boolean = false
    ): Boolean {
        val stub = voiceInteractionManagerStub ?: run {
            logShowSessionFailure(
                logger,
                "${source}_hotword_stub_missing",
                logFailures
            ) { "$source: mServiceStub 尚未就绪，无法恢复软件热词检测" }
            return false
        }

        return runCatching {
            synchronized(stub) {
                val impl = HookSupport.getFieldValue(stub, "mImpl") ?: return@synchronized false
                val component = HookSupport.getFieldValue(impl, "mComponent") as? ComponentName
                if (component?.packageName != ModuleConfig.GOOGLE_PACKAGE) {
                    return@synchronized false
                }

                val session = findSoftwareHotwordSession(impl) ?: return@synchronized false
                val running = HookSupport.getFieldValue(
                    session,
                    "mPerformingSoftwareHotwordDetection"
                ) as? Boolean ?: false
                if (running) {
                    return@synchronized false
                }

                val callback = HookSupport.getFieldValue(session, "mSoftwareCallback")
                    ?: return@synchronized false
                val startListeningMethod = impl.javaClass.declaredMethods.firstOrNull {
                    it.name == "startListeningFromMicLocked" && it.parameterTypes.size == 2
                }?.apply { isAccessible = true } ?: return@synchronized false

                startListeningMethod.invoke(impl, null, callback)
                true
            }
        }.getOrElse { throwable ->
            logShowSessionFailure(
                logger,
                "${source}_hotword_resume_failed",
                logFailures
            ) { "$source: 恢复软件热词检测失败，type=${throwable.safeLogType()}" }
            false
        }
    }

    private fun scheduleGoogleAssistantConfiguration(
        context: Context,
        userId: Int?,
        logger: ModuleLogger,
        handler: Handler,
        forceRefresh: Boolean,
        rebuildWhenVerified: Boolean,
        requiredPreferenceKey: String
    ): Boolean = handler.post {
        try {
            // 请求入队后开关可能已关闭，真正执行前必须重新读取 RemotePreferences。
            if (!Prefs.isEnabled(requiredPreferenceKey)) {
                return@post
            }
            val resolvedUserId = userId ?: resolveCurrentUserId()
            configureGoogleAssistantForUser(
                context = context,
                userId = resolvedUserId,
                logger = logger,
                handler = handler,
                forceRefresh = forceRefresh,
                rebuildWhenVerified = rebuildWhenVerified,
                requiredPreferenceKey = requiredPreferenceKey
            )
        } catch (exception: Exception) {
            logger.errorThrottled(
                key = "assistant_configuration_task_failed",
                throwable = exception
            ) { "AssistantManager: 默认助理后台任务异常" }
        }
    }

    private fun configureGoogleAssistantForUser(
        context: Context,
        userId: Int,
        logger: ModuleLogger,
        handler: Handler,
        forceRefresh: Boolean,
        rebuildWhenVerified: Boolean,
        requiredPreferenceKey: String
    ) {
        if (!beginConfiguration(userId)) {
            logger.debug { "AssistantManager: 已有校正任务，跳过重复请求" }
            return
        }

        try {
            val now = SystemClock.uptimeMillis()
            if (!forceRefresh &&
                userId == lastVerifiedUserId &&
                now - lastVerifiedUptime < CONFIG_VERIFY_COOLDOWN_MS
            ) {
                if (rebuildWhenVerified) {
                    rebuildVoiceInteractionImplementation(
                        logger = logger,
                        userId = userId,
                        force = false,
                        logFailures = false
                    )
                }
                finishConfiguration(userId)
                return
            }

            val roleOk = hasGoogleAssistantRole(context, userId)
            val settingsOk = hasGoogleAssistantSettings(context, userId)
            if (!forceRefresh && roleOk && settingsOk) {
                markVerified(userId, now)
                if (rebuildWhenVerified) {
                    rebuildVoiceInteractionImplementation(
                        logger = logger,
                        userId = userId,
                        force = false,
                        logFailures = false
                    )
                }
                finishConfiguration(userId)
                return
            }

            if (forceRefresh && now - lastForcedRefreshUptime < REFRESH_COOLDOWN_MS) {
                if (roleOk && settingsOk) {
                    markVerified(userId, now)
                    if (rebuildWhenVerified) {
                        rebuildVoiceInteractionImplementation(
                            logger = logger,
                            userId = userId,
                            force = false,
                            logFailures = false
                        )
                    }
                }
                finishConfiguration(userId)
                return
            }
            if (forceRefresh) {
                lastForcedRefreshUptime = now
                // 先做一次无等待重建，角色核验完成后仍会按最终状态再次确认。
                rebuildVoiceInteractionImplementation(
                    logger = logger,
                    userId = userId,
                    force = true,
                    logFailures = false
                )
            }

            val onRoleMutationFinished: (Boolean) -> Unit = { roleChanged ->
                completeGoogleAssistantConfiguration(
                    context = context,
                    userId = userId,
                    logger = logger,
                    forceRefresh = forceRefresh,
                    rebuildWhenVerified = rebuildWhenVerified,
                    roleChanged = roleChanged,
                    requiredPreferenceKey = requiredPreferenceKey
                )
            }

            if (!roleOk) {
                addGoogleAssistantRoleAsync(
                    context = context,
                    userId = userId,
                    logger = logger,
                    handler = handler,
                    onFinished = onRoleMutationFinished
                )
            } else {
                onRoleMutationFinished(false)
            }
        } catch (exception: Exception) {
            finishConfiguration(userId)
            logger.warnThrottled("assistant_configuration_start_failed") {
                "AssistantManager: 启动默认助理校正失败，type=${exception.safeLogType()}"
            }
        }
    }

    private fun completeGoogleAssistantConfiguration(
        context: Context,
        userId: Int,
        logger: ModuleLogger,
        forceRefresh: Boolean,
        rebuildWhenVerified: Boolean,
        roleChanged: Boolean,
        requiredPreferenceKey: String
    ) {
        try {
            // RoleManager 请求无法取消；回调到达时若功能已关闭，只收尾状态，不再写设置或重建服务。
            if (!Prefs.isEnabled(requiredPreferenceKey)) {
                invalidateVerificationCache()
                return
            }
            val settingsChanged = updateGoogleAssistantSettings(
                context = context,
                userId = userId,
                forceRefresh = forceRefresh,
                logger = logger
            )
            val verified = hasGoogleAssistantRole(context, userId) &&
                hasGoogleAssistantSettings(context, userId)

            if (!verified) {
                invalidateVerificationCache()
                return
            }

            markVerified(userId, SystemClock.uptimeMillis())
            if (roleChanged || settingsChanged || forceRefresh || rebuildWhenVerified) {
                rebuildVoiceInteractionImplementation(
                    logger = logger,
                    userId = userId,
                    force = forceRefresh || roleChanged || settingsChanged,
                    logFailures = false
                )
                logger.debug {
                    if (forceRefresh) {
                        "AssistantManager: 已刷新 Google 默认助理绑定"
                    } else {
                        "AssistantManager: 已校正 Google 默认助理绑定"
                    }
                }
            }
        } catch (exception: Exception) {
            invalidateVerificationCache()
            logger.warnThrottled("assistant_configuration_complete_failed") {
                "AssistantManager: 完成默认助理校正失败，type=${exception.safeLogType()}"
            }
        } finally {
            finishConfiguration(userId)
        }
    }

    private fun resolveVoiceInteractionService(
        logger: ModuleLogger,
        source: String,
        logFailures: Boolean
    ): Any? {
        val binder = runCatching {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = serviceManagerClass.getDeclaredMethod("getService", String::class.java)
            getServiceMethod.invoke(null, ModuleConfig.VOICE_INTERACTION_SERVICE) as? IBinder
        }.getOrNull()
        if (binder == null) {
            logShowSessionFailure(
                logger,
                "${source}_voice_service_missing",
                logFailures
            ) { "$source: 无法取得 voiceinteraction binder" }
            return null
        }

        return runCatching {
            val stubClass = Class.forName("com.android.internal.app.IVoiceInteractionManagerService\$Stub")
            val asInterfaceMethod = stubClass.getDeclaredMethod("asInterface", IBinder::class.java)
            asInterfaceMethod.invoke(null, binder)
        }.getOrElse { throwable ->
            logShowSessionFailure(
                logger,
                "${source}_voice_service_as_interface_failed",
                logFailures
            ) {
                "$source: 解析 IVoiceInteractionManagerService 失败，type=${throwable.safeLogType()}"
            }
            null
        }
    }

    private fun addGoogleAssistantRoleAsync(
        context: Context,
        userId: Int,
        logger: ModuleLogger,
        handler: Handler,
        onFinished: (Boolean) -> Unit
    ) {
        mutateRoleHoldersAsync(
            context = context,
            userId = userId,
            methodName = "addRoleHolderAsUser",
            baseArgs = arrayOf(
                ModuleConfig.ASSISTANT_ROLE,
                ModuleConfig.GOOGLE_PACKAGE,
                0,
                resolveUserHandle(userId)
            ),
            logger = logger,
            handler = handler,
            onFinished = onFinished
        )
    }

    private fun mutateRoleHoldersAsync(
        context: Context,
        userId: Int,
        methodName: String,
        baseArgs: Array<Any>,
        logger: ModuleLogger,
        handler: Handler,
        onFinished: (Boolean) -> Unit
    ) {
        val roleManager = context.getSystemService(RoleManager::class.java)
        if (roleManager == null) {
            onFinished(false)
            return
        }
        val method = roleManager.javaClass.methods.firstOrNull {
            it.name == methodName && it.parameterTypes.size == baseArgs.size + 2
        } ?: run {
            logger.warnThrottled("assistant_role_method_$methodName") {
                "AssistantManager: RoleManager 缺少 $methodName"
            }
            onFinished(false)
            return
        }

        val completed = AtomicBoolean(false)
        lateinit var timeout: Runnable
        val complete: (Boolean) -> Unit = { success ->
            if (completed.compareAndSet(false, true)) {
                handler.removeCallbacks(timeout)
                try {
                    onFinished(success)
                } catch (exception: Exception) {
                    invalidateVerificationCache()
                    finishConfiguration(userId)
                    logger.errorThrottled(
                        key = "assistant_role_completion_${methodName}_$userId",
                        throwable = exception
                    ) { "AssistantManager: $methodName 完成回调异常" }
                }
            }
        }
        timeout = Runnable {
            logger.warnThrottled("assistant_role_timeout_${methodName}_$userId") {
                "AssistantManager: $methodName 回调超过框架超时，核验最终角色状态"
            }
            complete(hasGoogleAssistantRole(context, userId))
        }
        val executor = Executor { runnable ->
            if (!handler.post(runnable)) {
                logger.warnThrottled("assistant_role_callback_rejected_${methodName}_$userId") {
                    "AssistantManager: $methodName 回调无法投递到系统 Handler"
                }
                runnable.run()
            }
        }
        val callback = Consumer<Boolean> { result ->
            complete(result == true)
        }

        try {
            val args = arrayOfNulls<Any>(baseArgs.size + 2)
            baseArgs.copyInto(args, endIndex = baseArgs.size)
            args[baseArgs.size] = executor
            args[baseArgs.size + 1] = callback
            method.invoke(roleManager, *args)
            if (!handler.postDelayed(timeout, ROLE_OPERATION_WATCHDOG_MS)) {
                logger.warnThrottled("assistant_role_timeout_rejected_${methodName}_$userId") {
                    "AssistantManager: $methodName 超时兜底无法投递到系统 Handler"
                }
                complete(hasGoogleAssistantRole(context, userId))
            }
        } catch (exception: Exception) {
            logger.warnThrottled("assistant_role_mutation_$methodName") {
                "AssistantManager: $methodName 失败，type=${exception.safeLogType()}"
            }
            complete(false)
        }
    }

    private fun updateGoogleAssistantSettings(
        context: Context,
        userId: Int,
        forceRefresh: Boolean,
        logger: ModuleLogger
    ): Boolean {
        val resolver = context.contentResolver
        var changed = false
        changed = updateSecureString(
            resolver,
            ModuleConfig.SECURE_ASSISTANT,
            ModuleConfig.GOOGLE_ASSISTANT_COMPONENT,
            userId,
            forceRefresh
        ) || changed
        changed = updateSecureString(
            resolver,
            ModuleConfig.SECURE_VOICE_INTERACTION_SERVICE,
            ModuleConfig.GOOGLE_ASSISTANT_COMPONENT,
            userId,
            forceRefresh
        ) || changed
        if (changed) {
            logger.debug { "AssistantManager: 已写入 Google 助理 secure 配置" }
        }
        return changed
    }

    private fun updateSecureString(
        resolver: ContentResolver,
        key: String,
        targetValue: String,
        userId: Int,
        forceRefresh: Boolean
    ): Boolean {
        val currentValue = getSecureStringForUser(resolver, key, userId)
        if (!forceRefresh && currentValue == targetValue) {
            return false
        }
        if (forceRefresh) {
            putSecureStringForUser(resolver, key, null, userId)
        }
        return putSecureStringForUser(resolver, key, targetValue, userId)
    }

    private fun hasGoogleAssistantRole(context: Context, userId: Int): Boolean {
        val roleManager = context.getSystemService(RoleManager::class.java) ?: return false
        val method = roleManager.javaClass.methods.firstOrNull {
            it.name == "getRoleHoldersAsUser" && it.parameterTypes.size == 2
        } ?: return false
        val holders = runCatching {
            @Suppress("UNCHECKED_CAST")
            method.invoke(roleManager, ModuleConfig.ASSISTANT_ROLE, resolveUserHandle(userId)) as? List<String>
        }.getOrNull().orEmpty()
        return holders.contains(ModuleConfig.GOOGLE_PACKAGE)
    }

    private fun hasGoogleAssistantSettings(context: Context, userId: Int): Boolean {
        val resolver = context.contentResolver
        return getSecureStringForUser(resolver, ModuleConfig.SECURE_ASSISTANT, userId) ==
            ModuleConfig.GOOGLE_ASSISTANT_COMPONENT &&
            getSecureStringForUser(
                resolver,
                ModuleConfig.SECURE_VOICE_INTERACTION_SERVICE,
                userId
            ) == ModuleConfig.GOOGLE_ASSISTANT_COMPONENT
    }

    private fun getSecureStringForUser(
        resolver: ContentResolver,
        key: String,
        userId: Int
    ): String? =
        runCatching {
            val method = Settings.Secure::class.java.getDeclaredMethod(
                "getStringForUser",
                ContentResolver::class.java,
                String::class.java,
                Int::class.javaPrimitiveType
            )
            method.invoke(null, resolver, key, userId) as? String
        }.getOrNull()

    private fun putSecureStringForUser(
        resolver: ContentResolver,
        key: String,
        value: String?,
        userId: Int
    ): Boolean =
        runCatching {
            val method = Settings.Secure::class.java.getDeclaredMethod(
                "putStringForUser",
                ContentResolver::class.java,
                String::class.java,
                String::class.java,
                Int::class.javaPrimitiveType
            )
            method.invoke(null, resolver, key, value, userId) as? Boolean ?: false
        }.getOrDefault(false)

    private fun resolveUserHandle(userId: Int): Any =
        runCatching {
            val userHandleClass = Class.forName("android.os.UserHandle")
            val ofMethod = userHandleClass.methods.firstOrNull {
                it.name == "of" && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
            }
            if (ofMethod != null) {
                return@runCatching ofMethod.invoke(null, userId) ?: error("UserHandle.of 返回 null")
            }
            val constructor = userHandleClass.getDeclaredConstructor(Int::class.javaPrimitiveType)
            constructor.isAccessible = true
            constructor.newInstance(userId) ?: error("UserHandle(int) 返回 null")
        }.getOrElse {
            error("无法构造 user=$userId 的 UserHandle")
        }

    private fun resolveCurrentUserId(): Int =
        runCatching {
            val activityManagerClass = Class.forName("android.app.ActivityManager")
            val method = activityManagerClass.getDeclaredMethod("getCurrentUser")
            method.invoke(null) as Int
        }.getOrDefault(0)

    private fun hookUserLifecycleSelfHeal(
        hooks: HookRegistrar,
        serviceClass: Class<*>?,
        methodName: String,
        parameterCount: Int
    ) {
        val logger = hooks.logger
        if (serviceClass == null) {
            hooks.skipped(
                id = "system.assistant-${methodName.removePrefix("on").lowercase()}",
                description = "VoiceInteractionManagerService.$methodName",
                detail = "未找到 VoiceInteractionManagerService，跳过 $methodName Hook"
            )
            return
        }
        val method = HookSupport.findPublicMethod(serviceClass) {
            it.name == methodName && it.parameterTypes.size == parameterCount
        }
        if (method == null) {
            hooks.missing(
                id = "system.assistant-${methodName.removePrefix("on").lowercase()}",
                description = "VoiceInteractionManagerService.$methodName",
                detail = "未找到 VoiceInteractionManagerService.$methodName/$parameterCount"
            )
            return
        }

        hooks.intercept(
            id = "system.assistant-${methodName.removePrefix("on").lowercase()}",
            executable = method,
            description = "VoiceInteractionManagerService.$methodName"
        ) { chain ->
            val targetUserId = when (methodName) {
                "onUserSwitching" -> resolveTargetUserId(chain.getArg(1))
                else -> resolveTargetUserId(chain.getArg(0))
            }
            val result = chain.proceed()
            val service = chain.getThisObject()
            captureVoiceInteractionManagerStub(service)
            // 开关关闭则不自动校正默认助理。
            if (!Prefs.isEnabled(Prefs.Keys.ASSISTANT_AUTO_CONFIG)) {
                return@intercept result
            }
            val context = HookSupport.getFieldValue(service, "mContext") as? Context
            if (context != null) {
                scheduleGoogleAssistantConfiguration(
                    context = context,
                    userId = targetUserId,
                    logger = logger,
                    handler = systemHandler,
                    forceRefresh = false,
                    rebuildWhenVerified = true,
                    requiredPreferenceKey = Prefs.Keys.ASSISTANT_AUTO_CONFIG
                )
            }
            result
        }
    }

    private fun resolveTargetUserId(targetUser: Any?): Int? =
        targetUser?.let {
            runCatching {
                val method = it.javaClass.methods.firstOrNull { candidate ->
                    candidate.name == "getUserIdentifier" && candidate.parameterTypes.isEmpty()
                } ?: return@runCatching null
                method.invoke(it) as? Int
            }.getOrNull()
        }

    private fun captureVoiceInteractionManagerStub(serviceInstance: Any) {
        val stub = HookSupport.getFieldValue(serviceInstance, "mServiceStub") ?: return
        voiceInteractionManagerStub = stub
    }

    private fun findSoftwareHotwordSession(impl: Any): Any? {
        val connection = HookSupport.getFieldValue(impl, "mHotwordDetectionConnection") ?: return null
        val detectorSessions = HookSupport.getFieldValue(connection, "mDetectorSessions") ?: return null
        val sizeMethod = HookSupport.findMethod(detectorSessions.javaClass, "size") ?: return null
        val valueAtMethod = HookSupport.findMethod(
            detectorSessions.javaClass,
            "valueAt",
            Int::class.javaPrimitiveType!!
        ) ?: return null
        val size = sizeMethod.invoke(detectorSessions) as? Int ?: return null
        repeat(size) { index ->
            val session = valueAtMethod.invoke(detectorSessions, index) ?: return@repeat
            if (session.javaClass.name == "com.android.server.voiceinteraction.SoftwareTrustedHotwordDetectorSession") {
                return session
            }
        }
        return null
    }

    private fun beginConfiguration(userId: Int): Boolean = synchronized(configurationLock) {
        usersWithConfigurationInFlight.add(userId)
    }

    private fun finishConfiguration(userId: Int) {
        synchronized(configurationLock) {
            usersWithConfigurationInFlight.remove(userId)
        }
    }

    private fun markVerified(userId: Int, now: Long) {
        lastVerifiedUserId = userId
        lastVerifiedUptime = now
    }

    private fun invalidateVerificationCache() {
        lastVerifiedUserId = UserHandleHidden.USER_NULL
        lastVerifiedUptime = 0L
    }

    private fun logShowSessionFailure(
        logger: ModuleLogger,
        key: String,
        logFailures: Boolean,
        message: () -> String
    ) {
        if (!logFailures) return
        logger.warnThrottled(key, message = message)
    }

    private fun isKeyguardLocked(context: Context): Boolean =
        runCatching {
            val keyguardManager = context.getSystemService(KeyguardManager::class.java)
            keyguardManager?.isKeyguardLocked == true
        }.getOrDefault(false)

    private object UserHandleHidden {
        const val USER_NULL = -10_000
    }
}
