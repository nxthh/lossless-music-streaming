package com.music.spotui.ui.immersive.shell.backdrop

/** True where the platform can blur and filter what is behind a surface (Android 12+). */
expect fun isRenderEffectSupported(): Boolean

/** True where the platform can run a custom shader on it (Android 13+); the lens needs it. */
expect fun isRuntimeShaderSupported(): Boolean
