package io.github.romanvht.byedpi.services

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Process
import io.github.romanvht.byedpi.R
import io.github.romanvht.byedpi.data.*
import io.github.romanvht.byedpi.ewenloy.tgws.EwenloyTgWsServiceExtension
import io.github.romanvht.byedpi.receiver.ServiceActionReceiver
import io.github.romanvht.byedpi.utility.*

class ByeDpiProxyService : Service() {
    companion object {
        internal const val FOREGROUND_SERVICE_ID = 2
        private const val PAUSE_NOTIFICATION_ID = 3
        private const val NOTIFICATION_CHANNEL_ID = "ByeDPI Proxy"
    }

    private val tgWsExt = EwenloyTgWsServiceExtension()

    private val session by lazy { NativeSession(this) }

    override fun onCreate() {
        super.onCreate()
        registerNotificationChannel(this, NOTIFICATION_CHANNEL_ID, R.string.proxy_channel_name)
        tgWsExt.initialize(this, getPreferences())
    }

    override fun onDestroy() {
        super.onDestroy()
        session.destroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        return when (intent.action) {
            EngineProtocol.CONTROL_ACTION -> session.binder
            EngineProtocol.BOUND_CONTROL_ACTION -> if (session.onBound()) session.binder else null
            else -> null
        }
    }

    override fun onUnbind(intent: Intent): Boolean {
        if (intent.action == EngineProtocol.BOUND_CONTROL_ACTION) session.onUnbound()
        return super.onUnbind(intent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            START_ACTION -> {
                startForeground()
                session.onStarted()
            }
            null, RESUME_ACTION -> {
                startForeground()
                session.onStarted()
                sendControl(START_ACTION)
            }
            STOP_ACTION -> {
                sendControl(STOP_ACTION)
                session.stop()
            }
            PAUSE_ACTION -> {
                sendControl(PAUSE_ACTION)
                session.stop()
            }
            else -> session.stop()
        }
        return START_NOT_STICKY
    }

    private fun startForeground() {
        val notifications = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notifications.cancel(PAUSE_NOTIFICATION_ID)
        val notification: Notification = createConnectionNotification(
            this,
            NOTIFICATION_CHANNEL_ID,
            R.string.notification_title,
            R.string.proxy_notification_content,
            Mode.Proxy,
            tgWsExt.statusLine().takeIf { it.isNotBlank() },
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(FOREGROUND_SERVICE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(FOREGROUND_SERVICE_ID, notification)
        }
    }

    private fun sendControl(action: String) {
        sendBroadcast(Intent(this, ServiceActionReceiver::class.java)
            .setAction(action)
            .putExtra("pid", Process.myPid())
            .putExtra(ServiceActionReceiver.EXTRA_MODE, Mode.Proxy.name))
    }
}
