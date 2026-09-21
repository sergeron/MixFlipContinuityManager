package com.sergeron.mixflipcontinuity

import android.content.Context
import java.util.regex.Pattern

/**
 * Repozytorium do zarządzania stanem funkcji Flip Continuity w HyperOS 3.
 *
 * Komunikuje się z:
 * 1. Globalnym przełącznikiem w Settings.System:
 *    `settings --user 0 get/put system flip_continuity_enabled`
 * 2. Providerem per-aplikacja:
 *    `content query/insert/update --uri content://com.android.settings.continuity.ContinuityProvider/packages --user 0`
 */
class ContinuityRepository {

    companion object {
        const val PROVIDER_URI = "content://com.android.settings.continuity.ContinuityProvider/packages"
        const val GLOBAL_SETTING_KEY = "flip_continuity_enabled"
        const val USER_ID = 0

        private val ROW_PATTERN = Pattern.compile("Row:\\s*\\d+\\s*(.+)")
        private val PKG_PATTERN = Pattern.compile("(?:^|[,\\s])pkgName=([^,\\s]+)")
        private val ENABLE_PATTERN = Pattern.compile("(?:^|[,\\s])enable=([0-9]+)")
        private val USER_PATTERN = Pattern.compile("(?:^|[,\\s])userId=(-?[0-9]+)")
    }

