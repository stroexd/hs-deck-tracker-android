#!/usr/bin/env python3
"""Fails when a 64-bit native library in an APK/AAB isn't aligned for 16 KB memory pages (a Google Play requirement)."""
import struct
import sys
import zipfile

PAGE = 16 * 1024
PT_LOAD = 1


def load_alignments(elf: bytes):
    if elf[:4] != b"\x7fELF" or elf[4] != 2:  # only 64-bit libraries need 16 KB pages
        return []
    endian = "<" if elf[5] == 1 else ">"
    phoff, = struct.unpack_from(endian + "Q", elf, 0x20)
    phentsize, phnum = struct.unpack_from(endian + "HH", elf, 0x36)
    aligns = []
    for i in range(phnum):
        offset = phoff + i * phentsize
        p_type, = struct.unpack_from(endian + "I", elf, offset)
        if p_type == PT_LOAD:
            aligns.append(struct.unpack_from(endian + "Q", elf, offset + 0x30)[0])
    return aligns


def main(paths):
    bad = []
    for path in paths:
        with zipfile.ZipFile(path) as archive:
            for name in archive.namelist():
                if not name.endswith(".so"):
                    continue
                aligns = load_alignments(archive.read(name))
                status = "ok" if all(a >= PAGE for a in aligns) else "NOT 16 KB aligned"
                print(f"{path}: {name}: {status}")
                if status != "ok":
                    bad.append(name)
    if bad:
        sys.exit(f"{len(bad)} native libraries are not 16 KB aligned")


if __name__ == "__main__":
    main(sys.argv[1:])
