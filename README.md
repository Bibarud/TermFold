# TermFold

An Android launcher that opens a terminal in any folder you pick, so shell tools and terminal
agents start in the right project instead of a home directory. The terminal is a real Ubuntu
environment running inside the app — no separate app to install, no root.

## What it does

Pick a folder with the system picker and TermFold stores it along with its real filesystem path.
Each folder holds a set of sessions: a plain shell, and presets for agents such as OpenCode and Pi.
Tapping one opens a full-screen terminal in-app, rooted at that folder.

## How it works

```
Your app (Compose)
  └── TerminalView + TerminalSession          (Termux's emulator, PTY-backed)
        └── PRoot                             (userspace chroot, from nativeLibraryDir)
              └── Ubuntu 24.04 LTS rootfs     (extracted into app-private storage, persistent)
                    └── /bin/bash  →  apt, git, node, opencode …
```

There is no Termux dependency and no separate app. Ubuntu is unpacked into the app's own storage
on first launch, and everything installed inside it with `apt` survives restarts and updates.

A picked project folder is bind-mounted into the guest at `/workspace`, so the shell and any agent
launched from it start in your project.

## Requirements

None beyond Android 8.0 (API 26). The first launch unpacks the bundled Ubuntu image, which takes a
few seconds; after that the environment is ready.

## Why this is built the way it is

Five constraints shape almost every file here, and each one cost real debugging time.

**Android only executes files in `nativeLibraryDir`.** Since Android 10 there is no `execve` on
anything under `filesDir`, whatever its mode. That location is populated only from `jniLibs`, and
only from files named `lib*.so`, so PRoot, its loader, its libraries and busybox all ship under
that naming scheme (`app/src/main/java/com/termfold/app/shell/ShellConfig.kt`).

**`targetSdk` must be 28.** Android only lets an app execute binaries from its own directory when
the app targets API 28 or lower; at 29+ the process runs in the `untrusted_app` domain and `execve`
is denied outright. This is exactly why Termux itself pins `targetSdk = 28`.

**`fork()` is blocked, `clone()` is not.** Android's seccomp policy makes the raw `fork` syscall
fail with `ENOSYS` for app processes. glibc binaries use `clone`, so the guest is fine — but
BusyBox's `tar` calls `fork` directly, so the rootfs cannot be unpacked by shelling out. It is
unpacked in-process instead (`TarExtractor.kt`), which is also why that file has no Android
dependencies and is covered by a JVM test.

**The guest has no certificate authorities.** The Ubuntu base image ships the archive keyring but
no CA bundle, so every `https://` request inside it fails with "No system certificates available".
Installing `ca-certificates` with apt is impossible, because apt is the thing that needs TLS. The
bundle is shipped as an asset, unpacked during provisioning, and pointed at through
`SSL_CERT_FILE`, `CURL_CA_BUNDLE` and friends.

**Hard links are forbidden in app data.** Android answers `link(2)` with `EPERM` inside an app's
own directory, which breaks dpkg: any `apt install` dies with "error creating new backup file
'/var/lib/dpkg/status-old'". PRoot's `--link2symlink` extension is the documented fix.

## Building

```bash
py -3 tools/fetch-shell-runtime.py    # PRoot + the Ubuntu image, into jniLibs/ and assets/
py -3 tools/fetch-ca-bundle.py        # the CA bundle, into assets/
./gradlew :app:assembleDebug
```

Both scripts are idempotent and cache their downloads under `.work/`. Their outputs are gitignored:
they are fetched build inputs, not source.

The debug APK is roughly 80 MB, because two Ubuntu images (arm64-v8a and x86_64) and the
executables ship uncompressed — compressed native libraries cannot be executed.

## Tests

```bash
./gradlew :app:testDebugUnitTest
```

`TarExtractorTest` runs the tar reader against the real Ubuntu image. A tar reader fails silently
(a stream that loses sync stops early, or drops entries, without throwing), so it is tested against
the shipped archive rather than a hand-made fixture. Symlink and colon-in-filename assertions are
skipped on Windows, which cannot represent them.

## Project layout

```
core/      models, ids, the ViewModel
data/      SAF tree-URI to real-path mapping, DataStore persistence
shell/     the bundled Linux environment
  ShellRuntime    provisioning: unpack the rootfs, install the CA bundle
  TarExtractor    the in-process rootfs unpacker (pure Kotlin, unit-tested)
  ProotCommand    builds the PRoot invocation and both environments
  ShellSessions   starts PTY-backed sessions
  TerminalHost    owns live sessions across navigation and rotation
  TerminalBridge  implements Termux's session and view clients
  ShellConfig     the fixed names and paths, and why they are fixed
  ShellTheme      the terminal colour table
ui/screens
  EmbeddedTerminal  hosts the TerminalView, wired for redraws
  TerminalScreen    header, terminal, and the extra key rows
ui/theme   colour tokens, typography, hand-built vector icons
ui/components  rows, tiles, nav bar, nav rail, glow background
```

State is a single `AppData` value persisted as JSON in Preferences DataStore; there is no database
because the data is small, read wholesale, and never queried by predicate.

## The terminal keyboard

A phone keyboard cannot produce most of what a shell needs, so the terminal screen carries two
scrollable rows of extra keys:

| Row | Keys |
| --- | --- |
| Modifiers and control | Ctrl, Alt, Esc, Tab, `^C`, `^D`, Paste, Enter |
| Navigation and symbols | Home, End, PgUp, PgDn, `\|`, `/`, `-`, ←, ↓, ↑, → |

Ctrl and Alt latch until pressed again, which is what makes `Ctrl+C` and `Alt+B` reachable. Paste
strips newlines: a multi-line paste would otherwise execute each line as it arrived.

## Notes and limits

- **CPU architecture.** PRoot only runs foreign binaries when a QEMU user-mode binary is supplied,
  and none is bundled, so the guest must match the host. `arm64-v8a` and `x86_64` are supported;
  on anything else the app says so instead of failing obscurely.
- **Folders must map to a real path.** Cloud and virtual providers (Google Drive and similar) have
  no directory a shell can `cd` into, so the folder detail screen says so rather than silently
  launching a session in the wrong place.
- **Storage permission is required.** Without it the folder is bind-mounted but every access inside
  the terminal returns "Permission denied", so the app requests it on first launch.
- **Not a virtual machine.** This is the Android kernel with a translated filesystem view, so
  kernel modules, raw sockets and `systemd` are unavailable. `apt`, language toolchains and
  full-screen TUIs all work.
- **Uninstalling removes the environment**, since it lives in app-private storage.
