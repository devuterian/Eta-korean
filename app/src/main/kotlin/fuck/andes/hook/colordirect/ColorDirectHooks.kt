package fuck.andes.hook.colordirect

import fuck.andes.core.HookSupport
import fuck.andes.core.HookInstallation
import fuck.andes.core.HookRegistrar
import fuck.andes.core.ModuleConfig
import fuck.andes.core.ModuleLogger
import fuck.andes.hook.system.CircleToSearchInvoker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Parcelable
import android.os.SystemClock
import fuck.andes.config.Prefs
import io.github.libxposed.api.XposedModule
import org.json.JSONObject

internal object ColorDirectHooks {
    private const val SOURCE = "ColorDirectActivity"
    private const val DIRECT_EXT_FINGER_TRIGGER = "fingerTrigger"
    private const val DIRECT_EXT_TOUCH_INFO = "touchInfo"
    private const val DIRECT_EXT_FINGER_COUNT = "fingerCount"

    @Volatile
    private var lastHandledUptime = 0L

    fun install(
        module: XposedModule,
        rootLogger: ModuleLogger,
        classLoader: ClassLoader
    ): HookInstallation {
        val hooks = HookRegistrar(module, rootLogger, "ColorDirect")
        return hooks.install {
            hookCollectInfoActivity(hooks, classLoader)
        }
    }

    private fun hookCollectInfoActivity(
        hooks: HookRegistrar,
        classLoader: ClassLoader
    ) {
        val logger = hooks.logger
        val activityClass = HookSupport.findClassOrNull(
            classLoader,
            ModuleConfig.COLOR_DIRECT_COLLECT_ACTIVITY_CLASS
        )
        if (activityClass == null) {
            hooks.missing(
                id = "colordirect.collect-info",
                description = "CollectInfoActivity.M(Intent)",
                detail = "CollectInfoActivity를 찾을 수 없어 두 손가락 스크린 인식 Activity를 인계할 수 없습니다."
            )
            return
        }
        val startInfoClass = HookSupport.findClassOrNull(
            classLoader,
            ModuleConfig.COLOR_DIRECT_START_INFO_CLASS
        )

        val method = HookSupport.findMethod(activityClass, "M", Intent::class.java)
        if (method == null) {
            hooks.missing(
                id = "colordirect.collect-info",
                description = "CollectInfoActivity.M(Intent)",
                detail = "CollectInfoActivity.M(Intent)를 찾을 수 없습니다."
            )
            return
        }

        hooks.intercept(
            id = "colordirect.collect-info",
            executable = method,
            description = "CollectInfoActivity.M(Intent)"
        ) { chain ->
            // 开关关闭则走原双指识屏逻辑。
            if (!Prefs.isEnabled(Prefs.Keys.DOUBLE_FINGER_CIRCLE_TO_SEARCH)) {
                return@intercept chain.proceed()
            }
            val activity = chain.getThisObject() as? Activity
            val intent = chain.getArg(0) as? Intent
            if (activity == null || !isDoubleFingerCollectIntent(intent, startInfoClass)) {
                return@intercept chain.proceed()
            }

            if (tryStartCircleToSearch(activity, logger)) {
                finishColorDirectActivity(activity)
                null
            } else {
                chain.proceed()
            }
        }
    }

    private fun tryStartCircleToSearch(context: Context, logger: ModuleLogger): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastHandledUptime <= ModuleConfig.INTERCEPT_DEDUP_WINDOW_MS) {
            logger.debug { "$SOURCE: 중복 제거 창에 적중, 중복된 두 손가락 스크린 인식 무시" }
            return true
        }

        if (!CircleToSearchInvoker.isAvailable(context, logger, SOURCE, "Breeno 두 손가락 스크린 인식 롤백")) {
            return false
        }

        if (!CircleToSearchInvoker.trigger(logger, "$SOURCE 두 손가락으로 화면 인식")) {
            return false
        }

        lastHandledUptime = now
        logger.debug { "$SOURCE: 두 손가락 화면 인식 감지, 서클 투 서치로 전달됨" }
        return true
    }

    private fun finishColorDirectActivity(activity: Activity) {
        activity.finishAndRemoveTask()
        activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
    }

    private fun isDoubleFingerCollectIntent(intent: Intent?, startInfoClass: Class<*>?): Boolean {
        val directExt = resolveDirectExt(intent, startInfoClass) ?: return false
        return runCatching {
            val json = JSONObject(directExt)
            json.optBoolean(DIRECT_EXT_FINGER_TRIGGER, false) &&
                json.optJSONObject(DIRECT_EXT_TOUCH_INFO)
                    ?.optInt(DIRECT_EXT_FINGER_COUNT, 0) == ModuleConfig.COLOR_DIRECT_DOUBLE_FINGER_COUNT
        }.getOrDefault(false)
    }

    private fun resolveDirectExt(intent: Intent?, startInfoClass: Class<*>?): String? {
        if (intent == null) return null
        intent.getStringExtra(ModuleConfig.COLOR_DIRECT_EXTRA_DIRECT_EXT)?.let { return it }
        val startInfo = resolveStartInfo(intent, startInfoClass) ?: return null
        return HookSupport.invokeNoArgs(startInfo, "getDirectExt") as? String
    }

    @Suppress("DEPRECATION")
    private fun resolveStartInfo(intent: Intent, startInfoClass: Class<*>?): Any? {
        if (startInfoClass != null && Parcelable::class.java.isAssignableFrom(startInfoClass)) {
            @Suppress("UNCHECKED_CAST")
            val typedStartInfoClass = startInfoClass as Class<Parcelable>
            intent.getParcelableExtra(
                ModuleConfig.COLOR_DIRECT_EXTRA_START_INFO,
                typedStartInfoClass
            )?.let { return it }
        }

        return runCatching {
            intent.extras?.get(ModuleConfig.COLOR_DIRECT_EXTRA_START_INFO)
        }.getOrNull()
    }
}
