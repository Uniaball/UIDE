#!/usr/bin/env python3
"""Assemble the UIDE native toolchain bootstrap.

Downloads prebuilt Termux packages (clang / cmake / ninja / make) plus the link
stubs from a local Android NDK and packs them into a single
``bootstrap-<abi>.bin`` (a gzip stream, so the ``.bin`` suffix keeps AGP from
gunzipping it while merging assets) that the app extracts into its data
directory.

The Termux binaries are compiled with ``/data/data/com.termux/files/usr`` baked
in, so this script deliberately does NOT try to patch them. Relocation is done
at runtime instead:

* ``LD_LIBRARY_PATH=<prefix>/lib`` resolves every shared library,
* ``PATH`` / ``PREFIX`` / ``TERMUX_PREFIX`` cover the remaining lookups,
* the CMake toolchain file passes ``--target`` and ``--sysroot`` explicitly,
  which overrides clang's compiled-in default sysroot.

Anything that still references the Termux prefix is reported at the end of the
run so it can be verified on a device.
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import io
import json
import re
import sys
import tarfile
import tempfile
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

TERMUX_MIRRORS = [
    "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main",
    "https://packages.termux.dev/apt/termux-main",
    "https://mirrors.ustc.edu.cn/termux/apt/termux-main",
]
DEFAULT_PACKAGES = ["clang", "cmake", "ninja", "make"]
SELF_PATH = b"/data/data/com.termux/files/usr"
# Some Termux packages (ndk-sysroot) install through an absolute path.
ABS_PREFIX_RE = re.compile(r"^(?:\./)?data/data/[^/]+/files/(.*)$")

DROP_PREFIXES = (
    "usr/share/man/",
    "usr/share/doc/",
    "usr/share/info/",
    "usr/share/locale/",
    "usr/share/bash-completion/",
    "usr/share/zsh/",
    "usr/share/vim/",
    "usr/lib/cmake/",
    "usr/lib/pkgconfig/",
    # LLVM/Clang development headers: ~70 MiB that user code never needs.
    "usr/include/llvm",
    "usr/include/clang",
    "usr/include/sanitizer",
    # Static library variants of the shared objects we already ship.
    "usr/lib/liblld",
    "usr/lib/libLLVM-",
)
DROP_EXACT = {
    "usr/share/cmake/README.rst",
    # Only needed by clang-format / clang-tidy wrappers that we do not ship.
    "usr/lib/libclang.so",
    "usr/lib/libclang-cpp.a",
}
KEEP_MARKERS = ("usr/share/cmake-",)

# Everything under usr/bin that the build actually needs. Dropping the rest
# (llvm-exegesis alone is 54 MiB) keeps the bootstrap two orders of magnitude
# smaller than the full Termux package set.
KEEP_BIN = {
    "clang",
    "clang++",
    "clang-21",
    "clang-cpp",
    "cc",
    "c++",
    "cpp",
    "gcc",
    "g++",
    "ld",
    "ld.lld",
    "lld",
    "ar",
    "ranlib",
    "strip",
    "nm",
    "objcopy",
    "objdump",
    "readelf",
    "size",
    "strings",
    "addr2line",
    "elfedit",
    "make",
    "cmake",
    "ninja",
    "pkg-config",
}
KEEP_BIN_PREFIXES = ("llvm-ar", "llvm-ranlib", "llvm-strip", "llvm-nm", "llvm-objcopy",
                     "llvm-objdump", "llvm-readelf", "llvm-size", "llvm-strings",
                     "aarch64-linux-android-")

TOOLCHAIN_CMAKE = """# UIDE Android toolchain file.
#
# Compiles for the ABI of the device UIDE runs on, using the clang that ships in
# UIDE's own bootstrap directory. Everything is referenced relative to this file
# so the bootstrap can live anywhere.

if(NOT DEFINED UIDE_PREFIX)
  get_filename_component(UIDE_PREFIX "${CMAKE_CURRENT_LIST_DIR}/../.." ABSOLUTE)
endif()

set(CMAKE_SYSTEM_NAME Android)
set(CMAKE_SYSTEM_VERSION 24)
set(ANDROID_ABI aarch64)
set(ANDROID_PLATFORM android-24)

set(CMAKE_SYSROOT "${UIDE_PREFIX}")
set(CMAKE_FIND_ROOT_PATH "${UIDE_PREFIX}")

