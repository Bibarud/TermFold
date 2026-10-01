# Staying compatible with Android: the target SDK question

Status: research and a first experiment, not implemented. Written 2026-10-01 against TermFold 2.3.

## Short answer

TermFold targets API 28 (Android 9). The reason it was built that way is that Android 10 and
later forbid an app that targets API 29+ from `execve`-ing files in its own storage, and the
Linux system lives there. That reasoning was incomplete.

**An experiment on a real tablet shows TermFold's Linux environment working with the app set to
target API 34.** The setup completed, and freshly built or copied programs, a freshly built
shared library and an npm package with a native binary all ran. The reason is that PRoot does not
`execve` guest programs; its own loader, which Android lets the app run from its native library
directory, maps them into memory instead.

So the dependency on the old target looks much weaker than we thought, and a path to a modern target
SDK exists. It is based on one device and one Android version, so it needs a wider test before it is
trusted (see "What is not known").

## Why this matters

- **Minimum installable target.** Android refuses to install new apps that target very old
  APIs. Android 14 blocks targets below 23, Android 15 and 16 block below 24
  ([matrix](https://bayton.org/android/android-minimum-targetsdk-matrix/)). 28 is four levels above
  the floor and the floor has moved about one level per release, so this is years away, but the
  direction is one way. Already installed apps are not removed.
- **Google Play** no longer accepts targets that old. This is one of several reasons TermFold is not
  on Play (see "Play and F-Droid" below).
- **The sandbox rules Android applies depend on the target.** Apps at 28 or lower run in an older,
  more permissive SELinux domain. If Android ever removes that domain, apps still at 28 lose it.
  Nothing announced says it will, but it is the real long-term risk.

The mitigation is to stop depending on the permissive domain, which the experiment suggests is
possible.

## What Android actually restricts

From the AOSP SELinux policy
([app_neverallows.te](https://android.googlesource.com/platform/system/sepolicy/+/master/private/app_neverallows.te)),
as summarised by search results and Android's
[Android 10 behaviour changes](https://developer.android.com/about/versions/10/behavior-changes-10):

- Apps targeting API 29+ cannot `execve` a file labelled `app_data_file` (anything under their own
  data directory). Apps at 28 or lower are exempt for compatibility.
- A separate rule treats `dlopen` of code from the data directory as a W^X violation for the same
  apps.
- The one place an app may execute from is its native library directory (`nativeLibraryDir`),
  filled at install time from `jniLibs` with files named `lib*.so`.

Termux is affected directly because it `execve`s programs in its own prefix. That is why Termux
stays at 28
([Termux and Android 10](https://github.com/termux/termux-packages/wiki/Termux-and-Android-10)) and
why it has a "system linker exec" mode, which starts a program through `/system/bin/linker64`
([execution environment](https://github.com/termux/termux-packages/wiki/Termux-execution-environment)).

## What was tested

| | |
| --- | --- |
| Device | Lenovo TB373FU (Idea Tab Pro), production build, release-keys |
| Android | 16 (API 36), security patch 2026-09-05, SELinux **Enforcing**, 4 KB pages |
| App under test | TermFold 2.3 built unchanged except `targetSdk = 34`, installed under a different package name next to the real app (no data shared) |
| Process domain | `u:r:untrusted_app:s0` (the current, restrictive domain, not the legacy one) |

How: a throwaway build was installed beside the real app, its first-run setup was run in the app's own
terminal session (so it ran in the app's real SELinux domain; `adb run-as` uses a different,
more permissive domain and would not have proven anything), then a script was run there.

| Test | Result |
| --- | --- |
| First-run setup: `apt-get update`, 145 packages (including gcc, python, git, build tools), Node download | completed, "Ready" |
| Compile a C program and run the new binary (an executable written by the app, then executed) | ran |
| Copy `/bin/ls` into a writable folder and run the copy | ran |
| Build a shared library and load it from Python (`dlopen` of a fresh file in app storage) | ran |
| `npm install esbuild` and run its native binary (the same pattern Claude Code uses) | ran |

The throwaway app was uninstalled afterwards.

## Why PRoot gets away with it

PRoot intercepts the guest's `execve` with ptrace. Instead of letting the kernel execute the target,
it makes the process execute PRoot's *loader*, a tiny static program that PRoot ships as
`libproot-loader.so` in the native library directory, which is allowed. The loader then opens the
guest program and maps its segments into memory itself, and jumps to the guest's dynamic linker.
The kernel never sees an `execve` of a file in app storage. Other apps rely on the same thing:
the PRoot-based app [pr](https://github.com/oonid/pr) targets 35, and UserLAnd, which uses PRoot, is on
Google Play.

## What is not known

- **Why the `dlopen` test passed.** The policy summary above says `dlopen` of app-data code is
  also denied for new targets, yet a freshly built `.so` in app storage loaded fine on this
  device. Possible explanations: the rule does not cover mappings made by a guest process's own
  linker, this device's policy differs, or the policy text was summarised wrongly. This was not
  investigated further. It matters because if a future Android enforces `mmap(PROT_EXEC)` of app data
  more strictly, the PRoot approach would stop working at *any* target.
- **Other Android versions and makers.** Only Android 16 on one Lenovo tablet was tested. Android
  10 to 15 and other vendors (Samsung, Xiaomi, Pixel) are untested.
- **The rest of the app at a higher target.** Only the Linux environment was exercised. Other
  behaviour changes (below) were not tried.
- **Whether Google Play would accept it.**

## Options

| Option | What it is | Verdict |
| --- | --- | --- |
| A. Stay at 28 | Status quo | Works today. Depends on Android keeping the legacy domain and the 28 install floor. Blocks Play. |
| **B. Raise the target, keep PRoot's loader** | What was tested | **Recommended.** Small change, verified once, no new moving parts. |
| C. System-linker exec (Termux's mode) | Start guest programs through `/system/bin/linker64` | Not needed with PRoot. Useful as a fallback for any program started outside PRoot. |
| D. Package every program as a native library | Turn Debian packages into `lib*.so` inside an APK | Fully compliant, but the system would stop being a normal `apt` system. A huge change. Only as a last resort if Android closes the loader route. |
| E. Android Virtualization Framework (Debian VM) | Use the platform's VM | Only a few Pixel-class devices ship it. Could be an optional extra backend later, never the main path. |
| F. Our own VM (QEMU) | Emulate a machine | Far slower. No. |

## Recommended plan

1. **Test more before changing anything.** Repeat the experiment (a side-by-side build with a
   package-name suffix and a higher target) on an emulator for each of API 29, 31, 33, 34, 35, 36
   (x86_64), and on at least one Samsung and one Xiaomi/Pixel device if available. Record
   the process domain and the results of the four tests above. Also read the current
   `app_neverallows.te` directly to answer the `dlopen` question.
2. **Raise the target to 34 in one step, and fix what that changes:**
   - The foreground service that keeps sessions alive (`KeepAliveService`) must declare a foreground
     service type and the matching permission, or `startForeground` throws on Android 14+.
   - Check each broadcast receiver and `PendingIntent` for the Android 14 export and mutability rules.
   - Re-run the full app on a tablet and a phone, including the browser, bubble, file provider and updater.
3. **Then 35 and 36.** Edge-to-edge is enforced at 35 (the app already enables it); Android 16 ignores
   fixed-orientation and resize restrictions on large screens for apps that target 36, so the layouts
   should be checked.
4. **16 KB memory pages.** Devices and the Play requirement for newer targets expect 64-bit native
   libraries to be aligned for 16 KB pages. Checked on 2026-10-01: all PRoot libraries and the arm64
   compatibility shim are already aligned (16 KB or 64 KB). **The x86_64 compatibility shim is aligned
   to 4 KB and must be rebuilt** (`tools/build-compat-shim.py`).
5. **Keep a fallback ready.** Keep the code that starts guest programs behind one place, so that if
   Android closes the loader route, option D or C can replace it.

## Play and F-Droid

Moving off target 28 removes one obstacle to Google Play, not all of them:

- Play's [Device and Network Abuse](https://support.google.com/googleplay/android-developer/answer/16559646?hl=en)
  policy forbids an app from updating itself by any method other than Play, and from downloading
  executable code (dex, JAR, `.so`) from elsewhere. The exemption is for code run in a virtual machine
  or interpreter with indirect access to Android APIs, and it is unclear whether a PRoot environment
  counts. TermFold's in-app updater and its `apt` and `npm` installs both touch this.
- The `REQUEST_INSTALL_PACKAGES` permission the updater uses is
  [restricted on Play](https://support.google.com/googleplay/android-developer/answer/12085295?hl=en)
  to apps whose core purpose is installing packages, and may not be used for self-updates.
- A Play build would therefore need to be a separate flavor without the updater, and acceptance cannot
  be predicted. UserLAnd's presence on Play is encouraging but not a guarantee.

F-Droid is a separate problem (every binary built from source; see issue 7) and is unchanged by the target SDK.

## Monitoring

Watch the platform source rather than waiting for a break:

- Changes to `private/app_neverallows.te` and `untrusted_app*.te` in AOSP `system/sepolicy`.
- Android developer previews and the "behavior changes" pages for each release.
- The Termux and UserLAnd issue trackers, which would hit any change first.

## References

- [Termux and Android 10](https://github.com/termux/termux-packages/wiki/Termux-and-Android-10)
- [Termux execution environment (system linker exec)](https://github.com/termux/termux-packages/wiki/Termux-execution-environment)
- [Android 10 behaviour changes](https://developer.android.com/about/versions/10/behavior-changes-10)
- [AOSP app_neverallows.te](https://android.googlesource.com/platform/system/sepolicy/+/master/private/app_neverallows.te)
- [Minimum target SDK by Android version](https://bayton.org/android/android-minimum-targetsdk-matrix/)
- [pr: PRoot app targeting 35](https://github.com/oonid/pr)
- [Google Play: Device and Network Abuse](https://support.google.com/googleplay/android-developer/answer/16559646?hl=en)
- [Google Play: REQUEST_INSTALL_PACKAGES](https://support.google.com/googleplay/android-developer/answer/12085295?hl=en)
- The engineering notes in `docs/SHELL-NOTES.md`
