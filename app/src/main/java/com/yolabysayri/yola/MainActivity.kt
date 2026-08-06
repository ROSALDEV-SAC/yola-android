package com.yolabysayri.yola

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.yolabysayri.yola.ui.YolaFace
import com.yolabysayri.yola.ui.SoulState
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale

/**
 * ╔═══════════════════════════════════════════════════════════════════════════╗
 * ║                      MAIN ACTIVITY - LEGACY MASTER                        ║
 * ║                         COMPATIBLE ANDROID 5.0                            ║
 * ╠═══════════════════════════════════════════════════════════════════════════╣
 * ║  UI adaptada para correr sin librerías deprecadas (LocalBroadcastManager).║
 * ║  Usa registerReceiver estándar del contexto.                              ║
 * ╚═══════════════════════════════════════════════════════════════════════════╝
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "YOLA_MAIN"
    }

    // Estados
    private var statusText = mutableStateOf("👻 MODO GHOST")
    private var actionText = mutableStateOf("Iniciando sistema...")
    private var currentState = mutableStateOf("GHOST")
    private var isConnected = mutableStateOf(false)
    private var showFace = mutableStateOf(true) // FASE 2: ONE FACE Protocol
    private var soulState = mutableStateOf(SoulState.PRESENT) // Soul transfer state
    private var coreIp = mutableStateOf<String?>(null)
    private var coreState = mutableStateOf("idle") // YOLA's current state for Soul Eye
    
    // Referencia al WebView para llamar JavaScript
    private var webViewRef: android.webkit.WebView? = null

    // FASE 4: Profile Manager
    private lateinit var profileManager: BodyProfileManager
    private var showProfileSelector = mutableStateOf(false)
    private var isPTTMode = mutableStateOf(false)
    private var isStreamActive = mutableStateOf(false) // MODO STREAM overlay
    private var currentZoom = mutableStateOf(1f)
    private var virtualCursorPos = mutableStateOf(Offset(0.5f, 0.5f)) // 0.0 to 1.0

    // Permission launcher (modern way, but compatible via AndroidX)
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        startYolaService()
    }

    // Track previous showFace state for transitions
    private var previousShowFace = true

    // Receiver nativo
    private val uiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let { i ->
                i.getStringExtra(BackgroundService.EXTRA_STATUS)?.let { statusText.value = it }
                i.getStringExtra(BackgroundService.EXTRA_ACTION)?.let { actionText.value = it }
                i.getStringExtra(BackgroundService.EXTRA_STATE)?.let { currentState.value = it }
                if (i.hasExtra(BackgroundService.EXTRA_CONNECTED)) {
                    isConnected.value = i.getBooleanExtra(BackgroundService.EXTRA_CONNECTED, false)
                }
                if (i.hasExtra("core_ip")) {
                    coreIp.value = i.getStringExtra("core_ip")
                }
                // FASE 2: ONE FACE Protocol - El Core controla si mostramos cara o ghost
                if (i.hasExtra(BackgroundService.EXTRA_SHOW_FACE)) {
                    val newShowFace = i.getBooleanExtra(BackgroundService.EXTRA_SHOW_FACE, true)
                    
                    // Detect soul transitions for animation effects
                    if (newShowFace && !previousShowFace) {
                        // Soul is ARRIVING to this device
                        soulState.value = SoulState.ARRIVING
                        Log.i(TAG, "✨ Soul ARRIVING - triggering burst effect")
                        // Reset to PRESENT after animation (handled in composable)
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            soulState.value = SoulState.PRESENT
                        }, 800)
                    } else if (!newShowFace && previousShowFace) {
                        // Soul is DEPARTING from this device
                        soulState.value = SoulState.DEPARTING
                        Log.i(TAG, "👻 Soul DEPARTING - triggering fade effect")
                    } else if (newShowFace) {
                        soulState.value = SoulState.PRESENT
                    }
                    
                    previousShowFace = newShowFace
                    showFace.value = newShowFace
                    Log.i(TAG, "🎭 Face control: showFace=$newShowFace, soulState=${soulState.value}")
                }
                // FASE 5: State sync - Update Soul Eye with YOLA's state
                if (i.hasExtra("core_state")) {
                    val newState = i.getStringExtra("core_state") ?: "idle"
                    coreState.value = newState
                    Log.i(TAG, "🎭 Core state: $newState")
                    
                    // Update WebView Soul Eye
                    webViewRef?.evaluateJavascript(
                        "if(window.yolaSoul && window.yolaSoul.setState) { window.yolaSoul.setState('$newState'); }",
                        null
                    )
                }
                // FASE 3.8: Voice transcription display on Soul Eye
                if (i.hasExtra("voice_transcript_text")) {
                    val text = i.getStringExtra("voice_transcript_text") ?: ""
                    val type = i.getStringExtra("voice_transcript_type") ?: "user"
                    Log.i(TAG, "🎤 Voice transcript ($type): $text")
                    
                    // Escape quotes for JavaScript
                    val escapedText = text.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"")
                    
                    // Update WebView Soul Eye
                    webViewRef?.evaluateJavascript(
                        "if(window.yolaSoul && window.yolaSoul.showTranscript) { window.yolaSoul.showTranscript('$escapedText', '$type'); }",
                        null
                    )
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "Activity Created - Legacy Safe Mode")

        setupFullscreen()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* Blocked */ }
        })

        // FASE 4: Profile Manager - Default to BODY on first run
        profileManager = BodyProfileManager(this)
        if (profileManager.isFirstRun() || profileManager.getCurrentProfile() == null) {
            profileManager.setProfile(BodyProfile.FULL_INTERACTIVE)
            profileManager.markFirstRunCompleted()
            showProfileSelector.value = false
        }
        
        isPTTMode.value = profileManager.isPushToTalkMode()

        setContent {
            // Profile Selector Dialog
            if (showProfileSelector.value) {
                ProfileSelectorDialog(
                    profileManager = profileManager,
                    onProfileSelected = { profile ->
                        profileManager.setProfile(profile)
                        profileManager.markFirstRunCompleted()
                        isPTTMode.value = profileManager.isPushToTalkMode()
                        showProfileSelector.value = false
                        Log.i(TAG, "✅ Perfil seleccionado: ${profile.displayName}")
                    }
                )
            }
            
            val context = androidx.compose.runtime.remember { this }
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

            // ============================================================
            // UNIFIED DESIGN: The Soul Eye is always the base
            // ============================================================
            Box(modifier = Modifier.fillMaxSize()) {
                YolaBodyScreen(
                    status = statusText.value,
                    action = actionText.value,
                    state = currentState.value,
                    connected = isConnected.value,
                    showFace = showFace.value,
                    soulState = soulState.value,
                    coreIp = coreIp.value,
                    showPTTButton = isPTTMode.value,
                    isInteractive = true,
                    isStreamActive = isStreamActive.value,
                    onToggleStream = { isStreamActive.value = !isStreamActive.value },
                    onPTTPress = { 
                        val mode = audioManager.mode
                        if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION) {
                            Toast.makeText(context, "⚠️ Micrófono ocupado por otra app (Zoom/Llamada). Yola no podrá escucharte.", Toast.LENGTH_LONG).show()
                        } else {
                            BackgroundService.instance?.startPTTRecording()
                        }
                    },
                    onPTTRelease = { BackgroundService.instance?.stopPTTRecording() },
                    onUnlockCore = { BackgroundService.instance?.unlockCore() },
                    onWebViewCreated = { webView -> webViewRef = webView }
                )

                // Removed Remote Control Banner
            }
        }

        checkPermissions()
    }

    override fun onResume() {
        super.onResume()
        Log.i(TAG, "🚀 CHECK_VERSION_FIXED: El nuevo código está corriendo!")
        
        // Registro de broadcast usando ContextCompat (maneja automáticamente flags de seguridad)
        val filter = IntentFilter(BackgroundService.ACTION_UI_UPDATE)
        try {
            // ContextCompat.registerReceiver maneja automáticamente RECEIVER_NOT_EXPORTED en API 33+
            ContextCompat.registerReceiver(
                this,
                uiReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            Log.i(TAG, "✅ Receiver registrado exitosamente")
        } catch (e: Exception) {
            Log.e(TAG, "Error registrando receiver: ${e.message}", e)
            // Fallback: intentar registro directo como último recurso
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    registerReceiver(uiReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    registerReceiver(uiReceiver, filter)
                }
            } catch (e2: Exception) {
                Log.e(TAG, "Fallback receiver registration also failed: ${e2.message}")
            }
        }
        
        // FASE 5: Report to Core that we are in foreground
        BackgroundService.instance?.reportActivityState(true)
        Log.i(TAG, "📱 Activity RESUMED - reported foreground=true")
    }

    // Se llama ANTES de que Android tome el snapshot para la miniatura
    // Es nuestra mejor oportunidad de cerrar el ojo
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        Log.i(TAG, "👁️ User leaving - closing eye for thumbnail")
        
        // Cerrar el ojo inmediatamente
        webViewRef?.evaluateJavascript(
            "if(window.yolaSoul) { window.yolaSoul.closeEyeInstant(); }",
            null
        )
        
        // Dar un pequeño tiempo para que el WebView procese
        Thread.sleep(50)
    }

    override fun onPause() {
        super.onPause()
        
        // Reforzar cierre del ojo (por si onUserLeaveHint no se llamó)
        webViewRef?.evaluateJavascript(
            "if(window.yolaSoul) { window.yolaSoul.closeEyeInstant(); }",
            null
        )
        Log.i(TAG, "👁️ onPause - ensuring eye is closed")
        
        // FASE 5: Report to Core that we are in background
        BackgroundService.instance?.reportActivityState(false)
        Log.i(TAG, "📴 Activity PAUSED - reported foreground=false")
        
        try {
            unregisterReceiver(uiReceiver)
        } catch (e: Exception) { }
    }

    private fun setupFullscreen() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        )
    }

    private fun checkPermissions() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_MULTICAST_STATE,
            Manifest.permission.WAKE_LOCK,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE
        )
        
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add("android.permission.POST_NOTIFICATIONS")
        }
        // FGS mic permission for Android 14
        if (Build.VERSION.SDK_INT >= 34) {
             perms.add("android.permission.FOREGROUND_SERVICE_MICROPHONE")
        }

        val needed = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isEmpty()) {
            startYolaService()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun startYolaService() {
        // Avoid restarting if service is already running
        if (BackgroundService.instance != null) {
            Log.i("YOLA_BODY", "✅ Service already running, skipping start")
            return
        }
        // Double check audio permission before starting FGS to avoid crash
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            // FASE 6: Request battery optimization exemption for persistent WebSocket
            requestBatteryOptimizationExemption()
            
            val intent = Intent(this, BackgroundService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } catch (e: Exception) {
                Log.e("YOLA_BODY", "❌ Error starting service: ${e.message}")
            }
        } else {
            Log.w("YOLA_BODY", "❌ Cannot start service: Record Audio permission missing")
        }
    }
    
    /**
     * FASE 6: Request exemption from battery optimization (Doze mode)
     * This is CRITICAL for maintaining WebSocket connection when screen is off.
     * Without this, Android will kill network connections after ~30 seconds.
     */
    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val packageName = packageName
            
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                Log.i(TAG, "🔋 Requesting battery optimization exemption...")
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ Could not request battery exemption: ${e.message}")
                    // Fallback: open battery settings manually
                    try {
                        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        startActivity(intent)
                    } catch (e2: Exception) {
                        Log.e(TAG, "❌ Cannot open battery settings: ${e2.message}")
                    }
                }
            } else {
                Log.i(TAG, "✅ Already exempt from battery optimization")
            }
        }
    }
}

