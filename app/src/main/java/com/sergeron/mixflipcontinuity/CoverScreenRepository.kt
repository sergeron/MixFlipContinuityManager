package com.sergeron.mixflipcontinuity

import android.content.Context
import android.content.SharedPreferences
import android.os.Parcel
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * Repozytorium do zarządzania możliwością uruchamiania aplikacji na zewnętrznym ekranie (cover screen)
 * Xiaomi MIX Flip w HyperOS.
 *
 * Wykorzystuje oficjalny mechanizm systemowy:
 * - `dumpsys window -setForceDisplayCompatMode <pkg1[:pkg2...]> allowstart` (dodanie do listy dozwolonych)
 * - `dumpsys window -setForceDisplayCompatMode <pkg1[:pkg2...]> clear` (usunięcie)
 *
 * Odpytuje systemowy serwis IContinuityManager ("continuity") o rzeczywisty stan systemowy,
 * dzięki czemu widzi również aplikacje aktywowane wcześniej przez MixFlipTool!
 */
class CoverScreenRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("cover_screen_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_ALLOWED_PACKAGES = "allowed_packages"
        private const val CHUNK_SIZE = 50

        // Transakcja getContinuityPackages w android.appcompat.IContinuityManager$Stub
        private const val TRANSACTION_GET_CONTINUITY_PACKAGES = 2
        private const val CONTINUITY_DESCRIPTOR = "android.appcompat.IContinuityManager"
        private const val POLICY_ALLOWLIST = "app_intercept_allowlist"
        private const val TRUSTED_CALLER = "com.miui.fliphome"

        // Domyślne optymalizacje skalowania dla wbudowanych aplikacji MIUI na małym ekranie (jak w MixFlipTool)
        private val DEFAULT_APP_SCALES = mapOf(
            "com.android.contacts" to "0.67",
            "com.android.calendar" to "0.7",
            "com.android.mms" to "0.8",
            "com.android.soundrecorder" to "0.7",
            "com.miui.calculator" to "0.7",
            "com.miui.gallery" to "0.7"
        )
    }

    /**
     * Odpytuje systemowy serwis IContinuityManager o rzeczywistą listę aplikacji dozwolonych
     * do uruchomienia na zewnętrznym ekranie (w tym aktywowanych przez MixFlipTool lub naszą aplikację).
     */
    fun queryLiveCoverScreenPackages(): Result<Set<String>> {
        return try {
            val binder = SystemServiceHelper.getSystemService("continuity")
                ?: return Result.failure(IllegalStateException("Serwis continuity niedostępny"))

            val parcelIn = Parcel.obtain()
            val parcelOut = Parcel.obtain()
            try {
                parcelIn.writeInterfaceToken(CONTINUITY_DESCRIPTOR)
                parcelIn.writeString(POLICY_ALLOWLIST)
                parcelIn.writeString(TRUSTED_CALLER)

                val success = ShizukuBinderWrapper(binder).transact(
                    TRANSACTION_GET_CONTINUITY_PACKAGES,
                    parcelIn,
                    parcelOut,
                    0
                )
                if (!success) {
                    return Result.failure(IllegalStateException("Transakcja IPC do IContinuityManager nie powiodła się"))
                }
                parcelOut.readException()
                val list = parcelOut.createStringArrayList() ?: emptyList()
                val resultSet = list.toSet()

                // Aktualizujemy zapisany cache lokalny rzeczywistym stanem z systemu
                if (resultSet.isNotEmpty()) {
                    saveAllAllowedPackages(resultSet)
                }

                Result.success(resultSet)
            } finally {
                parcelIn.recycle()
                parcelOut.recycle()
            }
        } catch (t: Throwable) {
            // W razie braku możliwości bezpośredniego IPC, zwracamy zapisany stan lokalny
            Result.failure(t)
        }
    }

    /**
     * Zwraca zbiór pakietów zapisanych w pamięci podręcznej aplikacji jako dozwolone na ekranie zewnętrznym.
     */
    fun getSavedAllowedPackages(): Set<String> {
        return prefs.getStringSet(KEY_ALLOWED_PACKAGES, emptySet()) ?: emptySet()
    }

    /**
     * Zapisuje stan pojedynczej aplikacji w SharedPreferences.
     */
    fun saveAppAllowedState(packageName: String, allowed: Boolean) {
        val current = getSavedAllowedPackages().toMutableSet()
        if (allowed) {
            current.add(packageName)
        } else {
            current.remove(packageName)
        }
        prefs.edit().putStringSet(KEY_ALLOWED_PACKAGES, current).apply()
    }

    /**
     * Zapisuje całą listę aplikacji jako dozwolone.
     */
    fun saveAllAllowedPackages(packages: Collection<String>) {
        prefs.edit().putStringSet(KEY_ALLOWED_PACKAGES, packages.toSet()).apply()
    }

    /**
     * Czyści zapisane uprawnienia.
     */
    fun clearSavedAllowedPackages() {
        prefs.edit().remove(KEY_ALLOWED_PACKAGES).apply()
    }

    /**
     * Włącza lub wyłącza możliwość uruchamiania aplikacji na ekranie zewnętrznym.
     */
    fun setAppAllowed(packageName: String, allowed: Boolean): Result<Unit> {
        return try {
            val action = if (allowed) "allowstart" else "clear"
            val cmd = "dumpsys window -setForceDisplayCompatMode $packageName $action"
            val res = ShizukuShell.runCommand(cmd)
            if (!res.isOk) {
                return Result.failure(IllegalStateException("Błąd shella (${res.exitCode}): ${res.combinedOutput()}"))
            }
            saveAppAllowedState(packageName, allowed)
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /**
     * Masowo włącza lub wyłącza aplikacje na ekranie zewnętrznym.
     * Pakiety są dzielone na paczki (chunk) po 50, aby nie przekraczać limitów i zachować płynność.
     */
    fun setAllAppsAllowed(
        packageNames: List<String>,
        allowed: Boolean,
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ): Result<Int> {
        return try {
            val action = if (allowed) "allowstart" else "clear"
            val total = packageNames.size
            var processed = 0

            val chunks = packageNames.chunked(CHUNK_SIZE)
            for (chunk in chunks) {
                val joined = chunk.joinToString(":")
                val cmd = "dumpsys window -setForceDisplayCompatMode $joined $action"
                val res = ShizukuShell.runCommand(cmd)
                if (!res.isOk) {
                    return Result.failure(IllegalStateException("Błąd shella (${res.exitCode}): ${res.combinedOutput()}"))
                }
                processed += chunk.size
                onProgress?.invoke(processed, total)
            }

            if (allowed) {
                saveAllAllowedPackages(packageNames)
            } else {
                clearSavedAllowedPackages()
            }

            Result.success(processed)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /**
     * Aplikuje optymalizacje proporcji i skalowania okna (MiuiSizeCompat) dla wybranych aplikacji Xiaomi.
     */
    fun applyDefaultAppScales(): Result<Unit> {
        return try {
            val commands = mutableListOf<String>()
            for ((pkg, scale) in DEFAULT_APP_SCALES) {
                commands.add("cmd MiuiSizeCompat update-rule $pkg::$scale")
            }
            commands.add("cmd MiuiSizeCompat reload-rule")

            val combined = commands.joinToString(" && ")
            val res = ShizukuShell.runCommand(combined)
            if (!res.isOk) {
                return Result.failure(IllegalStateException("Błąd skalowania: ${res.combinedOutput()}"))
            }
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }
}
