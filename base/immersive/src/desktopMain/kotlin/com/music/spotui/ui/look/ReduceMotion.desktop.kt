package com.music.spotui.ui.look

import androidx.compose.runtime.Composable

// No portable OS hint on desktop yet; the app can still provide LocalReduceMotion.
@Composable
internal actual fun rememberReduceMotionSetting(): Boolean = false
