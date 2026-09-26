"""Cross-compiles tools/compat-shim/termfold_compat.c into app/src/main/assets/compat-shim-<abi>.so.

The library is preloaded into every program in the Ubuntu guest (see the comment at the top of
the C file). Zig is used as the cross-compiler so this runs on any host:

    py -3 -m pip install ziglang
    py -3 tools/build-compat-shim.py

The outputs are a few KB and are committed, so a normal build does not need Zig.
"""
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOURCE = os.path.join(ROOT, "tools", "compat-shim", "termfold_compat.c")
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")

# Android ABI -> Zig target. glibc 2.17 is the floor, so the library loads in any Ubuntu guest.
TARGETS = {
    "arm64-v8a": "aarch64-linux-gnu.2.17",
    "x86_64": "x86_64-linux-gnu.2.17",
}


def main():
    for abi, target in TARGETS.items():
        out = os.path.join(ASSETS, f"compat-shim-{abi}.so")
        subprocess.run(
            [sys.executable, "-m", "ziglang", "cc", "-target", target, "-shared", "-fPIC",
             "-O2", "-s", "-Wall", "-o", out, SOURCE, "-ldl"],
            check=True,
        )
        print(f"  {out} ({os.path.getsize(out)} bytes)")


if __name__ == "__main__":
    main()
