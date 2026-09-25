package com.termfold.app.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.termfold.app.BubbleActivity
import com.termfold.app.MainActivity
import com.termfold.app.R

/**
 * Everything TermFold posts to the notification shade:
 *
 *  - "finished" and "needs you" alerts for agents and shells working in the background;
 *  - the bubble: the whole app floating over other apps, opened where the user left off;
 *  - the quiet ongoing notification of [KeepAliveService], which keeps sessions running.
 */
object Notifier {

    const val EXTRA_FOLDER = "com.termfold.app.extra.FOLDER"
    const val EXTRA_SESSION = "com.termfold.app.extra.SESSION"

    private const val CHANNEL_DONE = "agent_done"
    private const val CHANNEL_ATTENTION = "agent_attention"
    private const val CHANNEL_BUBBLE = "bubble"
    const val CHANNEL_RUNNING = "running"

    private const val BUBBLE_ID = 1001
    const val RUNNING_ID = 1002
    private const val SHORTCUT_ID = "termfold"

    private const val PREFS = "termfold_prefs"
    private const val KEY_BUBBLES = "bubble_on_leave"
    private const val KEY_NOTIFY = "notify_when_done"

    // ---- Preferences --------------------------------------------------------------------------

    fun bubblesEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_BUBBLES, true)
    fun setBubblesEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_BUBBLES, on).apply()
        if (!on) cancelBubble(context)
    }

    fun notificationsEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_NOTIFY, true)
    fun setNotificationsEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_NOTIFY, on).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- Channels -----------------------------------------------------------------------------

    /**
     * Creates the channels. On Android 13+ the first channel an app creates while on screen is
     * what makes the system ask for the notification permission, so this runs from the activity.
     */
    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_DONE, context.getString(R.string.channel_done), NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = context.getString(R.string.channel_done_desc) },
                NotificationChannel(CHANNEL_ATTENTION, context.getString(R.string.channel_attention), NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = context.getString(R.string.channel_attention_desc) },
                NotificationChannel(CHANNEL_BUBBLE, context.getString(R.string.channel_bubble), NotificationManager.IMPORTANCE_DEFAULT)
                    .apply {
                        description = context.getString(R.string.channel_bubble_desc)
                        setSound(null, null)
                        enableVibration(false)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setAllowBubbles(true)
                    },
                NotificationChannel(CHANNEL_RUNNING, context.getString(R.string.channel_running), NotificationManager.IMPORTANCE_LOW)
                    .apply {
                        description = context.getString(R.string.channel_running_desc)
                        setShowBadge(false)
                    },
            ),
        )
    }

    fun canNotify(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    // ---- Alerts -------------------------------------------------------------------------------

    /** An agent or shell finished its work. Tapping opens that session. */
    fun notifyFinished(context: Context, sessionKey: String, title: String, text: String) {
        post(context, CHANNEL_DONE, sessionKey, title, text)
    }

    /** An agent is waiting on the user (a permission prompt, or the terminal bell). */
    fun notifyAttention(context: Context, sessionKey: String, title: String, text: String) {
        post(context, CHANNEL_ATTENTION, sessionKey, title, text)
    }

    /** The user opened the session; its alert has served its purpose. */
    fun cancelFor(context: Context, sessionKey: String) {
        NotificationManagerCompat.from(context).cancel(sessionKey, 0)
    }

    private fun post(context: Context, channel: String, sessionKey: String, title: String, text: String) {
        if (!notificationsEnabled(context) || !canNotify(context)) return
        val place = placeOf(sessionKey) ?: return
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_termfold)
            .setColor(0xFFFF7A2E.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(if (channel == CHANNEL_DONE) NotificationCompat.CATEGORY_STATUS else NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openIntent(context, place, requestCode = sessionKey.hashCode()))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(sessionKey, 0, notification) }
    }

    // ---- The bubble ---------------------------------------------------------------------------

    /** Whether Android will actually show TermFold as a bubble right now. */
    fun bubblesAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            when (nm.bubblePreference) {
                NotificationManager.BUBBLE_PREFERENCE_ALL -> true
                NotificationManager.BUBBLE_PREFERENCE_SELECTED ->
                    nm.getNotificationChannel(CHANNEL_BUBBLE, SHORTCUT_ID)?.canBubble() == true
                else -> false
            }
        } else {
            @Suppress("DEPRECATION")
            nm.areBubblesAllowed()
        }
    }

    /** Android's own screen where the user lets TermFold float as a bubble. */
    fun bubbleSettingsIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_BUBBLE_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Floats the app as a bubble that opens at [place]. When Android lets TermFold bubble, only
     * the bubble appears; when the user has limited bubbles to chosen conversations, the
     * notification shows in the shade too, where its bubble button turns it into one.
     */
    fun showBubble(context: Context, place: AppPresence.Place, label: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !bubblesEnabled(context) || !canNotify(context)) return
        val app = Person.Builder()
            .setName(context.getString(R.string.app_name))
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .setBot(true)
            .setImportant(true)
            .setKey(SHORTCUT_ID)
            .build()

        // Bubbles belong to conversations, and a conversation is a long-lived shortcut.
        runCatching {
            ShortcutManagerCompat.pushDynamicShortcut(
                context,
                ShortcutInfoCompat.Builder(context, SHORTCUT_ID)
                    .setShortLabel(context.getString(R.string.app_name))
                    .setLongLived(true)
                    .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
                    .setIntent(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW))
                    .setPerson(app)
                    .setIsConversation()
                    .build(),
            )
        }

        val bubbleIntent = PendingIntent.getActivity(
            context,
            BUBBLE_ID,
            Intent(context, BubbleActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .putExtra(EXTRA_FOLDER, place.folderId)
                .putExtra(EXTRA_SESSION, place.sessionId),
            // Android requires a bubble's intent to be mutable.
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0),
        )
        val allowed = bubblesAllowed(context)
        val metadata = NotificationCompat.BubbleMetadata.Builder(
            bubbleIntent,
            IconCompat.createWithResource(context, R.mipmap.ic_launcher),
        )
            .setDesiredHeight(640)
            .setAutoExpandBubble(false)
            .setSuppressNotification(allowed)
            .build()

        val you = Person.Builder().setName(context.getString(R.string.bubble_you)).build()
        val notification = NotificationCompat.Builder(context, CHANNEL_BUBBLE)
            .setSmallIcon(R.drawable.ic_stat_termfold)
            .setColor(0xFFFF7A2E.toInt())
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(label)
            .setStyle(
                NotificationCompat.MessagingStyle(you)
                    .addMessage(label, System.currentTimeMillis(), app),
            )
            .setShortcutId(SHORTCUT_ID)
            .setLocusId(androidx.core.content.LocusIdCompat(SHORTCUT_ID))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setBubbleMetadata(metadata)
            .setContentIntent(openIntent(context, place, requestCode = BUBBLE_ID + 1))
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(BUBBLE_ID, notification) }
    }

    fun cancelBubble(context: Context) {
        NotificationManagerCompat.from(context).cancel(BUBBLE_ID)
    }

    // ---- Intents ------------------------------------------------------------------------------

    /** Opens the main window at [place], reusing it when it is already running. */
    fun openIntent(context: Context, place: AppPresence.Place?, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (place != null) {
            intent.putExtra(EXTRA_FOLDER, place.folderId).putExtra(EXTRA_SESSION, place.sessionId)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun placeFrom(intent: Intent?): AppPresence.Place? {
        val folder = intent?.getStringExtra(EXTRA_FOLDER) ?: return null
        return AppPresence.Place(folder, intent.getStringExtra(EXTRA_SESSION))
    }

    /** Session keys are "folderId/sessionId". */
    fun placeOf(sessionKey: String): AppPresence.Place? {
        val slash = sessionKey.indexOf('/')
        if (slash <= 0) return null
        return AppPresence.Place(sessionKey.substring(0, slash), sessionKey.substring(slash + 1))
    }
}
