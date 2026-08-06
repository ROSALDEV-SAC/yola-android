# yola-android

Cliente YOLA nativo para Android. Cuatro capas integradas: Kotlin + Jetpack Compose (shell nativo), Rust (daemon ARM64), SolidJS (UI WebView), y sherpa-onnx (TTS neuronal local).

## Stack

| Capa | Tecnología | Versión |
|---|---|---|
| Native Shell | Kotlin + Jetpack Compose | 1.9.22 / BOM 2024.02.00 |
| Daemon | Rust edition 2021 (ARM64 cross-compile) | 0.1.0 |
| UI WebView | SolidJS + Vite | 1.9.14 / 6.x |
| Runtime Bridge | WebSocket (OkHttp) | 4.12.0 |
| TTS | sherpa-onnx (Piper VITS español MX) | 1.10.38 |
| Build | Android Studio + Cargo NDK | AGP 8.2.2 |

## Estructura

```
yola-android/
├── daemon/                  # Binario Rust mínimo para Android ARM64
│   ├── Cargo.toml           # Dependencias mínimas (sin browser/voice/tts)
│   ├── .cargo/config.toml   # Cross-compile targets (aarch64/armv7/x86_64)
│   └── src/main.rs          # CLI, UDP beacon :41335, HTTP bridge :7779
├── app/                     # Kotlin nativo + Jetpack Compose
│   ├── build.gradle.kts     # AGP 8.2.2, Compose, OkHttp, sherpa-onnx
│   └── src/main/
│       ├── AndroidManifest.xml  # Permisos, servicios, activity, receivers
│       ├── assets/              # Bundle SolidJS + binario daemon
│       └── java/com/yolabysayri/yola/
│           ├── MainActivity.kt      # Activity principal, WebView host
│           ├── YolaForegroundService.kt  # Foreground service para daemon
│           └── YolaWebViewClient.kt     # Bridge JS ↔ Kotlin
```

## Cómo buildear

```bash
# 1. Compilar daemon Rust para ARM64
cd daemon
cargo build --release --target aarch64-linux-android

# 2. Copiar binario a assets
copy target\aarch64-linux-android\release\yola-daemon ..\app\src\main\assets\

# 3. Compilar APK
cd ..\app
./gradlew assembleDebug
```

## Requisitos

- Android Studio Hedgehog (2023.1+) o superior
- Rust toolchain con target `aarch64-linux-android`
- Android NDK 26+
- Java 17 / Kotlin 1.9.22
