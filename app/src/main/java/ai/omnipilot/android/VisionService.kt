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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.TimeUnit

class VisionService : Service() {
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
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
        tts = TextToSpeech(this) { tts?.language = Locale.US }
        ServiceCompat.startForeground(\n            this,\n            42,\n            notification(),\n            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION\n        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        token = getSharedPreferences("omni", 0).getString("token", "") ?: ""
        goal = getSharedPreferences("omni", 0).getString("goal", "Assist me with the current Android screen.") ?: ""
        autoExecute = getSharedPreferences("omni", 0).getBoolean("autoExecute", false)
        val code = intent?.getIntExtra("resultCode", 0) ?: 0
        val data = if (Build.VERSION.SDK_INT >= 33) {\n            intent?.getParcelableExtra("data", Intent::class.java)\n        } else {\n            @Suppress("DEPRECATION")\n            intent?.getParcelableExtra("data")\n        } ?: return START_NOT_STICKY
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mgr.getMediaProjection(code, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                running = false
                handler.removeCallbacksAndMessages(null)
                reader?.close()
                reader = null
                stopSelf()
            }
        }, handler)
        setupCapture()
        running = true
        loop()
        return START_NOT_STICKY
    }

    private fun setupCapture() {
        val dm = resources.displayMetrics
        reader = ImageReader.newInstance(dm.widthPixels, dm.heightPixels, android.graphics.PixelFormat.RGBA_8888, 2)
        projection?.createVirtualDisplay(
            "OmniPilot",
            dm.widthPixels, dm.heightPixels, dm.densityDpi,
            0, reader!!.surface, null, null
        )
    }

    private fun loop() {
        if (!running) return
        handler.postDelayed({
            if (running && !busy) captureAndAsk()
            loop()
        }, 1500)
    }

    private fun captureAndAsk() {
        val image = reader?.acquireLatestImage() ?: return
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
            askGemini(out.toByteArray())
        } catch (_: Exception) {
            image.close()
            busy = false
        }
    }

    private fun askGemini(jpeg: ByteArray) {
        val url = "https://omnipilot-jo6bv8.v2.appdeploy.ai/api/agent/android-vision?token=" +
                java.net.URLEncoder.encode(token, "UTF-8") +
                "&goal=" + java.net.URLEncoder.encode(goal, "UTF-8")
        val body = jpeg.toRequestBody("image/jpeg".toMediaType())
        val req = Request.Builder().url(url).post(body).build()
        client.newCall(req).enqueue(object: Callback {
            override fun onFailure(call: Call, e: java.io.IOException) { busy = false }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        val j = JSONObject(it.body?.string() ?: "{}")
                        if (j.optString("status") == "ok") {
                            j.optString("voiceCue").takeIf { s -> s.isNotBlank() }?.let { speak(it) }
                            val action = j.optJSONObject("action")
                            val confidence = j.optDouble("confidence", 0.0)
                            if (autoExecute && confidence >= 0.72 && action != null && action.optString("type") != "none") {
                                execute(action)
                            }
                        }
                    } catch (_: Exception) {}
                    busy = false
                }
            }
        })
    }

    private fun execute(a: JSONObject) {
        when (a.optString("type")) {
            "tap" -> OmniPilotAccessibilityService.instance?.tap(a.optDouble("x").toFloat(), a.optDouble("y").toFloat())
            "swipe" -> OmniPilotAccessibilityService.instance?.swipe(
                a.optDouble("x").toFloat(), a.optDouble("y").toFloat(),
                a.optDouble("x2").toFloat(), a.optDouble("y2").toFloat()
            )
            "type" -> OmniPilotAccessibilityService.instance?.typeText(a.optString("text"))
            "key" -> OmniPilotAccessibilityService.instance?.pressEnter()
        }
    }

    private fun speak(s: String) {
        Handler(Looper.getMainLooper()).post {
            tts?.speak(s.take(180), TextToSpeech.QUEUE_FLUSH, null, "omni")
        }
    }

    private fun notification(): Notification {
        val channel = "omni"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(channel, "OmniPilot", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, channel)
            .setContentTitle("OmniPilot is running")
            .setContentText("Vision → action loop active")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        projection?.stop()
        reader?.close()
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
