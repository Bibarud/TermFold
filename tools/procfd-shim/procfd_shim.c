/*
 * LD_PRELOAD shim: make dlopen("/proc/self/fd/N") work under PRoot.
 *
 * Some agents (Google's .par bundles, e.g. Antigravity) write their embedded shared libraries to
 * a memfd and dlopen("/proc/self/fd/N"). Under PRoot, re-opening *any* file through a
 * /proc/self/fd link yields an empty read, even though stat() reports the right size, so the
 * loader fails with "file too short". Reading the descriptor itself works.
 *
 * So dlopen is interposed: a /proc/self/fd/N (or /proc/<pid>/fd/N) path is copied through the
 * descriptor into an ordinary temp file, which is loaded instead and then unlinked; the mapping
 * keeps the library alive. Every other dlopen goes straight through.
 *
 * Built by tools/build-procfd-shim.py into app/src/main/assets/procfd-shim-<abi>.so.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

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
        for (ssize_t w = 0; w < n;) {
            ssize_t m = write(dst, buf + w, (size_t) (n - w));
            if (m < 0 && errno == EINTR) continue;
            if (m < 0) goto fail;
            w += m;
        }
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
