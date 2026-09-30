package com.gmail.absolutebollockscreations.gattlibrary.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import timber.log.Timber

/**
 * Keeps the process alive while the FTMS peripheral is advertising or serving a
 * client.
 *
 * Without this Android will trim the process when FitBridge is backgrounded and
 * the BluetoothGattServer goes with it - the client silently loses its connection
 * mid-workout.
 *
 * Startup is an acknowledged handshake, not a fire-and-forget Intent. The
 * controller does not enter RUNNING or start notifications until
 * [startForeground] has succeeded. Tokens make a stop concurrent with startup a
 * cancellation barrier: a late onStartCommand cannot resurrect a peripheral that
 * has already been stopped.
 */
class FtmsForegroundService : Service() {

    private val foregroundStopped = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "FTMS pass through"
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Sharing workout data"
        val token = intent?.getLongExtra(EXTRA_TOKEN, -1L) ?: -1L
        // stop() advances the barrier before asking Android to stop the Service.
        // Rejecting older tokens closes the start/stop delivery-order race.
        if (token <= cancelledThroughToken.get()) {
            acknowledge(
                token,
                Result.failure(CancellationException("Foreground start cancelled")),
            )
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        val result = runCatching { startAsForeground(title, text) }
        acknowledge(token, result)
        if (result.isFailure) {
            Timber.tag(TAG).e(result.exceptionOrNull(), "startForeground failed")
            stopSelf(startId)
        }
        // Do not restart automatically: the controller owns the lifecycle and will
        // start us again if it is still running.
        return START_NOT_STICKY
    }

    private fun startAsForeground(title: String, text: String) {
        ensureChannel()
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        val notification: Notification = builder
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        if (foregroundStopped.compareAndSet(false, true)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "FTMS pass through",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shown while your workout is being shared over Bluetooth"
                setShowBadge(false)
            },
        )
    }

    companion object {
        private const val TAG = "FtmsFgService"
        private const val CHANNEL_ID = "ftms_pass_through"
        private const val NOTIFICATION_ID = 0x46544D53 // "FTMS"

        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_TOKEN = "token"
        private const val ACK_TIMEOUT_MS = 5_000L

        private val nextToken = AtomicLong(0L)
        private val cancelledThroughToken = AtomicLong(0L)
        private val main = Handler(Looper.getMainLooper())

        // Process-local bridge from Android's asynchronous Service delivery back
        // to the controller's start transaction. The timeout guarantees a start
        // call cannot hang forever if the OS never delivers it.
        private val pending = ConcurrentHashMap<Long, (Result<Unit>) -> Unit>()

        fun start(context: Context, title: String, text: String, onResult: (Result<Unit>) -> Unit) {
            val token = nextToken.incrementAndGet()
            val intent = Intent(context, FtmsForegroundService::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_TEXT, text)
                .putExtra(EXTRA_TOKEN, token)
            pending[token] = onResult
            main.postDelayed({
                pending.remove(token)?.invoke(
                    Result.failure(
                        IllegalStateException("Foreground service did not acknowledge startup"),
                    ),
                )
            }, ACK_TIMEOUT_MS)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (t: Throwable) {
                Timber.tag(TAG).e(t, "could not start foreground service")
                pending.remove(token)?.invoke(Result.failure(t))
            }
        }

        private fun acknowledge(token: Long, result: Result<Unit>) {
            if (token < 0L) return
            main.post { pending.remove(token)?.invoke(result) }
        }

        fun stop(context: Context) {
            // Mark every issued token cancelled before stopService(). Android may
            // still deliver a previously queued start Intent after this call.
            val cancelledThrough = nextToken.get()
            cancelledThroughToken.updateAndGet { current -> maxOf(current, cancelledThrough) }
            pending.entries.removeIf { (token, callback) ->
                if (token <= cancelledThrough) {
                    main.post {
                        callback(
                            Result.failure(
                                CancellationException("Foreground service stopped"),
                            ),
                        )
                    }
                    true
                } else {
                    false
                }
            }
            runCatching { context.stopService(Intent(context, FtmsForegroundService::class.java)) }
        }
    }
}
