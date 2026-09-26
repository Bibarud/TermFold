/*
 * libtermfold-compat: makes the Ubuntu guest behave like Ubuntu on a PC where Android differs.
 *
 * Preloaded into every guest program through /etc/ld.so.preload, exactly as a distribution
 * would ship a system-wide preload. Two things are fixed here:
 *
 * 1. Hard links. Android refuses link(2) inside an app's storage (EACCES/EPERM from SELinux).
 *    PRoot's --link2symlink used to fake them with a hidden ".l2s" data file kept in the
 *    directory of the first name, which silently breaks as soon as that directory is replaced:
 *    npm updating a package deleted Claude Code's 240 MB binary out from under the other name.
 *    Here a refused link() instead produces an independent copy of the file (a symlink stays a
 *    symlink), with the same mode and timestamps, and link()'s own rules kept: the new name
 *    must not exist, directories cannot be linked. Tools that hard-link to save space or to
 *    move files (dpkg, apt, npm's cache, git, pip, tar) cannot tell the difference; only
 *    st_nlink stays 1. A link that the kernel allows, or that fails for another reason
 *    (EXDEV, ENOENT, EEXIST, ...), is untouched.
 *
 * 2. dlopen("/proc/self/fd/N"). Under PRoot, re-opening a file through a /proc/self/fd link
 *    reads as empty, so bundles that load libraries from a memfd (Google's .par agents) fail
 *    with "file too short". Such a path is copied through the descriptor into a temp file,
 *    which is loaded instead.
 *
 * Built by tools/build-compat-shim.py into app/src/main/assets/compat-shim-<abi>.so.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

/* ---- Hard links ------------------------------------------------------------------------- */

/* The raw system call, so the real kernel answer is seen whatever libc does internally. */
static int sys_linkat(int olddirfd, const char *oldpath, int newdirfd, const char *newpath, int flags) {
    return (int) syscall(SYS_linkat, olddirfd, oldpath, newdirfd, newpath, flags);
}

static int write_all(int fd, const char *buf, size_t len) {
    while (len > 0) {
        ssize_t w = write(fd, buf, len);
        if (w < 0 && errno == EINTR) continue;
        if (w < 0) return -1;
        buf += w;
        len -= (size_t) w;
    }
    return 0;
}

/* Copies a regular file to a name that must not exist yet. Returns 0 or -1 with errno set. */
static int copy_regular(int olddirfd, const char *oldpath, int newdirfd, const char *newpath,
                        const struct stat *st, int follow) {
    int src = openat(olddirfd, oldpath, O_RDONLY | O_CLOEXEC | (follow ? 0 : O_NOFOLLOW));
    if (src < 0) return -1;
    /* O_EXCL gives link()'s EEXIST for an existing name, including a dangling symlink. */
    int dst = openat(newdirfd, newpath, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, (mode_t) (st->st_mode & 07777) | S_IWUSR);
    if (dst < 0) {
        int e = errno;
        close(src);
        errno = e;
        return -1;
    }
    size_t cap = 1 << 20;
    char *buf = malloc(cap);
    int rc = buf == NULL ? -1 : 0;
    if (buf == NULL) errno = ENOMEM;
    while (rc == 0) {
        ssize_t n = read(src, buf, cap);
        if (n < 0 && errno == EINTR) continue;
        if (n < 0) { rc = -1; break; }
        if (n == 0) break;
        if (write_all(dst, buf, (size_t) n) != 0) rc = -1;
    }
    free(buf);
    if (rc == 0) {
        /* Same permissions (the create mask may have trimmed them) and timestamps. */
        fchmod(dst, st->st_mode & 07777);
        struct timespec times[2] = { st->st_atim, st->st_mtim };
        futimens(dst, times);
    }
    int e = errno;
    close(src);
    if (close(dst) != 0 && rc == 0) { rc = -1; e = errno; }
    if (rc != 0) unlinkat(newdirfd, newpath, 0);
    errno = e;
    return rc;
}