set(CMAKE_C_COMPILER "${UIDE_PREFIX}/bin/clang")
set(CMAKE_CXX_COMPILER "${UIDE_PREFIX}/bin/clang++")
set(CMAKE_ASM_COMPILER "${UIDE_PREFIX}/bin/clang")
set(CMAKE_AR "${UIDE_PREFIX}/bin/llvm-ar" CACHE FILEPATH "")
set(CMAKE_RANLIB "${UIDE_PREFIX}/bin/llvm-ranlib" CACHE FILEPATH "")
set(CMAKE_STRIP "${UIDE_PREFIX}/bin/llvm-strip" CACHE FILEPATH "")

set(CMAKE_C_COMPILER_TARGET aarch64-linux-android24)
set(CMAKE_CXX_COMPILER_TARGET aarch64-linux-android24)
set(CMAKE_ASM_COMPILER_TARGET aarch64-linux-android24)

# libc++ must come first: <cstdio> and friends verify that they picked up
# libc++' own <stdio.h> wrapper rather than the C header of the same name.
set(UIDE_INCLUDES "-isystem ${UIDE_PREFIX}/include/c++/v1 -isystem ${UIDE_PREFIX}/include/aarch64-linux-android -isystem ${UIDE_PREFIX}/include")

# clang resolves its own resource directory (stddef.h, stdarg.h, arm_neon.h, the
# compiler-rt builtins) from its compile-time default, which points at the
# Termux prefix. Discover the real one instead of hardcoding a version.
file(GLOB uide_clang_dirs "${UIDE_PREFIX}/lib/clang/*")
foreach(uide_clang_dir ${uide_clang_dirs})
  if(IS_DIRECTORY "${uide_clang_dir}/include")
    set(UIDE_RESOURCE_DIR "${uide_clang_dir}")
    break()
  endif()
endforeach()
if(UIDE_RESOURCE_DIR)
  set(UIDE_INCLUDES "${UIDE_INCLUDES} -resource-dir ${UIDE_RESOURCE_DIR}")
endif()

set(UIDE_LINK_FLAGS "-fuse-ld=lld -B${UIDE_PREFIX}/sysroot/lib -L${UIDE_PREFIX}/sysroot/lib -L${UIDE_PREFIX}/lib")

set(CMAKE_C_FLAGS_INIT "${UIDE_INCLUDES}")
set(CMAKE_CXX_FLAGS_INIT "-stdlib=libc++ ${UIDE_INCLUDES}")
set(CMAKE_EXE_LINKER_FLAGS_INIT "${UIDE_LINK_FLAGS}")
set(CMAKE_SHARED_LINKER_FLAGS_INIT "${UIDE_LINK_FLAGS}")
set(CMAKE_MODULE_LINKER_FLAGS_INIT "${UIDE_LINK_FLAGS}")

# Configure-time compile checks must not produce runnable binaries.
set(CMAKE_TRY_COMPILE_TARGET_TYPE STATIC_LIBRARY)

set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE ONLY)
set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE ONLY)
"""

ENV_SCRIPT = """# Environment used by UIDE when spawning build processes.
PREFIX="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
export PREFIX
export TERMUX_PREFIX="$PREFIX"
export PATH="$PREFIX/bin:/system/bin"
export LD_LIBRARY_PATH="$PREFIX/lib"
export TMPDIR="$PREFIX/tmp"
export HOME="$PREFIX/home"
export TERM=dumb
"""

PROBE_SCRIPT = """#!/system/bin/sh
# Smoke test for the UIDE bootstrap: prints versions, compiles and runs a C file.
PREFIX="$(cd "$(dirname "$0")" && pwd)"
export PREFIX
export PATH="$PREFIX/bin:/system/bin"
export LD_LIBRARY_PATH="$PREFIX/lib"

echo "clang: $(clang --version 2>&1 | head -1)"
echo "cmake: $(cmake --version 2>&1 | head -1)"
echo "ninja: $(ninja --version 2>&1 | head -1)"

WORK=/data/local/tmp/uide-probe
rm -rf "$WORK"
mkdir -p "$WORK"
printf '#include <stdio.h>\\nint main(void){printf("hello from uide\\\\n");return 0;}\\n' > "$WORK/main.c"
clang --target=aarch64-linux-android24 --sysroot="$PREFIX" \\
    -isystem "$PREFIX/include/aarch64-linux-android" -isystem "$PREFIX/include" \\
    -B"$PREFIX/sysroot/lib" -L"$PREFIX/sysroot/lib" -fuse-ld=lld \\
    "$WORK/main.c" -o "$WORK/hello" && echo "compile: ok" || echo "compile: FAILED"
