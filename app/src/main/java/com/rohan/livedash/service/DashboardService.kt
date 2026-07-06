package com.rohan.livedash.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.lifecycle.LifecycleService
import com.rohan.livedash.MainActivity
import com.rohan.livedash.network.DashboardServer

class DashboardService : LifecycleService() {

    companion object {
        const val ACTION_STOP = "com.rohan.livedash.STOP_SERVER"
        const val EXTRA_PORT = "port"
        private const val NOTIF_ID = 1001
        private const val CHANNEL_ID = "livedash_server"
    }

    private var server: DashboardServer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LiveDash:DashboardService")
        wakeLock?.acquire(10 * 60 * 60 * 1000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopServer()
            stopSelf()
            return START_NOT_STICKY
        }
        val port = intent?.getIntExtra(EXTRA_PORT, 8765) ?: 8765
        startForeground(NOTIF_ID, buildNotification("Server running on :$port"))
        startServer(port)
        return START_STICKY
    }

    private fun startServer(port: Int) {
        server?.stop()
        val srv = DashboardServer(port)
        srv.onMessage = { msg -> DashboardState.addMessage(msg) }
        srv.onScreenshot = { entry -> DashboardState.addScreenshot(entry) }
        srv.onVideoFrame = { senderId, data, flags ->
            DashboardState.getOrCreateVideoStream(senderId)
            DashboardState.emitVideoFrame(senderId, data, flags)
        }
        srv.onSendersChanged = { list -> DashboardState.senders.value = list }
        srv.start()
        server = srv
        DashboardState.server = srv
        DashboardState.serverRunning.value = true
    }

    private fun stopServer() {
        try { server?.stop(500) } catch (_: Exception) {}
        DashboardState.reset()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopServer(); stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopServer()
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? { super.onBind(intent); return null }

    private fun createChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "LiveDash Server", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, DashboardService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("LiveDash")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(openIntent)
            .addAction(Notification.Action.Builder(null, "Stop", stopIntent).build())
            .setOngoing(true)
            .build()
    }
}
