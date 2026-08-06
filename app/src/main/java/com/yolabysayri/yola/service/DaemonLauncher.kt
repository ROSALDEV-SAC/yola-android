package com.yolabysayri.yola.service

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Lanza el daemon YOLA mínimo (yola-daemon-mobile) como proceso nativo.
 * El binario ARM64 se empaqueta en assets/daemon/yola-daemon-mobile.
 */
object DaemonLauncher {
    private const val TAG = "DaemonLauncher"
    private const val DAEMON_ASSET = "daemon/yola-daemon-mobile"
    private const val DAEMON_EXECUTABLE = "yola-daemon-mobile"
    
    /**
     * Extrae el binario de assets al directorio interno y lo ejecuta.
     * @return true si el daemon arrancó correctamente
     */
    fun launch(context: Context, port: Int = 7779, discoveryPort: Int = 41335): Boolean {
        return try {
            val daemonDir = File(context.filesDir, "daemon")
            daemonDir.mkdirs()
            val daemonFile = File(daemonDir, DAEMON_EXECUTABLE)
            
            // Extraer binario de assets
            if (!daemonFile.exists()) {
                context.assets.open(DAEMON_ASSET).use { input ->
                    FileOutputStream(daemonFile).use { output ->
                        input.copyTo(output)
                    }
                }
                daemonFile.setExecutable(true)
                Log.i(TAG, "Daemon extraído: ${daemonFile.absolutePath}")
            }
            
            // Lanzar proceso
            val process = ProcessBuilder(
                daemonFile.absolutePath,
                "--port", port.toString(),
                "--discovery-port", discoveryPort.toString()
            )
                .directory(daemonDir)
                .redirectErrorStream(true)
                .start()
            
            Log.i(TAG, "Daemon iniciado PID: ${process.pid()} puerto: $port")
            
            // Esperar un momento para que arranque
            Thread.sleep(1500)
            
            process.isAlive
        } catch (e: Exception) {
            Log.e(TAG, "Error lanzando daemon", e)
            false
        }
    }
}
