package io.github.romanvht.byedpi.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.romanvht.byedpi.data.Mode
import io.github.romanvht.byedpi.data.PAUSE_ACTION
import io.github.romanvht.byedpi.data.RESTART_ACTION
import io.github.romanvht.byedpi.data.RESUME_ACTION
import io.github.romanvht.byedpi.data.START_ACTION
import io.github.romanvht.byedpi.data.STOP_ACTION
import io.github.romanvht.byedpi.data.TG_WS_START_ACTION
import io.github.romanvht.byedpi.data.TG_WS_STOP_ACTION
import io.github.romanvht.byedpi.ewenloy.tgws.EwenloyTgWsServiceExtension
import io.github.romanvht.byedpi.services.ServiceManager
import io.github.romanvht.byedpi.utility.getPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ServiceActionReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "ServiceActionReceiver"
        const val EXTRA_MODE = "mode"
        const val EXTRA_PID = "pid"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        // TG WS с плитки быстрых настроек: тот же receiver и путь запуска, что у ByeDPI
        if (action == TG_WS_START_ACTION || action == TG_WS_STOP_ACTION) {
            val pending = goAsync()
            CoroutineScope(Dispatchers.Main.immediate).launch {
                try {
                    val appCtx = context.applicationContext
                    val preferences = appCtx.getPreferences()
                    val tgWs = EwenloyTgWsServiceExtension().apply { initialize(appCtx, preferences) }
                    if (action == TG_WS_START_ACTION) tgWs.start(preferences) else tgWs.stop()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to handle TG WS action: $action", e)
                } finally {
                    pending.finish()
                }
            }
            return
        }

        if (action != START_ACTION && action != RESTART_ACTION && action != RESUME_ACTION &&
            action != STOP_ACTION && action != PAUSE_ACTION) {
            return
        }
        val modeName = intent.getStringExtra(EXTRA_MODE)
        val mode = Mode.entries.firstOrNull { it.name == modeName } ?: return
        val pending = goAsync()

        CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                ServiceManager.handleAction(context.applicationContext, action, mode, intent.getIntExtra(EXTRA_PID, 0))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle service action: $action", e)
            } finally {
                pending.finish()
            }
        }
    }
}
