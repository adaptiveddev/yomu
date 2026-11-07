package app.yomu.core.ui.components

/**
 * Hardware key routing from the activity to the active screen (volume keys page turning in readers).
 * The handler returns true when it consumed the key.
 */
object KeyBus {
    @Volatile
    var volumeHandler: ((up: Boolean) -> Boolean)? = null

    fun onVolume(up: Boolean): Boolean = volumeHandler?.invoke(up) ?: false
}
