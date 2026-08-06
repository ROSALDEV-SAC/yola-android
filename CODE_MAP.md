# CODE_MAP — yola-android

> **Stack**: Kotlin (Jetpack Compose) + Rust (daemon ARM64) + SolidJS (UI WebView) + Capacitor
> **Última actualización**: 2026-08-06 — generado por YOLA desde lectura exhaustiva del filesystem.

---

## 1. Stack Tecnológico

| Capa | Tecnología | Versión | Propósito |
|---|---|---|---|
| **Native Shell** | Kotlin + Jetpack Compose | 1.9.22 / BOM 2024.02.00 | Activity, foreground service, permisos, audio, WebView host |
| **Daemon (Rust)** | Rust edition 2021 | 0.1.0 | HTTP bridge :7779 + UDP beacon :41335, compilado a ARM64 |
| **UI (WebView)** | SolidJS + Vite | 1.9.14 / 6.x | Chat y Sesiones mobile-first, bundle servido desde assets o localhost |
| **Runtime Bridge** | WebSocket (OkHttp) | 4.12.0 | Comunicación Kotlin ↔ daemon ↔ Core |
| **TTS** | sherpa-onnx (Piper VITS) | 1.10.38 | Voz neuronal local sin internet, modelo español MX |
| **Build** | Android Studio + Cargo NDK | AGP 8.2.2 | APK compila Kotlin + empaqueta binario Rust |

---

## 2. Estructura de Directorios

```
yola-android/
├── daemon/                          # Binario Rust mínimo para Android ARM64
│   ├── Cargo.toml                   # Dependencias mínimas (sin browser/voice/tts)
│   ├── .cargo/config.toml           # Cross-compile targets (aarch64/armv7/x86_64)
│   └── src/main.rs                  # CLI, UDP beacon :41335, HTTP bridge :7779
│
├── app/                             # Kotlin nativo + Compose
│   ├── build.gradle.kts             # AGP 8.2.2, Compose, OkHttp, sherpa-onnx
│   └── src/main/
│       ├── AndroidManifest.xml      # Permisos, servicios, activity, receivers
│       ├── assets/README.md         # Instrucciones para binario daemon
│       ├── res/values/themes.xml    # Tema oscuro (#0a0a0f, accent #6C5CE7)
│       └── java/com/yolabysayri/yola/
│           ├── MainActivity.kt                  # Compose UI + WebView host
│           ├── service/
│           │   ├── BackgroundService.kt         # Foreground service, WS, audio, telemetría
│           │   ├── DaemonLauncher.kt            # Extrae y lanza binario Rust
│           │   └── BootReceiver.kt              # Auto-arranque al boot
│           ├── discovery/
│           │   └── DiscoveryClient.kt           # UDP listener :41335
│           ├── tts/
│           │   └── PiperTTSEngine.kt            # TTS neuronal con sherpa-onnx
│           └── profile/
│               └── BodyProfileManager.kt        # 4 perfiles de ejecución
│
├── ui/                              # si-yola adaptado mobile (SolidJS)
│   ├── package.json                 # solid-js, @yola/client, vite
│   ├── vite.config.js               # Dev :5174, build es2020
│   └── src/
│       ├── index.jsx                # Chat + Sesiones, stack navigation
│       ├── components/
│       │   └── DaemonSelector.jsx   # auto/local/manual daemon selector
│       └── styles/
│           ├── global.css           # Tema dark/light, CSS reset, syntax colors
│           └── mobile.css           # Safe-area, touch targets 44px, hide desktop
│
├── build.gradle.kts                 # Root: plugins AGP 8.2.2 + Kotlin 1.9.22
├── settings.gradle.kts              # rootProject.name = "YOLA", include :app
└── gradle.properties                # JVM 2048m, AndroidX, nonTransitiveRClass
```

---

## 3. daemon/ — Rust Binario Mínimo

### 3.1 Cargo.toml

```
Name:        yola-daemon-mobile v0.1.0
Edition:     2021
Description: "YOLA Daemon mínimo para Android — solo HTTP bridge + engine"
```

**Dependencias** (sin browser, voice, ni TTS — mínimo absoluto):

