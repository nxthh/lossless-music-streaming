package com.music.spotui.ui.immersive.motion

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NavBackStackEntry
import com.music.spotui.ui.immersive.motion.ImmersiveMotion.FADE_MS
import com.music.spotui.ui.immersive.motion.ImmersiveMotion.PAGE_MS
import com.music.spotui.ui.immersive.motion.ImmersiveMotion.PageFade
import com.music.spotui.ui.navigation.Routes

/**
 * Page transitions: the slides here, the fades run by the page itself ([PageFade], see
 * MyNavHost's `page`), so the Liquid Glass sampling a page fades with it frame by frame
 * instead of jumping once the transition is over.
 */
object ImmersivePages {
    private val tabRoutes = setOf(Routes.Home.route, Routes.Search.route, Routes.Library.route)

    private fun AnimatedContentTransitionScope<NavBackStackEntry>.isTabSwitch() =
        initialState.destination.route in tabRoutes && targetState.destination.route in tabRoutes

    fun enter(reduce: Boolean): AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        val still = reduce || isTabSwitch()
        PageFade.enterMs = if (still) FADE_MS else PAGE_MS / 2
        // Tabs cross-fade over FADE_MS; without an enter transition, Compose detaches the outgoing tab immediately (#172).
        if (still) fadeIn(tween(FADE_MS)) else slideInHorizontally(spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset(1, 1))) { it }
    }

    fun exit(reduce: Boolean): AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        val still = reduce || isTabSwitch()
        PageFade.exitMs = if (still) FADE_MS else PAGE_MS
        if (still) ExitTransition.None else slideOutHorizontally(tween(PAGE_MS)) { -it / 3 }
    }

    fun popEnter(reduce: Boolean): AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
        val still = reduce || isTabSwitch()
        PageFade.enterMs = if (still) FADE_MS else PAGE_MS
        // Tabs cross-fade over FADE_MS; without an enter transition, Compose detaches the outgoing tab immediately (#172).
        if (still) fadeIn(tween(FADE_MS)) else slideInHorizontally(tween(PAGE_MS)) { -it / 3 }
    }

    fun popExit(reduce: Boolean): AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
        val still = reduce || isTabSwitch()
        PageFade.exitMs = if (still) FADE_MS else PAGE_MS / 2
        if (still) ExitTransition.None else slideOutHorizontally(tween(PAGE_MS)) { it }
    }
}

