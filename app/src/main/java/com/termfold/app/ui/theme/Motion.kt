package com.termfold.app.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * The one set of timings every animation in the app uses, so a tab change, a sheet, a fade and a
 * selection highlight all feel like parts of the same app.
 *
 * Three speeds, one curve:
 *  - [quick]: small state flips that should feel instant (a pressed or selected highlight, a
 *    tab's tint, anything leaving the screen);
 *  - [standard]: most things appearing or changing (fades, expanding rows, banners, sheets);
 *  - [slow]: whole screens and large panels sliding in.
 *
 * It is tuned per device class. A tablet's surfaces are bigger and travel further, so the same
 * motion at phone speed looks hurried there; its durations are a little longer. [configure] is
 * called from the app's root whenever the window class is known or changes.
 */
object Motion {

    const val QUICK = 150
    const val STANDARD = 220
    const val SLOW = 320

    /** Durations are multiplied by this: 1 on a phone, a little more on a tablet. */
    @Volatile
    private var scale = 1f

    fun configure(wide: Boolean) {
        scale = if (wide) 1.15f else 1f
    }

    private fun ms(base: Int) = (base * scale).toInt()

    fun <T> quick(): TweenSpec<T> = tween(ms(QUICK), easing = FastOutSlowInEasing)

    fun <T> standard(): TweenSpec<T> = tween(ms(STANDARD), easing = FastOutSlowInEasing)

    fun <T> slow(): TweenSpec<T> = tween(ms(SLOW), easing = FastOutSlowInEasing)

    /** For things that follow a finger or settle after one (zoom, drag): no bounce, a calm stop. */
    fun <T> settle(): SpringSpec<T> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
}
