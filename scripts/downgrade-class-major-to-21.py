#!/usr/bin/env python3
"""
将 JAR 内所有 .class 的 major version 改为 65（Java 21），不重新编译。
用法: python downgrade-class-major-to-21.py <输入.jar> [输出.jar]
默认输出: 输入目录下 <原名>-java21-major.jar
"""
import struct
import sys
import zipfile
from pathlib import Path

JAVA_21_MAJOR = 65


def patch_class_major(data: bytes, target_major: int) -> tuple[bytes, int]:
    if len(data) < 8:
        return data, 0
    magic = struct.unpack(">I", data[0:4])[0]
    if magic != 0xCAFEBABE:
        return data, 0
    minor, major = struct.unpack(">HH", data[4:8])
    if major == target_major:
        return data, 0
    patched = bytearray(data)
    struct.pack_into(">H", patched, 6, target_major)
    return bytes(patched), 1


def process_jar(src: Path, dst: Path) -> None:
    changed = 0
    total_class = 0
    with zipfile.ZipFile(src, "r") as zin, zipfile.ZipFile(dst, "w", compression=zipfile.ZIP_DEFLATED) as zout:
        for info in zin.infolist():
            raw = zin.read(info.filename)
            if info.filename.endswith(".class"):
                total_class += 1
                raw, n = patch_class_major(raw, JAVA_21_MAJOR)
                changed += n
            zout.writestr(info, raw)
    print(f"classes: {total_class}, major patched to {JAVA_21_MAJOR}: {changed}")
    print(f"output: {dst}")


def main() -> None:
    if len(sys.argv) < 2:
        print(__doc__.strip())
        sys.exit(1)
    src = Path(sys.argv[1]).resolve()
    if not src.is_file():
        print(f"not found: {src}")
        sys.exit(1)
    if len(sys.argv) >= 3:
        dst = Path(sys.argv[2]).resolve()
    else:
        dst = src.with_name(f"{src.stem}-java21-major{src.suffix}")
    process_jar(src, dst)


if __name__ == "__main__":
    main()