| Crate | Versión | Uso |
|---|---|---|
| `yola-agent-runtime` | path | Runtime del agente (importa mod.rs::run_http_server) |
| `yola-core` | path | Core types compartidos |
| `tokio` | 1 (full) | Async runtime, UDP spawn, signal handling |
| `serde` / `serde_json` | 1 | Serialización del beacon JSON |
| `clap` | 4 (derive) | CLI args (--port, --discovery-port) |
| `reqwest` | 0.12 (json+stream) | HTTP client interno |
| `chrono` | 0.4 | Timestamps |
| `log` / `env_logger` | 0.4 / 0.11 | Logging |
| `uuid` | 1 (v4) | Generación de IDs |
| `socket2` | 0.5 | Socket utilities |

**Perfil release**: `lto = true`, `codegen-units = 1`, `strip = true`, `opt-level = "s"` → binario mínimo optimizado para espacio.

### 3.2 .cargo/config.toml — Cross-compile

```toml
[target.aarch64-linux-android]   linker = "aarch64-linux-android21-clang"
[target.armv7-linux-androideabi] linker = "armv7a-linux-androideabi21-clang"
[target.x86_64-linux-android]    linker = "x86_64-linux-android21-clang"
```

Tres targets Android: ARM64 (principal), ARMv7 (32-bit legacy), x86_64 (emulador).

### 3.3 src/main.rs — 61 líneas

**Estructura**:
```
main() → env_logger::init()
       → Cli::parse()           // --port 7779 --discovery-port 41335
       → start_discovery_beacon() → tokio::spawn (loop 2s)
       → (pendiente: HTTP bridge delegado a yola-agent-runtime)
       → tokio::signal::ctrl_c().await
```

**CLI** (`clap` derive):
- `--port` (default: 7779) — Puerto HTTP bridge
- `--discovery-port` (default: 41335) — Puerto UDP beacon

**UDP Beacon** (`start_discovery_beacon`):
- Bind UDP a `0.0.0.0:41335`
- `set_broadcast(true)`
- Cada 2 segundos: obtiene IP local → broadcast a `255.255.255.255:41335`
- Payload JSON:
  ```json
  {
    "type": "YOLA_BEACON",
    "host": "<ip>",
    "port": 7779,
    "version": "0.1.0",
    "device": "android"
  }
  ```

**get_local_ip()**: UDP connect a `8.8.8.8:80` para resolver IP local real (no loopback).

**HTTP Bridge**: Comentario indica intención de usar `yola-agent-runtime::run_http_server` si es público. Actualmente el daemon solo emite beacon y espera Ctrl+C.

---

## 4. app/ — Kotlin Nativo + Compose

### 4.1 build.gradle.kts

| Config | Valor |
|---|---|
| namespace / applicationId | `com.yolabysayri.yola` |
| compileSdk / targetSdk | 34 |
| minSdk | 26 (Android 8.0) |
| versionCode / versionName | 1 / "0.1.0" |
| Compose | BOM 2024.02.00, compiler ext 1.5.8 |
| Java/Kotlin target | 17 |

**Dependencias clave**:
- `androidx.compose:compose-bom:2024.02.00` — UI, Material3, Animation
- `androidx.core:core-ktx:1.12.0`, `activity-compose:1.8.2`, `lifecycle-runtime-ktx:2.7.0`
- `com.squareup.okhttp3:okhttp:4.12.0` — WebSocket client
- `org.json:json:20231013` — JSON parsing nativo
- `com.k2fsa.sherpa:onnx:1.10.38` — TTS neuronal (Piper VITS)

### 4.2 AndroidManifest.xml

**Permisos**:
| Permiso | Razón |
|---|---|
| `INTERNET` | WebSocket, HTTP bridge |
| `ACCESS_NETWORK_STATE` | Detectar conectividad |
| `ACCESS_WIFI_STATE` | Multicast lock |
| `CHANGE_WIFI_MULTICAST_STATE` | UDP discovery |
| `FOREGROUND_SERVICE` + `DATA_SYNC` | BackgroundService persistente |
| `RECORD_AUDIO` | Micrófono PTT/continuo |
| `WAKE_LOCK` | Mantener CPU despierta |
| `RECEIVE_BOOT_COMPLETED` | Auto-arranque |
| `POST_NOTIFICATIONS` | Notificación foreground |

