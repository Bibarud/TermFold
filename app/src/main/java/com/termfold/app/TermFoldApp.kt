package com.termfold.app

import android.app.Application

class TermFoldApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // The emulator reads its colour table from a static, so it has to be in place before the
        // first session is created or the terminal renders in the library's own palette.
        com.termfold.app.shell.ShellTheme.apply()
    }
}
