package fuck.andes.hook.system

import fuck.andes.core.HookSupport
import fuck.andes.core.HookInstallation
import fuck.andes.core.HookRegistrar
import fuck.andes.core.ModuleConfig
import fuck.andes.core.ModuleLogger
import fuck.andes.core.safeLogType

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Message
import android.os.SystemClock
import fuck.andes.config.Prefs
import io.github.libxposed.api.XposedModule

internal object PowerHooks {
    private const val OEM_ASSISTANT_HAPTIC_EFFECT_ID = 0
    private const val OEM_ASSISTANT_HAPTIC_REASON = "Speech - Long Press"

    @Volatile
    private var lastInterceptUptime = 0L

    private enum class LaunchResult {
        LAUNCHED,
        ACTIVITY_FALLBACK_REQUIRED,
        NOT_HANDLED
    }

    fun install(
        module: XposedModule,
        rootLogger: ModuleLogger,
        classLoader: ClassLoader
    ): HookInstallation {
        val hooks = HookRegistrar(module, rootLogger, "Power")
        return hooks.install {
            // 当前机型实测证明 OplusSpeechHandler 是必要路径。
            // 开关在拦截回调里判断，关闭则走原逻辑。
            hookOplusSpeechHandler(hooks, classLoader)
        }
    }

    private fun hookOplusSpeechHandler(
        hooks: HookRegistrar,
        classLoader: ClassLoader
    ) {
        val logger = hooks.logger
        val handlerClass = HookSupport.findClassOrNull(classLoader, ModuleConfig.OP_LUS_SPEECH_HANDLER_CLASS)
        val handleMessageMethod = handlerClass?.let {
            HookSupport.findMethod(it, "handleMessage", Message::class.java)
        }
        if (handleMessageMethod == null) {
            hooks.missing(
                id = "system.power-assist-message",
                description = "OplusSpeechHandler.handleMessage",
                detail = "OplusSpeechHandler.handleMessage(Message)를 찾을 수 없습니다"
            )
            return
        }

        hooks.intercept(
            id = "system.power-assist-message",
            executable = handleMessageMethod,
            description = "PhoneWindowManagerExtImpl\$OplusSpeechHandler.handleMessage"
        ) { chain ->
            val message = chain.getArg(0) as? Message
            if (message?.what != ModuleConfig.OP_LUS_ASSIST_MESSAGE_WHAT) {
                return@intercept chain.proceed()
            }

            // 开关关闭则走原逻辑（小布），不拦截。
            if (!Prefs.isEnabled(Prefs.Keys.POWER_KEY_TAKEOVER)) {
                return@intercept chain.proceed()
            }

            val handler = chain.getThisObject() as? Handler
            val pwm = resolvePhoneWindowManager(chain.getThisObject())
            if (pwm == null) {
                logger.warnThrottled("oplus_speech_missing_pwm") {
                    "OplusSpeechHandler가 PhoneWindowManager를 파싱하지 못해 기존 로직으로 복귀합니다"
                }
                return@intercept chain.proceed()
            }

            when (tryLaunchGoogleAssist(
                logger = logger,
                phoneWindowManager = pwm,
                source = "OplusSpeechHandler"
            )) {
                LaunchResult.LAUNCHED -> null
                LaunchResult.ACTIVITY_FALLBACK_REQUIRED -> {
                    val activityStarted = tryStartGoogleAssistActivityFallback(
                        logger = logger,
                        phoneWindowManager = pwm,
                        source = "OplusSpeechHandler"
                    )
                    // Activity 兜底能处理本次触发，但仍需后台修复首选 voiceinteraction 路径。
                    scheduleBackgroundRecovery(
                        handler = handler,
                        logger = logger,
                        phoneWindowManager = pwm,
                        source = "OplusSpeechHandler"
                    )
                    if (activityStarted) {
                        null
                    } else {
                        // 当前触发不再等待后台修复；Google 两条快速路径都失败时立即回退小布。
                        chain.proceed()
                    }
                }
                LaunchResult.NOT_HANDLED -> chain.proceed()
            }
        }
    }

    private fun tryLaunchGoogleAssist(
        logger: ModuleLogger,
        phoneWindowManager: Any,
        source: String
    ): LaunchResult {
        val context = HookSupport.getFieldValue(phoneWindowManager, "mContext") as? Context
        if (context == null) {
            logger.warnThrottled("${source}_missing_context") {
                "${source}에 mContext가 없어 기존 로직으로 복귀합니다"
            }
            return LaunchResult.NOT_HANDLED
        }

        if (!HookSupport.isPackageInstalled(context, ModuleConfig.GOOGLE_PACKAGE)) {
            logger.warnThrottled("${source}_google_missing") {
                "$source: Google 앱이 설치되어 있지 않아 기존 로직으로 복귀합니다"
            }
            return LaunchResult.NOT_HANDLED
        }

        val now = SystemClock.uptimeMillis()
        if (now - lastInterceptUptime <= ModuleConfig.INTERCEPT_DEDUP_WINDOW_MS) {
            logger.debug { "$source: 중복 윈도우에 감지되어 중복 트리거를 무시합니다" }
            return LaunchResult.LAUNCHED
        }

        if (AssistantManager.showGoogleAssistantSession(
                context = context,
                logger = logger,
                source = source,
                logFailures = false
            )) {
            finalizeSuccessfulLaunch(logger, phoneWindowManager, source, now)
            logger.debug { "$source: voiceinteraction으로 Google을 실행했습니다" }
            return LaunchResult.LAUNCHED
        }

        return LaunchResult.ACTIVITY_FALLBACK_REQUIRED
    }

