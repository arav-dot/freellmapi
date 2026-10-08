package ai.omnipilot.android

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.ImageReader
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.content.pm.ServiceInfo
import android.speech.tts.TextToSpeech
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.TimeUnit

class VisionService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    @Volatile private var running = false
    private var token = ""
    private var goal = ""
    private var tts: TextToSpeech? = null
    private val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var busy = false
    private var autoExecute = false
    private var screenWidth = 0
    private var screenHeight = 0
    private var projectionCallback: MediaProjection.Callback? = null
    @Volatile private var activeCall: Call? = null
    @Volatile private var sessionGeneration = 0
    @Volatile private var retryDelayMs = 1_500L

    override fun onCreate() {
        super.onCreate()
        instance = this
        tts = TextToSpeech(this) { tts?.language = Locale.US }
        val foregroundNotification = notification("Starting screen capture")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                42,
                foregroundNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(42, foregroundNotification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        releaseCaptureResources()
        token = intent?.getStringExtra("token") ?: ""
        goal = getSharedPreferences("omni", 0).getString("goal", "Assist me with the current Android screen.") ?: ""
        autoExecute = getSharedPreferences("omni", 0).getBoolean("autoExecute", false)
        val code = intent?.getIntExtra("resultCode", 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>("data")
        if (code == 0 || data == null || token.isBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            projection = mgr.getMediaProjection(code, data)
            if (projection == null) {
                stopSelf(startId)
                return START_NOT_STICKY
            }
            projectionCallback = object : MediaProjection.Callback() {
                override fun onStop() {
                    running = false
                    stopSelf()
                }
            }.also { projection?.registerCallback(it, handler) }
            setupCapture()
            running = true
            retryDelayMs = 1_500L
            updateNotification("Vision loop active")
            loop()
        } catch (_: Exception) {
            updateNotification("Could not start screen capture")
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun setupCapture() {
        val dm = resources.displayMetrics
        screenWidth = dm.widthPixels
        screenHeight = dm.heightPixels
        reader = ImageReader.newInstance(dm.widthPixels, dm.heightPixels, android.graphics.PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection?.createVirtualDisplay(
            "OmniPilot",
            dm.widthPixels, dm.heightPixels, dm.densityDpi,
            0, reader!!.surface, null, null
        ) ?: throw IllegalStateException("Screen capture display could not be created")
    }

    private fun loop(delayMs: Long = 1_500L) {
        if (!running) return
        handler.postDelayed({
            if (running && !busy) captureAndAsk()
        }, delayMs)
    }

    private fun increaseRetryDelay() {
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(30_000L)
    }

    private fun captureAndAsk() {
        val image = try { reader?.acquireLatestImage() } catch (_: Exception) { null }
        if (image == null) {
            loop()
            return
        }
        busy = true
        var bmp: android.graphics.Bitmap? = null
        var cropped: android.graphics.Bitmap? = null
        try {
            val plane = image.planes[0]
            val buffer: ByteBuffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val width = image.width
            val height = image.height
            val rowPadding = rowStride - pixelStride * width
            val rawBitmap = android.graphics.Bitmap.createBitmap(
                width + rowPadding / pixelStride, height, android.graphics.Bitmap.Config.ARGB_8888
            )
            bmp = rawBitmap
            rawBitmap.copyPixelsFromBuffer(buffer)
            val captureBitmap = if (rawBitmap.width != width)
                android.graphics.Bitmap.createBitmap(rawBitmap, 0, 0, width, height)
            else rawBitmap
            cropped = captureBitmap

            val out = ByteArrayOutputStream()
            if (!captureBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 55, out)) {
                busy = false
                loop()
                return
            }
            askGemini(out.toByteArray())
        } catch (_: Exception) {
            busy = false
            loop()
        } finally {
            try {
                image.close()
            } finally {
                if (cropped !== bmp) cropped?.recycle()
                bmp?.recycle()
            }
        }
    }

    private fun askGemini(jpeg: ByteArray) {
        val url = "https://omnipilot-jo6bv8.v2.appdeploy.ai/api/agent/vision"
        val requestJson = JSONObject()
            .put("goal", goal)
            .put("image", android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP))
            .put("mimeType", "image/jpeg")
            .put("approved", autoExecute)
        val body = requestJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()
        val call = client.newCall(req)
        val requestGeneration = sessionGeneration
        activeCall = call
        call.enqueue(object: Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                if (sessionGeneration == requestGeneration) {
                    if (activeCall === call) activeCall = null
                    busy = false
                    if (running) {
                        updateNotification("Connection failed; retrying")
                        increaseRetryDelay()
                        handler.post { if (running && sessionGeneration == requestGeneration) loop(retryDelayMs) }
                    }
                }
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    var requestSucceeded = false
                    var authenticationRejected = false
                    try {
                        if (running && sessionGeneration == requestGeneration && !it.isSuccessful) {
                            authenticationRejected = it.code == 401 || it.code == 403
                            updateNotification(if (authenticationRejected)
                                "Pairing token rejected; stopping OmniPilot"
                            else "Backend request failed (HTTP ${it.code}); retrying")
                        } else if (running && sessionGeneration == requestGeneration && it.isSuccessful) {
                            val j = JSONObject(it.body?.string() ?: "{}")
                            if (j.optString("status") == "ok") {
                                requestSucceeded = true
                                updateNotification("Vision loop active")
                                j.optString("voiceCue").takeIf { s -> s.isNotBlank() }?.let { speak(it) }
                                val action = j.optJSONObject("action")
                                val confidence = j.optDouble("confidence", Double.NaN)
                                if (running && sessionGeneration == requestGeneration && autoExecute &&
                                    confidence.isFinite() && confidence in 0.72..1.0 &&
                                    action != null && action.optString("type") != "none"
                                ) {
                                    handler.post {
                                        if (running && sessionGeneration == requestGeneration) execute(action)
                                    }
                                }
                            } else {
                                updateNotification("Invalid backend response; retrying")
                            }
                        }
                    } catch (_: Exception) {
                        if (running && sessionGeneration == requestGeneration)
                            updateNotification("Invalid backend response; retrying")
                    }
                    if (sessionGeneration == requestGeneration) {
                        if (activeCall === call) activeCall = null
                        busy = false
                        if (authenticationRejected) {
                            running = false
                            stopSelf()
                        }
                        if (running) {
                            if (requestSucceeded) retryDelayMs = 1_500L else increaseRetryDelay()
                            handler.post {
                                if (running && sessionGeneration == requestGeneration) loop(retryDelayMs)
                            }
                        }
                    }
                }
            }
        })
    }

    private fun execute(a: JSONObject) {
        fun coordinate(key: String, limit: Int): Float? {
            val value = a.optDouble(key, Double.NaN)
            if (!value.isFinite() || value < 0.0 || value >= limit.toDouble()) return null
            return value.toFloat()
        }
        when (a.optString("type")) {
            "tap" -> {
                val x = coordinate("x", screenWidth)
                val y = coordinate("y", screenHeight)
                if (x != null && y != null) OmniPilotAccessibilityService.instance?.tap(x, y)
            }
            "swipe" -> {
                val x = coordinate("x", screenWidth)
                val y = coordinate("y", screenHeight)
                val x2 = coordinate("x2", screenWidth)
                val y2 = coordinate("y2", screenHeight)
                if (x != null && y != null && x2 != null && y2 != null)
                    OmniPilotAccessibilityService.instance?.swipe(x, y, x2, y2)
            }
            "type" -> a.optString("text").takeIf { it.isNotBlank() && it.length <= 500 }
                ?.let { OmniPilotAccessibilityService.instance?.typeText(it) }
            "key" -> OmniPilotAccessibilityService.instance?.pressEnter()
        }
    }

    private fun speak(s: String) {
        Handler(Looper.getMainLooper()).post {
            if (running) tts?.speak(s.take(180), TextToSpeech.QUEUE_FLUSH, null, "omni")
        }
    }

    private fun notification(message: String): Notification {
        val channel = "omni"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(channel, "OmniPilot", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, channel)
            .setContentTitle("OmniPilot")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
    }

    private fun updateNotification(message: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(42, notification(message))
    }

    private fun releaseCaptureResources() {
        sessionGeneration += 1
        running = false
        handler.removeCallbacksAndMessages(null)
        activeCall?.cancel()
        activeCall = null
        virtualDisplay?.release()
        virtualDisplay = null
        reader?.close()
        reader = null
        projectionCallback?.let { projection?.unregisterCallback(it) }
        projectionCallback = null
        projection?.stop()
        projection = null
        busy = false
        retryDelayMs = 1_500L
        token = ""
    }

    override fun onDestroy() {
        releaseCaptureResources()
        tts?.shutdown()
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    companion object {
        @Volatile private var instance: VisionService? = null

        fun start(context: Context, resultCode: Int, data: Intent, token: String) {
            context.startForegroundService(Intent(context, VisionService::class.java).apply {
                putExtra("resultCode", resultCode)
                putExtra("data", data)
                putExtra("token", token)
            })
        }
        fun stop(context: Context) {
            instance?.stopSession()
            context.stopService(Intent(context, VisionService::class.java))
        }
    }

    private fun stopSession() {
        releaseCaptureResources()
        stopSelf()
    }
}
