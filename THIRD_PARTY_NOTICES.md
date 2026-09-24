# Third-party notices

TermFold itself is licensed under the Apache License 2.0 (see [LICENSE](LICENSE)). The app, its
APK and this repository also contain the following third-party components, each under its own
license. Where a license requires the corresponding source to be available, it is linked below.

## Bundled executables (`app/src/main/jniLibs/`)

| Component | Version | License | Source |
| --- | --- | --- | --- |
| PRoot (Termux build): `libproot.so`, `libproot-loader.so` | 5.1.107.92 | GPL-2.0 | [termux/proot](https://github.com/termux/proot), packaging in [termux/termux-packages](https://github.com/termux/termux-packages/tree/master/packages/proot) |
| talloc: `libtalloc.so` | 2.4.3 | LGPL-3.0-or-later | [samba.org/ftp/talloc](https://www.samba.org/ftp/talloc/), packaging in [termux/termux-packages](https://github.com/termux/termux-packages/tree/master/packages/libtalloc) |
| libandroid-shmem: `libandroid-shmem.so` | 0.7 | BSD-3-Clause | [termux/libandroid-shmem](https://github.com/termux/libandroid-shmem) |

These are the unmodified Termux builds, taken from the packages at
`https://packages.termux.dev/apt/termux-main/pool/main` by `tools/fetch-shell-runtime.py`. The only
change is that `libproot.so`'s `DT_NEEDED` entry for `libtalloc.so.2` is rewritten in place to
`libtalloc.so` so Android installs it (see the script). TermFold runs PRoot as a separate program;
it does not link against it.

## Bundled data (`app/src/main/assets/`)

| Component | License | Source |
| --- | --- | --- |
| Ubuntu 24.04.5 base image (`ubuntu-*.bin`) | Various free licenses (see `/usr/share/doc/*/copyright` inside the image) | [cdimage.ubuntu.com/ubuntu-base](https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/); package sources at [launchpad.net/ubuntu](https://launchpad.net/ubuntu) |
| Mozilla CA certificate bundle (`ca-certificates.crt`) | MPL-2.0 | Ubuntu's [`ca-certificates`](https://launchpad.net/ubuntu/+source/ca-certificates) package |

Ubuntu is a registered trademark of Canonical Ltd. TermFold is not affiliated with or endorsed by
Canonical.

## Libraries

| Library | License |
| --- | --- |
| Termux `terminal-emulator` and `terminal-view` 0.118.3 | Apache-2.0 |
| AndroidX, Jetpack Compose, Kotlin, kotlinx.coroutines | Apache-2.0 |
| Coil (image loading, SVG) | Apache-2.0 |
| Apache Commons Compress | Apache-2.0 |

## Fonts (`app/src/main/res/font/`)

| Font | License |
| --- | --- |
| Outfit | SIL Open Font License 1.1 |
| JetBrains Mono | SIL Open Font License 1.1 |

## Agents

TermFold does not bundle any AI agent. Agents (Claude Code, Codex, Gemini CLI, OpenCode, Cursor,
Devin, Pi, omp, Google Antigravity and others) are downloaded from their official sources when you
first use them, under their own licenses and terms. All product names are trademarks of their
respective owners; TermFold is not affiliated with any of them.
