"""Fetches the CA bundle this app installs into the guest.

The Ubuntu base image carries the archive keyring but no certificate authorities, so every
https:// request from inside the guest fails until one is provided. Installing
`ca-certificates` with apt is not possible, because apt is the thing that needs working TLS.

The bundle is taken from the official `ca-certificates` package that Ubuntu 24.04 ships, so it is
the same set of authorities the distribution itself trusts.

Run from the repo root:  py -3 tools/fetch-ca-bundle.py
"""
import io
import lzma
import os
import re
import subprocess
import sys
import tarfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
WORK = os.path.join(ROOT, ".work")

POOL = "http://archive.ubuntu.com/ubuntu/pool/main/c/ca-certificates/"


def newest_deb():
    """Finds the newest ca-certificates .deb the mirror offers."""
    out = subprocess.run(
        ["curl", "-fsSL", "--max-time", "120", POOL],
        capture_output=True, check=True,
    ).stdout.decode("utf-8", "replace")
    names = re.findall(r'href="(ca-certificates_([0-9][^"]*)_all\.deb)"', out)
    if not names:
        raise SystemExit("no ca-certificates package found at " + POOL)
    # Sort by the version string; these are date-like and sort correctly as text.
    names.sort(key=lambda pair: pair[1])
    return names[-1][0]


def ar_members(path):
    with open(path, "rb") as handle:
        if handle.read(8) != b"!<arch>\n":
            raise SystemExit("not an ar archive: " + path)
        while True:
            header = handle.read(60)
            if len(header) < 60:
                return
            name = header[0:16].decode("ascii", "replace").strip().rstrip("/")
            size = int(header[48:58].decode("ascii").strip())
            data = handle.read(size)
            if size % 2:
                handle.read(1)
            yield name, data


def data_tar(deb):
    for name, data in ar_members(deb):
        if name.startswith("data.tar"):
            if name.endswith(".xz"):
                return lzma.decompress(data)
            if name.endswith(".gz"):
                import gzip
                return gzip.decompress(data)
            if name.endswith(".zst"):
                # Python 3.14 has zstd in the standard library; older versions need the `zstandard`
                # package, which is worth avoiding as a build dependency.
                try:
                    from compression import zstd
                except ImportError:
                    try:
                        import zstandard as zstd
                    except ImportError:
                        raise SystemExit(
                            "unpacking data.tar.zst needs Python 3.14+ or `pip install zstandard`"
                        )
                return zstd.decompress(data)
            return data
    raise SystemExit("no data.tar in " + deb)


def main():
    os.makedirs(ASSETS, exist_ok=True)
    os.makedirs(WORK, exist_ok=True)

    deb_name = newest_deb()
    deb = os.path.join(WORK, deb_name)
    if not os.path.isfile(deb):
        print("downloading", deb_name)
        subprocess.run(["curl", "-fsSL", "--max-time", "300", "-o", deb, POOL + deb_name],
                       check=True)

    # The package carries one PEM per authority under /usr/share/ca-certificates/mozilla, not a
    # concatenated bundle, so the bundle is assembled here the same way update-ca-certificates
    # would. Authorities only; the local ones are for private infrastructure.
    authorities = []
    with tarfile.open(fileobj=io.BytesIO(data_tar(deb))) as tf:
        for member in tf.getmembers():
            if not member.isfile():
                continue
            if "/mozilla/" in member.name and member.name.endswith(".crt"):
                authorities.append((member.name, tf.extractfile(member).read()))

    if not authorities:
        raise SystemExit("no certificates found inside " + deb_name)

    authorities.sort(key=lambda pair: pair[0])
    out = os.path.join(ASSETS, "ca-certificates.crt")
    with open(out, "wb") as handle:
        for _name, pem in authorities:
            handle.write(pem if pem.endswith(b"\n") else pem + b"\n")

    size = os.path.getsize(out)
    print(f"{deb_name} -> assets/ca-certificates.crt "
          f"({size} bytes, {len(authorities)} authorities)")


if __name__ == "__main__":
    sys.exit(main())
