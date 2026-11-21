package com.music.spotui.ui.immersive.motion

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.music.spotui.ui.look.rememberReduceMotion

/**
 * The motion constants of the Immersive look in one place, so screens share one feel instead of
 * each picking numbers. The springs are BitChord's (medium-low stiffness for moving things,
 * high for press feedback); pages push like a navigation stack, the old page sliding a third of
 * the way as the new one covers it.
 */
object ImmersiveMotion {
    /** Placement of rows in a list (reorder, insert, filter). */
    fun <T> placementSpring() = spring<T>(stiffness = Spring.StiffnessMediumLow)

    /**
     * The floating chrome folding and unfolding (the tab bar minimizing to a pill, the top bar's
     * controls shrinking): a soft spring with a hint of overshoot. Snaps under "reduce motion".
     */
    fun <T> chromeSpring(reduce: Boolean = false): androidx.compose.animation.core.FiniteAnimationSpec<T> =
        if (reduce) androidx.compose.animation.core.snap() else spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)

    /**
     * The one set of durations and springs of the Immersive look (iOS 26 feel): everything that
     * moves on a finger uses a spring, everything that fades uses these. Screens pick from here
     * instead of writing their own numbers.
     */
    const val FADE_MS = 150
    const val MEDIUM_MS = 320
    const val LONG_MS = 420
    const val TINT_MS = 600

    /** A sheet warming into its cover's colours the first time it is seen: quicker than the player's [TINT_MS], as a sheet is brief. */
    const val SHEET_TINT_MS = 400

    /** Press feedback (scale under the finger): stiff and fully damped, it never wobbles. */
    fun <T> pressSpring() = spring<T>(stiffness = 900f)

    /**
     * Glass objects that travel (the tab selection pill, a lens): one soft pass beyond the mark,
     * then settled. Damping 0.72, stiffness 320.
     */
    fun <T> glassSpring() = spring<T>(dampingRatio = 0.72f, stiffness = 320f)

    /** Release of a glass surface after a press: a touch of overshoot. */
    fun <T> releaseSpring() = spring<T>(dampingRatio = 0.62f, stiffness = Spring.StiffnessMedium)

    /** Content that resizes inside a floating container (controls opening in a pill). */
    fun <T> sizeSpring() = spring<T>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

    /**
     * The full player rising from the mini player and going back: critically damped with a long
     * soft landing. Snaps under "reduce motion".
     */
    fun slideSpring(reduce: Boolean = false): androidx.compose.animation.core.AnimationSpec<Float> =
        if (reduce) androidx.compose.animation.core.snap()
        else spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 195f, visibilityThreshold = 0.5f)

    /** A page pushed or popped by the nav host. */
    const val PAGE_MS = 380

    /**
     * Enter/exit specs for `NavHost` in the Immersive look. Switching between the three tabs is
     * a short fade, as in Classic (tabs are siblings, not a stack); with "reduce motion" every
     * page change is that short fade. Pass them as `enterTransition`, `exitTransition`,
     * `popEnterTransition`, `popExitTransition`.
     */
    /**
     * How long the page coming in fades in, and the one leaving fades out, in the transition
     * starting: set by [Pages] when the navigation picks its transition, read by the pages.
     */
    object PageFade {
        var enterMs: Int = PAGE_MS / 2
        var exitMs: Int = PAGE_MS
    }
}

/**
 * Items of a lazy list glide to their new place (no cross-fade, the list stays readable), and
 * stay put under "reduce motion". Use on every row of an Immersive list.
 */
@Composable
fun LazyItemScope.immersiveItemPlacement(): Modifier {
    if (rememberReduceMotion()) return Modifier
    return Modifier.animateItem(
        fadeInSpec = null,
        fadeOutSpec = null,
        placementSpec = ImmersiveMotion.placementSpring(),
    )
}
