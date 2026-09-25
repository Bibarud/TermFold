package com.termfold.app.acp

import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the running ACP agent connections, keyed like [com.termfold.app.shell.TerminalHost]
 * sessions ("folderId/sessionId"). A connection — and its agent process — survives navigating
 * away, and is only released when its session is removed or the app itself goes away.
 */
object AcpSessions {

    private val clients = ConcurrentHashMap<String, AcpClient>()

    fun client(key: String): AcpClient? = clients[key]

    /** Every live agent chat, keyed like [TerminalHost] sessions ("folderId/sessionId"). */
    fun all(): Map<String, AcpClient> = HashMap(clients)

    fun put(key: String, client: AcpClient): AcpClient {
        clients[key] = client
        return client
    }

    /** Ends the agent process for [key], if one is running. */
    fun close(key: String) {
        clients.remove(key)?.close()
    }
}
