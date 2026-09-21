package com.sergeron.mixflipcontinuity

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * Narzędzie do bezpiecznego wykonywania poleceń powłoki w kontekście Shizuku (UID 2000 - com.android.shell).
 *
 * UZASADNIENIE UŻYCIA DEPRECATED API (`Shizuku.newProcess`):
 * Metoda `Shizuku.newProcess` jest oznaczona jako przestarzała na rzecz UserService (zbindowany daemon AIDL).
 * W naszym zastosowaniu wywołujemy standardowe binarki Androida (/system/bin/content i /system/bin/settings),
 * które muszą działać bezpośrednio w powłoce systemowej z uprawnieniami powłoki (UID 2000).
 * Zgodnie z kodem HyperOS (ContinuityProvider.checkPermission()):
 *   int callingUid = Binder.getCallingUid();
 *   boolean z = callingUid == 1000 || UserHandle.isCore(callingUid);
 * UID 2000 (shell) jest traktowany przez jądro i framework Androida jako "Core UID" (< 10000), co zapewnia
 * pełny i bezpieczny dostęp do providera continuity bez potrzeby wdrażania skomplikowanego daemona IPC UserService.
 */
object ShizukuShell {

    data class CommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String
    ) {
        val isOk: Boolean
            get() = exitCode == 0

        fun combinedOutput(): String {
            val out = stdout.trim()
            val err = stderr.trim()
            return when {
                out.isEmpty() -> err
                err.isEmpty() -> out
                else -> "$out\n$err"
            }
        }
    }

    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (t: Throwable) {
            false
        }
    }

    fun hasPermission(): Boolean {
        return try {
            isShizukuAvailable() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (t: Throwable) {
            false
        }
    }

    @Suppress("DEPRECATION")
    @Throws(Exception::class)
    fun runCommand(command: String): CommandResult {
        if (!isShizukuAvailable()) {
            throw IllegalStateException("Usługa Shizuku nie odpowiada (pingBinder = false). Upewnij się, że Shizuku działa.")
        }
        if (!hasPermission()) {
            throw SecurityException("Aplikacja nie posiada uprawnień Shizuku. Nadaj uprawnienia i spróbuj ponownie.")
        }

        // W Shizuku API 13.1.5 metoda newProcess została oznaczona jako private w klasie Shizuku.
        // Ponieważ klasa Shizuku znajduje się w naszym pakiecie APK (a nie w bootclasspath Androida),
        // wywołanie jej przez refleksję jest całkowicie bezpieczne i nie podlega restrykcjom Hidden API.
        val newProcessMethod = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        ).apply {
            isAccessible = true
        }

        val process = newProcessMethod.invoke(
            null,
            arrayOf("/system/bin/sh", "-c", command),
            null,
            null
        ) as Process

        val stdout: String
        val stderr: String
        try {
            stdout = readStream(process.inputStream)
            stderr = readStream(process.errorStream)
            val exitCode = process.waitFor()
            return CommandResult(exitCode, stdout, stderr)
        } finally {
            process.destroy()
        }
    }

    private fun readStream(inputStream: InputStream): String {
        val sb = StringBuilder()
        BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (sb.isNotEmpty()) {
                    sb.append('\n')
                }
                sb.append(line)
            }
        }
        return sb.toString()
    }
}
