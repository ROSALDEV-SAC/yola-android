package com.yolabysayri.yola.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.BatteryManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.net.wifi.WifiManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.yolabysayri.yola.camera.RetinaServer
import com.yolabysayri.yola.discovery.DiscoveredCore
import com.yolabysayri.yola.discovery.DiscoveryClient
import com.yolabysayri.yola.service.DaemonLauncher

/**
 * +---------------------------------------------------------------------------+
 * �                    BACKGROUND SERVICE - ONE FACE PROTOCOL                  �
 * �                      COMPATIBLE ANDROID 5.0 (API 21)                      �
 * �---------------------------------------------------------------------------�
 * �  FASE 5: Reports BODY_ACTIVITY to Core for global presence orchestration  �
 * +---------------------------------------------------------------------------+
 */
class BackgroundService : Service(), LifecycleOwner {

    companion object {
        private const val TAG = "YOLA_BODY"
        private const val PREFS_NAME = "YolaBodyPrefs"
        private const val KEY_LAST_IP = "last_core_ip"
        private const val KEY_LAST_PORT = "last_core_port"
        private const val DEFAULT_PORT = 7779
        
        // Intent actions globales
        const val ACTION_UI_UPDATE = "com.example.YOLA.UI_UPDATE"
        const val EXTRA_STATUS = "status"
        const val EXTRA_ACTION = "action"
        const val EXTRA_STATE = "state"
        const val EXTRA_CONNECTED = "connected"
        const val EXTRA_SHOW_FACE = "show_face" // FASE 2: ONE FACE Protocol
        
        // Singleton instance for Activity binding
        @Volatile
        var instance: BackgroundService? = null
            private set
    }

    // Binder for Activity communication
    inner class LocalBinder : Binder() {
        fun getService(): BackgroundService = this@BackgroundService
    }
    private val binder = LocalBinder()

    enum class State { GHOST, CONNECTING, CONNECTED, RECONNECTING }
    private var currentState = State.GHOST
    private var isConnected = false
    private var isForeground = false  // FASE 5: Track if app is in foreground
    private var connectedCoreIp: String? = null  // ? GUARDAR IP del Core para reenviarla
    
    // FASE 6: Heartbeat para mantener WebSocket vivo con pantalla apagada
    // Usamos un Thread dedicado en lugar de Handler porque el Handler depende del Looper
    // que Android puede pausar cuando la pantalla est� apagada
    private val HEARTBEAT_INTERVAL_MS = 10_000L  // 10 segundos
    @Volatile private var heartbeatThreadRunning = false
    private var heartbeatThread: Thread? = null
    