**Application**:
- `usesCleartextTraffic="true"` — HTTP/WS sin TLS en LAN
- `launchMode="singleTask"` — Una sola instancia de Activity
- `configChanges`: orientation, screenSize, screenLayout, keyboardHidden
- `windowSoftInputMode="adjustResize"`

**Componentes registrados**:
- `<activity>`: `.MainActivity` (MAIN/LAUNCHER)
- `<service>`: `.service.BackgroundService` (foregroundServiceType="dataSync")
- `<receiver>`: `.service.BootReceiver` (BOOT_COMPLETED)

### 4.3 themes.xml

Tema oscuro: `Material.NoActionBar` con `#0a0a0f` fondo, `#6C5CE7` primary, `#a09bfe` accent.

---

## 5. Clases Kotlin — Detalle

### 5.1 MainActivity.kt — `class MainActivity : ComponentActivity()`

**Rol**: Shell Compose que contiene WebView + UI nativa. Punto de entrada visible del APK.

**Funciones Composable** (orden de aparición):
| Composable | Propósito |
|---|---|
| `YolaBodyScreen()` | Pantalla principal: estado conexión, PTT, perfil, WebView |
| `GhostConnectedOrb(Color)` | Orbe "fantasma" cuando no hay Core |
| `ConnectedIndicator(Color)` | Indicador de conexión activa |
| `PulsingOrb(Color, Boolean)` | Orbe con animación de pulso |
| `ProfileSelectorDialog(...)` | Diálogo de selección de perfil (4 opciones) |
| `PTTButton(...)` | Botón Push-to-Talk con estado visual |
| `InteractiveButton(...)` | Botón interactivo genérico |
| `AdvancedStreamDisplay(String, Offset)` | Visualización de stream avanzada |
| `VirtualJoystick(onMove)` | Joystick virtual para control |
| `SourceSelectorDialog(...)` | Selector de fuente/cámara |

**Patrones**:
- Binding a `BackgroundService.instance` vía `ServiceConnection`
- `BroadcastReceiver` para UI_UPDATE desde el servicio
- Permisos runtime: RECORD_AUDIO, POST_NOTIFICATIONS
- `OnBackPressedCallback` para navegación WebView
- Coroutines (`lifecycleScope.launch`) para operaciones async
- Usa `com.yolabysayri.yola.ui.YolaFace` y `SoulState` (componente de animación facial)

### 5.2 BackgroundService.kt — `class BackgroundService : Service(), LifecycleOwner`

**Rol**: Foreground service que mantiene la conexión WebSocket, audio, telemetría y TTS incluso con la app en background.

**Estados** (enum `State`):
```
GHOST → CONNECTING → CONNECTED → RECONNECTING → (loop)
```

**Propiedades clave**:
- `deviceId: String` — UUID generado una vez, guardado en SharedPreferences
- `connectedCoreIp: String?` — IP del Core conectado
- `isConnected: Boolean`, `isForeground: Boolean`
- `instance: BackgroundService?` — Singleton volatile para binding desde Activity

**WebSocket** (`OkHttp`):
- Endpoint: `ws://<ip>:<port>/body`
- Handshake inicial: `{"type":"BODY_READY","deviceId":"...","role":"BODY"}`
- Reporta estado foreground: `{"type":"BODY_APP_STATE","foreground":true/false}`
- Recibe mensajes del Core (texto TTS, comandos)
- Heartbeat: thread dedicado cada 10s con `{"type":"PING"}` (evita cierre por Doze)

**Audio**:
- **Micrófono**: `AudioRecord` (16-bit PCM, 16000Hz mono). Envía chunks binarios por WS como `ByteString`.
- **Altavoz**: `AudioTrack` para reproducción de audio recibido.
- **PTT**: `startPTTRecording()` envía `{"type":"PTT_START"}` + audio stream; `stopPTTRecording()` envía `{"type":"AUDIO_END"}` como señal explícita de fin de turno.
- **Modo continuo**: Mic siempre activo (según perfil).

**TTS**:
- Fallback: `android.speech.tts.TextToSpeech` (sintetizador del sistema).
- Primario: `PiperTTSEngine` — voz neuronal local.
- Método `speak(text: String)` con cola de mensajes.

