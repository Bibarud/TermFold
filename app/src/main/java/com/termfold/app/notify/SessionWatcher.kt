package com.termfold.app.notify

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import com.termfold.app.R
import com.termfold.app.acp.AcpItem
import com.termfold.app.acp.AcpSessions
import com.termfold.app.shell.TerminalHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Watches every live session, agent chats and shells alike, and tells the user when one that
 * was working finishes or starts waiting on them, unless they are already looking at it.
 * It also keeps [KeepAliveService] running while there is work to protect and the app is not on
 * screen, so Android does not reclaim the process mid-task.
 *
 * Polling once a second is deliberate: it needs no hooks into every state change, costs next to
 * nothing, and the same signals already drive the working spinners in the session list.
 */
object SessionWatcher {

    /** A shell counts as done once it has been quiet this long after working. */
    private const val SHELL_QUIET_MS = 8_000L
    /** Short bursts (a prompt redraw, a quick `ls`) are not worth a notification. */
    private const val SHELL_MIN_WORK_MS = 20_000L

    private class AcpTrack(var busy: Boolean = false, var permissionId: Any? = null, var busySince: Long = 0)
    private class ShellTrack(var busySince: Long = 0, var lastActive: Long = 0)

    private val acp = HashMap<String, AcpTrack>()
    private val shells = HashMap<String, ShellTrack>()
    private var keepingAlive = false

    /** When something last needed the process kept alive; the notification lingers a while after. */
    private var lastActive = 0L

    /** Idle sessions are kept protected this long after the last real work, then released. */
    private const val IDLE_GRACE_MS = 5 * 60_000L

    /** Ends every agent chat and shell and removes the notification: what its Stop button does. */
    fun stopAll(context: Context) {
        runCatching { AcpSessions.all().keys.forEach { AcpSessions.close(it) } }
        runCatching { TerminalHost.liveSessions().forEach { TerminalHost.closeSession(it.key) } }
        KeepAliveService.stop(context)
        keepingAlive = false
    }

    fun start(app: Application) {
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            while (true) {
                runCatching { tick(app) }
                delay(1_000)
            }
        }
    }

    /** The terminal bell: a CLI agent asking for attention. */
    fun onBell(context: Context, sessionKey: String, title: String) {
        if (AppPresence.isWatching(sessionKey) || AppPresence.inForeground) return
        Notifier.notifyAttention(
            context,
            sessionKey,
            context.getString(R.string.notify_bell_title, title),
            context.getString(R.string.notify_bell_body),
        )
    }

    private fun tick(context: Context) {
        val now = System.currentTimeMillis()
        var live = 0
        var working = 0
        // Sessions that are doing something or waiting for the user (a permission prompt), as
        // opposed to one just sitting at an idle prompt, which needs nothing kept alive.
        var active = 0

        // ---- Agent chats
        val clients = AcpSessions.all()
        acp.keys.retainAll(clients.keys)
        for ((key, client) in clients) {
            val state = client.state.value
            val track = acp.getOrPut(key) { AcpTrack(busy = state.agentBusy, permissionId = state.pendingPermission?.requestId) }
            live++
            if (state.agentBusy) working++
            if (state.agentBusy || state.pendingPermission != null) active++
            val watching = AppPresence.isWatching(key)
            if (watching) Notifier.cancelFor(context, key)

            if (state.agentBusy && !track.busy) track.busySince = now
            if (!state.agentBusy && track.busy && !watching) {
                val reply = state.items.lastOrNull { it is AcpItem.AgentText } as? AcpItem.AgentText
                Notifier.notifyFinished(
                    context,
                    key,
                    context.getString(R.string.notify_done_title, client.agentName, client.folderLabel),
                    reply?.text?.let(::preview) ?: context.getString(R.string.notify_done_body),
                )
            }
            val permission = state.pendingPermission
            if (permission != null && permission.requestId != track.permissionId && !watching) {
                Notifier.notifyAttention(
                    context,
                    key,
                    context.getString(R.string.notify_permission_title, client.agentName),
                    state.items.lastOrNull { it is AcpItem.ToolCall && it.toolCallId == permission.toolCallId }
                        ?.let { (it as AcpItem.ToolCall).title }
                        ?: context.getString(R.string.notify_permission_body, client.folderLabel),
                )
            }
            track.busy = state.agentBusy
            track.permissionId = permission?.requestId
        }

        // ---- Shells (and the CLI agents running in them)
        val terminals = TerminalHost.liveSessions()
        shells.keys.retainAll(terminals.map { it.key }.toSet())
        for (t in terminals) {
            if (!t.running) continue
            live++
            val track = shells.getOrPut(t.key) { ShellTrack() }
            if (t.working) {
                working++
                active++
                if (track.busySince == 0L || now - track.lastActive > SHELL_QUIET_MS) track.busySince = now
                track.lastActive = now
                continue
            }
            val watching = AppPresence.isWatching(t.key)
            if (watching) Notifier.cancelFor(context, t.key)
            val workedFor = track.lastActive - track.busySince
            if (track.busySince != 0L && now - track.lastActive >= SHELL_QUIET_MS) {
                if (workedFor >= SHELL_MIN_WORK_MS && !watching) {
                    Notifier.notifyFinished(
                        context,
                        t.key,
                        context.getString(R.string.notify_shell_title, t.title, t.folderLabel),
                        context.getString(R.string.notify_shell_body),
                    )
                }
                track.busySince = 0L
            }
        }

        // ---- Keep the process alive while the user is elsewhere and sessions exist.
        if (active > 0 || AppPresence.inForeground) lastActive = now
        val wantAlive = live > 0 && !AppPresence.inForeground && (active > 0 || now - lastActive < IDLE_GRACE_MS)
        if (wantAlive) {
            KeepAliveService.update(context, active, working)
            keepingAlive = true
        } else if (keepingAlive) {
            KeepAliveService.stop(context)
            keepingAlive = false
        }
    }

    private fun preview(text: String): String {
        val flat = text.replace(Regex("[`*#>_]+"), "").replace(Regex("\\s+"), " ").trim()
        return if (flat.length > 240) flat.take(240).trimEnd() + "…" else flat
    }
}

