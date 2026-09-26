package com.termfold.app.shell

/**
 * Fixed names and paths for the bundled Linux environment.
 *
 * Several of these values are load-bearing and non-obvious, so they live in one place.
 *
 * Android enforces W^X on app-private storage: since Android 10 nothing under `filesDir` or
 * `cacheDir` may be `execve`d, whatever its permission bits. The single exception is
 * `nativeLibraryDir`, which the platform populates at install time from `jniLibs`. The only way
 * to get a file there is to ship it under a `lib*.so` name, which is why every executable here is
 * named `lib*.so` and invoked under that name rather than being copied somewhere friendlier.
 */
object ShellConfig {

    /** Root of everything the bundled environment owns, inside the app's private storage. */
    const val ROOT_DIR = "linux"

    /** The Ubuntu 24.04 LTS root filesystem, extracted here and never written anywhere else. */
    const val ROOTFS_DIR = "rootfs"

    /** Writable scratch for PRoot and for package-manager temporaries. */
    const val TEMP_DIR = "tmp"

    // --- Files inside nativeLibraryDir. These are the only executables we may run. --------------

    /** PRoot itself, built by Termux so it understands Android's seccomp policy. */
    const val NATIVE_PROOT = "libproot.so"

    /**
     * PRoot's loader, statically linked on purpose.
     *
     * PRoot does not `execve` guest binaries directly; it injects this loader and lets it bring
     * the guest binary up. Without it every guest program fails with "Permission denied".
     */
    const val NATIVE_LOADER = "libproot-loader.so"

    /** In-guest home directory for the root user. */
    const val GUEST_HOME = "/root"

    /** In-guest path to the shell every session starts. */
    const val GUEST_SHELL = "/bin/bash"

    /** Bundled rootfs archives, one per ABI, stored with a neutral extension. */
    fun rootfsAsset(abi: String): String = "ubuntu-$abi.bin"

    /**
     * A rootfs must match the host CPU: PRoot only runs foreign binaries when a QEMU user-mode
     * binary is supplied, and none is bundled.
     */
    fun rootfsAbi(abi: String): String? = when (abi) {
        "arm64-v8a", "x86_64" -> abi
        else -> null
    }
}