"$WORK/hello" && echo "run: ok" || echo "run: FAILED"

cat > "$WORK/main.cpp" <<'EOF'
#include <cstdio>
#include <vector>
int main() { std::vector<int> v{1, 2, 3}; std::printf("cxx ok: %zu\\n", v.size()); return 0; }
EOF
clang++ --target=aarch64-linux-android24 --sysroot="$PREFIX" \\
    -isystem "$PREFIX/include/c++/v1" -isystem "$PREFIX/include/aarch64-linux-android" \\
    -isystem "$PREFIX/include" \\
    -B"$PREFIX/sysroot/lib" -L"$PREFIX/sysroot/lib" -L"$PREFIX/lib" \\
    -stdlib=libc++ -fuse-ld=lld \\
    "$WORK/main.cpp" -o "$WORK/hellocxx" && echo "compile-cxx: ok" || echo "compile-cxx: FAILED"
"$WORK/hellocxx" && echo "run-cxx: ok" || echo "run-cxx: FAILED"

mkdir -p "$WORK/cmproj"
cat > "$WORK/cmproj/CMakeLists.txt" <<'EOF'
cmake_minimum_required(VERSION 3.20)
project(uideprobe C CXX)
add_executable(probe main.c)
add_executable(probecxx main.cpp)
EOF
cp "$WORK/main.c" "$WORK/cmproj/main.c"
cp "$WORK/main.cpp" "$WORK/cmproj/main.cpp"
cmake -S "$WORK/cmproj" -B "$WORK/cmbuild" -G Ninja \\
    -DCMAKE_TOOLCHAIN_FILE="$PREFIX/share/uide/uide-android.cmake" \\
    -DCMAKE_BUILD_TYPE=Debug > "$WORK/cmake.log" 2>&1 &&
    echo "cmake-configure: ok" || { echo "cmake-configure: FAILED"; tail -30 "$WORK/cmake.log"; }
cmake --build "$WORK/cmbuild" --parallel 2 >> "$WORK/cmake.log" 2>&1 &&
    echo "cmake-build: ok" || { echo "cmake-build: FAILED"; tail -30 "$WORK/cmake.log"; }
"$WORK/cmbuild/probe" && echo "cmake-run: ok" || echo "cmake-run: FAILED"
"$WORK/cmbuild/probecxx" && echo "cmake-run-cxx: ok" || echo "cmake-run-cxx: FAILED"

