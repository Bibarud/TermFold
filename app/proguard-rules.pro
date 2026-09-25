# Keep line numbers useful in release crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# The terminal library is reached through JNI and through interfaces implemented on the Java side.
# R8 cannot see either edge, so it strips the classes and the app dies with NoClassDefFoundError the
# moment a session starts. The native method names in libtermux.so are also bound to these exact
# class and method names, so they cannot be renamed.
-keep class com.termux.terminal.** { *; }
-keep class com.termux.view.** { *; }
-keepclassmembers class com.termux.terminal.** { *; }
-keepclassmembers class com.termux.view.** { *; }

# The native library looks these up by name.
-keepclasseswithmembernames class com.termux.terminal.JNI {
    native <methods>;
}

# The manifest names this class, and it is instantiated by the platform.
-keep class com.termfold.app.TermFoldApp { *; }
-keep class com.termfold.app.MainActivity { *; }

# The terminal's colour table is a public static that the emulator reads reflectively.
-keep class com.termux.terminal.TerminalColors { *; }
-keep class com.termux.terminal.TerminalColorScheme { *; }

# The file editor's page calls back into these through addJavascriptInterface.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