// ============================================================================
// UI (Compose UI es compatible hacia atrás gracias a AndroidX)
// ============================================================================

@Composable
fun YolaBodyScreen(
    status: String, 
    action: String, 
    state: String, 
    connected: Boolean, 
    showFace: Boolean = true,
    soulState: SoulState = SoulState.PRESENT,
    coreIp: String? = null,
    showPTTButton: Boolean = false,
    isInteractive: Boolean = false,
    isStreamActive: Boolean = false,
    onToggleStream: () -> Unit = {},
    onPTTPress: () -> Unit = {},
    onPTTRelease: () -> Unit = {},
    onUnlockCore: () -> Unit = {},
    onWebViewCreated: ((android.webkit.WebView) -> Unit)? = null
) {
    // FASE 2: ONE FACE Protocol
    // Si connected=true pero showFace=false, el Core está mostrando la cara en PC
    // En ese caso, mostramos el Ghost orb aunque estemos conectados
    val effectiveShowFace = connected && showFace
    
    val bgColor = when {
        effectiveShowFace || state == "CONNECTED" && showFace -> Color(0xFF000000) // Negro puro para Webview blend
        connected && !showFace -> Color(0xFF1A0A1F) // Púrpura oscuro para "Ghost conectado"
        state == "CONNECTING" -> Color(0xFF1F1A0A)
        state == "RECONNECTING" -> Color(0xFF0A1A1F)
        else -> Color(0xFF0F0A1F)
    }

    val accentColor = when {
        effectiveShowFace -> Color(0xFF00FF66)
        connected && !showFace -> Color(0xFF9955FF) // Púrpura para indicar "cara en PC"
        state == "CONNECTING" -> Color(0xFFFFAA00)
        state == "RECONNECTING" -> Color(0xFF00CCFF)
        else -> Color(0xFFAA55FF)
    }

    Box(
        modifier = Modifier.fillMaxSize().background(bgColor),
        contentAlignment = Alignment.Center
    ) {
        // LAYER 1: VISTA PREVIA NATIVA (Fondo o Fallback)
        // Solo mostrar cuando NO tenemos WebView (no hay coreIp)
        if (coreIp == null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(32.dp)
            ) {
                if (connected && !showFace) {
                    // Conectado pero cara en PC → Ghost orb especial
                    GhostConnectedOrb(color = accentColor)
                } else {
                    PulsingOrb(color = accentColor, fast = state == "CONNECTING" || state == "RECONNECTING")
                }
                Spacer(modifier = Modifier.height(48.dp))
                
                // Texto de estado
                val displayStatus = if (connected && !showFace) "🔗 ENLAZADO" else status
                val displayAction = if (connected && !showFace) "Cara activa en PC" else action
                
                Text(text = displayStatus, color = accentColor, fontSize = 32.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = displayAction, color = Color.White.copy(alpha = 0.7f), fontSize = 16.sp, textAlign = TextAlign.Center)
                
                // 🔧 DEBUG: Mostrar valores de variables en pantalla
                Spacer(modifier = Modifier.height(32.dp))
                Text(text = "--- DEBUG ---", color = Color.Yellow, fontSize = 12.sp)
                Text(text = "connected=$connected", color = Color.Cyan, fontSize = 10.sp)
                Text(text = "showFace=$showFace", color = Color.Cyan, fontSize = 10.sp)
                Text(text = "effectiveShowFace=$effectiveShowFace", color = Color.Cyan, fontSize = 10.sp)
                Text(text = "coreIp=$coreIp", color = Color.Cyan, fontSize = 10.sp)
                Text(text = "state=$state", color = Color.Cyan, fontSize = 10.sp)
            }
        }

        // LAYER 2: HIGH FIDELITY NEURAL FACE (WEBVIEW)
        // SIMPLIFICADO: Mostrar WebView siempre que tengamos IP, sin importar otros estados
        // Esto asegura que el WebView no desaparezca por cambios de estado
        if (coreIp != null) {
            val targetUrl = "http://$coreIp:3333/mobile-face"
            var webViewError by remember { mutableStateOf<String?>(null) }
            
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { context ->
                    android.webkit.WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.allowFileAccess = true
                        
                        // CRÍTICO: Desactivar caché para desarrollo
                        settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                        
                        // Permitir contenido mixto (HTTP local)
                        if (Build.VERSION.SDK_INT >= 21) {
                            settings.mixedContentMode = 0 // MIXED_CONTENT_ALWAYS_ALLOW
                        }

                        // Fondo negro para blend con la página
                        setBackgroundColor(0xFF0a0a1a.toInt())
                        
                        // Limpiar caché al iniciar
                        clearCache(true)
                        clearHistory()

                        // DEBUG: Puente de logs JS -> Android Logcat
                        android.webkit.WebView.setWebContentsDebuggingEnabled(true)
                        
                        webChromeClient = object : android.webkit.WebChromeClient() {
                            override fun onConsoleMessage(message: android.webkit.ConsoleMessage?): Boolean {
                                val logMsg = "JS [${message?.messageLevel()}]: ${message?.message()} (${message?.sourceId()}:${message?.lineNumber()})"
                                android.util.Log.d("YOLA_WEBVIEW", logMsg)
                                
                                // Mostrar errores graves en pantalla
                                if (message?.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) {
                                    webViewError = "JS: ${message?.message()?.take(40)}..."
                                }
                                return true
                            }
                        }

                        webViewClient = object : android.webkit.WebViewClient() {
                            override fun onPageStarted(view: android.webkit.WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                android.util.Log.i("YOLA_WEBVIEW", "Page Started: $url")
                                webViewError = "Loading..."
                            }

                            override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                android.util.Log.i("YOLA_WEBVIEW", "Page Finished: $url")
                                // Clean error if successful loaded
                                if (webViewError == "Loading...") webViewError = null
                            }

                            override fun onReceivedError(view: android.webkit.WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) {
                                super.onReceivedError(view, request, error)
                                android.util.Log.e("YOLA_WEBVIEW", "Load Error: ${error?.description}")
                                webViewError = "ERR: ${error?.description}"
                            }
                            
                            override fun onReceivedHttpError(view: android.webkit.WebView?, request: android.webkit.WebResourceRequest?, errorResponse: android.webkit.WebResourceResponse?) {
                                super.onReceivedHttpError(view, request, errorResponse)
                                android.util.Log.e("YOLA_WEBVIEW", "HTTP Error: ${errorResponse?.statusCode}")
                                webViewError = "HTTP ${errorResponse?.statusCode}"
                            }
                        }
                        
                        // Cache-busting: añadir timestamp a URL para forzar recarga
                        val cacheBuster = System.currentTimeMillis()
                        val freshUrl = "http://$coreIp:3333/mobile-face?v=$cacheBuster"
                        android.util.Log.i("YOLA_WEBVIEW", "Loading fresh URL: $freshUrl")
                        loadUrl(freshUrl)
                    }.also { webView ->
                        // Guardar referencia para poder llamar JavaScript después
                        onWebViewCreated?.invoke(webView)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            
            // HUD Overlay Minimalista
            Box(modifier = Modifier.fillMaxSize().padding(bottom = 50.dp), contentAlignment = Alignment.BottomCenter) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "NEURAL LINK ESTABLISHED", color = Color(0xFF00CCFF), fontSize = 10.sp, letterSpacing = 3.sp, modifier = Modifier.alpha(0.5f))
                    // DIAGNOSTICS
                    Text(text = "LINK: $targetUrl", color = Color.Gray, fontSize = 9.sp, modifier = Modifier.padding(top=4.dp))
                    if (webViewError != null) {
                        Text(text = webViewError!!, color = Color.Red, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.background(Color.Black).padding(4.dp))
                    }
                    
                    // BOTÓN DESBLOQUEAR CORE
                    if (effectiveShowFace) {
                        Spacer(modifier = Modifier.height(16.dp))
                        TextButton(
                            onClick = onUnlockCore,
                            colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFF00CCFF).copy(alpha = 0.6f))
                        ) {
                            Text(text = "🔓 DESBLOQUEAR CORE", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
        
        var showSourceSelector by remember { mutableStateOf(false) }
        var virtualCursor by remember { mutableStateOf(Offset(0.5f, 0.5f)) }

        // LAYER 3: INTERACTIVE CONTROLS (Top Right)
        if (connected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 100.dp, end = 20.dp),
                contentAlignment = Alignment.TopEnd
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    InteractiveButton(
                        text = if (isStreamActive) "OCULTAR" else "STREAM",
                        icon = "📺",
                        active = isStreamActive,
                        onClick = onToggleStream
                    )
                    
                    if (isStreamActive) {
                        Spacer(modifier = Modifier.height(12.dp))
                        InteractiveButton(
                            text = "PANTALLAS",
                            icon = "🖥️",
                            onClick = { showSourceSelector = true }
                        )
                    }
                }
            }
        }

        // LAYER 3.5: Source Selector Dialog
        if (showSourceSelector && coreIp != null) {
            SourceSelectorDialog(
                coreIp = coreIp,
                onDismiss = { showSourceSelector = false },
                onSourceSelected = { sourceId ->
                    // Call REST endpoint to switch source
                    kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                        try {
                            val url = java.net.URL("http://$coreIp:7779/api/source/select")
                            val conn = url.openConnection() as java.net.HttpURLConnection
                            conn.requestMethod = "POST"
                            conn.setRequestProperty("Content-Type", "application/json")
                            conn.doOutput = true
                            conn.outputStream.write("{\"sourceId\":\"$sourceId\"}".toByteArray())
                            conn.responseCode // trigger request
                            conn.disconnect()
                            Log.i("YOLA_MAIN", "Source selected: $sourceId")
                        } catch (e: Exception) {
                            Log.e("YOLA_MAIN", "Error selecting source: ${e.message}")
                        }
                    }
                    showSourceSelector = false
                }
            )
        }

        // LAYER 4: ADVANCED STREAM OVERLAY
        if (isStreamActive && coreIp != null && connected) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                AdvancedStreamDisplay(coreIp = coreIp, cursorPosition = virtualCursor)
                
                // JOYSTICK LAYER (Bottom Left)
                Box(
                    modifier = Modifier.fillMaxSize().padding(bottom = 120.dp, start = 40.dp),
                    contentAlignment = Alignment.BottomStart
                ) {
                    VirtualJoystick(
                        onMove = { dx, dy ->
                            // Update virtual cursor (clamped 0..1)
                            val speed = 0.02f
                            virtualCursor = Offset(
                                (virtualCursor.x + dx * speed).coerceIn(0f, 1f),
                                (virtualCursor.y + dy * speed).coerceIn(0f, 1f)
                            )
                            
                            val payload = JSONObject().apply {
                                put("type", "mousestep")
                                put("dx", (dx * 20).toInt())
                                put("dy", (dy * 20).toInt())
                            }
                            BackgroundService.instance?.sendRemoteInput(payload)
                        }
                    )
                }
            }
        }

        // LAYER 5: PTT BUTTON (Glassmorphism)
        if (showPTTButton && connected) {
            Box(
                modifier = Modifier.fillMaxSize().padding(bottom = 80.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                PTTButton(
                    onPress = onPTTPress,
                    onRelease = onPTTRelease
                )
            }
        }
    }
}