    private fun startHeartbeatThread() {
        if (heartbeatThreadRunning) return
        heartbeatThreadRunning = true
        heartbeatThread = Thread {
            Log.i(TAG, "?? [HEARTBEAT-THREAD] Iniciado")
            while (heartbeatThreadRunning && isConnected) {
                try {
                    Thread.sleep(HEARTBEAT_INTERVAL_MS)
                    if (isConnected && webSocket != null) {
                        val heartbeat = org.json.JSONObject()
                        heartbeat.put("type", "HEARTBEAT")
                        heartbeat.put("timestamp", System.currentTimeMillis())
                        heartbeat.put("deviceId", deviceId)
                        webSocket?.send(heartbeat.toString())
                        Log.i(TAG, "?? [APK-HEARTBEAT] Enviado (Thread)")
                    }
                } catch (e: InterruptedException) {
                    Log.i(TAG, "?? [HEARTBEAT-THREAD] Interrumpido")
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "?? [APK-HEARTBEAT] Error: ${e.message}")
                }
            }
            Log.i(TAG, "?? [HEARTBEAT-THREAD] Terminado")
        }.apply {
            name = "YOLA-Heartbeat"
            isDaemon = true
            start()
        }
    }
    
    private fun stopHeartbeatThread() {
        heartbeatThreadRunning = false
        heartbeatThread?.interrupt()
        heartbeatThread = null
        Log.i(TAG, "?? [HEARTBEAT-THREAD] Detenido")
    }

    private var discoveryClient: DiscoveryClient? = null
    private var webSocket: WebSocket? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    
    // PIPER TTS � Motor de voz neural nativo (prioridad sobre Android TTS)
    private var piperTTS: PiperTTSEngine? = null
    
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var isRecording = false

    private lateinit var prefs: SharedPreferences
    private val mainHandler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null  // NUEVO: Mantiene WiFi activo

    // FASE 4: Profile Manager para PTT y modos de operaci�n
    private lateinit var profileManager: BodyProfileManager

    // Screen Off Receiver - FASE 5: Detect when user locks screen
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.i(TAG, "?? Screen OFF detected")
                    reportActivityState(false)
                }
                Intent.ACTION_SCREEN_ON -> {
                    Log.i(TAG, "?? Screen ON detected")
                    // Note: We only report foreground=true when Activity resumes
                }
            }
        }
    }

    // OkHttp Client
    private val wsClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private val sampleRateMic = 16000
    private val sampleRateSpk = 22050
    private val encoding = AudioFormat.ENCODING_PCM_16BIT

    private val deviceId: String by lazy {
        Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            ?: UUID.randomUUID().toString()
    }

    // LIFECYCLE (Required for CameraX)
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    // RETINA SERVER & CAMERA MANAGER
    private var retinaServer: RetinaServer? = null
    private var cameraManager: com.example.YOLA.camera.CameraManager? = null

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        
        Log.i(TAG, "?? BackgroundService Creado - API: " + Build.VERSION.SDK_INT)
        instance = this

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Capturar CRASHES globales para verlos en Logcat
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "?? [FATAL-CRASH] Exception in thread ${thread.name}: ${throwable.message}", throwable)
        }

        // FASE 4: Initialize Profile Manager
        profileManager = BodyProfileManager(this)
        Log.i(TAG, "?? Perfil actual: ${profileManager.getCurrentProfile()?.displayName ?: "No configurado"}")

        startForegroundNotification()
        initializeTTS()
        
        initializePiperTTS()
        // Init Camera Systems
        retinaServer = RetinaServer()
        cameraManager = com.example.YOLA.camera.CameraManager(this, this) { frame ->
            retinaServer?.pushFrame(frame)
        }
        
        initializeAudioTrack()
        acquireWakeLock()
        registerScreenReceiver()
        
        lifecycleRegistry.currentState = Lifecycle.State.STARTED

        enterGhostMode()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // CRITICAL: Always call startForeground when onStartCommand is invoked
        // This prevents ForegroundServiceDidNotStartInTimeException
        startForegroundNotification()

        // 1. Discovery: buscar daemon en la red
        var daemonHost = "localhost"
        var daemonPort = 7779

        val discoveryClient = DiscoveryClient(this)
        val discovered = discoveryClient.discover() // escucha UDP :41335 por 3 segundos

        if (discovered != null) {
            // Encontró un daemon en la red → conectarse a él
            daemonHost = discovered.host
            daemonPort = discovered.port
            Log.i(TAG, "Daemon encontrado en red: $daemonHost:$daemonPort")
        } else {
            // No encontró daemon → lanzar local
            Log.i(TAG, "Sin daemon en red. Iniciando local...")
            val started = DaemonLauncher.launch(this, daemonPort, 41335)
            if (!started) {
                Log.e(TAG, "No se pudo iniciar el daemon local")
                stopSelf()
                return START_NOT_STICKY
            }
            Log.i(TAG, "Daemon local iniciado en :$daemonPort")
        }

        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "?? BackgroundService Destruido")
        instance = null
        retinaServer?.stop()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        
        stopRecording()
        stopHeartbeatThread()  // FASE 6: Detener heartbeat thread
        unregisterScreenReceiver()
        discoveryClient?.stop()
        webSocket?.close(1000, "Service destroyed")
        audioRecord?.release()
        audioTrack?.release()
        piperTTS?.shutdown()  // Liberar Piper TTS
        tts?.shutdown()
        releaseWakeLock()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ========================================================================
    // FASE 5: SCREEN RECEIVER & ACTIVITY REPORTING
    // ========================================================================

    private fun registerScreenReceiver() {
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            // Note: ACTION_SCREEN_ON/OFF are system-wide broadcasts, need RECEIVER_EXPORTED on API 33+
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(screenReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(screenReceiver, filter)
            }
            Log.d(TAG, "?? Screen receiver registered")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register screen receiver: ${e.message}")
            // Non-fatal: continue without screen detection
        }
    }

    private fun unregisterScreenReceiver() {
        try {
            unregisterReceiver(screenReceiver)
        } catch (e: Exception) { }
    }

    /**
     * FASE 5: Report activity state (foreground/background) to Core
     * Called by MainActivity on lifecycle events
     */
    fun reportActivityState(foreground: Boolean) {
        if (isForeground == foreground) return  // No change
        isForeground = foreground
        
        if (!isConnected || webSocket == null) {
            Log.d(TAG, "?? Cannot report activity - not connected")
            return
        }

        try {
            val json = JSONObject().apply {
                put("type", "BODY_ACTIVITY")
                put("foreground", foreground)
                put("deviceId", deviceId)
                put("timestamp", System.currentTimeMillis())
            }
            webSocket?.send(json.toString())
            Log.i(TAG, "?? Reported BODY_ACTIVITY: foreground=$foreground")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to report activity state: ${e.message}")
        }
    }

    // ========================================================================
    // NOTIFICACIONES (API 21 SAFE)
    // ========================================================================

    @android.annotation.SuppressLint("ForegroundServiceType", "WrongConstant")
    private fun startForegroundNotification() {
        val channelId = "YolaBodyChannel"
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "YOLA Body Service",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "Mantiene conexi�n con el Cerebro YOLA"
            channel.setShowBadge(false)
            
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        // Crear Intent para abrir la app al tocar la notificaci�n
        val pendingIntent = android.app.PendingIntent.getActivity(
            this, 
            0, 
            Intent(this, MainActivity::class.java),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) android.app.PendingIntent.FLAG_IMMUTABLE else 0
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("YOLA Body")
            .setContentText("?? Buscando se�al...")
            .setSmallIcon(android.R.drawable.stat_sys_headset)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent) // Importante para Android modernos
            .build()

        // Reemplaza la llamada actual a startForeground con esto:
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                // 128 (MICROPHONE) | 64 (CAMERA) = 192
                // Usamos el entero directo para evitar problemas de importaci�n
                startForeground(1, notification, 192) 
            } else {
                // Android 8.0 a 9.0 (No soportan el par�metro de tipo)
                startForeground(1, notification)
            }
        } catch (e: Exception) {
            android.util.Log.e("YOLA", "Error fatal iniciando servicio foreground: ${e.message}")
        }
    }

    private fun updateNotification(text: String) {
        val channelId = "YolaBodyChannel"
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("YOLA Body")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_headset)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(1, notification)
    }

    // ========================================================================
    // WAKELOCK & WIFILOCK - Mantiene CPU y WiFi activos en segundo plano
    // ========================================================================

    private fun acquireWakeLock() {
        try {
            // 1. CPU WakeLock
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "YOLA:BodyService")
            wakeLock?.acquire(60 * 60 * 1000L) // 1 hora
            Log.i(TAG, "?? [LOCK] WakeLock ADQUIRIDO (CPU activa)")
            
            // 2. WiFi WakeLock - CR�TICO para mantener WebSocket vivo
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wm != null) {
                @Suppress("DEPRECATION")
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "YOLA:WifiLock")
                wifiLock?.setReferenceCounted(false)
                wifiLock?.acquire()
                Log.i(TAG, "?? [LOCK] WiFiLock ADQUIRIDO (WiFi HIGH_PERF)")
            } else {
                Log.w(TAG, "?? [LOCK] WifiManager no disponible!")
            }
        } catch (e: Exception) {
            Log.e(TAG, "? [LOCK] Error WakeLock/WifiLock: " + e.message)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
                Log.d(TAG, "?? WiFiLock liberado")
            }
        } catch (e: Exception) { }
    }

    // ========================================================================
    // TTS (PIPER NEURAL + ANDROID FALLBACK)
    // ========================================================================

    /**
     * Inicializa Android TTS como fallback.
     */
    private fun initializeTTS() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale("es", "ES"))
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale.getDefault())
                }
                ttsReady = true
                Log.d(TAG, "? Android TTS listo (fallback)")
            }
        }
    }

    /**
     * Inicializa Piper TTS como motor primario de voz neural.
     * Se ejecuta en un thread separado para no bloquear onCreate.
     */
    private fun initializePiperTTS() {
        Thread {
            try {
                piperTTS = PiperTTSEngine(this)
                val success = piperTTS?.initialize() ?: false
                if (success) {
                    Log.i(TAG, "??? ? Piper TTS neural inicializado � voz de alta calidad activa")
                    mainHandler.post {
                        speak("Sistema Yola activo. Voz neural activada. Buscando red.")
                    }
                } else {
                    Log.w(TAG, "?? Piper TTS fall�, usando Android TTS como fallback")
                    piperTTS = null
                    mainHandler.post {
                        speak("Sistema Yola activo. Buscando red.")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "? Error cargando Piper TTS: ${e.message}", e)
                piperTTS = null
                mainHandler.post {
                    speak("Sistema Yola activo. Buscando red.")
                }
            }
        }.apply {
            name = "PiperTTS-Init"
            isDaemon = true
            start()
        }
    }

    /**
     * Habla usando Piper TTS (neural) o Android TTS (fallback).
     * Piper tiene prioridad si est� inicializado.
     */
    private fun speak(text: String) {
        try {
            val piper = piperTTS
            if (piper != null) {
                if (piper.isReady()) {
                    Log.i(TAG, "??? [PIPER-GATE] Enviando a motor: \"${text.take(40)}...\"")
                    piper.speak(text)
                    return
                } else {
                    Log.d(TAG, "? [PIPER] Motor existe pero a�n no est� listo. Usando fallback.")
                }
            } else {
                Log.d(TAG, "?? [PIPER] Motor no instanciado. Usando fallback.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "?? [PIPER-GATE-ERROR] Error fatal en speak(): ${e.message}", e)
        }

        // Prioridad 2: Android TTS (voz del sistema, fallback)
        if (ttsReady) {
            Log.i(TAG, "??? [ANDROID-TTS] Hablando (fallback): \"${text.take(40)}...\"")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "yola_tts")
            } else {
                @Suppress("DEPRECATION")
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null)
            }
        }
    }

    // ========================================================================
    // L�GICA PRINCIPAL
    // ========================================================================

    private fun enterGhostMode() {
        currentState = State.GHOST
        isConnected = false
        webSocket?.close(1000, "Ghost")
        webSocket = null
        stopRecording()

        broadcastUIUpdate("?? MODO GHOST", "Escaneando...", "GHOST", false)
        updateNotification("?? Buscando se�al...")

        startDiscovery()

        mainHandler.postDelayed({
            if (currentState == State.GHOST && !isConnected) {
                tryLastKnownCore()
            }
        }, 5000)
    }

    private fun startDiscovery() {
        discoveryClient?.stop()
        discoveryClient = DiscoveryClient(this, { ip, port ->
            if (!isConnected && currentState != State.CONNECTED) {
                saveCore(ip, port)
                connectToCore(ip, port)
            }
        }, { status ->
            if (currentState == State.GHOST) {
                broadcastUIUpdate(null, status, null, null)
            }
        })
        discoveryClient?.start()
    }

    private fun tryLastKnownCore() {
        val lastIp = prefs.getString(KEY_LAST_IP, null)
        val lastPort = prefs.getInt(KEY_LAST_PORT, DEFAULT_PORT)

        if (lastIp != null && !isConnected) {
            currentState = State.RECONNECTING
            broadcastUIUpdate("?? Reconectando...", "Probando $lastIp", "RECONNECTING", false)
            connectToCore(lastIp, lastPort)
        }
    }

    private fun saveCore(ip: String, port: Int) {
        prefs.edit().putString(KEY_LAST_IP, ip).putInt(KEY_LAST_PORT, port).apply()
    }

    // ========================================================================
    // WEBSOCKET
    // ========================================================================

    private fun connectToCore(ip: String, port: Int) {
        if (isConnected) return

        webSocket?.close(1000, "New conn")
        webSocket = null
        currentState = State.CONNECTING
        
        broadcastUIUpdate("? Conectando...", "Enlazando $ip", "CONNECTING", false)

        val url = "ws://$ip:$port/body"
        Log.i(TAG, "Conectando WS: $url")

        val request = Request.Builder().url(url).build()

        webSocket = wsClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                mainHandler.post {
                    isConnected = true
                    currentState = State.CONNECTED
                    connectedCoreIp = ip  // ? GUARDAR IP para uso posterior
                    broadcastUIUpdate("?? CONECTADO", "Enlace activo", "CONNECTED", true, ip)
                    updateNotification("?? Conectado al N�cleo")
                    speak("Enlace neuronal establecido.")
                    discoveryClient?.stop()
                    
                    // 1. Send handshake with role info
                    val handshake = JSONObject()
                    handshake.put("type", "BODY_READY")
                    handshake.put("deviceId", deviceId)
                    handshake.put("role", "BODY")
                    ws.send(handshake.toString())
                    
                    // 2. FASE 5: Report current foreground state immediately
                    // Activity is likely already in foreground when we connect
                    val activityState = JSONObject()
                    activityState.put("type", "BODY_ACTIVITY")
                    activityState.put("foreground", true) // We're definitely active when connecting
                    activityState.put("deviceId", deviceId)
                    activityState.put("timestamp", System.currentTimeMillis())
                    ws.send(activityState.toString())
                    Log.i(TAG, "?? Sent initial BODY_ACTIVITY: foreground=true")
                    isForeground = true
                    
                    // FASE 6: Iniciar heartbeat peri�dico para mantener conexi�n viva
                    Log.i(TAG, "?? Iniciando heartbeat cada ${HEARTBEAT_INTERVAL_MS/1000}s")
                    startHeartbeatThread()
                    
                    // FASE 4: Start recording based on profile
                    // FULL_INTERACTIVE: No auto-start (PTT mode)
                    // SENSOR_ONLY: Auto-start continuous mic
                    // PASSIVE_DISPLAY: No mic at all
                    if (profileManager.isContinuousMicMode()) {
                        Log.i(TAG, "?? SENSOR_ONLY: Iniciando micr�fono continuo")
                        startRecording()
                    } else if (profileManager.isPushToTalkMode()) {
                        Log.i(TAG, "?? FULL_INTERACTIVE: Modo PTT activo (esperando bot�n)")
                    } else {
                        Log.i(TAG, "?? PASSIVE_DISPLAY: Micr�fono desactivado")
                    }
                    startTelemetry()
                }
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                playAudio(bytes.toByteArray())
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val msgType = json.optString("type", "")
                    
                    when (msgType) {
                        "TTS_LOCAL" -> {
                            val txt = json.optString("text")
                            mainHandler.post { speak(txt) }
                        }
                        "UPDATE_UI" -> {
                            // FASE 2: ONE FACE Protocol - Core controla d�nde se muestra la cara
                            val showFace = json.optBoolean("show_face", true)
                            mainHandler.post {
                                Log.i(TAG, "?? Recibido UPDATE_UI: show_face=$showFace, coreIp=$connectedCoreIp")
                                // Enviar broadcast a MainActivity para que ajuste UI
                                val uiIntent = Intent(ACTION_UI_UPDATE)
                                uiIntent.setPackage(packageName)
                                uiIntent.putExtra(EXTRA_SHOW_FACE, showFace)
                                // ? CR�TICO: Enviar CONNECTED=true para que effectiveShowFace funcione
                                uiIntent.putExtra(EXTRA_CONNECTED, true)
                                // ? CR�TICO: Tambi�n enviar core_ip para que WebView funcione!
                                if (connectedCoreIp != null) {
                                    uiIntent.putExtra("core_ip", connectedCoreIp)
                                }
                                sendBroadcast(uiIntent)
                            }
                        }
                        "CORE_STATE" -> {
                            // FASE 5: State sync - forward YOLA's state to Soul Eye
                            val state = json.optString("state", "idle")
                            mainHandler.post {
                                Log.i(TAG, "?? CORE_STATE: $state")
                                val stateIntent = Intent(ACTION_UI_UPDATE)
                                stateIntent.setPackage(packageName)
                                stateIntent.putExtra("core_state", state)
                                sendBroadcast(stateIntent)
                            }
                        }
                        "CMD_CAMERA" -> {
                             val action = json.optString("action")
                             Log.i(TAG, "?? CMD_CAMERA: $action")
                             
                             mainHandler.post {
                                 when (action) {
                                     "ON", "START" -> {
                                         cameraManager?.start()
                                         retinaServer?.start()
                                     }
                                     "OFF", "STOP" -> {
                                         cameraManager?.stop()
                                         retinaServer?.stop()
                                     }
                                     "SWITCH" -> {
                                         cameraManager?.switchCamera()
                                     }
                                     "TORCH" -> {
                                         val value = json.optBoolean("value", false)
                                         cameraManager?.setTorch(value)
                                     }
                                     "ZOOM" -> {
                                         val value = json.optDouble("value", 0.0).toFloat()
                                         cameraManager?.setZoom(value)
                                     }
                                 }
                             }
                        }
                        "VOICE_TRANSCRIPT" -> {
                            // FASE 3.8: Voice transcription display on Soul Eye
                            val text = json.optString("text", "")
                            val type = json.optString("type", "user") // "user" or "yola"
                            mainHandler.post {
                                Log.i(TAG, "?? VOICE_TRANSCRIPT ($type): $text")
                                val transcriptIntent = Intent(ACTION_UI_UPDATE)
                                transcriptIntent.setPackage(packageName)
                                transcriptIntent.putExtra("voice_transcript_text", text)
                                transcriptIntent.putExtra("voice_transcript_type", type)
                                sendBroadcast(transcriptIntent)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error parsing WS message: ${e.message}")
                }
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(1000, null)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                handleDisconnection()
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WS Error: " + t.message)
                handleDisconnection()
            }
        })
    }

    private fun handleDisconnection() {
        if (!isConnected && currentState == State.GHOST) return
        mainHandler.post {
            // FASE 6: Detener heartbeat (Thread dedicado)
            stopHeartbeatThread()
            
            isConnected = false
            connectedCoreIp = null  // ? Limpiar IP al desconectar
            webSocket = null
            stopRecording()
            speak("Enlace perdido. Buscando se�al.")
            mainHandler.postDelayed({ enterGhostMode() }, 2000)
        }
    }

    // ========================================================================
    // AUDIO
    // ========================================================================

    private fun startRecording() {
        if (isRecording) return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return

        val bufferSize = AudioRecord.getMinBufferSize(sampleRateMic, AudioFormat.CHANNEL_IN_MONO, encoding) * 2
        
        try {
            audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRateMic, AudioFormat.CHANNEL_IN_MONO, encoding, bufferSize)
            audioRecord?.startRecording()
            isRecording = true

            Thread {
                val data = ByteArray(1024)
                while (isRecording && isConnected) {
                    val read = audioRecord?.read(data, 0, data.size) ?: 0
                    if (read > 0) {
                        webSocket?.send(data.toByteString(0, read))
                    }
                }
            }.start()
        } catch (e: Exception) {
            isRecording = false
        }
    }

    private fun stopRecording() {
        isRecording = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {}
        audioRecord = null
    }


    /**
     * Env�a comandos de entrada remota (Mouse/Teclado) al PC.
     */
    fun sendRemoteInput(payload: JSONObject) {
        if (isConnected && webSocket != null) {
            try {
                val msg = JSONObject()
                msg.put("type", "REMOTE_INPUT")
                msg.put("payload", payload)
                webSocket?.send(msg.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Error enviando remote input: ${e.message}")
            }
        }
    }

    /**
     * Inicia la grabaci�n PTT.
     * Llamado por MainActivity cuando el usuario presiona el bot�n.
     */
    fun startPTTRecording() {
        if (!profileManager.isPushToTalkMode()) {
            Log.w(TAG, "?? PTT no disponible en este perfil")
            return
        }
        
        Log.i(TAG, "?? PTT: Iniciando grabaci�n")
        startRecording()
        
        // Notificar al Core que el usuario est� hablando
        try {
            val json = JSONObject().apply {
                put("type", "PTT_START")
                put("deviceId", deviceId)
                put("timestamp", System.currentTimeMillis())
            }
            webSocket?.send(json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error enviando PTT_START: ${e.message}")
        }
        
        // Broadcast para actualizar UI
        broadcastUIUpdate(null, "?? Escuchando...", "LISTENING", null)
    }

    /**
     * Detiene la grabaci�n PTT.
     * Llamado por MainActivity cuando el usuario suelta el bot�n.
     */
    fun stopPTTRecording() {
        if (!profileManager.isPushToTalkMode()) return
        
        Log.i(TAG, "?? PTT: Deteniendo grabaci�n")
        stopRecording()
        
        // ?? CR�TICO: Enviar se�al de fin de flujo para que el PC procese YA
        try {
            val json = JSONObject().apply {
                put("type", "AUDIO_END") // Se�al expl�cita de fin de turno
                put("deviceId", deviceId)
                put("timestamp", System.currentTimeMillis())
            }
            webSocket?.send(json.toString())
            Log.d(TAG, "PTT Released - Signal Sent")
        } catch (e: Exception) {
            Log.e(TAG, "Error enviando AUDIO_END: ${e.message}")
        }
        
        // Broadcast para actualizar UI
        broadcastUIUpdate(null, "Procesando...", "PROCESSING", null)
    }

    /**
     * Verifica si el modo PTT est� activo
     */
    fun isPTTMode(): Boolean = profileManager.isPushToTalkMode()

    private fun initializeAudioTrack() {
        val bufferSize = AudioTrack.getMinBufferSize(sampleRateSpk, AudioFormat.CHANNEL_OUT_MONO, encoding) * 2
        try {
            audioTrack = AudioTrack(AudioManager.STREAM_MUSIC, sampleRateSpk, AudioFormat.CHANNEL_OUT_MONO, encoding, bufferSize, AudioTrack.MODE_STREAM)
            audioTrack?.play()
        } catch (e: Exception) {}
    }

    private fun playAudio(data: ByteArray) {
        try {
            audioTrack?.write(data, 0, data.size)
            broadcastUIUpdate(null, "?? Hablando...", null, null)
        } catch (e: Exception) {}
    }

    // ========================================================================
    // TELEMETR�A & UI
    // ========================================================================

    private fun startTelemetry() {
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                if (isConnected) {
                    sendTelemetry()
                    mainHandler.postDelayed(this, 10000)
                }
            }
        }, 5000)
    }

    private fun sendTelemetry() {
        // Battery level - use sticky broadcast (works without receiver registration)
        val batteryStatus: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            // API 33+: Use BatteryManager directly (no receiver needed)
            null
        } else {
            registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }
        
        val pct: Float = if (Build.VERSION.SDK_INT >= 21) {
            // Modern way: Use BatteryManager
            val bm = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.toFloat() ?: -1f
        } else {
            // Legacy fallback
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (scale > 0) level * 100 / scale.toFloat() else -1f
        }
        
        val json = JSONObject()
        json.put("type", "TELEMETRY")
        json.put("deviceId", deviceId)
        json.put("battery", pct)
        webSocket?.send(json.toString())
    }

    private fun broadcastUIUpdate(status: String?, action: String?, state: String?, connected: Boolean?, coreIp: String? = null) {
        val intent = Intent(ACTION_UI_UPDATE)
        intent.setPackage(packageName) // CR�TICO: Asegura entrega interna en Android 14
        if (status != null) intent.putExtra(EXTRA_STATUS, status)
        if (action != null) intent.putExtra(EXTRA_ACTION, action)
        if (state != null) intent.putExtra(EXTRA_STATE, state)
        if (connected != null) intent.putExtra(EXTRA_CONNECTED, connected)
        if (coreIp != null) intent.putExtra("core_ip", coreIp)
        
        // Broadcast nativo (sin LocalBroadcastManager)
        sendBroadcast(intent)
    }

    /**
     * Env�a un comando gen�rico al PC.
     */
    fun sendCommand(type: String, payload: JSONObject = JSONObject()) {
        if (!isConnected || webSocket == null) return
        try {
            val json = JSONObject().apply {
                put("type", type)
                put("payload", payload)
                put("deviceId", deviceId)
                put("timestamp", System.currentTimeMillis())
            }
            webSocket?.send(json.toString())
            Log.d(TAG, "?? Comando enviado: $type")
        } catch (e: Exception) {
            Log.e(TAG, "Error enviando comando $type: ${e.message}")
        }
    }

    /**
     * FASE 5: Unlock Core interactions manually
     * Allows keeping the soul in the body but enabling mouse/keyboard on PC
     */
    fun unlockCore() {
        if (!isConnected || webSocket == null) return

        try {
            val json = JSONObject().apply {
                put("type", "UNLOCK_CORE")
                put("deviceId", deviceId)
                put("timestamp", System.currentTimeMillis())
            }
            webSocket?.send(json.toString())
            Log.i(TAG, "?? Sent UNLOCK_CORE signal to PC")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send UNLOCK_CORE: ${e.message}")
        }
    }

}
