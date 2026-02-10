# Arabic TTS

Bilingual Arabic/English text-to-speech Android app that runs entirely on-device.

## Features

- On-device TTS using Piper VITS models via Sherpa-ONNX
- Arabic voice: arabic-emirati-female (Emirati Arabic, female)
- English voice: en_US-amy (American English, female)
- Automatic Arabic/English language detection for mixed text
- Adjustable speech speed (0.5x - 2.0x)
- Models downloaded on first launch (~120 MB)

## Architecture

| Component | Technology |
|---|---|
| TTS Engine | Sherpa-ONNX v1.10.31 (ONNX Runtime) |
| TTS Models | Piper VITS (low quality, ~20-60 MB each) |
| Language Detection | Unicode range analysis |
| Audio Playback | Android AudioTrack |
| UI | Material Design 3 |

## Building

```bash
# Set Android SDK path
export ANDROID_HOME=/path/to/android-sdk

# Build debug APK
./gradlew assembleDebug

# APK location: app/build/outputs/apk/debug/app-debug.apk
```

Requires:
- JDK 17+
- Android SDK Platform 34
- Android Build Tools 34.0.0

## Project Structure

```
app/src/main/java/com/arabictts/app/
  MainActivity.kt       - UI and app lifecycle
  TTSEngine.kt          - Sherpa-ONNX TTS wrapper
  LanguageDetector.kt   - Arabic/English text segmentation
  ModelManager.kt       - Model download and management

app/src/main/java/com/k2fsa/sherpa/onnx/
  Tts.kt                - Sherpa-ONNX Kotlin API bindings

app/src/main/jniLibs/   - Pre-built native libraries
  arm64-v8a/            - 64-bit ARM
  armeabi-v7a/          - 32-bit ARM
  x86/                  - x86 emulator
  x86_64/               - x86_64 emulator
```

## Next Steps (Phase 2+)

- [ ] Integrate libtashkeel for automatic Arabic diacritization
- [ ] Fine-tune Piper VITS model on a custom voice
- [ ] Model quantization for smaller size
- [ ] Background TTS service