/**
 * A foreground service with a quiet ongoing notification. Its only job is to tell Android that
 * TermFold is doing work the user asked for, so running agents and shells survive while the user
 * is in other apps or has swiped TermFold away.
 */
class KeepAliveService : android.app.Service() {

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val live = intent?.getIntExtra(EXTRA_LIVE, 0) ?: 0
        val working = intent?.getIntExtra(EXTRA_WORKING, 0) ?: 0
        startForeground(Notifier.RUNNING_ID, notification(this, live, working))
        return START_NOT_STICKY
    }

    companion object {
        private const val EXTRA_LIVE = "live"
        private const val EXTRA_WORKING = "working"
        private var shown: Pair<Int, Int>? = null

        fun update(context: Context, live: Int, working: Int) {
            if (shown == live to working) return
            shown = live to working
            val intent = Intent(context, KeepAliveService::class.java)
                .putExtra(EXTRA_LIVE, live)
                .putExtra(EXTRA_WORKING, working)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
            }
        }

        fun stop(context: Context) {
            shown = null
            runCatching { context.stopService(Intent(context, KeepAliveService::class.java)) }
        }

        private fun notification(context: Context, active: Int, working: Int): android.app.Notification {
            val stop = android.app.PendingIntent.getBroadcast(
                context,
                Notifier.RUNNING_ID + 1,
                Intent(context, StopSessionsReceiver::class.java).setAction(StopSessionsReceiver.ACTION),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
            return androidx.core.app.NotificationCompat.Builder(context, Notifier.CHANNEL_RUNNING)
                .setSmallIcon(R.drawable.ic_stat_termfold)
                .setColor(0xFFFF7A2E.toInt())
                // Only sessions that are really doing something are counted; one idle at its
                // prompt used to be reported as "running" for as long as it existed.
                .setContentTitle(
                    if (active > 0) context.resources.getQuantityString(R.plurals.running_title, active, active)
                    else context.getString(R.string.running_idle_title),
                )
                .setContentText(
                    if (active > 0) context.getString(R.string.running_active_text)
                    else context.getString(R.string.running_idle_text),
                )
                .addAction(0, context.getString(R.string.running_stop), stop)
                .setOngoing(true)
                .setSilent(true)
                .setShowWhen(false)
                .setContentIntent(Notifier.openIntent(context, AppPresence.place, requestCode = Notifier.RUNNING_ID))
                .build()
        }
    }
}

/** The notification's Stop button: ends every session, so nothing keeps running and the notification goes. */
class StopSessionsReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION) SessionWatcher.stopAll(context.applicationContext)
    }

    companion object {
        const val ACTION = "com.termfold.app.action.STOP_SESSIONS"
    }
}
