package com.yolabysayri.yola.tts

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.io.FileOutputStream

/**
 * ╔═══════════════════════════════════════════════════════════════════════════╗
 * ║            PIPER TTS ENGINE — VOZ NEURAL NATIVA EN ANDROID              ║
 * ╠═══════════════════════════════════════════════════════════════════════════╣
 * ║  Usa sherpa-onnx para ejecutar Piper VITS localmente en el ARM del      ║
 * ║  teléfono. Sin internet. Sin cloud. Voz neuronal pura.                  ║
 * ╚═══════════════════════════════════════════════════════════════════════════╝
 */
class PiperTTSEngine(private val context: Context) {

    companion object {
        private const val TAG = "YOLA_BODY" // Usar el mismo tag para facilitar debug
        private const val PIPER_ASSETS_DIR = "piper"
        private const val MODEL_FILE = "es_MX-claude-high.onnx"
        private const val TOKENS_FILE = "tokens.txt"
        private const val ESPEAK_DATA_DIR = "espeak-ng-data"
    }

    private var offlineTts: OfflineTts? = null
    private var isInitialized = false
    private var sampleRate = 22050

    @Volatile
    private var isSpeaking = false

    fun initialize(): Boolean {
        try {
            Log.i(TAG, "🎙️ [PIPER] Inicializando motor...")

            val modelDir = File(context.filesDir, PIPER_ASSETS_DIR)
            if (!modelDir.exists()) modelDir.mkdirs()

            // Invalidar cache si el modelo cambió (tokens.txt depende del modelo)
            val modelDest = File(modelDir, MODEL_FILE)
            if (!modelDest.exists()) {
                // Modelo nuevo: borrar tokens viejo para forzar re-copia
                val oldTokens = File(modelDir, TOKENS_FILE)
                if (oldTokens.exists()) {
                    Log.i(TAG, "🔄 [PIPER] Modelo cambió, borrando tokens.txt en cache")
                    oldTokens.delete()
                }
                // También limpiar modelos antiguos
                modelDir.listFiles()?.filter { it.name.endsWith(".onnx") && it.name != MODEL_FILE }
                    ?.forEach { old ->
                        Log.i(TAG, "🗑️ [PIPER] Borrando modelo viejo: ${old.name}")
                        old.delete()
                    }
            }

            val modelPath = copyAssetIfNeeded("$PIPER_ASSETS_DIR/$MODEL_FILE", modelDest)
            val tokensPath = copyAssetIfNeeded("$PIPER_ASSETS_DIR/$TOKENS_FILE", File(modelDir, TOKENS_FILE))
            val espeakDataPath = copyAssetDirIfNeeded("$PIPER_ASSETS_DIR/$ESPEAK_DATA_DIR", File(modelDir, ESPEAK_DATA_DIR))

            // Diagnóstico pre-init: verificar archivos
            val modelFile = File(modelPath)
            val tokensFile = File(tokensPath)
            Log.i(TAG, "📊 [PIPER-DIAG] Model: ${modelFile.name} (${modelFile.length()} bytes, exists=${modelFile.exists()})")
            Log.i(TAG, "📊 [PIPER-DIAG] Tokens: ${tokensFile.name} (${tokensFile.length()} bytes, exists=${tokensFile.exists()})")
            Log.i(TAG, "📊 [PIPER-DIAG] EspeakData: $espeakDataPath (exists=${File(espeakDataPath).exists()})")

            // Diagnóstico de memoria
            val runtime = Runtime.getRuntime()
            val freeMB = runtime.freeMemory() / (1024 * 1024)
            val totalMB = runtime.totalMemory() / (1024 * 1024)
            val maxMB = runtime.maxMemory() / (1024 * 1024)
            Log.i(TAG, "📊 [PIPER-DIAG] RAM: free=${freeMB}MB, total=${totalMB}MB, max=${maxMB}MB")

            // Liberar memoria antes de cargar modelo pesado
            System.gc()
            Thread.sleep(200)

            // RAM Optimization: numThreads = 1 para reducir consumo y posible kill del OS
            val config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = modelPath,
                        tokens = tokensPath,
                        dataDir = espeakDataPath
                    ),
                    numThreads = 1, // Reducir a 1 thread para ahorrar memoria
                    debug = true // ACTIVAR debug para ver errores nativos
                )
            )

            // Capturar crashes nativos antes de la llamada peligrosa
            val oldHandler = Thread.currentThread().uncaughtExceptionHandler
            Thread.currentThread().uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { t, e ->
                Log.e(TAG, "💀 [PIPER-NATIVE-CRASH] Crash en thread ${t.name}: ${e.message}", e)
                oldHandler?.uncaughtException(t, e)
            }

            // 3. Crear instancia TTS - PUNTO CRÍTICO
            Log.i(TAG, "⚡ [PIPER-INIT] >>> Creando OfflineTts AHORA (thread=${Thread.currentThread().name})...")
            Log.w(TAG, "⚡ [PIPER-INIT] Si no ves el log siguiente, el crash es NATIVO en C++")

            offlineTts = OfflineTts(config = config)

            Log.i(TAG, "⚡ [PIPER-INIT] <<< OfflineTts creado EXITOSAMENTE!")

            // Restaurar handler
            Thread.currentThread().uncaughtExceptionHandler = oldHandler

            sampleRate = offlineTts?.sampleRate() ?: 22050

            isInitialized = true
            Log.i(TAG, "🎙️ ✅ Piper TTS neural inicializado (sampleRate=$sampleRate)")
            return true

        } catch (e: Throwable) { // Catch Throwable para Error también
            Log.e(TAG, "❌ Error inicializando Piper TTS: ${e.javaClass.name}: ${e.message}", e)
            isInitialized = false
            return false
        }
    }

    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor()

    /**
     * Genera y reproduce voz a partir de texto.
     * Usa un Executor para asegurar que las peticiones se procesen en orden y no saturen el sistema.
     */
    fun speak(text: String) {
        if (!isInitialized || offlineTts == null) {
            Log.w(TAG, "⚠️ Piper no inicializado, ignorando speak()")
            return
        }

        Log.i(TAG, "🧵 [PIPER-PRE-THREAD] Solicitando hablar: \"${text.take(20)}...\"")

        executor.execute {
            try {
                isSpeaking = false
                Thread.sleep(50) 
                isSpeaking = true
                
                val currentTts = offlineTts ?: return@execute
                Log.i(TAG, "🗣️ [PIPER-START] Generando audio BATCH: \"${text.take(50)}...\"")

                // 1. Generar audio completo (SIN callback — más estable en JNI)
                val audio = try {
                    currentTts.generate(
                        text = text,
                        sid = 0,
                        speed = 1.0f
                    )
                } catch (e: Throwable) {
                    Log.e(TAG, "❌ [CRITICAL-JNI] Error en generate(): ${e.message}", e)
                    null
                }

                if (audio == null || audio.samples.isEmpty()) {
                    Log.w(TAG, "⚠️ [PIPER] generate() devolvió audio vacío o null")
                    isSpeaking = false
                    return@execute
                }

                Log.i(TAG, "✅ [PIPER-GENERATED] ${audio.samples.size} samples generados")

                if (!isSpeaking) return@execute  // Cancelado mientras generaba

                // 2. Configurar AudioTrack y reproducir
                val minBufferSize = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_FLOAT
                )
                
                val audioTrack = try {
                    AudioTrack(
                        AudioManager.STREAM_MUSIC,
                        sampleRate,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_FLOAT,
                        minBufferSize.coerceAtLeast(audio.samples.size * 4),
                        AudioTrack.MODE_STATIC
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "❌ No se pudo crear AudioTrack: ${e.message}")
                    null
                }

                if (audioTrack == null) {
                    isSpeaking = false
                    return@execute
                }

                try {
                    audioTrack.write(audio.samples, 0, audio.samples.size, AudioTrack.WRITE_BLOCKING)
                    audioTrack.play()
                    
                    // Esperar a que termine de reproducir
                    val durationMs = (audio.samples.size.toLong() * 1000L) / sampleRate
                    Log.i(TAG, "🔊 [PIPER-PLAYING] Reproduciendo ${durationMs}ms de audio...")
                    Thread.sleep(durationMs + 200) // +200ms de margen
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error reproduciendo audio: ${e.message}", e)
                } finally {
                    try { audioTrack.stop() } catch (_: Exception) {}
                    try { audioTrack.release() } catch (_: Exception) {}
                }

                Log.i(TAG, "✅ [PIPER-END] Reproducción completada: ${audio.samples.size} samples")

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error en Executor Piper: ${e.message}", e)
            } finally {
                isSpeaking = false
            }
        }
    }

    /**
     * Detiene la reproducción actual.
     */
    fun stop() {
        Log.d(TAG, "⏹️ Deteniendo voz Piper...")
        isSpeaking = false
    }

    /**
     * Verifica si el motor está listo para usar.
     */
    fun isReady(): Boolean = isInitialized

    /**
     * Libera recursos de sherpa-onnx.
     */
    fun shutdown() {
        Log.i(TAG, "🛑 Liberando Piper TTS...")
        isSpeaking = false
        try {
            offlineTts?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing OfflineTts: ${e.message}")
        }
        offlineTts = null
        isInitialized = false
    }

    // ========================================================================
    // ASSET MANAGEMENT — Copia modelo del APK al filesystem interno
    // ========================================================================

    /**
     * Copia un archivo de assets si no existe en destino (o si es más pequeño).
     * Retorna la ruta absoluta del archivo copiado.
     */
    private fun copyAssetIfNeeded(assetPath: String, destFile: File): String {
        if (destFile.exists() && destFile.length() > 0) {
            Log.d(TAG, "📦 Asset ya existe: ${destFile.name}")
            return destFile.absolutePath
        }

        Log.i(TAG, "📦 Copiando asset: $assetPath → ${destFile.absolutePath}")
        context.assets.open(assetPath).use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output, bufferSize = 8192)
            }
        }
        return destFile.absolutePath
    }

    /**
     * Copia recursivamente un directorio de assets al filesystem.
     * Retorna la ruta absoluta del directorio destino.
     */
    private fun copyAssetDirIfNeeded(assetDir: String, destDir: File): String {
        if (!destDir.exists()) {
            destDir.mkdirs()
        }

        val assets = context.assets.list(assetDir) ?: emptyArray()
        if (assets.isEmpty()) {
            Log.w(TAG, "⚠️ No se encontraron assets en: $assetDir")
            return destDir.absolutePath
        }

        for (asset in assets) {
            val assetPath = "$assetDir/$asset"
            val destFile = File(destDir, asset)

            // Verificar si es directorio o archivo
            val subAssets = context.assets.list(assetPath)
            if (subAssets != null && subAssets.isNotEmpty()) {
                // Es un subdirectorio → recursión
                copyAssetDirIfNeeded(assetPath, destFile)
            } else {
                // Es un archivo → copiar
                copyAssetIfNeeded(assetPath, destFile)
            }
        }

        return destDir.absolutePath
    }
}
