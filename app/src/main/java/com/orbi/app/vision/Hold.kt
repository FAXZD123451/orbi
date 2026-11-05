package com.orbi.app.vision

/**
 * Filtro anti-parpadeo para detecciones de la cámara.
 *
 * Una señal cruda (por ejemplo, "está sonriendo") cambia a veces en un solo cuadro por ruido.
 * [Hold] solo acepta el cambio si se mantiene: [onMs] para encenderse y [offMs] para apagarse.
 * Así Orbi no pasa de sonreír a serio y de vuelta en medio segundo.
 */
class Hold(private val onMs: Long, private val offMs: Long) {
    var value = false
        private set
    private var since = 0L

    fun update(raw: Boolean, now: Long): Boolean {
        if (raw == value) {
            since = 0L
            return value
        }
        if (since == 0L) since = now
        if (now - since >= if (raw) onMs else offMs) {
            value = raw
            since = 0L
        }
        return value
    }

    fun reset() {
        value = false
        since = 0L
    }
}
