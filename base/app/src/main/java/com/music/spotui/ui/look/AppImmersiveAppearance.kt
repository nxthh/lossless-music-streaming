package com.music.spotui.ui.look

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import com.music.spotui.ui.immersive.ImmersiveAppearance

/** The Immersive look's colours and typeface as this app decides them (extensions, AMOLED, accent, font setting). */
object AppImmersiveAppearance : ImmersiveAppearance {
    override val page: Color
        get() = if (app.myspoti.power.EnergySettings.oledBlack) Color.Black
        else com.music.spotui.extensions.ExtensionLook.color(app.myspoti.extensions.UiStyle.BACKGROUND) ?: Color.Black
    override val raised: Color
        get() = com.music.spotui.extensions.ExtensionLook.color(app.myspoti.extensions.UiStyle.SURFACE) ?: Color(0xFF1C1C1E)
    override val accent: Color
        get() = com.music.spotui.extensions.ExtensionLook.color(app.myspoti.extensions.UiStyle.ACCENT)
            ?: com.music.spotui.data.api.SupportReminder.run { if (immersiveAccentChosen) immersiveAccent.color else Color(0xFFFF4F7B) }
    override val liquidGlass: Boolean get() = com.music.spotui.data.preferences.LiquidGlassPref.on
    override val sans: FontFamily get() = ImmersiveSans
    override val display: FontFamily get() = ImmersiveDisplay
}
