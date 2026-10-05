package ai.omnipilot.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : Activity() {
    private lateinit var token: EditText
    private lateinit var goal: EditText
    private lateinit var status: TextView
    private lateinit var autoExecute: Switch

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            VisionService.start(this, result.resultCode, result.data!!)
            status.text = "RUNNING • vision loop active"
        } else status.text = "Screen capture permission denied"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
            setText(getSharedPreferences("omni", MODE_PRIVATE).getString("token", ""))
            setTextColor(0xFFE7EDF5.toInt())
            setHintTextColor(0xFF8190A3.toInt())
        }
        goal = EditText(this).apply {
            hint = "What should OmniPilot do?"
            setText("Assist me with the current Android screen.")
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
                getSharedPreferences("omni", MODE_PRIVATE).edit()
                    .putString("token", token.text.toString())
                    .putString("goal", goal.text.toString())
                    .putBoolean("autoExecute", autoExecute.isChecked)
                    .apply()
                if (!token.text.isNullOrBlank()) {
                    val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    captureLauncher.launch(mgr.createScreenCaptureIntent())
                } else status.text = "Enter a pairing token first"
            }
        }
        val stop = Button(this).apply {
            text = "EMERGENCY STOP"
            setOnClickListener {
                VisionService.stop(this)
                status.text = "STOPPED"
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
}
