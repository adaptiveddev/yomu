package app.yomu.feature.editor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TypesetMathTest {
    private val white = TypesetMath.WHITE
    private val black = TypesetMath.BLACK
    private val grey = 0xFF808080.toInt()

    /** A grey page with a white bubble (an outlined rectangle) and black "lettering" in its middle. */
    private fun page(w: Int = 200, h: Int = 200): IntArray {
        val px = IntArray(w * h) { grey }
        for (y in 75 until 125) for (x in 60 until 140) px[y * w + x] = white
        for (x in 60 until 140) { px[75 * w + x] = black; px[124 * w + x] = black }
        for (y in 75 until 125) { px[y * w + 60] = black; px[y * w + 139] = black }
        for (y in 90 until 110) for (x in 80 until 120) if ((x + y) % 3 == 0) px[y * w + x] = black
        return px
    }

    @Test
    fun dominantColorIgnoresTheLettering() {
        val px = page()
        assertEquals(white, TypesetMath.dominantColor(px, 200, PixelRect(78, 88, 122, 112)))
    }

    @Test
    fun bubbleIsTheConnectedBackground() {
        val px = page()
        val b = TypesetMath.bubble(px, 200, 200, PixelRect(78, 88, 122, 112), white)
        assertNotNull(b)
        // The white inside of the outline.
        assertEquals(PixelRect(61, 76, 139, 124), b)
        val area = TypesetMath.textArea(PixelRect(78, 88, 122, 112), b)
        assertEquals(PixelRect(70, 82, 130, 118), area)
    }

    @Test
    fun openBackgroundIsNoBubble() {
        val w = 200
        val px = IntArray(w * w) { white }
        for (y in 90 until 110) for (x in 80 until 120) if ((x + y) % 3 == 0) px[y * w + x] = black
        assertNull(TypesetMath.bubble(px, w, w, PixelRect(78, 88, 122, 112), white))
        assertEquals(PixelRect(78, 88, 122, 112), TypesetMath.textArea(PixelRect(78, 88, 122, 112), null))
    }

    @Test
    fun fitSizeFindsTheLargestFittingSize() {
        // Height grows with the size: 3 lines of 1.2 × size.
        val size = TypesetMath.fitSize(120, 6f, 100f) { s -> (3 * 1.2f * s).toInt() }
        // 3 × 1.2 × s, rounded down, stays within 120 up to s = 121 / 3.6 ≈ 33.6.
        assertTrue(size in 33.0f..33.62f, "size $size")
        // A word that never fits: the minimum.
        assertEquals(6f, TypesetMath.fitSize(120, 6f, 100f) { null })
    }

    @Test
    fun luminanceSeparatesInk() {
        assertTrue(TypesetMath.luminance(white) > 0.9f)
        assertTrue(TypesetMath.luminance(black) < 0.1f)
    }
}
