package com.garo.remsound

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.garo.remsound.kit.CuePlayer
import com.garo.remsound.kit.ReceiverController
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Holds the receiver for the app's lifetime.
 *
 * The Apple port keeps the pipeline alive in the app process because iOS grants a background
 * `audio` mode; Android's equivalent is a foreground service with the `mediaPlayback` type. It
 * exists for the same reason: the UDP socket, the heartbeats and the audio track must keep
 * running with the screen off and the activity gone, and the notification is what buys that.
 *
 * The service also holds a [WifiManager.MulticastLock]. Wi-Fi hardware filters broadcast frames
 * not addressed to this device once the screen is off, which is exactly the traffic peer
 * discovery is listening for — without the lock, LAN peers stop appearing after a while and
 * only manual addresses keep working.
 */
class RemSoundService : LifecycleService() {
    private lateinit var controller: ReceiverController
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onCreate() {
        super.onCreate()
        controller = ReceiverController.shared(this)
        if (controller.cues == null) {
            controller.cues = CuePlayer(
                this,
                mapOf(
                    CuePlayer.Cue.CONNECT to R.raw.connect,
                    CuePlayer.Cue.DISCONNECT to R.raw.disconnect,
                    CuePlayer.Cue.RECEIVE_ON to R.raw.receive_on,
                    CuePlayer.Cue.RECEIVE_OFF to R.raw.receive_off,
                    CuePlayer.Cue.SEND_ON to R.raw.send_on,
                    CuePlayer.Cue.SEND_OFF to R.raw.send_off,
                    CuePlayer.Cue.PROFILE_SAVED to R.raw.save,
                ),
            ).apply { enabled = controller.cuesEnabled.value }
        }
        createNotificationChannel()
        // mediaPlayback always; microphone only when it is actually about to be used. On API 34+
        // the system checks the permission behind every declared type at the moment of the call,
        // so claiming microphone up front throws for a user who has only ever received — and
        // conversely, a persisted send resumes inside controller.start() below, which cannot open
        // the mic unless the type is already claimed. Hence asking the controller first.
        startForegroundCompat(
            NOTIFICATION_ID,
            buildNotification(),
            sending = controller.willResumeSendingAtStart,
        )
        acquireMulticastLock()
        controller.start()

        // The notification is the app's only visible surface while the screen is off, so keep it
        // saying what the receiver is actually doing — and re-declare the service type as the
        // microphone comes and goes.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                combine(controller.statusSummary, controller.sendEnabled) { status, sending ->
                    status to sending
                }.collect { (_, sending) ->
                    startForegroundCompat(NOTIFICATION_ID, buildNotification(), sending)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                controller.stop()
                stopSelf()
            }
        }
        // Restart if the system kills us: the whole point of the service is to stay up.
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        releaseMulticastLock()
        controller.stop()
        super.onDestroy()
    }

    private fun acquireMulticastLock() {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        multicastLock = wifi.createMulticastLock("RemSound.discovery").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseMulticastLock() {
        multicastLock?.takeIf { it.isHeld }?.release()
        multicastLock = null
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            // LOW: this notification is a status surface the system requires, not an alert.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, RemSoundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(controller.statusSummary.value)
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.notification_stop), stop)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "remsound-status"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.garo.remsound.STOP"

        fun start(context: Context) {
            val intent = Intent(context, RemSoundService::class.java)
            context.startForegroundService(intent)
        }
    }
}

/**
 * From API 34 the service type has to be named at the call, and the system checks the permission
 * behind every type it is given. Claiming `microphone` while only receiving would therefore throw
 * for a user who never granted RECORD_AUDIO — so the type follows whether capture is actually on.
 */
internal fun Service.startForegroundCompat(id: Int, notification: Notification, sending: Boolean) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        val type = if (sending) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        }
        startForeground(id, notification, type)
    } else {
        startForeground(id, notification)
    }
}
