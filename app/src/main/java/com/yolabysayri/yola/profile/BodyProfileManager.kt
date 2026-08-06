package com.yolabysayri.yola.profile

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log

/**
 * ╔═══════════════════════════════════════════════════════════════════════════╗
 * ║                    BODY PROFILE MANAGER                                    ║
 * ║                    FASE 4: Perfiles de Ejecución                          ║
 * ╠═══════════════════════════════════════════════════════════════════════════╣
 * ║  Gestiona los modos de operación del APK según capacidades del hardware   ║
 * ╚═══════════════════════════════════════════════════════════════════════════╝
 */

/**
 * Perfiles de ejecución disponibles para el dispositivo
 */
enum class BodyProfile(val displayName: String, val description: String) {
    /**
     * FULL_INTERACTIVE: UI Neural completa + Push-to-Talk
     * - Renderiza el Neural Core (Ojo Animado)
     * - Audio controlado por botón PTT
     * - Ideal para dispositivos de gama alta que se usan activamente
     */
    FULL_INTERACTIVE(
        "Modo Interactivo",
        "Control por voz con botón. Ideal para uso activo."
    ),
    
    /**
     * PASSIVE_DISPLAY: UI Neural completa + Solo salida de audio
     * - Renderiza el Neural Core (Ojo Animado)
     * - Micrófono desactivado (no envía audio)
     * - Solo recibe y reproduce audio/TTS
     * - Ideal para pantallas de visualización
     */
    PASSIVE_DISPLAY(
        "Pantalla Pasiva",
        "Solo visualización. Sin micrófono."
    ),
    
    /**
     * SENSOR_ONLY: UI mínima + Micrófono ambiental continuo
     * - Pantalla negra o indicador mínimo
     * - Streaming continuo de micrófono (modo legacy)
     * - Optimizado para dispositivos dedicados a audio
     */
    SENSOR_ONLY(
        "Sensor de Audio",
        "Micrófono siempre activo. Pantalla mínima."
    )
}

/**
 * Clasificación de capacidad del hardware
 */
enum class DeviceCapability {
    HIGH_END,    // >= 4GB RAM, puede manejar todo
    MID_RANGE,   // 2-4GB RAM, modo interactivo recomendado
    LOW_END      // < 2GB RAM, sensor only recomendado
}

class BodyProfileManager(private val context: Context) {
    
    companion object {
        private const val TAG = "BodyProfileManager"
        private const val PREFS_NAME = "YolaBodyPrefs"
        private const val KEY_PROFILE = "body_profile"
        private const val KEY_FIRST_RUN = "first_run_completed"
        private const val KEY_CAPABILITY_DETECTED = "capability_detected"
        
        // Umbrales de RAM en bytes
        private const val HIGH_END_RAM_THRESHOLD = 4L * 1024 * 1024 * 1024  // 4GB
        private const val MID_RANGE_RAM_THRESHOLD = 2L * 1024 * 1024 * 1024 // 2GB
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    /**
     * Verifica si es la primera ejecución del app
     */
    fun isFirstRun(): Boolean {
        return !prefs.getBoolean(KEY_FIRST_RUN, false)
    }
    
    /**
     * Marca la primera ejecución como completada
     */
    fun markFirstRunCompleted() {
        prefs.edit().putBoolean(KEY_FIRST_RUN, true).apply()
    }
    
    /**
     * Obtiene el perfil actual guardado
     * Si no hay perfil, retorna null (debe mostrar selector)
     */
    fun getCurrentProfile(): BodyProfile? {
        val profileName = prefs.getString(KEY_PROFILE, null)
        return profileName?.let { 
            try {
                BodyProfile.valueOf(it)
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }
    
    /**
     * Guarda el perfil seleccionado
     */
    fun setProfile(profile: BodyProfile) {
        prefs.edit().putString(KEY_PROFILE, profile.name).apply()
        Log.i(TAG, "✅ Perfil guardado: ${profile.displayName}")
    }
    
    /**
     * Detecta las capacidades del dispositivo basado en RAM
     */
    fun detectDeviceCapability(): DeviceCapability {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        
        val totalRam = memoryInfo.totalMem
        
        Log.i(TAG, "📊 RAM Total: ${totalRam / (1024 * 1024)} MB")
        
        return when {
            totalRam >= HIGH_END_RAM_THRESHOLD -> {
                Log.i(TAG, "🚀 Dispositivo HIGH_END detectado")
                DeviceCapability.HIGH_END
            }
            totalRam >= MID_RANGE_RAM_THRESHOLD -> {
                Log.i(TAG, "📱 Dispositivo MID_RANGE detectado")
                DeviceCapability.MID_RANGE
            }
            else -> {
                Log.i(TAG, "📟 Dispositivo LOW_END detectado")
                DeviceCapability.LOW_END
            }
        }
    }
    
    /**
     * Obtiene el perfil recomendado basado en capacidades
     */
    fun getRecommendedProfile(): BodyProfile {
        return when (detectDeviceCapability()) {
            DeviceCapability.HIGH_END -> BodyProfile.FULL_INTERACTIVE
            DeviceCapability.MID_RANGE -> BodyProfile.FULL_INTERACTIVE
            DeviceCapability.LOW_END -> BodyProfile.SENSOR_ONLY
        }
    }
    
    /**
     * Obtiene todos los perfiles disponibles para mostrar en selector
     */
    fun getAvailableProfiles(): List<BodyProfile> {
        return BodyProfile.values().toList()
    }
    
    /**
     * Verifica si el perfil actual permite usar el micrófono
     */
    fun isMicrophoneEnabled(): Boolean {
        val profile = getCurrentProfile() ?: return false
        return profile == BodyProfile.FULL_INTERACTIVE || profile == BodyProfile.SENSOR_ONLY
    }
    
    /**
     * Verifica si el perfil actual usa Push-to-Talk
     */
    fun isPushToTalkMode(): Boolean {
        return getCurrentProfile() == BodyProfile.FULL_INTERACTIVE
    }
    
    /**
     * Verifica si el perfil actual usa micrófono continuo
     */
    fun isContinuousMicMode(): Boolean {
        return getCurrentProfile() == BodyProfile.SENSOR_ONLY
    }
    
    /**
     * Verifica si el perfil actual muestra UI completa
     */
    fun shouldShowFullUI(): Boolean {
        val profile = getCurrentProfile() ?: return true
        return profile == BodyProfile.FULL_INTERACTIVE || profile == BodyProfile.PASSIVE_DISPLAY
    }
}