rm -rf "$WORK"
"""

NDK_LINK_LIBS = (
    "libc.so",
    "libm.so",
    "libdl.so",
    "liblog.so",
    "libz.so",
    "libANDROID.so",
    "libandroid.so",
    "libjnigraphics.so",
    "libEGL.so",
    "libGLESv2.so",
    "libOpenSLES.so",
    "libaaudio.so",
    "libcamera2ndk.so",
    "libmediandk.so",
    "libnativewindow.so",
    "libvulkan.so",
    "libneuralnetworks.so",
)
NDK_CXX_LIBS = ("libc++_static.a", "libc++abi.a", "libc++_shared.so")

# Termux patches ninja and make to run build rules through its own shell, which
# does not exist once the tree is relocated. Both the old and the new string fit
# in the same amount of space as long as the replacement is not longer, so the
# bytes can be rewritten in place.
SHELL_REPLACEMENTS = ((b"/data/data/com.termux/files/usr/bin/sh", b"/system/bin/sh"),)
RELOCATE_SCAN_PREFIXES = ("usr/bin/", "usr/libexec/")
RELOCATE_MAX_SIZE = 64 * 1024 * 1024


def log(message: str) -> None:
    print(f"[bootstrap] {message}", flush=True)


def http_get(url: str, retries: int = 3) -> bytes:
    last: Exception | None = None
    for attempt in range(1, retries + 1):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": "uide-bootstrap/1"})
            with urllib.request.urlopen(request, timeout=120) as response:
                return response.read()
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            last = error
            if attempt < retries:
                time.sleep(2 * attempt)
    raise RuntimeError(f"failed to download {url}: {last}")


def fetch_index(arch: str, mirrors: list[str]) -> tuple[str, dict[str, dict[str, str]]]:
    errors: list[str] = []
    for mirror in mirrors:
        url = f"{mirror}/dists/stable/main/binary-{arch}/Packages.gz"
        try:
            log(f"fetching package index from {mirror}")
            raw = gzip.decompress(http_get(url)).decode("utf-8", "replace")
        except Exception as error:  # noqa: BLE001 - mirror fallback
            errors.append(f"{mirror}: {error}")
            continue

        records: dict[str, dict[str, str]] = {}
        for stanza in raw.split("\n\n"):
            record: dict[str, str] = {}
            key = ""
            for line in stanza.splitlines():
                if not line.strip():
                    continue
                if line.startswith((" ", "\t")) and key:
                    record[key] += "\n" + line.strip()
                elif ":" in line:
                    key, _, value = line.partition(":")
                    record[key] = value.strip()
            name = record.get("Package")
            if name:
                records[name] = record
        log(f"index contains {len(records)} packages")
        return mirror, records
    raise RuntimeError("all mirrors failed: " + "; ".join(errors))


def parse_depends(record: dict[str, str]) -> list[str]:
    depends: list[str] = []
    for clause in record.get("Depends", "").split(","):
        clause = clause.strip()
        if not clause:
            continue
        first = clause.split("|")[0].strip()
        name = first.split("(")[0].split(":")[0].strip()
        if name:
            depends.append(name)
    return depends


def resolve(index: dict[str, dict[str, str]], roots: list[str]) -> list[dict[str, str]]:
    ordered: list[dict[str, str]] = []
    seen: set[str] = set()
    queue = list(roots)
    while queue:
        name = queue.pop(0)
        if name in seen:
            continue
        record = index.get(name)
        if record is None:
            log(f"warning: package '{name}' not found in index, skipping")
            continue
        seen.add(name)
        ordered.append(record)
        queue.extend(parse_depends(record))
    ordered.sort(key=lambda item: item["Package"])
    return ordered


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def cache_file_name(record: dict[str, str]) -> str:
    # Epoch versions ("1:3.6.5") contain a colon, which is illegal on Windows.
    name = record["Filename"].rsplit("/", 1)[-1]
    safe = re.sub(r'[^A-Za-z0-9._+-]', "_", name)
    return f"{record['Package']}__{safe}"


def download_packages(
    records: list[dict[str, str]], mirror: str, cache: Path, workers: int = 6
) -> list[tuple[dict[str, str], Path]]:
    cache.mkdir(parents=True, exist_ok=True)

    def fetch(record: dict[str, str]) -> Path:
        target = cache / cache_file_name(record)
        expected = record.get("SHA256", "")
        if target.is_file() and (not expected or sha256_of(target) == expected):
            return target
        log(f"downloading {record['Package']} {record.get('Version', '?')}")
        tmp = target.with_suffix(target.suffix + ".part")
        tmp.write_bytes(http_get(f"{mirror}/{record['Filename']}"))
        tmp.replace(target)
        return target

    with ThreadPoolExecutor(max_workers=workers) as pool:
        paths = list(pool.map(fetch, records))

    result: list[tuple[dict[str, str], Path]] = []
    for record, path in zip(records, paths):
        expected = record.get("SHA256", "")
        if expected and sha256_of(path) != expected:
            raise RuntimeError(f"checksum mismatch for {record['Package']}")
        result.append((record, path))
    return result


def deb_data_member(path: Path) -> bytes:
    """Extracts data.tar.* from a .deb (a plain `ar` archive)."""
    blob = path.read_bytes()
    if not blob.startswith(b"!<arch>\n"):
        raise RuntimeError(f"{path} is not an ar archive")
    offset = 8
    while offset + 60 <= len(blob):
        header = blob[offset : offset + 60]
        name = header[0:16].decode("ascii", "replace").strip()
        size = int(header[48:58].decode("ascii", "replace").strip() or "0")
        start = offset + 60
        if name.startswith("data.tar"):
            return blob[start : start + size]
        offset = start + size + (size % 2)
    raise RuntimeError(f"{path} has no data.tar member")


def map_path(name: str) -> str | None:
    path = name.lstrip("./")
    match = ABS_PREFIX_RE.match(name)
    if match:
        path = match.group(1)
    if path in ("", "data") or path.startswith("data/"):
        # Deb scaffolding directories that carry no payload.
        return None
    return path or None


def should_drop(path: str) -> bool:
    if path in DROP_EXACT:
        return True
    if any(path.startswith(prefix) for prefix in DROP_PREFIXES):
        return not any(marker in path for marker in KEEP_MARKERS)
    if path.endswith(".pyc"):
        return True
    if path.startswith("usr/bin/"):
        name = path.rsplit("/", 1)[-1]
        return not (name in KEEP_BIN or name.startswith(KEEP_BIN_PREFIXES))
    # Only the builtins for the device ABI are needed.
    if path.startswith("usr/lib/clang/") and "/lib/linux/" in path:
        return "aarch64" not in path.rsplit("/", 1)[-1]
    return False


class ArchiveWriter:
    """Writes entries into the output tar while keeping a global path index."""

    def __init__(self, handle: tarfile.TarFile, root: str) -> None:
        self.handle = handle
        self.root = root.strip("/")
        self.seen: set[str] = set()
        self.relocated: list[str] = []

    def _relocate(self, arcname: str, payload: bytes) -> bytes:
        if not arcname.startswith(RELOCATE_SCAN_PREFIXES) or len(payload) > RELOCATE_MAX_SIZE:
            return payload
        patched = payload
        for old, new in SHELL_REPLACEMENTS:
            if old in patched:
                patched = patched.replace(old, new + b"\0" * (len(old) - len(new)))
                self.relocated.append(arcname)
        return patched

    def _arcname(self, path: str) -> str:
        return path if self.root in ("", ".") else f"{self.root}/{path}"

    def add(self, info: tarfile.TarInfo, source=None) -> bool:
        arcname = self._arcname(info.name)
        if arcname in self.seen:
            return False
        self.seen.add(arcname)
        info.name = arcname
        info.uid = 0
        info.gid = 0
        info.uname = ""
        info.gname = ""
        if info.isdir():
            info.mode = 0o755
        elif info.isreg():
            # Termux stores 0600/0700 in its .deb files and relies on its own
            # installer to fix the modes, so the modes are assigned here instead.
            executable = info.name.startswith(("usr/bin/", "usr/libexec/"))
            info.mode = 0o755 if executable else 0o644
            if source is not None:
                payload = self._relocate(arcname, source.read())
                self.handle.addfile(info, io.BytesIO(payload))
                return True
        self.handle.addfile(info, source)
        return True

    def add_bytes(self, name: str, payload: bytes, mode: int) -> None:
        info = tarfile.TarInfo(name)
        info.size = len(payload)
        info.mode = mode
        info.mtime = int(time.time())
        self.add(info, io.BytesIO(payload))

    def add_file(self, source: Path, name: str, mode: int) -> bool:
        info = self.handle.gettarinfo(str(source), arcname=name)
        with source.open("rb") as handle:
            return self.add(info, handle)


def add_packages(
    downloaded: list[tuple[dict[str, str], Path]], writer: ArchiveWriter
) -> tuple[int, int]:
    added = 0
    dropped = 0
    for record, deb in downloaded:
        with tempfile.NamedTemporaryFile(suffix=".tar", delete=False) as handle:
            handle.write(deb_data_member(deb))
            data_path = Path(handle.name)
        try:
            with tarfile.open(data_path, "r:*") as data_tar:
                kept = 0
                for member in data_tar.getmembers():
                    path = map_path(member.name)
                    if path is None or should_drop(path):
                        dropped += 1
                        continue
                    member.name = path
                    source = data_tar.extractfile(member) if member.isreg() else None
                    if writer.add(member, source):
                        kept += 1
                added += kept
                log(f"  {record['Package']:16s} {record.get('Version', '?'):14s} {kept:6d} files")
        finally:
            data_path.unlink(missing_ok=True)
    return added, dropped


def find_ndk_sysroot(ndk: Path) -> Path:
    prebuilt = ndk / "toolchains/llvm/prebuilt"
    if not prebuilt.is_dir():
        raise RuntimeError(f"no NDK prebuilt directory under {prebuilt}")
    for child in sorted(prebuilt.iterdir()):
        sysroot = child / "sysroot"
        if sysroot.is_dir():
            return sysroot
    raise RuntimeError(f"no sysroot found under {prebuilt}")


def add_ndk_libs(writer: ArchiveWriter, ndk: Path, api: int, triple: str) -> int:
    """Adds the NDK link stubs.

    They deliberately go into ``usr/sysroot/lib`` and *not* ``usr/lib``: those
    files are the stub ``libc.so`` / ``libm.so`` used only at link time. Leaving
    them on ``LD_LIBRARY_PATH`` would make every process started by the app -
    including system tools such as ``toybox`` - load the stub libc instead of
    the real one and fail to link.
    """
    sysroot = find_ndk_sysroot(ndk)
    triple_dir = sysroot / "usr/lib" / triple
    if not triple_dir.is_dir():
        raise RuntimeError(f"NDK sysroot has no {triple} directory")
    api_dir = triple_dir / str(api)
    if not api_dir.is_dir():
        api_dir = max(
            (child for child in triple_dir.iterdir() if child.is_dir()),
            key=lambda child: int(child.name) if child.name.isdigit() else 0,
        )
    log(f"  NDK link stubs: {api_dir}")

    count = 0
    for name in NDK_LINK_LIBS:
        source = api_dir / name
        if source.is_file() and writer.add_file(source, f"usr/sysroot/lib/{name}", 0o644):
            count += 1
    for source in sorted(api_dir.glob("crt*.o")):
        if writer.add_file(source, f"usr/sysroot/lib/{source.name}", 0o644):
            count += 1
    for name in NDK_CXX_LIBS:
        source = triple_dir / name
        if source.is_file() and writer.add_file(source, f"usr/sysroot/lib/{name}", 0o644):
            count += 1
    return count


def scan_prefix_usage(tar_path: Path) -> list[str]:
    hits: list[str] = []
    with tarfile.open(tar_path, "r:gz") as archive:
        for member in archive.getmembers():
            if not member.isreg() or member.size > 32 * 1024 * 1024:
                continue
            handle = archive.extractfile(member)
            if handle is None:
                continue
            if SELF_PATH in handle.read():
                hits.append(member.name)
    return hits


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ndk", required=True, help="path to an Android NDK installation")
    parser.add_argument("--out", default="app/src/main/assets/bootstrap-aarch64.bin")
    parser.add_argument("--cache", default=".uide-cache/debs")
    parser.add_argument("--arch", default="aarch64", choices=["aarch64"])
    parser.add_argument("--api", type=int, default=24)
    parser.add_argument("--triple", default="aarch64-linux-android")
    parser.add_argument("--mirrors", default=",".join(TERMUX_MIRRORS))
    parser.add_argument("--packages", default=",".join(DEFAULT_PACKAGES))
    parser.add_argument("--workers", type=int, default=6)
    args = parser.parse_args()

    mirrors = [item for item in args.mirrors.split(",") if item.strip()]
    roots = [item for item in args.packages.split(",") if item.strip()]
    mirror, index = fetch_index(args.arch, mirrors)
    records = resolve(index, roots)
    log(f"resolved {len(records)} packages from {mirror}")

    downloaded = download_packages(records, mirror, Path(args.cache).resolve(), args.workers)

    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    tmp_path = out_path.with_suffix(out_path.suffix + ".tmp")

    started = time.time()
    with tarfile.open(tmp_path, "w:gz", compresslevel=6) as handle:
        writer = ArchiveWriter(handle, ".")
        added, dropped = add_packages(downloaded, writer)
        log(f"packages contributed {added} entries ({dropped} dropped)")
        ndk_count = add_ndk_libs(writer, Path(args.ndk), args.api, args.triple)
        log(f"NDK contributed {ndk_count} link stubs")
        writer.add_bytes("usr/share/uide/uide-android.cmake", TOOLCHAIN_CMAKE.encode(), 0o644)
        writer.add_bytes("usr/uide-env.sh", ENV_SCRIPT.encode(), 0o644)
        writer.add_bytes("usr/uide-probe.sh", PROBE_SCRIPT.encode(), 0o755)
    tmp_path.replace(out_path)

    size_mb = out_path.stat().st_size / (1024 * 1024)
    log(f"wrote {out_path} ({size_mb:.1f} MiB) in {time.time() - started:.0f}s")

    hits = scan_prefix_usage(out_path)
    manifest = {
        "arch": args.arch,
        "api": args.api,
        "triple": args.triple,
        "packages": {record["Package"]: record.get("Version", "") for record in records},
        "size_bytes": out_path.stat().st_size,
        "sha256": sha256_of(out_path),
        "termux_prefix_references": hits,
    }
    manifest_path = out_path.with_suffix(".json")
    manifest_path.write_text(json.dumps(manifest, indent=2), encoding="utf-8")

    if hits:
        log(f"warning: {len(hits)} files still reference the Termux prefix:")
        for name in hits[:20]:
            log(f"  {name}")
    else:
        log("no file references the Termux prefix")
    log(f"manifest: {manifest_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
