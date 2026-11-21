package com.music.spotui.ui.look

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

/**
 * The system's "Remove animations" setting, read once at the root (see App) for both looks.
 * Null where nothing provides it: [rememberReduceMotion] then reads the setting itself.
 */
val LocalReduceMotion = compositionLocalOf<Boolean?> { null }

/**
 * True when the system asks for no animation (Android: the animator duration scale is 0).
 * Read once per composition; the setting rarely changes while a screen is up.
 */
@Composable
fun rememberReduceMotion(): Boolean = LocalReduceMotion.current ?: rememberReduceMotionSetting()

@Composable
internal expect fun rememberReduceMotionSetting(): Boolean
