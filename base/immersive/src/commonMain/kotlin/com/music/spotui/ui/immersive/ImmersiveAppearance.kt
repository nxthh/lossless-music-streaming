package com.music.spotui.ui.immersive

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

/*
 * Where the Immersive look gets what its host app decides: the page and surface colours (an extension
 * can restyle them, the AMOLED setting forces black), the accent, and the typeface. Android (:app) and
 * desktop (:desktop) each provide one at startup; the defaults below are the look as shipped.
 * Implementations may read Compose state, so a screen drawn with these values redraws when it changes.
 */
interface ImmersiveAppearance {
    val page: Color get() = Color.Black
    val raised: Color get() = Color(0xFF1C1C1E)
    val accent: Color get() = Color(0xFFFF4F7B)
    /** The user's Liquid Glass switch; whether the device can draw it is decided separately. */
    val liquidGlass: Boolean get() = true
    /** Body text, captions, buttons. */
    val sans: FontFamily get() = FontFamily.Default
    /** Titles, the player's title and the lyrics (20sp and up). */
    val display: FontFamily get() = FontFamily.Default
}

object DefaultImmersiveAppearance : ImmersiveAppearance

/** Set once at startup by the host app, before the first Immersive screen is drawn. */
object ImmersiveAppearanceHost {
    var current: ImmersiveAppearance = DefaultImmersiveAppearance
}
