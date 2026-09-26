package com.termfold.app.shell

import android.content.Context
import android.util.Log
import com.termfold.app.acp.AcpInstaller
import java.io.File

/**
 * The one-time setup that turns the bare Ubuntu base image into a coding environment, and the
 * on-demand installers for the agent CLIs.
 *
 * The base image has no package lists, compiler, git or Node, so an agent CLI run straight after
 * installing the app fails. The setup itself is a shell script (assets/termfold-setup.sh) that the
 * first Shell session runs in front of the user, so progress and any failure are visible and it
 * can be re-run by hand with `termfold-setup`.
 *
 * The agent CLIs are not installed up front — together they are several hundred MB. Instead a
 * small stand-in for each lives in /usr/local/bin: the first `claude` (or `codex`, ...) installs the
 * real package with npm and hands over to it. npm puts the real binary in /opt/node/bin, which is
 * ahead of /usr/local/bin on PATH, so from then on the stand-in is never reached.
 */
object ShellSetup {

    private const val TAG = "ShellSetup"

    /** Guest path of the setup script. */
    const val SCRIPT = "/opt/termfold/setup.sh"

    /** Written by the script when it completes; bump the name to make every install re-run it. */
    private const val MARKER = "var/lib/termfold/setup-v1"

    /** Command name to npm package, for the on-demand installers. */
    private val CLIS = linkedMapOf(
        "claude" to "@anthropic-ai/claude-code",
        "codex" to "@openai/codex",
        "gemini" to "@google/gemini-cli",
        "opencode" to "opencode-ai",
        "pi" to "@earendil-works/pi-coding-agent",
        "qwen" to "@qwen-code/qwen-code",
    )

    fun isDone(context: Context): Boolean = File(ShellPaths.rootfsDir(context), MARKER).isFile

    /**
     * Installs the script, the `termfold-setup` command and the CLI stand-ins into the guest.
     * Cheap and idempotent: files are only rewritten when their content changed.
     */
    fun install(context: Context, rootfs: File) {
        runCatching {
            val script = context.assets.open("termfold-setup.sh").use { it.readBytes().decodeToString() }
                // A Windows checkout may have given the asset CRLF endings, which bash rejects.
                .replace("\r\n", "\n")
                .replace("@NODE_VERSION@", AcpInstaller.NODE_VERSION)
            writeExecutable(File(rootfs, SCRIPT.trimStart('/')), script)
            writeExecutable(
                File(rootfs, "usr/local/bin/termfold-setup"),
                "#!/bin/sh\n# Re-runs TermFold's environment setup.\nexec /bin/bash $SCRIPT --force \"\$@\"\n",
            )
            CLIS.forEach { (bin, pkg) ->
                writeExecutable(File(rootfs, "usr/local/bin/$bin"), standIn(bin, pkg))
            }
            installBrowserTools(context, rootfs)
        }.onFailure { Log.w(TAG, "Could not install the setup files", it) }
    }

    /** Where each agent reads its own global instructions; a marked section is kept up to date there. */
    private val AGENT_NOTES = listOf(
        "root/.codex/AGENTS.md",
        "root/.gemini/GEMINI.md",
        "root/.config/opencode/AGENTS.md",
        "root/.pi/agent/AGENTS.md",
        "root/.qwen/QWEN.md",
    )

    private const val NOTE_START = "<!-- termfold-browser:start -->"
    private const val NOTE_END = "<!-- termfold-browser:end -->"

    /**
     * The agent side of the preview browser: the `termfold-browser` command (also an MCP server),
     * a skill for Claude Code, and a short section in the other agents' global instructions.
     * Chat sessions get the MCP server directly when they start.
     */
    private fun installBrowserTools(context: Context, rootfs: File) {
        fun asset(name: String) = context.assets.open(name).use { it.readBytes().decodeToString() }.replace("\r\n", "\n")
        writeExecutable(File(rootfs, BROWSER_TOOL.trimStart('/')), asset("termfold-browser.js"))
        val skill = asset("termfold-browser-skill.md")
        writeIfChanged(File(rootfs, "root/.claude/skills/termfold-browser/SKILL.md"), skill)
        // The other agents get the same guide, without the skill header.
        val note = NOTE_START + "\n" + skill.substringAfter("\n---\n").trim() + "\n" + NOTE_END + "\n"
        AGENT_NOTES.forEach { path ->
            val file = File(rootfs, path)
            val current = if (file.isFile) file.readText() else ""
            val updated = if (current.contains(NOTE_START) && current.contains(NOTE_END)) {
                current.substringBefore(NOTE_START) + note + current.substringAfter(NOTE_END).trimStart('\n')
            } else {
                val gap = when {
                    current.isEmpty() || current.endsWith("\n\n") -> ""
                    current.endsWith("\n") -> "\n"
                    else -> "\n\n"
                }
                current + gap + note
            }
            writeIfChanged(file, updated)
        }
    }

    /** Guest path of the browser command; chat sessions start it as an MCP server. */
    const val BROWSER_TOOL = "/usr/local/bin/termfold-browser"

    private fun writeIfChanged(file: File, content: String) {
        if (file.isFile && file.readText() == content) return
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    /**
     * Prefixes a session's shell command with the setup when it has not completed yet. The
     * result is a `bash -c` script; [then] is what the session runs afterwards.
     */
    fun wrap(then: String): String =
        "/bin/bash $SCRIPT\n$then"

    /**
     * A stand-in for an npm-distributed CLI. Installs the package on first use, checks that the
     * result actually runs (npm silently skips a failed platform-specific optional dependency,
     * which leaves a binary that cannot start) and retries once, then execs the real thing.
     */
    private fun standIn(bin: String, pkg: String): String = """
        |#!/bin/sh
        |# TermFold: installs $pkg the first time `$bin` is run, then gets out of the way.
        |# npm puts the real $bin in /opt/node/bin, ahead of this file on PATH.
        |real=/opt/node/bin/$bin
        |if [ ! -x "${'$'}real" ]; then
        |  if [ ! -x /opt/node/bin/npm ]; then
        |    echo "Node.js is not set up yet. Run: termfold-setup" >&2
        |    exit 1
        |  fi
        |  printf '\033[38;5;208m==>\033[0m Installing %s (one time)\n' "$pkg"
        |  /opt/node/bin/npm install -g "$pkg" || { echo "Installing $pkg failed. Check the connection and run $bin again." >&2; exit 1; }
        |  if ! "${'$'}real" --version >/dev/null 2>&1; then
        |    echo "Retrying the install of $pkg..."
        |    /opt/node/bin/npm install -g "$pkg" >/dev/null 2>&1
        |  fi
        |fi
        |exec "${'$'}real" "${'$'}@"
        |""".trimMargin()

    private fun writeExecutable(file: File, content: String) {
        if (file.isFile && file.readText() == content) return
        file.parentFile?.mkdirs()
        // A symlink left by an earlier npm install (a real CLI) must not be overwritten.
        if (java.nio.file.Files.isSymbolicLink(file.toPath())) return
        file.writeText(content)
        file.setExecutable(true, false)
    }
}