**Telemetría**:
- Nivel de batería (`BatteryManager`), estado WiFi (`WifiManager`).
- Envío periódico al Core vía WS: `{"type":"TELEMETRY","battery":...,"wifi":...}`.

**Ciclo de vida**:
- `onStartCommand` → startForeground + discovery/connect
- `onBind` → retorna `LocalBinder` para Activity
- `onDestroy` → cleanup (WS close, audio release, wake lock)

**BroadcastReceiver interno**: Screen on/off → notifica al Core `{"type":"BODY_APP_STATE","foreground":...}`.

### 5.3 DaemonLauncher.kt — `object DaemonLauncher`

**Rol**: Extrae el binario Rust de `assets/daemon/yola-daemon-mobile` y lo lanza como proceso nativo.

**Flujo**:
1. `context.filesDir/daemon/` → crea directorio
2. Si `yola-daemon-mobile` no existe → extrae de assets con `context.assets.open()`
3. `setExecutable(true)`
4. `ProcessBuilder` con args `--port 7779 --discovery-port 41335`
5. `redirectErrorStream(true)` — stdout y stderr combinados
6. `Thread.sleep(1500)` — espera inicialización
7. Retorna `process.isAlive`

### 5.4 BootReceiver.kt — `class BootReceiver : BroadcastReceiver()`

**Rol**: Recibe `BOOT_COMPLETED` y lanza `BackgroundService` como foreground service.

**Lógica**: 15 líneas. Si SDK ≥ O → `startForegroundService()`, else → `startService()`.

### 5.5 DiscoveryClient.kt — `class DiscoveryClient`

**Rol**: "El Sabueso" — escucha UDP :41335 buscando `YOLA_CORE_BEACON`.

**Protocolo**:
- Puerto UDP: 41335 (estricto)
- Beacon esperado: `{"type":"YOLA_CORE_BEACON","ip":"...","port":7779}`
- También acepta: `{"type":"YOLA_BEACON"}` (formato daemon local)
- Puerto WS: extraído del JSON, fallback 7779

**Modos de uso**:
- **Síncrono**: `discover(timeoutMs=3000): DiscoveredCore?` — bloquea hasta recibir beacon o timeout.
- **Asíncrono**: `start()` → thread dedicado + `MulticastLock` + callbacks vía `Handler(Looper.getMainLooper())`.

**Seguridad**:
- `isRunning: AtomicBoolean` previene múltiples instancias.
- `MulticastLock` para evitar que WiFi descarte paquetes UDP multicast.

**Data class**:
```kotlin
data class DiscoveredCore(val ip: String, val port: Int)
```

### 5.6 PiperTTSEngine.kt — `class PiperTTSEngine(context: Context)`

**Rol**: Motor TTS neuronal local usando sherpa-onnx para ejecutar modelos Piper VITS en el ARM del teléfono. Sin internet.

**Configuración**:
- Modelo: `es_MX-claude-high.onnx` (español México, voz Claude, alta calidad)
- Sample rate: 22050 Hz
- Archivos en `assets/piper/`:
  - `es_MX-claude-high.onnx` — Modelo VITS
  - `tokens.txt` — Vocabulario de tokens
  - `espeak-ng-data/` — Datos fonéticos

**API**:
| Método | Retorno | Descripción |
|---|---|---|
| `initialize()` | `Boolean` | Extrae assets, carga modelo ONNX, configura AudioTrack |
| `speak(text: String)` | `Unit` | Sintetiza y reproduce. Thread-safe. |
| `stop()` | `Unit` | Detiene reproducción actual |
| `shutdown()` | `Unit` | Libera recursos ONNX |
| `isSpeaking` | `Boolean` | Estado volatile de reproducción |

**Flujo de síntesis**:
1. `offlineTts.generate(text, speed=1.0)` → `OfflineTtsResult` (array Float de samples)
2. `AudioTrack.write(samples)` → reproducción directa

### 5.7 BodyProfileManager.kt — `class BodyProfileManager(context: Context)`

**Rol**: Gestiona 4 perfiles de ejecución según capacidades del hardware. Persiste elección en SharedPreferences.

**Enum `BodyProfile`** (4 modos):

