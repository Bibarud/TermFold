# Bundled Linux environment: findings

Reference notes from building the in-app Ubuntu environment. Kept in-tree so the next session does
not have to rediscover any of it.

## The shape that works

```
TerminalView / TerminalSession   (com.termux.termux-app 0.118.3, from JitPack)
  └── PRoot                       (Termux's build, invoked from nativeLibraryDir)
        └── Ubuntu 24.04 LTS      (official ubuntu-base image, extracted into filesDir)
```

No Termux app, no root, no PRoot-distro. Everything below was verified on an API 34 emulator.

## Constraints, and the failure each one causes

### 1. Execution is only permitted from `nativeLibraryDir`

Android has enforced W^X on app-private storage since Android 10: `execve` on anything under
`filesDir` or `cacheDir` fails however the mode bits are set. The one exception is
`nativeLibraryDir`, which the platform populates at install time from `jniLibs`, and only from
files named `lib*.so`.

So PRoot, its loader, `libtalloc`, `libandroid-shmem` and busybox all ship as `lib*.so` in
`jniLibs/<abi>/` and are invoked under those names. Nothing is copied out to a friendlier filename,
because anywhere friendlier is not executable.

Renaming `libtalloc.so.2` to `libtalloc.so` breaks PRoot's `DT_NEEDED` entry, so the string is
rewritten in place by `tools/fetch-shell-runtime.py`. It only gets shorter, so no ELF offset moves.

`useLegacyPackaging = true` is required. With the modern default the libraries stay compressed
inside the APK and `execve` fails even though `dlopen` would work.

### 2. `targetSdk` must be 28

At API 29+ the process runs in the `untrusted_app` domain, where `execve` on app data is denied
outright. Termux pins `targetSdk = 28` for exactly this reason. This is not a workaround that can be
traded away for a newer target.

### 3. Raw `fork()` is blocked in app processes

Android's seccomp policy answers `fork` with `ENOSYS` for app processes; apps are expected to use
`clone`, and the `Seccomp: 2` filter is visible on the process. This is *not* the same as "PRoot
does not work": glibc binaries use `clone`, so the whole Ubuntu guest runs fine.

What it does break is anything that calls `fork` directly, and BusyBox's `tar` does. The diagnostic
signature in `proot --verbose=4` output is distinctive:

```
vpid 1: sysexit end: fork(...) = 0x39        <- fork returned a child pid
vpid 1: seccomp SIGSYS: fork(...) = 0x39     <- then the trap arrived
Setting result after SIGSYS to -ENOSYS       <- PRoot overwrote a successful result
tar: fork: Function not implemented
```

The fix is to not shell out for extraction at all. `TarExtractor.kt` unpacks the rootfs in-process.
A useful side effect is that the rootfs is in place before any bundled executable has to prove it
runs, which makes provisioning failures much easier to attribute.

**`run-as` cannot be trusted for any of this.** It starts a process in a different mount namespace
and the more permissive `runas_app` domain, so it cannot see shared storage and reports
"Permission denied" whether or not the app can read anything. Several hours were lost to chasing
that. Only the app's own process gives a truthful answer.

### 4. The guest has no certificate authorities

The official `ubuntu-base` image ships `ubuntu-archive-keyring.gpg` but no CA bundle, so
`apt-get update` over https fails with:

```
Certificate verification failed: The certificate is NOT trusted.
W: ... No system certificates available. Try installing ca-certificates.
```

Installing `ca-certificates` with apt is circular — apt is the thing that needs TLS. And the
`ca-certificates` package does not contain a prebuilt bundle anyway; it ships one PEM per authority
plus the `update-ca-certificates` generator.

So `tools/fetch-ca-bundle.py` assembles the bundle from that package and the app unpacks it during
provisioning, pointing `SSL_CERT_FILE`, `SSL_CERT_DIR`, `CURL_CA_BUNDLE`, `REQUESTS_CA_BUNDLE`,
`NODE_EXTRA_CA_CERTS` and `GIT_SSL_CAINFO` at it. Every variable is set because the tools disagree
about which one to read.

The apt sources are deliberately left on **`http://`**. They are integrity- and origin-checked
through the archive keyring, so they work on first boot before the bundle exists. Switching them to
https during provisioning is what produces the "No system certificates available" failure.

### 5. `link(2)` is forbidden in app data

Android answers `link` with `EPERM` inside an app's own directory. This breaks dpkg completely:

```
dpkg: error: error creating new backup file '/var/lib/dpkg/status-old': Permission denied
E: Sub-process /usr/bin/dpkg returned an error code (2)
```

PRoot's `--link2symlink` extension, which `proot-distro` enables, fakes a hard link with a hidden
`.l2s.<name>` data file kept **in the folder of the first name**, every name becoming a symlink to
it. That is fragile: when a program replaces that folder (npm does on every install and update),
the data goes with it while other names still point there, and the program then fails with "not
found" or `EPERM` (PRoot refuses to follow such a link). Claude Code's 240 MB binary, hard-linked
by its postinstall into `bin/`, was lost exactly that way, and `claude` reinstalled on every run.

