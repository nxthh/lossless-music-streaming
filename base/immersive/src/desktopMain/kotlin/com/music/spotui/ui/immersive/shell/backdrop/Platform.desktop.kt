package com.music.spotui.ui.immersive.shell.backdrop

// Desktop first runs the Haze-style fallback (ImmersiveHaze); a Skia RuntimeEffect port can follow.
actual fun isRenderEffectSupported(): Boolean = false

actual fun isRuntimeShaderSupported(): Boolean = false