| Perfil | Mic | UI | Audio Out | Caso de uso |
|---|---|---|---|---|
| `FULL_INTERACTIVE` | PTT | Completa (YolaFace) | TTS + audio | Gama alta, uso activo |
| `PASSIVE_DISPLAY` | OFF | Completa (YolaFace) | TTS + audio | Pantalla de visualización |
| `SENSOR_ONLY` | Continuo | Mínima (indicador) | TTS | Sensor ambiental, bajo consumo |
| `STEALTH` | OFF | Ninguna | Solo notificaciones | Background puro, batería crítica |

**Detección de capacidad** (`DeviceCapability`):
- `HIGH_END`: ≥ 4GB RAM → recomienda FULL_INTERACTIVE
- `MID_RANGE`: ≥ 2GB RAM → recomienda FULL_INTERACTIVE
- `LOW_END`: < 2GB RAM → recomienda SENSOR_ONLY

**API de consulta**:
| Método | Retorno |
|---|---|
| `isFirstRun()` | `Boolean` — primera ejecución |
| `getCurrentProfile()` | `BodyProfile?` — perfil guardado |
| `setProfile(profile)` | `Unit` — guarda selección |
| `detectDeviceCapability()` | `DeviceCapability` — RAM-based |
| `getRecommendedProfile()` | `BodyProfile` — sugerencia automática |
| `isMicrophoneEnabled()` | `Boolean` — ¿perfil actual usa mic? |
| `isPushToTalkMode()` | `Boolean` — ¿PTT activo? |
| `isContinuousMicMode()` | `Boolean` — ¿mic continuo? |
| `shouldShowFullUI()` | `Boolean` — ¿mostrar YolaFace? |

**Persistencia**: `SharedPreferences("YolaBodyPrefs")`, claves: `body_profile`, `first_run_completed`, `capability_detected`.

---

## 6. ui/ — SolidJS Mobile-First

### 6.1 package.json

```
Name: yola-android-ui v0.1.0
Type: module
Scripts: dev (vite :5174), build, preview
Deps: solid-js ^1.9.14, @yola/client (local)
DevDeps: vite ^6, vite-plugin-solid ^2.11.13
```

### 6.2 vite.config.js

```js
plugins: [solidPlugin()]
dev server: port 5174
build target: es2020
```

### 6.3 src/index.jsx — Entry Point

**Arquitectura**: Single Page Application con navegación tipo stack (2 vistas).

**Estado global** (SolidJS signals):
- `currentApp`: `'chat'` | `'sessions'`
- `daemonUrl`: URL del daemon (de DaemonSelector)

**Vistas**:

**ChatView** (vista principal):
- Lista de mensajes con auto-scroll
- Input text + botón enviar
- POST a `{daemonUrl}/api/chat` con `{message, agentId:'yola', sessionId:'yola'}`
- Respuesta JSON → append mensaje assistant
- Fallback si no hay daemonUrl: `http://localhost:7779`

**SessionsView**:
- Lista de sesiones desde API
- Selección de sesión → carga historial
- Navegación back al chat

**Navegación**:
- Bottom nav con 2 tabs: Chat, Sessions
- Sin taskbar, sin ventanas, sin desktop — mobile-first puro

### 6.4 DaemonSelector.jsx

**Rol**: Selector de conexión al daemon. 3 modos + health check.

**Modos**:
| Modo | URL | Descripción |
|---|---|---|
| `auto` | `http://localhost:7779` (fallback) | UDP discovery vía @yola/client |
| `local` | `http://localhost:7779` | Daemon local |
| `manual` | `http://{host}:{port}` | IP:puerto configurable |

**Estados**: `checking` → `connected` / `disconnected`

**Health check**: Poll cada 3s al endpoint `/health`. Timeout 2s con AbortController.

**Persistencia**: `localStorage` key `yola-daemon-pref` con `{mode, host, port}`.

### 6.5 mobile.css

**Safe Area**: CSS vars `--safe-top/bottom/left/right` desde `env(safe-area-inset-*, 0px)`.

**Touch Targets**: Botones mínimo 44×44px (Apple HIG), `touch-action: manipulation`.

**Desktop Hiding**: `.taskbar, .window-frame, .desktop, .desktop-icons, .window, .start-menu, .window-switcher` → `display: none !important`.

**Mobile Shell**: Flex column, `height: 100%`, sin overflow en body.

**Listas**: `-webkit-overflow-scrolling: touch`, `overflow-y: auto`.

