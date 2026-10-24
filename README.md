# TikTok Region Patcher — Android starter project

This is a native Android Studio/Gradle starter project. It can:
- Select a single APK using Android's document picker.
- Inspect ZIP entries, DEX filenames and native `.so` libraries without loading the entire APK into memory.
- Choose a local region profile: Kazakhstan, Kyrgyzstan, USA, Belarus, Ukraine.
- Export a text report.

**It does not modify or re-sign TikTok.** The region choice is a UI/profile prototype and does not change TikTok server-side region or recommendations. A real patch requires version-specific analysis and implementation.

## Build
Open this folder in Android Studio with Android SDK 35 installed, then build the `app` debug variant.
