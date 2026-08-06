package com.yolabysayri.yola.discovery

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ╔═══════════════════════════════════════════════════════════════════════════╗
 * ║                    DISCOVERY CLIENT - GOLD MASTER                         ║
 * ║                         UNIVERSAL BODY v1.0                               ║
 * ╠═══════════════════════════════════════════════════════════════════════════╣
 * ║                                                                           ║
 * ║  "El Sabueso" - Escucha UDP buscando el Beacon del Core                 ║
 * ║                                                                           ║
 * ║  PROTOCOLO:                                                               ║
 * ║  - Puerto UDP: 41335 (ESTRICTO)                                          ║
 * ║  - Beacon: {"type":"YOLA_CORE_BEACON","ip":"...","port":7779}          ║
 * ║  - Puerto WS: Extraído del JSON, fallback 7779                          ║
 * ║                                                                           ║
 * ║  CARACTERÍSTICAS:                                                         ║
 * ║  - Completamente desacoplado del BackgroundService                       ║
 * ║  - MulticastLock para WiFi                                               ║
 * ║  - Thread dedicado con nombre identificable                              ║
 * ║  - Callbacks en MainThread vía Handler                                   ║
 * ║                                                                           ║
 * ║  COMPATIBILIDAD: Android 5.0+ (API 21)                                   ║
 * ║                                                                           ║
 * ╚═══════════════════════════════════════════════════════════════════════════╝
 */
class DiscoveryClient(
    private val context: Context,
    private val onCoreDiscovered: (ip: String, port: Int) -> Unit,
    private val onStatusChange: (status: String) -> Unit
) {
    companion object {
        private const val TAG = "YOLA_DISCOVERY"
        private const val UDP_PORT = 41335           // Puerto UDP ESTRICTO
        private const val DEFAULT_WS_PORT = 7779    // Puerto WS por defecto
    }

    private val isRunning = AtomicBoolean(false)
    private var socket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var discoveryThread: Thread? = null
    
    // Handler para ejecutar callbacks en MainThread
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Inicia la escucha UDP en segundo plano.
     * Safe to call multiple times - only one instance runs.
     */
    fun start() {
        if (isRunning.getAndSet(true)) {
            Log.d(TAG, "Discovery ya corriendo, ignorando start()")
            return
        }

        Log.i(TAG, "═══════════════════════════════════════")
        Log.i(TAG, "   🔍 SABUESO INICIADO")
        Log.i(TAG, "   Puerto UDP: $UDP_PORT")
        Log.i(TAG, "═══════════════════════════════════════")

        // MulticastLock para recibir broadcasts en WiFi
        acquireMulticastLock()

        discoveryThread = Thread({
            runDiscoveryLoop()
        }, "YOLA-Discovery").apply {
            isDaemon = true
            start()
        }

        postStatus("Escaneando red...")
    }

    /**
     * Detiene la escucha UDP.
     */
    fun stop() {
        if (!isRunning.getAndSet(false)) return

        Log.i(TAG, "⏹️ Sabueso detenido")

        try {
            socket?.close()
        } catch (e: Exception) { /* Ignorar */ }
        socket = null

        releaseMulticastLock()
        discoveryThread?.interrupt()
        discoveryThread = null
    }

    fun isActive(): Boolean = isRunning.get()

    // ========================================================================
    // IMPLEMENTACIÓN PRIVADA
    // ========================================================================

    private fun runDiscoveryLoop() {
        try {
            socket = DatagramSocket(UDP_PORT).apply {
                broadcast = true
                reuseAddress = true
                soTimeout = 0 // Sin timeout, escucha infinita
            }

            Log.d(TAG, "🔊 Socket UDP activo en puerto $UDP_PORT")
            postStatus("Buscando Core...")

            val buffer = ByteArray(2048)

            while (isRunning.get()) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket?.receive(packet) // Bloquea hasta recibir

                    val message = String(packet.data, 0, packet.length).trim()
                    val sourceIp = packet.address?.hostAddress ?: "unknown"

                    Log.d(TAG, "📡 Beacon de $sourceIp: $message")
                    processBeacon(message, sourceIp)

                } catch (e: java.net.SocketException) {
                    if (isRunning.get()) {
                        Log.w(TAG, "SocketException: ${e.message}")
                        Thread.sleep(1000)
                    }
                } catch (e: Exception) {
                    if (isRunning.get()) {
                        Log.e(TAG, "Error UDP: ${e.message}")
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error fatal UDP: ${e.message}")
            postStatus("Error de red")
        } finally {
            isRunning.set(false)
            try { socket?.close() } catch (e: Exception) { }
            socket = null
            Log.d(TAG, "Discovery loop terminado")
        }
    }

    private fun processBeacon(message: String, fallbackIp: String) {
        try {
            val json = JSONObject(message)
            val type = json.optString("type", "")

            // Aceptar ambos tipos para compatibilidad
            if (type == "YOLA_CORE_BEACON" || type == "YOLA_BEACON") {
                // EXTRAER IP del JSON, fallback a IP del paquete
                val ip = json.optString("ip", "").ifEmpty { fallbackIp }
                
                // EXTRAER PUERTO del JSON, fallback a default
                val port = json.optInt("port", DEFAULT_WS_PORT)

                Log.i(TAG, "════════════════════════════════════")
                Log.i(TAG, "   🎯 ¡CORE ENCONTRADO!")
                Log.i(TAG, "   IP: $ip")
                Log.i(TAG, "   Puerto: $port")
                Log.i(TAG, "════════════════════════════════════")

                postStatus("Core detectado: $ip")
                
                // Notificar en MainThread
                mainHandler.post {
                    onCoreDiscovered(ip, port)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Beacon inválido: $message")
        }
    }

    private fun postStatus(status: String) {
        mainHandler.post {
            onStatusChange(status)
        }
    }

    private fun acquireMulticastLock() {
        try {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager

            multicastLock = wifiManager?.createMulticastLock("YOLA_Discovery")?.apply {
                setReferenceCounted(true)
                acquire()
            }
            Log.d(TAG, "🔓 MulticastLock OK")
        } catch (e: Exception) {
            Log.w(TAG, "MulticastLock failed: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (e: Exception) { }
        multicastLock = null
    }
}
