package fuck.andes.core

import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap

/** 进程内日志节流器；使用单调时钟，不受系统时间调整影响。 */
internal class LogThrottle(
    private val uptimeMillis: () -> Long = SystemClock::uptimeMillis
) {
    private val lastAcceptedAt = ConcurrentHashMap<String, Long>()

    fun shouldLog(key: String, windowMs: Long): Boolean {
        require(key.isNotBlank()) { "로그 스로틀 키는 비워둘 수 없습니다." }
        require(windowMs >= 0L) { "로그 스로틀 윈도우는 음수일 수 없습니다." }

        val now = uptimeMillis()
        var accepted = false
        lastAcceptedAt.compute(key) { _, previous ->
            if (previous == null || now < previous || now - previous >= windowMs) {
                accepted = true
                now
            } else {
                previous
            }
        }
        return accepted
    }
}
