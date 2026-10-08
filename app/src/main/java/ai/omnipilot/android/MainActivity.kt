package ai.omnipilot.android

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {
    private lateinit var token: EditText
    private lateinit var goal: EditText
    private lateinit var status: TextView
    private lateinit var autoExecute: Switch
    private val statusHandler = Handler(Looper.getMainLooper())
    private val statusPoll = object : Runnable {
        override fun run() {
            val serviceStatus = VisionService.sessionStatus()
            if (serviceStatus != null) {
                status.text = serviceStatus
                if (serviceStatus.startsWith("STOPPED") || serviceStatus.startsWith("ERROR")) {
                    token.visibility = android.view.View.VISIBLE
                    token.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
            }
            statusHandler.postDelayed(this, 500L)
        }
    }

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val sessionToken = pendingToken
            pendingToken = ""
            token.text?.clear()
            if (sessionToken.isNotBlank()) {
                try {
                    VisionService.start(this, result.resultCode, result.data!!, sessionToken)
                    status.text = "STARTING • check the service notification"
                } catch (_: Exception) {
                    status.text = "Could not start OmniPilot"
                    token.visibility = android.view.View.VISIBLE
                    token.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
            } else {
                token.visibility = android.view.View.VISIBLE
                token.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                status.text = "Screen capture permission denied"
            }
        } else {
            pendingToken = ""
            token.text?.clear()
            token.visibility = android.view.View.VISIBLE
            token.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            status.text = "Screen capture permission denied"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preferences = getSharedPreferences("omni", MODE_PRIVATE)
        // Pairing credentials are session input, not a preference. Also clear
        // any plaintext value saved by an earlier app version.
        preferences.edit().remove("token").apply()

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 36, 28, 28)
            setBackgroundColor(0xFF070A0F.toInt())
        }

        fun label(s: String) = TextView(this).apply {
            text = s
            textSize = 12f
            setTextColor(0xFF38D9FF.toInt())
            setPadding(0, 16, 0, 8)
        }

        token = EditText(this).apply {
            hint = "Private pairing token"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSaveEnabled = false
            setTextColor(0xFFE7EDF5.toInt())
            setHintTextColor(0xFF8190A3.toInt())
        }
        goal = EditText(this).apply {
            hint = "What should OmniPilot do?"
            setText(getSharedPreferences("omni", MODE_PRIVATE).getString("goal", "Assist me with the current Android screen."))
            setTextColor(0xFFE7EDF5.toInt())
            setHintTextColor(0xFF8190A3.toInt())
        }
        status = TextView(this).apply {
            text = "READY"
            textSize = 15f
            setTextColor(0xFF43E08A.toInt())
            setPadding(0, 18, 0, 18)
        }

        val accessibility = Button(this).apply {
            text = "ENABLE ACCESSIBILITY"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        autoExecute = Switch(this).apply {
            text = "AUTO-EXECUTE HIGH-CONFIDENCE ACTIONS"
            isChecked = getSharedPreferences("omni", MODE_PRIVATE).getBoolean("autoExecute", false)
            setTextColor(0xFFE7EDF5.toInt())
        }

        val start = Button(this).apply {
            text = "START OMNIPILOT"
            setOnClickListener {
                preferences.edit()
                    .putString("goal", goal.text.toString())
                    .putBoolean("autoExecute", autoExecute.isChecked)
                    .apply()
                if (!token.text.isNullOrBlank()) {
                    val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    pendingToken = token.text.toString()
                    token.text?.clear()
                    token.visibility = android.view.View.GONE
                    try {
                        captureLauncher.launch(mgr.createScreenCaptureIntent())
                    } catch (_: Exception) {
                        pendingToken = ""
                        token.visibility = android.view.View.VISIBLE
                        status.text = "Could not request screen capture permission"
                    }
                } else status.text = "Enter a pairing token first"
            }
        }
        val stop = Button(this).apply {
            text = "EMERGENCY STOP"
            setOnClickListener {
                pendingToken = ""
                token.text?.clear()
                VisionService.stop(this@MainActivity)
                status.text = "STOPPED"
                token.visibility = android.view.View.VISIBLE
                token.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
        }

        box.addView(TextView(this).apply {
            text = "OMNIPILOT\nANDROID COMPANION"
            textSize = 28f
            gravity = Gravity.CENTER_HORIZONTAL
            setTextColor(0xFFE7EDF5.toInt())
            setPadding(0, 0, 0, 8)
        })
        box.addView(TextView(this).apply {
            text = "OBSERVE  →  DECIDE  →  ACT  →  VERIFY"
            textSize = 12f
            gravity = Gravity.CENTER_HORIZONTAL
            setTextColor(0xFF8190A3.toInt())
        })
        box.addView(label("PAIRING TOKEN"))
        box.addView(token)
        box.addView(label("MISSION"))
        box.addView(goal)
        box.addView(status)
        box.addView(autoExecute)
        box.addView(accessibility)
        box.addView(start)
        box.addView(stop)

        setContentView(box)
    }

    override fun onResume() {
        super.onResume()
        statusHandler.removeCallbacks(statusPoll)
        statusHandler.post(statusPoll)
    }

    override fun onPause() {
        statusHandler.removeCallbacks(statusPoll)
        super.onPause()
    }

    private var pendingToken: String = ""
}