    /**
     * Sprawdza stan globalnego przełącznika continuity w ustawieniach systemowych.
     */
    fun isGlobalContinuityEnabled(): Result<Boolean> {
        return try {
            val res = ShizukuShell.runCommand("settings --user $USER_ID get system $GLOBAL_SETTING_KEY")
            if (!res.isOk) {
                return Result.failure(IllegalStateException("Błąd odczytu globalnego ustawienia: ${res.combinedOutput()}"))
            }
            val value = res.stdout.trim()
            Result.success(value == "1")
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /**
     * Włącza lub wyłącza globalne continuity w systemie.
     */
    fun setGlobalContinuityEnabled(enable: Boolean): Result<Unit> {
        return try {
            val value = if (enable) "1" else "0"
            val res = ShizukuShell.runCommand("settings --user $USER_ID put system $GLOBAL_SETTING_KEY $value")
            if (!res.isOk) {
                return Result.failure(IllegalStateException("Błąd zapisu globalnego ustawienia: ${res.combinedOutput()}"))
            }
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /**
     * Odczytuje wszystkie rekordy z ContinuityProvider dla danego użytkownika (userId=0).
     * Zwraca mapę: packageName -> stan enable (1 lub 0).
     */
    fun queryAllRecords(): Result<Map<String, Int>> {
        return try {
            val res = ShizukuShell.runCommand("content query --uri $PROVIDER_URI --user $USER_ID")
            if (!res.isOk) {
                return Result.failure(IllegalStateException("Błąd odczytu ContinuityProvider: ${res.combinedOutput()}"))
            }

            val records = mutableMapOf<String, Int>()
            val lines = res.stdout.lineSequence()

            for (line in lines) {
                val rowMatcher = ROW_PATTERN.matcher(line)
                if (!rowMatcher.find()) continue

                val rowData = rowMatcher.group(1) ?: line
                val pkgMatcher = PKG_PATTERN.matcher(rowData)
                val enableMatcher = ENABLE_PATTERN.matcher(rowData)
                val userMatcher = USER_PATTERN.matcher(rowData)

                if (pkgMatcher.find() && enableMatcher.find() && userMatcher.find()) {
                    val pkgName = pkgMatcher.group(1) ?: continue
                    val enable = enableMatcher.group(1)?.toIntOrNull() ?: 0
                    val userId = userMatcher.group(1)?.toIntOrNull() ?: -1

                    if (userId == USER_ID) {
                        records[pkgName] = enable
                    }
                }
            }
            Result.success(records)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /**
     * Aktualizuje lub tworzy wpis dla danej aplikacji w ContinuityProvider.
     *
     * Zgodnie z oficjalnym kodem HyperOS:
     * - Włączenie: zapis rekordu z enable=1 (update jeśli istnieje, w przeciwnym razie insert).
     * - Wyłączenie: update rekordu na enable=0 (co powoduje usunięcie z pamięciowego setu systemowego).
     *
     * Jeśli aplikacja jest włączana, automatycznie upewnia się, że globalny switch jest włączony.
     */
    fun setAppContinuity(packageName: String, enable: Boolean): Result<Unit> {
        return try {
            val targetVal = if (enable) 1 else 0

            // 1. Jeśli włączamy aplikację, upewnijmy się, że globalny switch jest aktywny (1)
            if (enable) {
                val globalRes = isGlobalContinuityEnabled().getOrDefault(false)
                if (!globalRes) {
                    setGlobalContinuityEnabled(true)
                }
            }

            // 2. Najpierw sprawdzamy aktualny stan bazy dla tej aplikacji
            val allRecords = queryAllRecords().getOrThrow()
            val existsInDb = allRecords.containsKey(packageName)
            val currentVal = allRecords[packageName]

            if (existsInDb && currentVal == targetVal) {
                // Stan jest już pożądany
                return Result.success(Unit)
            }

            if (existsInDb) {
                // Rekord już istnieje -> wykonujemy UPDATE
                // Ważne: ContinuityProvider wymaga pkgName i userId w wartościach ContentValues (--bind)
                val updateCmd = "content update --uri $PROVIDER_URI --user $USER_ID " +
                        "--bind pkgName:s:$packageName " +
                        "--bind enable:i:$targetVal " +
                        "--bind userId:i:$USER_ID"
                val updateRes = ShizukuShell.runCommand(updateCmd)
                if (!updateRes.isOk) {
                    throw IllegalStateException("Błąd podczas aktualizacji rekordu (update): ${updateRes.combinedOutput()}")
                }
            } else {
                // Rekord nie istnieje w bazie
                if (enable) {
                    // Wstawiamy nowy rekord z enable=1
                    val insertCmd = "content insert --uri $PROVIDER_URI --user $USER_ID " +
                            "--bind pkgName:s:$packageName " +
                            "--bind enable:i:1 " +
                            "--bind userId:i:$USER_ID"
                    val insertRes = ShizukuShell.runCommand(insertCmd)
                    if (!insertRes.isOk) {
                        throw IllegalStateException("Błąd podczas dodawania rekordu (insert): ${insertRes.combinedOutput()}")
                    }
                } else {
                    // Jeśli chcemy wyłączyć, a rekordu w ogóle nie ma w bazie,
                    // system HyperOS traktuje brak rekordu jako brak continuity, więc stan już jest wyłączony.
                }
            }

            // 3. Weryfikacja: ponowny query w celu potwierdzenia rzeczywistego stanu
            val verifyRecords = queryAllRecords().getOrThrow()
            val finalVal = verifyRecords[packageName] ?: 0
            if (finalVal != targetVal) {
                throw IllegalStateException("Weryfikacja nie powiodła się: oczekiwano enable=$targetVal, w bazie jest $finalVal")
            }

            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /**
     * Odczytuje surowe wiersze z bazy danych ContinuityProvider (tabela packages).
     */
    fun queryRawDbRows(): Result<List<ContinuityDbRow>> {
        return try {
            val res = ShizukuShell.runCommand("content query --uri $PROVIDER_URI --user $USER_ID")
            if (!res.isOk) {
                return Result.failure(IllegalStateException("Błąd odczytu ContinuityProvider: ${res.combinedOutput()}"))
            }

            val rows = mutableListOf<ContinuityDbRow>()
            val lines = res.stdout.lineSequence()

            for (line in lines) {
                val rowMatcher = ROW_PATTERN.matcher(line)
                if (!rowMatcher.find()) continue

                val rowData = rowMatcher.group(1) ?: line
                val pkgMatcher = PKG_PATTERN.matcher(rowData)
                val enableMatcher = ENABLE_PATTERN.matcher(rowData)
                val userMatcher = USER_PATTERN.matcher(rowData)

                if (pkgMatcher.find() && enableMatcher.find() && userMatcher.find()) {
                    val pkgName = pkgMatcher.group(1) ?: continue
                    val enable = enableMatcher.group(1)?.toIntOrNull() ?: 0
                    val userId = userMatcher.group(1)?.toIntOrNull() ?: -1

                    val rowIdx = try {
                        line.substringAfter("Row:").trim().substringBefore(" ").toInt()
                    } catch (e: Exception) {
                        rows.size
                    }

                    rows.add(ContinuityDbRow(rowIdx, pkgName, enable, userId))
                }
            }
            Result.success(rows)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    /**
     * Fizycznie usuwa cały rekord z bazy danych ContinuityProvider przy użyciu ContinuityDbBridge.
     */
    fun deleteRawDbRow(context: Context, packageName: String, userId: Int = USER_ID): Result<Unit> {
        return try {
            val apkPath = context.packageCodePath
            val cmd = "CLASSPATH=$apkPath app_process /system/bin com.sergeron.mixflipcontinuity.ContinuityDbBridge delete $packageName $userId"
            val res = ShizukuShell.runCommand(cmd)
            if (!res.isOk || !res.stdout.contains("DELETE_SUCCESS")) {
                return Result.failure(IllegalStateException("Błąd usuwania rekordu: ${res.combinedOutput()}"))
            }
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }
}

data class ContinuityDbRow(
    val rowIndex: Int,
    val packageName: String,
    val enable: Int,
    val userId: Int
)
