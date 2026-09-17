"""Stages PRoot + its loaders into jniLibs, and the Ubuntu rootfs into assets.

Run from the repo root:  py -3 tools/fetch-shell-runtime.py

Why this is done the way it is
------------------------------
Android refuses to execve() anything under an app's filesDir/cacheDir (the W^X
policy), with exactly one exception: nativeLibraryDir. The only way to get a
file there is to place it in app/src/main/jniLibs/<abi>/ under a lib*.so name,
so every executable we bundle is renamed to fit that pattern and then copied out
to a real filename at first run.

Renaming libtalloc.so.2 to libtalloc.so means libproot.so can no longer resolve
its DT_NEEDED entry, so that string is rewritten in place (it only gets shorter,
so no ELF offsets move).
"""
import lzma
import os
import shutil
import struct
import subprocess
import sys
import tarfile
import io

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WORK = os.path.join(ROOT, ".work")
JNI = os.path.join(ROOT, "app", "src", "main", "jniLibs")
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")

TERMUX_POOL = "https://packages.termux.dev/apt/termux-main/pool/main"
# proot is built by Termux and is the only thing here that understands
# Android's seccomp policy and the loader hand-off.
PROOT_PKGS = {
    "arm64-v8a": {
        "proot": f"{TERMUX_POOL}/p/proot/proot_5.1.107.92_aarch64.deb",
        "talloc": f"{TERMUX_POOL}/libt/libtalloc/libtalloc_2.4.3_aarch64.deb",
        "shmem": f"{TERMUX_POOL}/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb",
    },
    "x86_64": {
        "proot": f"{TERMUX_POOL}/p/proot/proot_5.1.107.92_x86_64.deb",
        "talloc": f"{TERMUX_POOL}/libt/libtalloc/libtalloc_2.4.3_x86_64.deb",
        "shmem": f"{TERMUX_POOL}/liba/libandroid-shmem/libandroid-shmem_0.7_x86_64.deb",
    },
}

UBUNTU = {
    "arm64-v8a": "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/"
                 "ubuntu-base-24.04.5-base-arm64.tar.gz",
    "x86_64": "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/"
              "ubuntu-base-24.04.5-base-amd64.tar.gz",
}

# The rootfs is a single 30 MB archive shared by both ABIs *only* if we also
# ship per-ABI images, so each ABI gets its own asset named after the ABI.
def download(url, dest):
    if os.path.isfile(dest) and os.path.getsize(dest) > 0:
        print(f"  cached {os.path.basename(dest)}")
        return dest
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    print(f"  downloading {url}")
    subprocess.run(["curl", "-fsSL", "--retry", "3", "-o", dest, url], check=True)
    return dest


def ar_members(path):
    with open(path, "rb") as f:
        if f.read(8) != b"!<arch>\n":
            raise SystemExit(f"not an ar archive: {path}")
        while True:
            header = f.read(60)
            if len(header) < 60:
                return
            name = header[0:16].decode("ascii", "replace").strip().rstrip("/")
            size = int(header[48:58].decode("ascii").strip())
            data = f.read(size)
            if size % 2:
                f.read(1)
            yield name, data


def deb_file(deb, wanted):
    """Returns the bytes of one path inside a .deb's data.tar."""
    for name, data in ar_members(deb):
        if name.startswith("data.tar"):
            if name.endswith(".xz"):
                data = lzma.decompress(data)
            elif name.endswith(".gz"):
                import gzip
                data = gzip.decompress(data)
            with tarfile.open(fileobj=io.BytesIO(data)) as tf:
                prefix = "data/data/com.termux/files/usr/"
                for member in tf.getmembers():
                    name = member.name.lstrip("./")
                    if name == prefix + wanted:
                        return tf.extractfile(member).read()
    raise SystemExit(f"{wanted} not found in {deb}")


