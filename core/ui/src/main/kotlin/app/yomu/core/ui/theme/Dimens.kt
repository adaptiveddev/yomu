package app.yomu.core.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** 4/8dp grid used across all screens. */
object Dimens {
    val xxs = 2.dp
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val touch = 48.dp
    val coverWidth = 110.dp
    val gridMin = 112.dp
    /** Corner radius of cards and grouped panels. */
    val card = 22.dp
    /** Height of the floating navigation bar (without the system navigation inset). */
    val navBar = 64.dp
}

/** Soft, generous corners: covers 12dp, cards 18–24dp, sheets and dialogs 32dp. */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)
