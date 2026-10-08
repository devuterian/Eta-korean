package io.github.mangi.eta.agent.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import io.github.mangi.eta.R
import io.github.mangi.eta.i18n.ko

/** Overlay 不等同于前台 Activity；只在可见浮窗采集或播放音频期间提升服务类型。 */
internal class AssistantSpeechForeground(private val service: Service) {
    fun recording() = start(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, ko("Eta 正在聆听", "Eta가 듣는 중"))
    fun playback() = start(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK, ko("Eta 正在朗读", "Eta가 읽는 중"))

    private fun start(type: Int, title: String) {
        service.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, ko("助手语音", "어시스턴트 음성"), NotificationManager.IMPORTANCE_LOW),
        )
        val stop = PendingIntent.getService(service, 0,
            Intent(service, EtaAssistantOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(service, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(ko("仅在助手浮窗可见时运行", "어시스턴트 오버레이가 보일 때만 실행됩니다"))
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, ko("停止", "중지"), stop).build())
            .build()
        try {
            service.startForeground(NOTIFICATION_ID, notification, type)
        } catch (_: RuntimeException) {
            throw SpeechFailure(SpeechErrorCode.PERMISSION, ko("系统不允许当前浮窗使用音频，请检查麦克风权限和默认数字助理设置", "시스템이 현재 오버레이의 오디오 사용을 허용하지 않습니다. 마이크 권한과 기본 디지털 어시스턴트 설정을 확인하세요."))
        }
    }

    fun stop() { service.stopForeground(Service.STOP_FOREGROUND_REMOVE) }

    companion object {
        const val ACTION_STOP = "io.github.mangi.eta.STOP_ASSISTANT_SPEECH"
        private const val CHANNEL = "eta_assistant_speech"
        private const val NOTIFICATION_ID = 4203
    }
}