**Media Queries**: Ajustes para portrait, landscape, teclado visible.

### 6.6 global.css

**Tema Dark** (default):
- `--bg-desktop: #1a1a2e`
- `--bg-window: #1e1e3a`
- `--accent: #4fc3f7`
- `--text-primary: #e0e0e0`

**Tema Light** (`[data-theme="light"]`):
- `--bg-desktop: #e8eaf6`
- `--bg-window: #ffffff`
- `--text-primary: #1a1a2e`

**YOLA Code Syntax** (dark):
- keyword: `#c678dd`, string: `#98c379`, comment: `#7f848e`
- number: `#d19a66`, function: `#61afef`, punct: `#e06c75`

**Reset**: `*, *::before, *::after { margin:0; padding:0; box-sizing:border-box }`

---

## 7. Flujo de Arranque (End-to-End)

```
BOOT / APP LAUNCH
    │
    ▼
┌──────────────────────────────────────────────────────┐
│ 1. BootReceiver (si BOOT_COMPLETED)                  │
│    └─→ startForegroundService(BackgroundService)     │
│                                                      │
│ 2. MainActivity.onCreate()                           │
│    ├─→ bindService(BackgroundService)                │
│    ├─→ SetContent { YolaBodyScreen + WebView }       │
│    └─→ load WebView → si-yola bundle (localhost/ui)  │
└──────────────┬───────────────────────────────────────┘
               │
               ▼
┌──────────────────────────────────────────────────────┐
│ 3. BackgroundService.onStartCommand()                │
│    ├─→ BodyProfileManager: detectar capacidad         │
│    ├─→ startForegroundNotification()                 │
│    ├─→ DiscoveryClient.start()                       │
│    │     └─→ UDP :41335, busca YOLA_CORE_BEACON      │
│    │                                                  │
│    ├─→ ¿Beacon recibido?                              │
│    │   ├─ SÍ → connectToCore(ip, port)               │
│    │   │        └─→ ws://ip:port/body (OkHttp)       │
│    │   │              ├─→ BODY_READY handshake        │
│    │   │              ├─→ BODY_APP_STATE (foreground) │
│    │   │              └─→ Audio + Telemetry stream    │
│    │   │                                              │
│    │   └─ NO → tryLastKnownCore() (SharedPreferences)│
│    │           └─→ Si falla → DaemonLauncher.launch() │
│    │                          ├─→ Extrae binario ARM64│
│    │                          ├─→ ProcessBuilder      │
│    │                          │   --port 7779         │
│    │                          │   --discovery 41335   │
│    │                          └─→ UDP beacon local    │
│    │                                                  │
│    └─→ PiperTTSEngine.initialize()                   │
└──────────────────────────────────────────────────────┘
               │
               ▼
┌──────────────────────────────────────────────────────┐
│ 4. WebView (si-yola)                                 │
│    ├─→ DaemonSelector: health check → /health        │
│    ├─→ ChatView: POST /api/chat                      │
│    └─→ SessionsView: GET /api/sessions               │
└──────────────────────────────────────────────────────┘
```

**Protocolo WebSocket** (BackgroundService ↔ Core):
```
BODY → CORE:  {"type":"BODY_READY","deviceId":"...","role":"BODY"}
BODY → CORE:  {"type":"BODY_APP_STATE","foreground":true/false}
BODY → CORE:  {"type":"PTT_START","deviceId":"...","timestamp":...}
BODY → CORE:  <audio chunks binary>
BODY → CORE:  {"type":"AUDIO_END","deviceId":"..."}
BODY → CORE:  {"type":"PING"}  (heartbeat 10s)
BODY → CORE:  {"type":"TELEMETRY","battery":...,"wifi":...}
CORE → BODY:  {"type":"TTS","text":"..."}
CORE → BODY:  {"type":"AUDIO", ...}
```

---

## 8. Build & Deploy

### 8.1 Compilar daemon (Rust → ARM64)

```bash
cd daemon
cargo build --release --target aarch64-linux-android
# Output: daemon/target/aarch64-linux-android/release/yola-daemon-mobile
```

Requisitos: Android NDK + `aarch64-linux-android21-clang` en PATH.

### 8.2 Empaquetar en APK

