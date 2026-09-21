package com.sergeron.mixflipcontinuity

import android.graphics.drawable.Drawable

/**
 * Model reprezentujący zainstalowaną aplikację oraz jej stan:
 * 1. continuityEnabled - czy aplikacja kontynuuje działanie po zamknięciu klapki
 * 2. coverScreenAllowed - czy aplikacja może uruchamiać się na ekranie zewnętrznym (allowstart)
 */
data class AppEntry(
    val packageName: String,
    val label: String,
    var icon: Drawable? = null,
    val systemApp: Boolean,
    var continuityEnabled: Boolean = false,
    var coverScreenAllowed: Boolean = false,
    var isBusy: Boolean = false
)
