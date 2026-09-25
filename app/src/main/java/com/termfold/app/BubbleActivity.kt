package com.termfold.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.termfold.app.core.AppViewModel
import com.termfold.app.notify.AppPresence
import com.termfold.app.notify.Notifier
import com.termfold.app.ui.theme.TermFoldTheme

/**
 * The whole app in an Android bubble, floating over whatever the user is doing.
 *
 * It is the same UI as the main window and shares the running sessions with it (agents and
 * shells live in the process, not in a window), so the bubble opens on the exact chat or shell
 * the user left and they can still move around the app from there.
 */
class BubbleActivity : TermFoldActivity() {

    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Notifier.placeFrom(intent)?.let(AppPresence::request)
        setContent {
            TermFoldTheme {
                TermFoldRoot(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Notifier.placeFrom(intent)?.let(AppPresence::request)
    }
}
