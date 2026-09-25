package com.termfold.app.notify

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the user is looking at, process-wide: whether any TermFold window is on screen, which
 * folder or session it shows, and where a notification tap should take the app.
 *
 * Notifications use this to stay quiet about the session the user is already watching; the
 * bubble uses it to open where the user left off.
 */
object AppPresence {

    /** A place in the app: a folder, or a session inside one. */
    data class Place(val folderId: String, val sessionId: String? = null)

    /** The folder or session the main window (or the bubble) currently shows, if any. */
    @Volatile
    var place: Place? = null

    /** A short name for [place], e.g. "pi ACP · Demo", shown in the bubble's notification. */
    @Volatile
    var label: String = ""

    @Volatile
    private var started = 0

    /** True while at least one TermFold activity is visible. */
    val inForeground: Boolean get() = started > 0

    /** A notification or bubble asked the running app to show this place. */
    private val _requests = MutableStateFlow<Place?>(null)
    val requests: StateFlow<Place?> = _requests.asStateFlow()

    fun request(place: Place) {
        _requests.value = place
    }

    fun consume(place: Place) {
        if (_requests.value == place) _requests.value = null
    }

    /** True when [sessionKey] ("folderId/sessionId") is on screen right now. */
    fun isWatching(sessionKey: String): Boolean {
        val p = place ?: return false
        return inForeground && p.sessionId != null && "${p.folderId}/${p.sessionId}" == sessionKey
    }

    fun register(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                started++
            }

            override fun onActivityStopped(activity: Activity) {
                started = (started - 1).coerceAtLeast(0)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
