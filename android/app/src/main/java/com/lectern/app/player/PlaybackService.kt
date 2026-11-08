package com.lectern.app.player

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media.session.MediaButtonReceiver
import com.lectern.app.MainActivity
import com.lectern.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps reading when the app is in the background: a foreground service with a media
 * notification, lock-screen / headset controls (MediaSession) and a CPU wake lock so
 * speech keeps being generated with the screen off.
 */
class PlaybackService : Service() {

    companion object {
        private const val CHANNEL = "playback"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java))
        }
    }

    private lateinit var session: MediaSessionCompat
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var noisyRegistered = false

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) Reader.pause() // headphones unplugged
        }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Lectura", NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false)
        })
        session = MediaSessionCompat(this, "Lectern").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = Reader.play()
                override fun onPause() = Reader.pause()
                override fun onSkipToNext() = Reader.next()
                override fun onSkipToPrevious() = Reader.previous()
                override fun onStop() = Reader.stop()
            })
            setSessionActivity(openAppIntent())
            isActive = true
        }
        startInForeground()

        scope.launch {
            Reader.state
                .map { Triple(it.status, it.pos, it.book?.title) }
                .distinctUntilChanged()
                .collect { update() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        MediaButtonReceiver.handleIntent(session, intent)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (Reader.state.value.status != Reader.Status.PLAYING) stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        if (noisyRegistered) unregisterReceiver(noisyReceiver)
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    private fun startInForeground() {
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
        )
    }

    private fun update() {
        val s = Reader.state.value
        val playing = s.status == Reader.Status.PLAYING || s.status == Reader.Status.LOADING

        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, s.book?.title ?: "Lectern")
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, s.current?.let { s.book?.sections?.getOrNull(it.section)?.title } ?: "")
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, s.current?.text ?: "")
                .build(),
        )
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_STOP,
                )
                .setState(
                    when (s.status) {
                        Reader.Status.PLAYING -> PlaybackStateCompat.STATE_PLAYING
                        Reader.Status.LOADING -> PlaybackStateCompat.STATE_BUFFERING
                        Reader.Status.PAUSED -> PlaybackStateCompat.STATE_PAUSED
                        else -> PlaybackStateCompat.STATE_STOPPED
                    },
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f,
                )
                .build(),
        )

        if (playing) acquireWakeLock() else releaseWakeLock()
        setNoisyReceiver(s.status == Reader.Status.PLAYING)

        when (s.status) {
            Reader.Status.IDLE, Reader.Status.DONE -> {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                if (androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()) {
                    @SuppressLint("MissingPermission")
                    val n = buildNotification()
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n)
                }
            }
        }
    }

    private fun buildNotification(): Notification {
        val s = Reader.state.value
        val playing = s.status == Reader.Status.PLAYING || s.status == Reader.Status.LOADING
        val section = s.current?.let { s.book?.sections?.getOrNull(it.section)?.title }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_lectern)
            .setContentTitle(s.book?.title ?: "Lectern")
            .setContentText(section ?: "")
            .setContentIntent(openAppIntent())
            .setDeleteIntent(MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_STOP))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(android.R.drawable.ic_media_previous, "Anterior",
                MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS))
            .addAction(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "Pausa" else "Reproducir",
                MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PLAY_PAUSE),
            )
            .addAction(android.R.drawable.ic_media_next, "Siguiente",
                MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_NEXT))
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2),
            )
            .build()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Lectern:playback")
            .apply { acquire(6 * 60 * 60 * 1000L) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun setNoisyReceiver(on: Boolean) {
        if (on && !noisyRegistered) {
            ContextCompat.registerReceiver(this, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
            noisyRegistered = true
        } else if (!on && noisyRegistered) {
            unregisterReceiver(noisyReceiver)
            noisyRegistered = false
        }
    }
}