def patch_needed(path, old, new):
    """Rewrites a DT_NEEDED string in place. `new` must not be longer than `old`."""
    if len(new) > len(old):
        raise SystemExit("in-place patch can only shorten a string")
    data = bytearray(open(path, "rb").read())
    is64 = data[4] == 2
    if is64:
        phoff, phentsize, phnum = (struct.unpack_from("<Q", data, 32)[0],
                                  struct.unpack_from("<H", data, 54)[0],
                                  struct.unpack_from("<H", data, 56)[0])
    else:
        phoff, phentsize, phnum = (struct.unpack_from("<I", data, 28)[0],
                                  struct.unpack_from("<H", data, 42)[0],
                                  struct.unpack_from("<H", data, 44)[0])
    dynoff = dynsz = None
    for i in range(phnum):
        off = phoff + i * phentsize
        if struct.unpack_from("<I", data, off)[0] == 2:
            if is64:
                dynoff = struct.unpack_from("<Q", data, off + 8)[0]
                dynsz = struct.unpack_from("<Q", data, off + 32)[0]
            else:
                dynoff = struct.unpack_from("<I", data, off + 4)[0]
                dynsz = struct.unpack_from("<I", data, off + 16)[0]
    if dynoff is None:
        return False
    entsz = 16 if is64 else 8
    strtab = None
    targets = []
    for i in range(dynsz // entsz):
        off = dynoff + i * entsz
        if is64:
            tag = struct.unpack_from("<q", data, off)[0]
            val = struct.unpack_from("<Q", data, off + 8)[0]
        else:
            tag = struct.unpack_from("<i", data, off)[0]
            val = struct.unpack_from("<I", data, off + 4)[0]
        if tag == 0:
            break
        if tag == 5:
            strtab = val
        elif tag == 1:
            targets.append(val)
    if strtab is None:
        return False
    patched = False
    for value in targets:
        start = strtab + value
        end = data.index(b"\x00", start)
        if bytes(data[start:end]) == old.encode():
            data[start:start + len(old)] = new.encode() + b"\x00" * (len(old) - len(new))
            patched = True
    if patched:
        open(path, "wb").write(data)
    return patched


def stage_native():
    for abi, urls in PROOT_PKGS.items():
        print(f"[{abi}] native")
        dest = os.path.join(JNI, abi)
        os.makedirs(dest, exist_ok=True)
        cache = os.path.join(WORK, "debs")
        proot_deb = download(urls["proot"], os.path.join(cache, f"proot-{abi}.deb"))
        talloc_deb = download(urls["talloc"], os.path.join(cache, f"talloc-{abi}.deb"))
        shmem_deb = download(urls["shmem"], os.path.join(cache, f"shmem-{abi}.deb"))

        bins = os.path.join(dest, "libproot.so")
        with open(bins, "wb") as f:
            f.write(deb_file(proot_deb, "bin/proot"))
        with open(os.path.join(dest, "libtalloc.so"), "wb") as f:
            f.write(deb_file(talloc_deb, "lib/libtalloc.so.2.4.3"))
        with open(os.path.join(dest, "libandroid-shmem.so"), "wb") as f:
            f.write(deb_file(shmem_deb, "lib/libandroid-shmem.so"))
        # The loader is statically linked on purpose: it runs before any dynamic
        # linker exists inside the guest, and it is what makes exec from
        # nativeLibraryDir work at all.
        with open(os.path.join(dest, "libproot-loader.so"), "wb") as f:
            f.write(deb_file(proot_deb, "libexec/proot/loader"))

        if patch_needed(bins, "libtalloc.so.2", "libtalloc.so"):
            print("  patched DT_NEEDED libtalloc.so.2 -> libtalloc.so")
        else:
            print("  !! DT_NEEDED patch did not apply")

        for name in os.listdir(dest):
            os.chmod(os.path.join(dest, name), 0o755)


def stage_rootfs():
    os.makedirs(ASSETS, exist_ok=True)
    for abi, url in UBUNTU.items():
        print(f"[{abi}] rootfs")
        cache = os.path.join(WORK, "rootfs")
        # A neutral extension: AGP transparently gunzips assets that end in .gz,
        # which would silently change the bytes we ship.
        archive = download(url, os.path.join(cache, f"ubuntu-{abi}.tar.gz"))
        out = os.path.join(ASSETS, f"ubuntu-{abi}.bin")
        shutil.copyfile(archive, out)
        print(f"  -> assets/{os.path.basename(out)} "
              f"({os.path.getsize(out) / 1e6:.1f} MB)")


if __name__ == "__main__":
    os.makedirs(WORK, exist_ok=True)
    stage_native()
    stage_rootfs()
    print("done")
