package com.adzannotif.platform.audio

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.adzannotif.R
import com.adzannotif.core.prayer.Prayer
import com.adzannotif.domain.model.AdhanVoice
import com.adzannotif.platform.notification.NotificationHelper
import com.adzannotif.platform.receiver.StopAdhanReceiver
import com.adzannotif.presentation.localization.prayerLabel
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Keeps alarm playback alive after the alarm receiver finishes its callback.
 * Playback ends only when the configured source reports completion, an error is
 * reported, or the user explicitly dismisses/stops the alarm.
 */
@AndroidEntryPoint
class AdhanPlaybackService : Service() {

    @Inject
    lateinit var audioGateway: AudioGateway

    private var powerButtonReceiver: BroadcastReceiver? = null
    private var playbackStartedAt = 0L
    private var lastScreenToggleAt = 0L
    private var currentPrayer: Prayer? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val voice = intent?.getStringExtra(EXTRA_VOICE)
            ?.let { name -> runCatching { AdhanVoice.valueOf(name) }.getOrNull() }
            ?: return stopForInvalidRequest(startId)
        val prayer = intent.getStringExtra(EXTRA_PRAYER)
            ?.let { name -> runCatching { Prayer.valueOf(name) }.getOrNull() }
        val prayerTitle = intent.getStringExtra(EXTRA_PRAYER_TITLE)
            ?.takeIf(String::isNotBlank)
            ?: getString(R.string.alarm_default_title)
        val locationName = intent.getStringExtra(EXTRA_LOCATION_NAME)
            ?.takeIf(String::isNotBlank)
            ?: getString(R.string.location_unavailable)
        val customUriString = intent.getStringExtra(EXTRA_CUSTOM_URI)

        startForeground(
            SERVICE_NOTIFICATION_ID,
            buildPlaybackNotification(prayer, prayerTitle, locationName),
        )
        currentPrayer = prayer
        playbackStartedAt = SystemClock.elapsedRealtime()
        lastScreenToggleAt = 0L
        registerPowerButtonReceiver()
        audioGateway.playAdhan(
            voice = voice,
            customUriString = customUriString,
            onCompletion = {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelfResult(startId)
            },
        )
        return START_REDELIVER_INTENT
    }

    /**
     * Pressing the power button toggles the screen (SCREEN_ON / SCREEN_OFF). Two
     * toggles within [DOUBLE_PRESS_WINDOW_MS] are treated as a double press and stop
     * the adhan. Toggles right after playback starts are ignored, because the
     * full-screen alarm itself turns the screen on.
     */
    private fun registerPowerButtonReceiver() {
        if (powerButtonReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val now = SystemClock.elapsedRealtime()
                if (now - playbackStartedAt < IGNORE_AFTER_START_MS) {
                    lastScreenToggleAt = 0L
                    return
                }
                if (lastScreenToggleAt != 0L && now - lastScreenToggleAt <= DOUBLE_PRESS_WINDOW_MS) {
                    lastScreenToggleAt = 0L
                    stopByPowerButton()
                } else {
                    lastScreenToggleAt = now
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        runCatching {
            ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            powerButtonReceiver = receiver
        }.onFailure { Log.w(TAG, "Could not listen for power button presses", it) }
    }

    private fun unregisterPowerButtonReceiver() {
        powerButtonReceiver?.let { runCatching { unregisterReceiver(it) } }
        powerButtonReceiver = null
    }

    private fun stopByPowerButton() {
        Log.d(TAG, "Power button pressed twice; stopping adhan")
        audioGateway.stop()
        currentPrayer?.let {
            getSystemService(NotificationManager::class.java)
                ?.cancel(NotificationHelper.NOTIFICATION_ID_ADHAN + it.ordinal)
        }
        sendBroadcast(Intent(ACTION_ADHAN_STOPPED).setPackage(packageName))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopForInvalidRequest(startId: Int): Int {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(startId)
        return START_NOT_STICKY
    }

    private fun buildPlaybackNotification(
        prayer: Prayer?,
        prayerTitle: String,
        locationName: String,
    ): Notification {
        val localizedPrayer = prayer?.let { prayerLabel(this, it) } ?: prayerTitle
        val stopIntent = Intent(this, StopAdhanReceiver::class.java).apply {
            action = StopAdhanReceiver.ACTION_STOP_ADHAN
            prayer?.let {
                putExtra(
                    StopAdhanReceiver.EXTRA_NOTIFICATION_ID,
                    NotificationHelper.NOTIFICATION_ID_ADHAN + it.ordinal,
                )
            }
        }
        val stopPendingIntent = PendingIntent.getBroadcast(
            this,
            STOP_REQUEST_CODE,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, NotificationHelper.CHANNEL_ADHAN_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(getString(R.string.notification_adhan_title, localizedPrayer))
            .setContentText(getString(R.string.notification_adhan_content, localizedPrayer, locationName))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.notification_stop_audio),
                stopPendingIntent,
            )
            .build()
    }

    override fun onDestroy() {
        unregisterPowerButtonReceiver()
        audioGateway.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val ACTION_PLAY = "com.adzannotif.action.PLAY_ADHAN"
        private const val EXTRA_VOICE = "extra_voice"
        private const val EXTRA_CUSTOM_URI = "extra_custom_uri"
        private const val EXTRA_PRAYER = "extra_prayer"
        private const val EXTRA_PRAYER_TITLE = "extra_prayer_title"
        private const val EXTRA_LOCATION_NAME = "extra_location_name"
        private const val SERVICE_NOTIFICATION_ID = 9001
        private const val STOP_REQUEST_CODE = 9002
        private const val TAG = "AdhanPlaybackService"
        private const val DOUBLE_PRESS_WINDOW_MS = 2_000L
        private const val IGNORE_AFTER_START_MS = 1_500L

        /** Sent (package-local) when playback is stopped by the power button. */
        const val ACTION_ADHAN_STOPPED = "com.satriaweb.adzanmi.action.ADHAN_STOPPED"

        fun start(
            context: Context,
            voice: AdhanVoice,
            customUriString: String?,
            prayer: Prayer,
            prayerTitle: String,
            locationName: String,
        ) {
            val intent = Intent(context, AdhanPlaybackService::class.java).apply {
                action = ACTION_PLAY
                putExtra(EXTRA_VOICE, voice.name)
                putExtra(EXTRA_CUSTOM_URI, customUriString)
                putExtra(EXTRA_PRAYER, prayer.name)
                putExtra(EXTRA_PRAYER_TITLE, prayerTitle)
                putExtra(EXTRA_LOCATION_NAME, locationName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AdhanPlaybackService::class.java))
        }
    }
}