@Composable
fun GhostConnectedOrb(color: Color) {
    // Orbe estático pero con indicador de conexión
    Box(modifier = Modifier.size(150.dp).background(color.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
        Box(modifier = Modifier.size(80.dp).background(color.copy(alpha = 0.3f), CircleShape), contentAlignment = Alignment.Center) {
            Text(text = "🔗", fontSize = 32.sp)
        }
    }
}


@Composable
fun ConnectedIndicator(color: Color) {
    Box(modifier = Modifier.size(150.dp).background(color.copy(alpha = 0.2f), CircleShape), contentAlignment = Alignment.Center) {
        Box(modifier = Modifier.size(100.dp).background(color.copy(alpha = 0.4f), CircleShape), contentAlignment = Alignment.Center) {
            Box(modifier = Modifier.size(60.dp).background(color, CircleShape))
        }
    }
}

@Composable
fun PulsingOrb(color: Color, fast: Boolean) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val duration = if (fast) 500 else 2000
    val scale by transition.animateFloat(0.8f, 1.2f, infiniteRepeatable(tween(duration, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "scale")
    val alpha by transition.animateFloat(0.4f, 1f, infiniteRepeatable(tween(duration, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "alpha")

    Box(modifier = Modifier.size(150.dp).scale(scale).alpha(alpha).background(color.copy(alpha = 0.3f), CircleShape), contentAlignment = Alignment.Center) {
        Box(modifier = Modifier.size(60.dp).background(color, CircleShape))
    }
}

// ============================================================================
// FASE 4: PROFILE SELECTOR DIALOG
// ============================================================================

@Composable
fun ProfileSelectorDialog(
    profileManager: BodyProfileManager,
    onProfileSelected: (BodyProfile) -> Unit
) {
    val profiles = profileManager.getAvailableProfiles()
    val recommended = profileManager.getRecommendedProfile()
    
    AlertDialog(
        onDismissRequest = { /* No dismiss */ },
        title = {
            Text(
                text = "📱 Configuración Inicial",
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
        },
        text = {
            Column {
                Text(
                    text = "¿Cómo quieres usar este dispositivo?",
                    color = Color.Gray,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                
                profiles.forEach { profile ->
                    val isRecommended = profile == recommended
                    Button(
                        onClick = { onProfileSelected(profile) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isRecommended) Color(0xFF00AA66) else Color(0xFF333355)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(8.dp),
                            horizontalAlignment = Alignment.Start
                        ) {
                            Row {
                                Text(
                                    text = profile.displayName,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                if (isRecommended) {
                                    Text(
                                        text = " ⭐ Recomendado",
                                        color = Color.Yellow,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                            Text(
                                text = profile.description,
                                color = Color.LightGray,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { /* Buttons are inline */ }
    )
}

// ============================================================================
// FASE 4: PTT BUTTON (GLASSMORPHISM)
// ============================================================================

@Composable
fun PTTButton(
    onPress: () -> Unit,
    onRelease: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val isMicBusy = audioManager.mode == AudioManager.MODE_IN_CALL || audioManager.mode == AudioManager.MODE_IN_COMMUNICATION
    
    var isPressed by remember { mutableStateOf(false) }
    
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessHigh),
        label = "ptt_scale"
    )
    
    val defaultColor = if (isMicBusy) Color(0xFFFFAA00) else Color(0xFF00CCFF)
    val glowColor = if (isPressed) Color(0xFF00FF66) else defaultColor
    val glowAlpha by animateFloatAsState(
        targetValue = if (isPressed) 0.8f else 0.4f,
        label = "ptt_glow"
    )
    
    Box(
        modifier = Modifier
            .scale(scale)
            .size(100.dp)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val isDown = event.changes.any { it.pressed }
                        
                        if (isDown && !isPressed) {
                            isPressed = true
                            onPress()
                        } else if (!isDown && isPressed) {
                            isPressed = false
                            onRelease()
                        }
                    }
                }
            }
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(
                        glowColor.copy(alpha = glowAlpha),
                        glowColor.copy(alpha = 0.1f),
                        Color.Transparent
                    )
                ),
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(
                    color = Color.White.copy(alpha = 0.1f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (isPressed) "🔴" else "🎙️",
                fontSize = 32.sp
            )
        }
    }
}

// ============================================================================
// DUAL-MODE: CORE REMOTE SCREEN (WebView con todo el control remoto)
// ============================================================================

@Composable
fun InteractiveButton(
    text: String,
    icon: String,
    active: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (active) Color(0xFF00FF66).copy(alpha = 0.2f)
                else Color.White.copy(alpha = 0.1f)
            )
            .border(
                1.dp,
                if (active) Color(0xFF00FF66).copy(alpha = 0.5f)
                else Color.White.copy(alpha = 0.2f),
                RoundedCornerShape(12.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = icon, fontSize = 16.sp)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = text,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}



@Composable
fun AdvancedStreamDisplay(coreIp: String, cursorPosition: Offset) {
    var scale by remember { mutableStateOf(1f) }
    val state = rememberTransformableState { zoomChange, _, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 5f)
    }

    // Native bitmap state - no WebView!
    var frameBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var isPolling by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Coroutine-based frame polling
    LaunchedEffect(coreIp) {
        Log.i("YOLA_STREAM", "Starting snapshot polling to $coreIp")
        isPolling = true
        while (isPolling) {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    val url = java.net.URL("http://$coreIp:7779/stream/snapshot?t=${System.currentTimeMillis()}")
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 3000
                    conn.readTimeout = 3000
                    conn.requestMethod = "GET"
                    try {
                        val responseCode = conn.responseCode
                        if (responseCode == 200) {
                            val inputStream = conn.inputStream
                            val bmp = android.graphics.BitmapFactory.decodeStream(inputStream)
                            inputStream.close()
                            bmp
                        } else {
                            Log.w("YOLA_STREAM", "Snapshot HTTP $responseCode")
                            null
                        }
                    } finally {
                        conn.disconnect()
                    }
                }
                if (bitmap != null) {
                    frameBitmap = bitmap
                    errorMsg = null
                } else if (errorMsg == null) {
                    errorMsg = "Esperando frames..."
                }
                kotlinx.coroutines.delay(150) // ~7 FPS
            } catch (e: Exception) {
                Log.e("YOLA_STREAM", "Snapshot error: ${e.message}")
                errorMsg = e.message
                kotlinx.coroutines.delay(1000) // Retry slower on error
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { isPolling = false }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth(0.95f)
            .aspectRatio(1.77f)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black)
            .border(2.dp, Color(0xFF00CCFF).copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            .transformable(state = state),
        contentAlignment = Alignment.Center
    ) {
        if (frameBitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = frameBitmap!!.asImageBitmap(),
                contentDescription = "PC Screen",
                modifier = Modifier
                    .fillMaxSize()
                    .scale(scale),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit
            )
        } else {
            // Loading / Error state
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "📺",
                    fontSize = 32.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = errorMsg ?: "Conectando...",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
            }
        }

        // Tap = Click
        Box(
            modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures {
                    val payload = JSONObject().apply {
                        put("type", "click"); put("button", "left")
                    }
                    BackgroundService.instance?.sendRemoteInput(payload)
                }
            }
        )
    }
}

@Composable
fun VirtualJoystick(onMove: (Float, Float) -> Unit) {
    var joystickOffset by remember { mutableStateOf(Offset.Zero) }
    val maxRadius = 100f

    Box(
        modifier = Modifier
            .size(150.dp)
            .background(Color.Black.copy(alpha = 0.3f), CircleShape)
            .border(2.dp, Color(0xFF00CCFF).copy(alpha = 0.2f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        // Handle
        Box(
            modifier = Modifier
                .offset { IntOffset(joystickOffset.x.toInt(), joystickOffset.y.toInt()) }
                .size(60.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFF00CCFF), Color(0xFF0066FF)),
                        radius = 80f
                    ),
                    CircleShape
                )
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = { joystickOffset = Offset.Zero },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val newOffset = joystickOffset + dragAmount
                            val distance = newOffset.getDistance()
                            
                            joystickOffset = if (distance <= maxRadius) {
                                newOffset
                            } else {
                                newOffset * (maxRadius / distance)
                            }
                            
                            // Normalizar y enviar
                            onMove(joystickOffset.x / maxRadius, joystickOffset.y / maxRadius)
                        }
                    )
                }
        )
    }
}

@Composable
fun SourceSelectorDialog(
    coreIp: String,
    onDismiss: () -> Unit,
    onSourceSelected: (String) -> Unit
) {
    var sources by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(coreIp) {
        withContext(Dispatchers.IO) {
            try {
                val url = java.net.URL("http://$coreIp:7779/api/sources")
                val jsonStr = url.readText()
                val jsonRes = JSONObject(jsonStr)
                val jsonArray = jsonRes.getJSONArray("sources")
                val list = mutableListOf<Pair<String, String>>()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    list.add(obj.getString("id") to obj.getString("name"))
                }
                sources = list
                isLoading = false
            } catch (e: Exception) {
                Log.e("YOLA_MAIN", "Error fetching sources: ${e.message}")
                isLoading = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Seleccionar Pantalla", fontWeight = FontWeight.Bold) },
        text = {
            if (isLoading) {
                Text("Cargando pantallas...")
            } else if (sources.isEmpty()) {
                Text("No se encontraron pantallas disponibles.")
            } else {
                Box(modifier = Modifier.heightIn(max = 300.dp)) {
                    LazyColumn {
                        items(sources) { source ->
                            TextButton(
                                onClick = { onSourceSelected(source.first) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = source.second,
                                    textAlign = TextAlign.Left,
                                    modifier = Modifier.fillMaxWidth(),
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = Color.Gray.copy(alpha = 0.5f))) { 
                Text("Cancelar", color = Color.White) 
            }
        }
    )
}