1. Copiar binario a `app/src/main/assets/daemon/yola-daemon-mobile`
2. Copiar modelos Piper a `app/src/main/assets/piper/`
   - `es_MX-claude-high.onnx`
   - `tokens.txt`
   - `espeak-ng-data/`
3. Abrir en Android Studio
4. Build → Build APK

### 8.3 Compilar UI (SolidJS)

```bash
cd ui
bun run build
# Output: ui/dist/ → servido por WebView desde assets o HTTP local
```

---

## 9. Dependencias entre Módulos

```
┌──────────────────────────────────────────┐
│                 APK                       │
│                                          │
│  ┌──────────┐    ┌──────────────────┐   │
│  │ SolidJS   │◄───│ MainActivity.kt  │   │
│  │ (WebView) │    │  (Compose host)  │   │
│  └──────────┘    └────────┬─────────┘   │
│                           │ bindService  │
│                  ┌────────▼─────────┐   │
│                  │ BackgroundService│   │
│                  │  (foreground)    │   │
│                  └──┬───────┬───────┘   │
│                     │       │           │
│          ┌──────────▼─┐ ┌──▼──────────┐│
│          │DiscoveryCl │ │DaemonLauncher││
│          │ UDP :41335 │ │ ProcessBuilder││
│          └────────────┘ └──────┬──────┘│
│                                │       │
│  ┌─────────────────────────────▼──┐    │
│  │     PiperTTSEngine             │    │
│  │     (sherpa-onnx)              │    │
│  └────────────────────────────────┘    │
│                                        │
│  ┌────────────────────────────────┐    │
│  │  BodyProfileManager             │    │
│  │  (4 profiles, SharedPrefs)      │    │
│  └────────────────────────────────┘    │
└──────────────────────────────────────────┘
                      │
                      │ WebSocket (OkHttp)
                      ▼
┌──────────────────────────────────────────┐
│         yola-daemon-mobile (Rust)         │
│  ┌────────────────────────────────────┐  │
│  │ UDP Beacon :41335 (cada 2s)        │  │
│  │ HTTP Bridge :7779                  │  │
│  └────────────────────────────────────┘  │
└──────────────┬───────────────────────────┘
               │
               ▼
         YOLA Core (PC)
```

---

## 10. Archivos de Assets Requeridos

| Ruta en assets/ | Archivo | Origen | Tamaño aprox |
|---|---|---|---|
| `daemon/yola-daemon-mobile` | Binario Rust ARM64 | `cargo build --release --target aarch64-linux-android` | ~5-15 MB |
| `piper/es_MX-claude-high.onnx` | Modelo VITS español MX | Piper TTS models | ~50 MB |
| `piper/tokens.txt` | Vocabulario de tokens | Piper TTS models | <1 KB |
| `piper/espeak-ng-data/` | Datos fonéticos | espeak-ng | ~5 MB |
| `ui/dist/*` | Bundle SolidJS | `bun run build` | ~200 KB |

---

## 11. Notas de Arquitectura

1. **HTTP bridge sin implementar**: El `main.rs` del daemon tiene comentado `// Iniciar bridge HTTP`. Actualmente solo emite beacon UDP. La implementación real del HTTP bridge depende de si `yola-agent-runtime::run_http_server` es pública.

2. **Dos motores TTS**: Existe fallback dual — `android.speech.tts.TextToSpeech` (voz del sistema) y `PiperTTSEngine` (voz neuronal). El servicio prefiere Piper si el modelo está disponible.

3. **Ghost Mode**: Cuando no hay Core conectado, el servicio entra en `State.GHOST` y el Activity muestra `GhostConnectedOrb`. El daemon local puede lanzarse en este estado para proporcionar funcionalidad offline básica.

4. **Telemetría reactiva**: El nivel de batería y estado WiFi se reportan al Core, permitiendo que el Core adapte su comportamiento (ej: reducir TTS si batería < 15%).

5. **No Capacitor real**: Aunque se menciona Capacitor en el stack conceptual, no hay `@capacitor/core` en las dependencias. La integración es vía WebView nativo de Android con comunicación por WebSocket.

6. **Persistencia mínima**: Solo se guarda IP:puerto del último Core (`SharedPreferences`), perfil seleccionado, y preferencia de daemon (`localStorage`). Sin base de datos local.
