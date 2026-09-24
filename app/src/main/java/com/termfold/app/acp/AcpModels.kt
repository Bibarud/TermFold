package com.termfold.app.acp

/**
 * One entry of the ACP registry: a coding agent that can be driven over the Agent Client
 * Protocol. The registry (cdn.agentclientprotocol.com) is the same index Zed and JetBrains
 * consume, so anything listed here is installable and runnable inside the bundled Ubuntu
 * environment.
 */
data class AcpAgent(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val iconUrl: String,
    val repository: String,
    val kind: Kind,
    /** linux-* archive URL, for [Kind.BINARY] agents. */
    val archiveUrl: String?,
    /** Expected sha256 of the archive, for [Kind.BINARY] agents. */
    val archiveSha256: String?,
    /**
     * Program to launch, exactly as the registry spells it (e.g. "./opencode", "./bin/devin").
     * For [Kind.BINARY] it is a path relative to the archive root and may sit in a
     * subdirectory; [Kind.NPX] and [Kind.UVX] agents are launched through their package
     * runner instead, so this stays null.
     */
    val cmd: String?,
    /** Arguments appended to [cmd] (e.g. ["acp"]). */
    val args: List<String>,
    /** npm package spec, for [Kind.NPX] agents (e.g. "pi-acp@0.0.33"). */
    val npxPackage: String?,
    /**
     * Extra arguments the package distribution passes to the package itself — npx entries
     * (e.g. ["--acp"]) and uvx entries (e.g. ["-x"]) alike.
     */
    val packageArgs: List<String>,
    /** uv package spec, for [Kind.UVX] agents (e.g. "fast-agent-acp==0.10.1"). */
    val uvxPackage: String? = null,
    /** Environment the registry requires the agent process to run with (uvx distributions). */
    val env: Map<String, String> = emptyMap(),
) {
    enum class Kind { BINARY, NPX, UVX }
}
