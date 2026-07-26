package fuck.andes.hook.system

import fuck.andes.core.HookSupport
import fuck.andes.core.HookInstallation
import fuck.andes.core.HookRegistrar
import fuck.andes.core.ModuleConfig
import fuck.andes.core.ModuleLogger

import android.content.Context
import fuck.andes.config.Prefs
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

internal object SystemUiHooks {
    private const val OCR_LONG_PRESS_HAPTIC_EFFECT_ID = 1

    @Volatile
    private var systemUiClassLoader: ClassLoader? = null

    @Volatile
    private var vibrationHelperGetInstanceMethod: Method? = null

    @Volatile
    private var vibrationHelperVibrateCustomizedMethod: Method? = null

    fun install(
        module: XposedModule,
        rootLogger: ModuleLogger,
        classLoader: ClassLoader
    ): HookInstallation {
        val hooks = HookRegistrar(module, rootLogger, "SystemUI")
        val logger = hooks.logger
        return hooks.install {
            systemUiClassLoader = classLoader
            val businessClass = HookSupport.findClassOrNull(classLoader, ModuleConfig.OCR_BUSINESS_CLASS)
            val onLongPressedMethod = businessClass?.let {
                HookSupport.findMethod(it, "onLongPressed")
            }
            if (onLongPressedMethod == null) {
                hooks.missing(
                    id = "systemui.ocr-long-press",
                    description = "OplusOcrScreenBusiness.onLongPressed",
                    detail = "OplusOcrScreenBusiness.onLongPressed()를 찾을 수 없습니다"
                )
                return@install
            }

            hooks.intercept(
                id = "systemui.ocr-long-press",
                executable = onLongPressedMethod,
                description = "OplusOcrScreenBusiness.onLongPressed"
            ) { chain ->
                // 开关关闭则走原 OCR 逻辑。
                if (!Prefs.isEnabled(Prefs.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH)) {
                    return@intercept chain.proceed()
                }
                val context = resolveContext(chain.getThisObject())
                if (context == null) {
                    logger.warnThrottled("systemui_context") {
                        "SystemUI에서 Context를 얻을 수 없어 기존 OCR 로직으로 복귀합니다"
                    }
                    return@intercept chain.proceed()
                }

                if (!CircleToSearchInvoker.isAvailable(
                        context,
                        logger,
                        "SystemUI",
                        "기존 OCR 로직으로 복귀합니다"
                    )
                ) {
                    return@intercept chain.proceed()
                }

                if (CircleToSearchInvoker.trigger(logger, "SystemUI")) {
                    performOriginalLongPressHaptic(context, logger)
                    null
                } else {
                    chain.proceed()
                }
            }
        }
    }

    private fun resolveContext(target: Any): Context? =
        HookSupport.invokeNoArgs(target, "getContext") as? Context
            ?: HookSupport.getFieldValue(target, "context") as? Context
            ?: HookSupport.getFieldValue(target, "mContext") as? Context
            ?: HookSupport.getFieldValue(target, "mOcrContext") as? Context

    private fun performOriginalLongPressHaptic(context: Context, logger: ModuleLogger) {
        val vibrationHelper = resolveVibrationHelper(context) ?: run {
            logger.warnThrottled("systemui_cts_vibration_helper_missing") {
                "SystemUI: 원본 VibrationHelper를 가져올 수 없어 네비게이션 바 길게 누르기 진동을 건너뜁니다."
            }
            return
        }

        if (!invokeVibrateCustomized(vibrationHelper, context)) {
            logger.warnThrottled("systemui_cts_linear_haptic_failed") {
                "SystemUI: 원본 네비게이션 바 길게 누르기 진동 호출 실패"
            }
        }
    }

    private fun resolveVibrationHelper(context: Context): Any? {
        val classLoader = systemUiClassLoader ?: return null
        val helperClass = HookSupport.findClassOrNull(
            classLoader,
            "com.oplus.systemui.navigationbar.gesture.VibrationHelper"
        ) ?: return null
        val getInstance = vibrationHelperGetInstanceMethod
            ?: HookSupport.findMethod(helperClass, "getInstance", Context::class.java)
                ?.also { vibrationHelperGetInstanceMethod = it }
            ?: return null
        return runCatching { getInstance.invoke(null, context) }.getOrNull()
    }

    private fun invokeVibrateCustomized(vibrationHelper: Any, context: Context): Boolean {
        val method = vibrationHelperVibrateCustomizedMethod
            ?: HookSupport.findMethod(
                vibrationHelper.javaClass,
                "vibrateCustomized",
                Context::class.java,
                Int::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!
            )?.also { vibrationHelperVibrateCustomizedMethod = it }
            ?: return false
        return runCatching {
            method.invoke(vibrationHelper, context, OCR_LONG_PRESS_HAPTIC_EFFECT_ID, false)
            true
        }.getOrDefault(false)
    }
}
