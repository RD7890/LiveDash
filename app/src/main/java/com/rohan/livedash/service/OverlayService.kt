package com.rohan.livedash.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.rohan.livedash.MainActivity
import com.rohan.livedash.data.Message
import com.rohan.livedash.data.MessageType
import com.rohan.livedash.network.SenderClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.UUID

class OverlayService : LifecycleService() {

    companion object {
        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val EXTRA_SERVER_IP = "serverIp"
        const val EXTRA_SERVER_PORT = "serverPort"
        const val EXTRA_SENDER_NAME = "senderName"
        private const val NOTIF_ID = 2001
        private const val CHANNEL_ID = "livedash_overlay"
    }

    private var windowManager: WindowManager? = null
    private var overlayRoot: FrameLayout? = null
    private var overlayParams: LayoutParams? = null
    private var chatPanel: android.view.View? = null
    private var chatPanelParams: LayoutParams? = null
    private var chatPanelVisible = false
    private var chatMsgContainer: LinearLayout? = null
    private var chatScrollView: ScrollView? = null
    private var savedOverlayY = -1

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var streamingJob: Job? = null
    private var client: SenderClient? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var targetW = 1280
    private var targetH = 720
    private var dpi = 320

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LiveDash:OverlayService")
        wakeLock?.acquire(10 * 60 * 60 * 1000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> { cleanup(); stopSelf(); return START_NOT_STICKY }
            ACTION_START -> {
                startForeground(NOTIF_ID, buildNotification())
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
                val serverIp = intent.getStringExtra(EXTRA_SERVER_IP) ?: return START_NOT_STICKY
                val serverPort = intent.getIntExtra(EXTRA_SERVER_PORT, 8765)
                val senderName = intent.getStringExtra(EXTRA_SENDER_NAME) ?: "Sender"
                setupClient(serverIp, serverPort, senderName)
                if (data != null) setupCapture(resultCode, data)
                showOverlay()
                observeState()
            }
        }
        return START_STICKY
    }

    private fun observeState() {
        lifecycleScope.launch {
            OverlayState.connected.collect { connected ->
                if (!connected) {
                    withContext(Dispatchers.Main) { cleanup(); stopSelf() }
                }
            }
        }
        lifecycleScope.launch {
            OverlayState.messages.collect { msgs ->
                withContext(Dispatchers.Main) { refreshChatPanel(msgs) }
            }
        }
    }

    private fun setupClient(ip: String, port: Int, name: String) {
        val c = SenderClient(
            serverIp = ip, port = port, senderName = name,
            onConnected = { _ -> OverlayState.connected.value = true },
            onDisconnected = { OverlayState.connected.value = false },
            onChatReceived = { text, msgId, replyTo ->
                OverlayState.addMessage(
                    Message(msgId, MessageType.TEXT, text, null,
                        System.currentTimeMillis(), "", "Dashboard", false, replyTo)
                )
            },
            onError = { errMsg ->
                showToast("Connection error — check the Viewer IP and make sure the server is running.\n($errMsg)")
            }
        )
        try {
            c.connect()
        } catch (e: Exception) {
            showToast("Could not connect to $ip:$port — ${e.message?.take(80)}")
        }
        client = c
        OverlayState.client = c
    }

    private fun setupCapture(resultCode: Int, data: Intent) {
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mgr.getMediaProjection(resultCode, data)
        val metrics = resources.displayMetrics
        val sw = metrics.widthPixels; val sh = metrics.heightPixels
        dpi = metrics.densityDpi

        targetW = (if (sw > 1280) 1280 else sw).let { if (it % 2 != 0) it - 1 else it }
        targetH = (sh * targetW / sw).let { if (it % 2 != 0) it - 1 else it }

        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d("OverlayService", "MediaProjection stopped")
                OverlayState.connected.value = false
            }
        }, Handler(Looper.getMainLooper()))

        // Single ImageReader VirtualDisplay — used for both live streaming and screenshots.
        // This avoids the Android 14+ restriction of only one createVirtualDisplay per projection.
        val reader = ImageReader.newInstance(targetW, targetH, PixelFormat.RGBA_8888, 3)
        imageReader = reader

        virtualDisplay = mp.createVirtualDisplay(
            "LiveDashCapture", targetW, targetH, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, null
        )
        mediaProjection = mp

        // Continuous JPEG streaming at ~8fps — low enough for shared WiFi, still smooth to view.
        streamingJob = lifecycleScope.launch(Dispatchers.IO) {
            while (true) {
                delay(125L)
                sendLatestJpeg(quality = 35, asScreenshot = false)
            }
        }
    }

    private fun captureScreenshot() {
        if (imageReader == null) {
            showToast("Screenshot unavailable — screen capture is not active")
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = sendLatestJpeg(quality = 88, asScreenshot = true)
            if (!ok) showToast("No frame captured yet — wait a moment and try again")
        }
    }

    private suspend fun sendLatestJpeg(quality: Int, asScreenshot: Boolean): Boolean {
        return try {
            val image = imageReader?.acquireLatestImage() ?: return false
            val planes = image.planes
            val buffer = planes[0].buffer
            val rowStride = planes[0].rowStride
            val pixelStride = planes[0].pixelStride
            val w = image.width; val h = image.height
            val bmp = Bitmap.createBitmap(rowStride / pixelStride, h, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(buffer)
            image.close()
            val cropped = if (bmp.width > w) Bitmap.createBitmap(bmp, 0, 0, w, h) else bmp
            val baos = ByteArrayOutputStream()
            cropped.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
            if (cropped !== bmp) cropped.recycle()
            bmp.recycle()
            if (asScreenshot) client?.sendScreenshot(b64) else client?.sendVideoFrame(b64, 0)
            true
        } catch (e: Exception) {
            Log.e("OverlayService", "JPEG capture error", e)
            false
        }
    }

    private fun showToast(msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this@OverlayService, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun showOverlay() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val params = LayoutParams(
            LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
            LayoutParams.TYPE_APPLICATION_OVERLAY,
            LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - 180
            y = resources.displayMetrics.heightPixels / 4
        }
        overlayParams = params

        // Anonymous FrameLayout: intercepts drags in onInterceptTouchEvent (so buttons still click),
        // then continues tracking position in onTouchEvent for subsequent MOVE events.
        val root = object : FrameLayout(this@OverlayService) {
            private var dX = 0f; private var dY = 0f
            private var iX = 0; private var iY = 0
            private var isDragging = false

            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        dX = ev.rawX; dY = ev.rawY
                        iX = params.x; iY = params.y
                        isDragging = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val ddx = ev.rawX - dX; val ddy = ev.rawY - dY
                        if (!isDragging && (Math.abs(ddx) > 8f || Math.abs(ddy) > 8f)) {
                            isDragging = true
                        }
                        if (isDragging) {
                            params.x = (iX + ddx).toInt()
                            params.y = (iY + ddy).toInt()
                            try { wm.updateViewLayout(this, params) } catch (_: Exception) {}
                            return true
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> isDragging = false
                }
                return false
            }

            // After interception, MOVE events come here — must keep updating position.
            override fun onTouchEvent(ev: MotionEvent): Boolean {
                if (!isDragging) return false
                when (ev.action) {
                    MotionEvent.ACTION_MOVE -> {
                        params.x = (iX + ev.rawX - dX).toInt()
                        params.y = (iY + ev.rawY - dY).toInt()
                        try { wm.updateViewLayout(this, params) } catch (_: Exception) {}
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> isDragging = false
                }
                return true
            }
        }
        overlayRoot = root

        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(4, 4, 4, 4)
        }

        val accentColor = Color.parseColor("#FF2541")
        val fabCamera = makeIconBtn(android.R.drawable.ic_menu_camera, accentColor) { captureScreenshot() }
        val fabChat = makeIconBtn(android.R.drawable.ic_dialog_email, Color.parseColor("#CC22C55E")) { toggleChatPanel() }
        val fabStop = makeIconBtn(android.R.drawable.ic_delete, Color.parseColor("#CC6868A0")) {
            stopReconnectAndDisconnect()
        }

        bubble.addView(fabCamera, LinearLayout.LayoutParams(100, 100).apply { bottomMargin = 8 })
        bubble.addView(fabChat, LinearLayout.LayoutParams(100, 100).apply { bottomMargin = 8 })
        bubble.addView(fabStop, LinearLayout.LayoutParams(100, 100))
        root.addView(bubble, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ))

        wm.addView(root, params)
    }

    private fun makeIconBtn(iconRes: Int, bgColor: Int, onClick: () -> Unit): ImageButton =
        ImageButton(this).apply {
            setImageResource(iconRes)
            setBackgroundColor(bgColor)
            setPadding(18, 18, 18, 18)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setOnClickListener { onClick() }
        }

    private fun stopReconnectAndDisconnect() {
        client?.stopReconnect()
        try { client?.close() } catch (_: Exception) {}
        OverlayState.connected.value = false
    }

    private fun toggleChatPanel() {
        if (chatPanelVisible) hideChatPanel() else showChatPanel()
    }

    private fun showChatPanel() {
        if (chatPanelVisible) return
        val wm = windowManager ?: return
        val metrics = resources.displayMetrics
        val panelW = (metrics.widthPixels * 0.88).toInt()
        val panelH = (metrics.heightPixels * 0.38).toInt()

        // Move button overlay to top-right so chat panel doesn't hide it
        overlayParams?.let { p ->
            savedOverlayY = p.y
            p.y = 80
            try { wm.updateViewLayout(overlayRoot, p) } catch (_: Exception) {}
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F01A1A24"))
        }

        val sv = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(20, 16, 20, 8)
        }
        sv.addView(container, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        panel.addView(sv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(12, 8, 12, 12)
            gravity = Gravity.CENTER_VERTICAL
        }
        val editText = EditText(this).apply {
            hint = "Message..."; setHintTextColor(Color.parseColor("#888888"))
            setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#CC222230"))
            setPadding(20, 14, 20, 14); textSize = 14f; maxLines = 2; isSingleLine = false
        }
        val sendBtn = Button(this).apply {
            text = "Send"; setBackgroundColor(Color.parseColor("#CCFF2541"))
            setTextColor(Color.WHITE); textSize = 13f; setPadding(20, 8, 20, 8)
            setOnClickListener {
                val text = editText.text.toString().trim()
                if (text.isNotEmpty()) {
                    val msg = Message(UUID.randomUUID().toString(), MessageType.TEXT, text, null,
                        System.currentTimeMillis(), "", "You", true)
                    OverlayState.addMessage(msg)
                    client?.sendChat(text)
                    editText.text.clear()
                }
            }
        }
        inputRow.addView(editText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        inputRow.addView(sendBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = 8 })
        panel.addView(inputRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        chatMsgContainer = container; chatScrollView = sv; chatPanel = panel

        val pp = LayoutParams(
            panelW, panelH,
            LayoutParams.TYPE_APPLICATION_OVERLAY,
            LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 0
            // Pan the panel above the keyboard when the EditText is focused
            softInputMode = LayoutParams.SOFT_INPUT_ADJUST_PAN or LayoutParams.SOFT_INPUT_STATE_VISIBLE
        }
        chatPanelParams = pp

        refreshChatPanel(OverlayState.messages.value)
        wm.addView(panel, pp)
        chatPanelVisible = true

        editText.requestFocus()
        Handler(Looper.getMainLooper()).postDelayed({
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
        }, 120)
    }

    private fun hideChatPanel() {
        // Restore button overlay to its pre-chat position
        overlayParams?.let { p ->
            if (savedOverlayY >= 0) {
                p.y = savedOverlayY
                try { windowManager?.updateViewLayout(overlayRoot, p) } catch (_: Exception) {}
            }
        }
        try { windowManager?.removeView(chatPanel) } catch (_: Exception) {}
        chatPanelVisible = false; chatPanel = null; chatMsgContainer = null; chatScrollView = null
    }

    private fun refreshChatPanel(msgs: List<Message>) {
        val container = chatMsgContainer ?: return
        val sv = chatScrollView ?: return
        container.removeAllViews()
        msgs.takeLast(25).forEach { msg ->
            val tv = TextView(this).apply {
                val who = if (msg.outgoing) "You" else msg.senderName.ifEmpty { "Dashboard" }
                text = "$who: ${msg.text}"
                setTextColor(if (msg.outgoing) Color.parseColor("#FF2541") else Color.WHITE)
                textSize = 13f; setPadding(0, 6, 0, 6)
            }
            container.addView(tv)
        }
        sv.post { sv.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun cleanup() {
        streamingJob?.cancel(); streamingJob = null
        hideChatPanel()
        try { windowManager?.removeView(overlayRoot) } catch (_: Exception) {}
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        imageReader = null
        try { mediaProjection?.stop() } catch (_: Exception) {}
        try { client?.stopReconnect(); client?.close() } catch (_: Exception) {}
        OverlayState.reset()
    }

    override fun onTaskRemoved(rootIntent: Intent?) { cleanup(); stopSelf(); super.onTaskRemoved(rootIntent) }
    override fun onDestroy() {
        cleanup()
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        super.onDestroy()
    }
    override fun onBind(intent: Intent): IBinder? { super.onBind(intent); return null }

    private fun createChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) == null)
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "LiveDash Overlay", NotificationManager.IMPORTANCE_LOW)
            )
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, OverlayService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("LiveDash Overlay")
            .setContentText("Streaming live — tap camera to screenshot, mail to chat")
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentIntent(openIntent)
            .addAction(Notification.Action.Builder(null, "Stop", stopIntent).build())
            .setOngoing(true)
            .build()
    }
}
