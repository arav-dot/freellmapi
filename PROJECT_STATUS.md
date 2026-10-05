# OmniPilot Project Status

## Goal
A free, permission-first Android companion that continuously observes the screen, asks Gemini what safe UI action should happen next, executes authorized actions, and repeats.

## Completed
- OmniPilot backend vision endpoint
- Gemini backend integration
- Pairing-token architecture
- MacroDroid fallback bridge
- Native Android project
- MediaProjection capture service
- AccessibilityService action layer
- Confidence gate
- Voice feedback
- Emergency stop
- Foreground service
- Token/goal handoff correction

## Remaining external validation
- Compile with Android SDK/Gradle
- Install on a physical Android device
- Grant Accessibility + screen-capture permissions
- Verify screenshot upload against live backend
- Verify tap/swipe/text coordinates on the target device
- Run a full observe/act/verify session
- Produce and test the signed/release APK

## Acceptance
The project is complete only after the external validation items pass. This environment cannot honestly mark those device/build checks as passed without an Android build host and device.