    private fun tryStartGoogleAssistActivityFallback(
        logger: ModuleLogger,
        phoneWindowManager: Any,
        source: String
    ): Boolean {
        val context = HookSupport.getFieldValue(phoneWindowManager, "mContext") as? Context
            ?: return false
        val now = SystemClock.uptimeMillis()
        return startGoogleAssistActivity(
            context = context,
            logger = logger,
            phoneWindowManager = phoneWindowManager,
            source = source,
            now = now,
            action = Intent.ACTION_ASSIST
        ) || startGoogleAssistActivity(
            context = context,
            logger = logger,
            phoneWindowManager = phoneWindowManager,
            source = source,
            now = now,
            action = Intent.ACTION_VOICE_COMMAND
        )
    }

    private fun startGoogleAssistActivity(
        context: Context,
        logger: ModuleLogger,
        phoneWindowManager: Any,
        source: String,
        now: Long,
        action: String
    ): Boolean {
        val intent = Intent(action).apply {
            setPackage(ModuleConfig.GOOGLE_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val resolves = runCatching { HookSupport.resolvesActivity(context, intent) }
            .getOrElse { throwable ->
                logger.warnThrottled("${source}_${action}_resolve_failed") {
                    "$source: Google $action 진입점 조회에 실패했습니다, type=${throwable.safeLogType()}"
                }
                false
            }
        if (!resolves) {
            logger.warnThrottled("${source}_${action}_missing") {
                "$source: Google이 ${action}을 노출하지 않아 기존 로직으로 복귀합니다"
            }
            return false
        }

        return runCatching {
            context.startActivity(intent)
            finalizeSuccessfulLaunch(logger, phoneWindowManager, source, now)
            logger.debug { "$source: ${action}으로 Google을 실행했습니다" }
            true
        }.getOrElse { throwable ->
            logger.warnThrottled("${source}_${action}_failed") {
                "$source: $action 실행에 실패하여 기존 로직으로 복귀합니다, type=${throwable.safeLogType()}"
            }
            false
        }
    }

    private fun finalizeSuccessfulLaunch(
        logger: ModuleLogger,
        phoneWindowManager: Any,
        source: String,
        now: Long
    ) {
        markLaunchSuccess(now)
        maybePerformAssistantHapticFeedback(logger, phoneWindowManager, source)
    }

    private fun maybePerformAssistantHapticFeedback(
        logger: ModuleLogger,
        phoneWindowManager: Any,
        source: String
    ) {
        if (invokeOplusAssistantHapticFeedback(phoneWindowManager)) {
            logger.debug { "$source: Oplus 기본 에이전트 진동을 보완 전송했습니다" }
            return
        }

        logger.warnThrottled("${source}_assistant_haptic_missing") {
            "$source: Oplus 기본 길게 누르기 에이전트 진동 진입점을 찾을 수 없습니다"
        }
    }

    private fun invokeOplusAssistantHapticFeedback(phoneWindowManager: Any): Boolean {
        val wrapper = HookSupport.invokeNoArgs(phoneWindowManager, "getWrapper") ?: return false
        val wrapperMethod = HookSupport.findMethod(
            wrapper.javaClass,
            "performHapticFeedback",
            Int::class.javaPrimitiveType!!,
            String::class.java
        ) ?: return false
        return runCatching {
            wrapperMethod.invoke(wrapper, OEM_ASSISTANT_HAPTIC_EFFECT_ID, OEM_ASSISTANT_HAPTIC_REASON)
            true
        }.getOrDefault(false)
    }

    private fun scheduleBackgroundRecovery(
        handler: Handler?,
        logger: ModuleLogger,
        phoneWindowManager: Any,
        source: String
    ) {
        if (handler == null) {
            logger.warnThrottled("${source}_recovery_missing_handler") {
                "$source: OplusSpeechHandler 인스턴스를 얻을 수 없어 백그라운드 설정 복구를 건너뜁니다"
            }
            return
        }

        val context = HookSupport.getFieldValue(phoneWindowManager, "mContext") as? Context
        if (context == null) {
            logger.warnThrottled("${source}_recovery_missing_context") {
                "$source: mContext를 얻을 수 없어 백그라운드 설정 복구를 건너뜁니다"
            }
            return
        }

        val scheduled = AssistantManager.scheduleGoogleAssistantRecovery(
            context = context,
            logger = logger,
            handler = handler,
            forceRefresh = true,
            requiredPreferenceKey = Prefs.Keys.POWER_KEY_TAKEOVER
        )
        if (!scheduled) {
            logger.warnThrottled("${source}_configuration_schedule_failed") {
                "$source: 기본 에이전트 백그라운드 복구를 큐에 추가할 수 없습니다"
            }
        } else {
            logger.warnThrottled("${source}_assistant_recovery_pending") {
                "$source: voiceinteraction 실패, 기본 에이전트 설정을 백그라운드에서 복구했습니다"
            }
        }
    }

    private fun markLaunchSuccess(now: Long) {
        lastInterceptUptime = now
    }

    private fun resolvePhoneWindowManager(handlerInstance: Any): Any? {
        val owner = HookSupport.getFieldValue(handlerInstance, "this$0") ?: return null
        HookSupport.findField(owner.javaClass, "mPhoneWindowManager")?.let { field ->
            return runCatching { field.get(owner) }.getOrNull()
        }

        var current: Class<*>? = owner.javaClass
        while (current != null) {
            current.declaredFields.forEach { field ->
                if (field.type.name == ModuleConfig.PHONE_WINDOW_MANAGER_CLASS) {
                    field.isAccessible = true
                    return runCatching { field.get(owner) }.getOrNull()
                }
            }
            current = current.superclass
        }
        return null
    }
}
