package com.termfold.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope

/**
 * A [LaunchedEffect] for work that only matters while the app is on screen (polling the disk,
 * looking for dev servers): it stops when the app goes to the background and starts again on
 * return. TermFold's process stays alive in the background for its sessions, and a plain
 * LaunchedEffect would keep polling there, costing battery and heat for nothing.
 */
@Composable
fun LaunchedWhileVisible(vararg keys: Any?, block: suspend CoroutineScope.() -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, *keys) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { block() }
    }
}
