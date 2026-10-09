# OmniPilot Android Companion

Native Android companion for the OmniPilot vision/action backend.

## Core loop

**Screen capture → Gemini vision → confidence gate → Accessibility action → next screenshot**

### Included

- MediaProjection screen capture
- Foreground service
- AccessibilityService with:
  - tap
  - swipe
  - text entry
  - Enter
  - home navigation and installed-app launch by app name
- Gemini vision through the existing OmniPilot backend
- 72% confidence gate
- Voice cues using Android TTS
- Pairing token + user goal
- Emergency STOP
- Persistent loop
- Permission-first architecture

## Build

Open this folder in Android Studio with Android SDK 35 installed.

```bash
./gradlew :app:assembleDebug
```

APK:

`app/build/outputs/apk/debug/app-debug.apk`

## First run

1. Install the debug APK.
2. Open OmniPilot.
3. Enter your private pairing token.
4. Enter the goal.
5. Enable OmniPilot under Android Accessibility settings.
6. Press START OMNIPILOT.
7. Approve screen capture.
8. Keep the foreground notification visible while the loop runs.
9. Press EMERGENCY STOP to halt it.

## Safety

This is intended for normal, user-authorized Android automation. It does not bypass authentication, CAPTCHA, anti-cheat systems, rate limits, device security, or access controls.

## Important implementation note

The companion sends screenshots to the configured OmniPilot `/api/agent/vision` endpoint. The endpoint should return:

```json
{
  "status": "ok",
  "summary": "short description",
  "voiceCue": "short spoken instruction",
  "confidence": 0.91,
  "action": {
    "type": "tap",
    "x": 540,
    "y": 1120
  }
}
```

Actions below the confidence threshold or `type: "none"` are not executed. With auto-execution enabled,
OmniPilot can use taps and swipes for game controls, enter text into the focused field, return to Home,
and launch an installed app by its visible name.

The Android project is source-complete for the native control path, but this chat environment does not have an Android SDK/build host attached, so an APK has not been falsely claimed as compiled here.