/* What link() does on a filesystem that has no hard links: a copy. */
static int emulate_link(int olddirfd, const char *oldpath, int newdirfd, const char *newpath, int flags) {
    int follow = (flags & AT_SYMLINK_FOLLOW) != 0;
    struct stat st;
    if (fstatat(olddirfd, oldpath, &st, follow ? 0 : AT_SYMLINK_NOFOLLOW) != 0) return -1;
    if (S_ISDIR(st.st_mode)) { errno = EPERM; return -1; }
    if (S_ISLNK(st.st_mode)) {
        char target[PATH_MAX];
        ssize_t n = readlinkat(olddirfd, oldpath, target, sizeof(target) - 1);
        if (n < 0) return -1;
        target[n] = '\0';
        return symlinkat(target, newdirfd, newpath);
    }
    if (S_ISFIFO(st.st_mode)) return mkfifoat(newdirfd, newpath, st.st_mode & 07777);
    if (S_ISREG(st.st_mode)) return copy_regular(olddirfd, oldpath, newdirfd, newpath, &st, follow);
    /* Devices and sockets cannot be copied; report what the kernel said. */
    errno = EPERM;
    return -1;
}

int linkat(int olddirfd, const char *oldpath, int newdirfd, const char *newpath, int flags) {
    if (sys_linkat(olddirfd, oldpath, newdirfd, newpath, flags) == 0) return 0;
    if (errno != EPERM && errno != EACCES) return -1;
    int saved = errno;
    /* An empty oldpath (AT_EMPTY_PATH) names an open descriptor; leave that to the kernel. */
    if ((flags & AT_EMPTY_PATH) && oldpath[0] == '\0') { errno = saved; return -1; }
    return emulate_link(olddirfd, oldpath, newdirfd, newpath, flags);
}

int link(const char *oldpath, const char *newpath) {
    /* link(2) does not follow a symlink given as oldpath on Linux. */
    return linkat(AT_FDCWD, oldpath, AT_FDCWD, newpath, 0);
}

/* ---- dlopen("/proc/self/fd/N") ------------------------------------------------------------- */

typedef void *(*dlopen_fn)(const char *, int);

/* Returns the descriptor number a /proc/{self,<pid>}/fd/N path names, or -1. */
static int proc_fd(const char *path) {
    const char *rest;
    if (strncmp(path, "/proc/self/fd/", 14) == 0) {
        rest = path + 14;
    } else if (strncmp(path, "/proc/", 6) == 0) {
        char *end;
        long pid = strtol(path + 6, &end, 10);
        if (end == path + 6 || pid != (long) getpid() || strncmp(end, "/fd/", 4) != 0) return -1;
        rest = end + 4;
    } else {
        return -1;
    }
    char *end;
    long fd = strtol(rest, &end, 10);
    if (end == rest || *end != '\0' || fd < 0) return -1;
    return (int) fd;
}

/* Copies the whole of [fd] into a new temp file; returns its path in [out] or -1. */
static int copy_out(int fd, char *out, size_t cap) {
    const char *dir = getenv("TMPDIR");
    if (dir == NULL || *dir == '\0') dir = "/tmp";
    size_t len = strlen(dir);
    if (len + sizeof("/.dlfd-XXXXXX") > cap) return -1;
    memcpy(out, dir, len);
    memcpy(out + len, "/.dlfd-XXXXXX", sizeof("/.dlfd-XXXXXX"));
    int dst = mkostemp(out, O_CLOEXEC);
    if (dst < 0) return -1;

    char buf[1 << 16];
    off_t off = 0;
    for (;;) {
        ssize_t n = pread(fd, buf, sizeof(buf), off);
        if (n < 0 && errno == EINTR) continue;
        if (n < 0) goto fail;
        if (n == 0) break;
        if (write_all(dst, buf, (size_t) n) != 0) goto fail;
        off += n;
    }
    close(dst);
    return 0;
fail:
    close(dst);
    unlink(out);
    return -1;
}

void *dlopen(const char *path, int flags) {
    static dlopen_fn real;
    if (real == NULL) real = (dlopen_fn) dlsym(RTLD_NEXT, "dlopen");

    int fd = path != NULL ? proc_fd(path) : -1;
    if (fd < 0) return real(path, flags);

    char copy[4096];
    if (copy_out(fd, copy, sizeof(copy)) != 0) return real(path, flags);
    void *handle = real(copy, flags);
    unlink(copy);
    return handle;
}
