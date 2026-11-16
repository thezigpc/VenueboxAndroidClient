package com.bargames.venuebox.client

import kotlin.math.abs

/** A display mode as Android reports it (Display.Mode), kept free of Android types for JVM tests. */
data class Mode(val id: Int, val w: Int, val h: Int, val hz: Float) {
    val pixels get() = w.toLong() * h
}

/**
 * The mode to ask the TV for: the most pixels it offers (4K on a 4K TV), so pages render at the
 * panel's fidelity while still being laid out at 1920x1080 CSS px and scaled to fit.
 *
 * - Never a lower refresh rate than now: a 60 Hz TV doesn't drop to 4K at 30 Hz.
 * - Same shape as now: a 16:9 TV doesn't jump to a 4096x2160 cinema mode.
 * - Null when nothing beats the current mode, so the TV isn't switched for no gain.
 */
fun bestMode(modes: List<Mode>, current: Mode): Mode? {
    val candidates = modes.filter { m ->
        m.hz >= current.hz - 0.5f &&
            abs(m.w.toLong() * current.h - m.h.toLong() * current.w) <= current.w.toLong() * current.h / 100
    }
    val most = candidates.maxOfOrNull { it.pixels } ?: return null
    if (most <= current.pixels) return null
    return candidates.filter { it.pixels == most }
        .minWith(compareBy<Mode> { abs(it.hz - current.hz) }.thenByDescending { it.hz })
}