What TermFold does instead: `libtermfold-compat.so` (`tools/compat-shim/`) is preloaded into every
guest program through `/etc/ld.so.preload`, the mechanism a distribution would use. A `link()` or
`linkat()` the kernel refuses with `EPERM`/`EACCES` becomes an independent copy (a symlink stays a
symlink, mode and times kept, `EEXIST` and "no directories" preserved). dpkg, apt, npm's cache,
git, pip, tar and `cp -al` cannot tell the difference; only `st_nlink` stays 1. Only glibc reads
`ld.so.preload`, so musl and static programs are untouched. PRoot runs without `--link2symlink`;
existing installs are migrated once (`GuestCompat.migrateFakeLinks`), each fake link becoming a
real file, and keep the extension until that has finished.

The migration must read PRoot's link targets correctly: they hold **host** paths
(`/data/user/0/<app>/files/linux/rootfs/usr/bin/.l2s.perl0001`), not guest ones. A first version
resolved them as guest paths, took working links for broken ones and deleted their data (perl
among them). It now accepts both forms and never deletes a hidden file that any remaining name
still reaches (`GuestCompatTest`, run on Linux since Windows cannot create symlinks).

## Smaller things worth knowing

- **`execvp` does not prepend `argv[0]`.** `TerminalSession` calls `execvp(shellPath, args)`, so
  `args[0]` must be the program path. Passing `command.drop(1)` leaves a PRoot option sitting in
  `argv[0]` and shifts every option by one; the symptom is a silent immediate exit with no output.
- **`assets.open(name, mode)` takes an `ACCESS_*` constant, not a buffer size.** Passing `1 shl 16`
  throws `IllegalArgumentException: Bad access mode`.
- **`GZIPInputStream.skip` can over-skip**, which silently desynchronises a tar stream. The extractor
  always reads and discards instead.
- **A regular file's payload must not be both read and skipped.** Reading it in `writeFile` and then
  also running the generic `skipFully` desynchronises the stream; the failure surfaces hundreds of
  entries later as a nonsense filename. The padding still has to be skipped.
- **`File.setReadable`/`setWritable` throw "Bad access mode" on Android.** Only
  `setExecutable` is needed: everything extracted already belongs to the app's UID, and PRoot runs
  as that UID while faking a different identity to the guest.
- **`/etc/group` entries are `name:password:gid:members`.** Omitting the password field puts the GID
  in the wrong column and it never resolves. The IDs to add are the *host* supplementary groups
  (Android's `inet`, `sdcard_rw`, plus a per-install range derived from the app's UID), which is why
  they are read from `/proc/self/status` rather than hardcoded.
- **The terminal only repaints if told to.** The session writes from a background thread and merely
  notifies its client, so something has to call `TerminalView.onScreenUpdated()`. Without that the
  first frame renders and the terminal then looks frozen.
- **`jniLibs` only packages `.so` files**, so a versioned name like `libtalloc.so.2` is silently
  dropped. Stage it as `.so`.
- **Assets ending in `.gz` are decompressed by AGP.** The Ubuntu image is stored as `.bin` and added
  to `noCompress`, so the bytes arrive exactly as downloaded.
- **`PRoot` needs a writable `PROOT_TMP_DIR`** or forked children die with "can't fork". It also
  needs a writable `/dev/shm` inside the guest, which apt requires and Android's own is not.
- **`PRoot --sysvipc`** is needed for apt's lockless download methods.
- **`PRoot --kill-on-exit`** stops an interrupted agent leaving orphaned processes on a phone.
- **Never set `PROOT_NO_SECCOMP`, not even to `0`.** PRoot tests only `getenv(...) == NULL`, so
  any value disables the seccomp mode that rewrites Android-blocked `rename(2)` into `renameat`.
  Symptom: apt fails with "Problem renaming the file ... pkgcache.bin - rename (38: Function not
  implemented)".
- **IPv6 resolvers must not lead `resolv.conf`.** The emulator reports `fec0::3` first and it is
  unreachable from the guest, so every lookup fails with "Temporary failure resolving". Only IPv4
  resolvers are written, public fallbacks appended, and the file is refreshed per session.
- **Projects live in the guest, not in Android's shared storage.** `/sdcard` is FUSE-backed media
  storage: no symlinks, no hard links, no Unix permissions, nothing executable, and owned by the
  media UID (git: "detected dubious ownership"). Picked folders were bind-mounted in at first and
  every tool tripped over something. Projects are now plain directories in `~/projects`; files
  come in through the system pickers or "Share → TermFold", go out by export to a picked folder,
  share or "open with", and the home folder is offered to other apps through a
  `DocumentsProvider` that never serves hidden files (agent sign-ins live in `~/.claude`,
  `~/.codex`, `~/.ssh`). No storage permission is requested.
- **The Android folder picker cannot pick the root of Download or of the storage**, only a folder
  inside it (Android 11+ privacy rule). "Create new folder" in the picker is the way.
- **The base image has no tzdata**, and glibc treats an unknown `TZ` name as UTC. A POSIX
  fixed-offset `TZ` is passed until `tzdata` is installed.
- **Building on this Windows host**: if Gradle fails with "Unable to establish loopback
  connection", set `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\gtmp` (any short writable
  directory).

## Verified working

On an API 34 x86_64 emulator, from the app's own process:

- Ubuntu 24.04.5 LTS, `uname -a` reporting the Android kernel.
- `apt-get update` fetching 34.3 MB from the Ubuntu archive.
- `apt-get install git curl` installing git 2.43.0 and curl 8.5.0.
- `curl https://archive.ubuntu.com/ubuntu/` returning HTTP 200 through the installed CA bundle.
- A project folder bind-mounted at `/workspace`, readable and writable from the guest.
- Full-screen terminal with correct cursor keys, Ctrl combinations, and repaint.
