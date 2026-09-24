package com.termfold.app.shell

import android.content.Context
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.File

/**
 * Creates the PTY-backed shell sessions that [TerminalHost] owns.
 *
 * `TerminalSession` forks and `execvp`s the command it is given, so the command is PRoot itself
 * and the guest shell is PRoot's own argument. That is what makes the terminal real rather than
 * simulated: the process on the far end of the pseudoterminal is an Ubuntu bash with a
 * controlling tty, its own job control, and full-screen curses output.
 */
object ShellSessions {

    /**
     * Starts an interactive Ubuntu shell rooted at [workspace], optionally running
     * [initialCommand] first so a preset such as `opencode` comes up in the right project.
     */
    fun start(
        context: Context,
        client: TerminalSessionClient,
        workspace: String?,
        initialCommand: String,
        sessionName: String,
    ): TerminalSession {
        val mounted = workspace != null && File(workspace).isDirectory

        // Mount the picked folder under its own name so the session begins where the user's
        // mental model is — a folder picked as "Pictures" starts at /Pictures, and the prompt
        // says so — instead of everything appearing as a generic /workspace.
        val guestMount = if (mounted) {
            "/" + ShellConfig.guestWorkspaceName(workspace)
        } else {
            ShellConfig.WORKSPACE
        }

        // Until the one-time environment setup has completed, every session runs it first, in
        // front of the user, then carries on to its prompt or preset.
        val needsSetup = !ShellSetup.isDone(context)
        // A session opened right after launch can beat the background refresh that installs the
        // script; installing here too (a few small files, skipped when unchanged) closes that gap.
        if (needsSetup) ShellSetup.install(context, ShellPaths.rootfsDir(context))

        val argv = buildList {
            add(ShellConfig.GUEST_SHELL)
            add("--login")

            // Only add the command form when there is a command. An empty `-c ""` argument is not
            // harmless: the arguments go through `env -i`, an empty string there is dropped, and
            // the invocation then becomes something bash does not accept.
            when {
                initialCommand.isNotBlank() -> {
                    add("-c")
                    val script = bootstrapScript(initialCommand)
                    add(if (needsSetup) ShellSetup.wrap(script) else script)
                }
                needsSetup -> {
                    add("-c")
                    add(ShellSetup.wrap("exec \"\$SHELL\" -l"))
                }
            }
        }

        val command = ProotCommand.build(
            context = context,
            workspace = workspace,
            guestMountPath = guestMount,
            argv = argv,
            guestCwd = if (mounted) guestMount else ShellConfig.GUEST_HOME,
        )

        return TerminalSession(
            /* shellPath = */ command.first(),
            /* cwd = */ context.filesDir.absolutePath,
            // The whole command line, including the program as argv[0].
            //
            // `TerminalSession` runs `execvp(shellPath, args)`, and `execvp` does not prepend the
            // program path to the argument vector. Dropping the first element here would leave
            // `-r` sitting in argv[0] and shift every PRoot option by one.
            /* args = */ command.toTypedArray(),
            /* env = */ ProotCommand.hostEnvironment(context)
                .map { (key, value) -> "$key=$value" }
                .toTypedArray(),
            /* transcriptRows = */ TRANSCRIPT_ROWS,
            /* client = */ client,
        ).also { it.mSessionName = sessionName }
    }

    /**
     * Wraps a preset command so the session always ends at a usable prompt.
     *
     * Two cases have to be handled, and both would otherwise leave a dead terminal:
     *
     *  - the binary is missing, which is the common one on a fresh environment, so the user is told
     *    what to install instead of being handed exit code 127;
     *  - the command finishes, which is normal for anything that is not a long-running TUI.
     *
     * In both cases an interactive shell is started at the end, so the session is never just over.
     */
    private fun bootstrapScript(command: String): String {
        val quoted = command.replace("'", "'\\''")
        return """
            __tf_cmd='$quoted'
            __tf_bin=${'$'}{__tf_cmd%% *}
            if ! command -v "${'$'}__tf_bin" >/dev/null 2>&1; then
              printf '\n\033[33m%s is not installed in this Ubuntu environment.\033[0m\n' "${'$'}__tf_bin"
              printf 'Install it with:  apt update && apt install -y <package>\n\n'
            else
              eval "${'$'}__tf_cmd"
              printf '\n\033[2m%s finished. Back to the shell.\033[0m\n\n' "${'$'}__tf_bin"
            fi
            # Hand the terminal to an interactive login shell so the session stays alive.
            exec "${'$'}SHELL" -l
        """.trimIndent()
    }

    /** Scrollback kept per session; enough to read a build log without re-running it. */
    private const val TRANSCRIPT_ROWS = 4000
}
