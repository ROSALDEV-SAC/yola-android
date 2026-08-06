# AGENTS.md — yola-android

Eres un agente de YOLA trabajando en este repositorio.

## Stack
- Native shell: Kotlin 1.9.22 + Jetpack Compose (BOM 2024.02.00)
- Daemon: Rust edition 2021 → ARM64 cross-compile
- UI WebView: SolidJS + Vite (servido desde assets)
- Bridge: WebSocket (OkHttp 4.12.0)
- TTS local: sherpa-onnx (Piper VITS, español MX)
- Build: Android Studio (AGP 8.2.2) + Cargo NDK

## Estructura
- `app/` — Kotlin nativo: Activity, foreground service, WebView host, permisos, audio
- `app/src/main/java/com/yolabysayri/yola/` — Código Kotlin (MainActivity, DaemonService, etc.)
- `app/src/main/AndroidManifest.xml` — Permisos, servicios, receivers
- `daemon/` — Binario Rust mínimo (HTTP bridge :7779 + UDP beacon :41335)
- `ui/` — Frontend SolidJS mobile-first (chat + sesiones)
- `build.gradle.kts` — Root build (Kotlin plugin)

## Cómo buildear
Abrir en Android Studio → Build → Build APK
O por CLI:
```
./gradlew assembleDebug
```

## Cómo testear
```
./gradlew test
```

## Reglas
- El daemon Rust se compila con features mínimas (`--no-default-features --features client`) — sin browser, voice, TTS
- Nunca hardcodees la IP del daemon — usar descubrimiento UDP (beacon :41335) o `localhost`
- La UI WebView carga desde `app/src/main/assets/` — el bundle SolidJS debe copiarse ahí antes de build
- El foreground service es obligatorio para mantener el daemon vivo en background
- No agregues dependencias (Gradle o Cargo) sin preguntar

## Dónde tocar
- ¿Fix de UI nativa? → `app/src/main/java/...` (Kotlin/Compose)
- ¿Fix del daemon? → `daemon/src/main.rs`
- ¿Fix de WebView UI? → `ui/src/` (SolidJS)
- ¿Permisos nuevos? → `app/src/main/AndroidManifest.xml`
