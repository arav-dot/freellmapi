package ai.omnipilot.android

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.content.pm.ServiceInfo
import androidx.core.app.ServiceCompat
import android.os.*
import android.speech.tts.TextToSpeech
import okhttp3.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.TimeUnit

class VisionService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var running = false
    private var token = ""
    private var goal = ""
    private var tts: TextToSpeech? = null
    private val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
    private val handler = Handler(Looper.getMainLooper())
    private var busy = false
    private var autoExecute = false

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this) { result ->
            if (result == TextToSpeech.SUCCESS) tts?.language = Locale.US
        }
        ServiceCompat.startForeground(
            this,
            42,
            notification("Starting vision service"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
        setStatus("READY", "Service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        token = getSharedPreferences("omni", 0).getString("token", "") ?: ""
        goal = getSharedPreferences("omni", 0).getString("goal", "Assist me with the current Android screen.") ?: ""
        autoExecute = getSharedPreferences("omni", 0).getBoolean("autoExecute", false)
        val code = intent?.getIntExtra("resultCode", 0) ?: 0
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra("data", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("data")
        } ?: return START_NOT_STICKY
        if (token.isBlank()) {
            setStatus("ERROR", "Pairing token is empty")
            stopSelf()
            return START_NOT_STICKY
        }
        if (OmniPilotAccessibilityService.instance == null && autoExecute) {
            setStatus("WARNING", "Accessibility is OFF, so actions cannot run")
        }
        return try {
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = mgr.getMediaProjection(code, data)
            if (projection == null) throw IllegalStateException("Android returned no MediaProjection")
            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    running = false
                    busy = false
                    handler.removeCallbacksAndMessages(null)
                    reader?.close()
                    reader = null
                    virtualDisplay?.release()
                    virtualDisplay = null
                    setStatus("STOPPED", "Android stopped screen capture")
                    stopSelf()
                }
            }
            projectionCallback = callback
            projection?.registerCallback(callback, handler)
            setupCapture()
            running = true
            setStatus("CAPTURE OK", "Screen capture is active")
            updateNotification("Screen capture active")
            loop()
            START_NOT_STICKY
        } catch (e: SecurityException) {
            setStatus("ERROR", "MediaProjection blocked: ${e.message ?: "permission/security error"}")
            stopSelf()
            START_NOT_STICKY
        } catch (e: Exception) {
            setStatus("ERROR", "Capture setup failed: ${e.message ?: e.javaClass.simpleName}")
            stopSelf()
            START_NOT_STICKY
        }
    }

    private fun setupCapture() {
        val dm = resources.displayMetrics
        reader = ImageReader.newInstance(dm.widthPixels, dm.heightPixels, android.graphics.PixelFormat.RGBA_8888, 2)
        virtualDisplay?.release()
        virtualDisplay = projection?.createVirtualDisplay(
            "OmniPilot",
            dm.widthPixels, dm.heightPixels, dm.densityDpi,
            0, reader!!.surface, null, null
        )
        if (virtualDisplay == null) throw IllegalStateException("Virtual display was not created")
    }

    private fun loop() {
        if (!running) return
        handler.postDelayed({
            if (running && !busy) captureAndAsk()
            loop()
        }, 1500)
    }

    private fun captureAndAsk() {
        val image = try { reader?.acquireLatestImage() } catch (e: Exception) {
            setStatus("ERROR", "Screenshot read failed: ${e.message ?: e.javaClass.simpleName}")
            null
        } ?: return
        busy = true
        try {
            val plane = image.planes[0]
            val buffer: ByteBuffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val width = image.width
            val height = image.height
            val rowPadding = rowStride - pixelStride * width
            val bmp = android.graphics.Bitmap.createBitmap(width + rowPadding / pixelStride, height, android.graphics.Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(buffer)
            image.close()

            val cropped = if (bmp.width != width) android.graphics.Bitmap.createBitmap(bmp, 0, 0, width, height) else bmp
            if (cropped !== bmp) bmp.recycle()

            val out = ByteArrayOutputStream()
            cropped.compress(android.graphics.Bitmap.CompressFormat.JPEG, 55, out)
            cropped.recycle()
            val jpeg = out.toByteArray()
            if (jpeg.isEmpty()) {
                setStatus("ERROR", "Screenshot compression returned empty data")
                busy = false
                return
            }
            setStatus("UPLOADING", "Sending ${jpeg.size / 1024} KB to Gemini")
            askGemini(jpeg)
        } catch (e: Exception) {
            try { image.close() } catch (_: Exception) {}
            busy = false
            setStatus("ERROR", "Screenshot processing failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun askGemini(jpeg: ByteArray) {
        val url = "https://omnipilot-jo6bv8.v2.appdeploy.ai/api/agent/vision?token=" +
                java.net.URLEncoder.encode(token, "UTF-8")
        val encoded = android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP)
        val requestJson = JSONObject()
            .put("goal", goal)
            .put("image", encoded)
            .put("mimeType", "image/jpeg")
            .put("approved", false)
            .toString()
        val body = RequestBody.create(MediaType.parse("application/json"), requestJson)
        val req = Request.Builder()
            .url(url)
            .post(body)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .build()
        client.newCall(req).enqueue(object: Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                busy = false
                setStatus("NETWORK ERROR", e.message ?: "Could not reach OmniPilot server")
                updateNotification("Network error")
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val raw = it.body?.string().orEmpty()
                    try {
                        val j = if (raw.isNotBlank()) JSONObject(raw) else JSONObject()
                        if (!it.isSuccessful) {
                            busy = false
                            setStatus("SERVER ERROR", "HTTP ${it.code}: ${raw.take(120)}")
                            updateNotification("Server error ${it.code}")
                            return
                        }
                        if (j.optString("status") == "error") {
                            busy = false
                            setStatus("VISION ERROR", j.optString("diagnostic", j.optString("summary", "Unknown vision error")))
                            updateNotification("Vision error")
                            return
                        }
                        val action = j.optJSONObject("action")
                        val actionType = action?.optString("type", "none") ?: "none"
                        val confidence = j.optDouble("confidence", 0.0)
                        val summary = j.optString("summary", "Screen analyzed")
                        j.optString("voiceCue").takeIf { s -> s.isNotBlank() }?.let { speak(it) }
                        if (actionType == "none" || actionType == "wait") {
                            setStatus("GEMINI OK", "$summary • no action • ${percent(confidence)}%")
                        } else if (!autoExecute) {
                            setStatus("ACTION READY", "$actionType • ${percent(confidence)}% • Auto-execute OFF")
                        } else if (confidence < 0.72) {
                            setStatus("ACTION HELD", "$actionType • confidence ${percent(confidence)}% < 72%")
                        } else if (OmniPilotAccessibilityService.instance == null) {
                            setStatus("ACTION BLOCKED", "Accessibility is OFF • proposed $actionType")
                        } else {
                            val ok = execute(action)
                            setStatus(if (ok) "ACTION SENT" else "ACTION FAILED", "$actionType • ${percent(confidence)}% • $summary")
                            updateNotification(if (ok) "Action sent: $actionType" else "Action failed: $actionType")
                        }
                    } catch (e: Exception) {
                        val preview = raw.replace("\n", " ").take(180)
                        setStatus("RESPONSE ERROR", "HTTP " + it.code + ", content-type " + (it.header("Content-Type") ?: "unknown") + " • " + preview)
                    } finally {
                        busy = false
                    }
                }
            }
        })
    }

    private fun execute(a: JSONObject?): Boolean {
        if (a == null) return false
        return when (a.optString("type")) {
            "tap" -> OmniPilotAccessibilityService.instance?.tap(a.optDouble("x").toFloat(), a.optDouble("y").toFloat()) ?: false
            "swipe" -> OmniPilotAccessibilityService.instance?.swipe(
                a.optDouble("x").toFloat(), a.optDouble("y").toFloat(),
                a.optDouble("x2").toFloat(), a.optDouble("y2").toFloat()
            ) ?: false
            "type" -> OmniPilotAccessibilityService.instance?.typeText(a.optString("text")) ?: false
            "key" -> OmniPilotAccessibilityService.instance?.pressEnter() ?: false
            else -> false
        }
    }

    private fun percent(value: Double): Int = (value.coerceIn(0.0, 1.0) * 100.0).toInt()

    private fun setStatus(state: String, detail: String) {
        getSharedPreferences("omni", 0).edit()
            .putString("lastStatus", state)
            .putString("lastDetail", detail.take(220))
            .putLong("lastStatusAt", System.currentTimeMillis())
            .apply()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(42, notification(text))
    }

    private fun speak(s: String) {
        Handler(Looper.getMainLooper()).post {
            tts?.speak(s.take(180), TextToSpeech.QUEUE_FLUSH, null, "omni")
        }
    }

    private fun notification(text: String = "Vision → action loop active"): Notification {
        val channel = "omni"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(channel, "OmniPilot", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, channel)
            .setContentTitle("OmniPilot is running")
            .setContentText(text.take(80))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        try { projectionCallback?.let { projection?.unregisterCallback(it) } } catch (_: Exception) {}
        virtualDisplay?.release()
        virtualDisplay = null
        projection?.stop()
        projection = null
        reader?.close()
        reader = null
        tts?.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    companion object {
        fun start(context: Context, resultCode: Int, data: Intent) {
            context.startForegroundService(Intent(context, VisionService::class.java).apply {
                putExtra("resultCode", resultCode)
                putExtra("data", data)
            })
        }
        fun stop(context: Context) { context.stopService(Intent(context, VisionService::class.java)) }
    }
}
