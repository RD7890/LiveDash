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
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
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
import android.view.Surface
import android.view.View
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
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.rohan.livedash.MainActivity
import com.rohan.livedash.data.Message
import com.rohan.livedash.data.MessageType
import com.rohan.livedash.network.SenderClient
import kotlinx.coroutines.Dispatchers
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
    private var chatPanel: View? = null
    private var chatPanelParams: LayoutParams? = null
    private var chatPanelVisible = false
    private var chatMsgContainer: LinearLayout? = null
    private var chatScrollView: ScrollView? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var videoEncoder: MediaCodec? = null
    private var encoderSurface: Surface? = null
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
            }
        )
        c.connect()
        client = c
        OverlayState.client = c
    }

    private fun setupCapture(resultCode: Int, data: Intent) {
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mgr.getMediaProjection(resultCode, data)
        val metrics = resources.displayMetrics
        val sw = metrics.widthPixels
        val sh = metrics.heightPixels
        dpi = metrics.densityDpi

        targetW = (if (sw > 1280) 1280 else sw).let { if (it % 2 != 0) it - 1 else it }
        targetH = (sh * targetW / sw).let { if (it % 2 != 0) it - 1 else it }

        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d("OverlayService", "MediaProjection stopped")
                OverlayState.connected.value = false
            }
        }, Handler(Looper.getMainLooper()))

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, targetW, targetH).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 15)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }

        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        enc.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}
            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {}
            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                Log.e("OverlayService", "Encoder error", e)
            }
            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                if (info.size > 0) {
                    try {
                        val buffer = codec.getOutputBuffer(index)
                        if (buffer != null) {
                            val bytes = ByteArray(info.size)
                            buffer.position(info.offset); buffer.limit(info.offset + info.size); buffer.get(bytes)
                            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                            client?.sendVideoFrame(b64, info.flags)
                        }
                    } catch (e: Exception) { Log.e("OverlayService", "Frame error", e) }
                }
                codec.releaseOutputBuffer(index, false)
            }
        })
        enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = enc.createInputSurface()
        enc.start()
        videoEncoder = enc
        encoderSurface = surface

        virtualDisplay = mp.createVirtualDisplay(
            "LiveDashCapture", targetW, targetH, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null
        )
        mediaProjection = mp
    }

    private fun captureScreenshot() {
        val mp = mediaProjection ?: return
        val w = targetW; val h = targetH; val d = dpi
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 1)
        val ssDisplay = mp.createVirtualDisplay(
            "LiveDashScreenshot", w, h, d,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, null
        )
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val image = reader.acquireLatestImage()
                if (image != null) {
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val rowStride = planes[0].rowStride
                    val pixelStride = planes[0].pixelStride
                    val bmp = Bitmap.createBitmap(rowStride / pixelStride, h, Bitmap.Config.ARGB_8888)
                    bmp.copyPixelsFromBuffer(buffer)
                    val cropped = if (bmp.width > w) Bitmap.createBitmap(bmp, 0, 0, w, h) else bmp
                    val baos = ByteArrayOutputStream()
                    cropped.compress(Bitmap.CompressFormat.JPEG, 85, baos)
                    val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                    client?.sendScreenshot(b64)
                    image.close()
                    if (cropped !== bmp) cropped.recycle()
                    bmp.recycle()
                }
            } catch (e: Exception) {
                Log.e("OverlayService", "Screenshot error", e)
            } finally {
                ssDisplay?.release()
                reader.close()
            }
        }, 250)
    }

    private fun showOverlay() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val root = FrameLayout(this)
        overlayRoot = root

        val params = LayoutParams(
            LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
            LayoutParams.TYPE_APPLICATION_OVERLAY,
            LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - 180
            y = resources.displayMetrics.heightPixels / 2
        }
        overlayParams = params

        // Bubble button
        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(4, 4, 4, 4)
        }

        val accentColor = Color.parseColor("#FF2541")
        val surfaceColor = Color.parseColor("#CC1A1A24")

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

        var downX = 0f; var downY = 0f; var initX = 0; var initY = 0; var dragging = false
        root.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    initX = params.x; initY = params.y; dragging = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX; val dy = event.rawY - downY
                    if (dragging || (Math.abs(dx) > 10 || Math.abs(dy) > 10)) {
                        dragging = true
                        params.x = initX + dx.toInt(); params.y = initY + dy.toInt()
                        try { wm.updateViewLayout(root, params) } catch (_: Exception) {}
                    }
                    true
                }
                else -> false
            }
        }

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
        val panelH = (metrics.heightPixels * 0.42).toInt()

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
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = 200 }
        chatPanelParams = pp

        refreshChatPanel(OverlayState.messages.value)
        wm.addView(panel, pp)
        chatPanelVisible = true

        editText.requestFocus()
        Handler(Looper.getMainLooper()).postDelayed({
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
        }, 100)
    }

    private fun hideChatPanel() {
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
        hideChatPanel()
        try { windowManager?.removeView(overlayRoot) } catch (_: Exception) {}
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { videoEncoder?.signalEndOfInputStream() } catch (_: Exception) {}
        try { videoEncoder?.stop() } catch (_: Exception) {}
        try { videoEncoder?.release() } catch (_: Exception) {}
        try { encoderSurface?.release() } catch (_: Exception) {}
        try { mediaProjection?.stop() } catch (_: Exception) {}
        try { client?.stopReconnect(); client?.close() } catch (_: Exception) {}
        videoEncoder = null; encoderSurface = null
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
