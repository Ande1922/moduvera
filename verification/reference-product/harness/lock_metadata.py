#!/usr/bin/env python3
"""Operate on small lock metadata without following directory or entry symlinks."""

from __future__ import annotations

import ctypes
import hashlib
import os
import pathlib
import stat
import subprocess
import sys


def nofollow_flag() -> int:
    return getattr(os, "O_NOFOLLOW", 0)


def open_directory(path: str) -> int:
    return os.open(path, os.O_RDONLY | os.O_DIRECTORY | nofollow_flag())


def read_metadata(directory: int, entry: str) -> str:
    descriptor = os.open(entry, os.O_RDONLY | nofollow_flag(), dir_fd=directory)
    try:
        metadata = os.fstat(descriptor)
        if not stat.S_ISREG(metadata.st_mode) or metadata.st_size > 4096:
            raise OSError("unsafe lock metadata")
        content = os.read(descriptor, 4097)
        if len(content) > 4096:
            raise OSError("oversized lock metadata")
        text = content.decode("ascii")
        if "\x00" in text:
            raise OSError("invalid lock metadata")
        return text.rstrip("\n")
    finally:
        os.close(descriptor)


def process_birth_identity(pid_text: str) -> str:
    if not pid_text.isascii() or not pid_text.isdecimal() or int(pid_text, 10) <= 0:
        raise OSError("invalid process id")
    pid = int(pid_text, 10)
    os.kill(pid, 0)
    proc_stat = pathlib.Path(f"/proc/{pid}/stat")
    if sys.platform == "darwin":
        class ProcBsdInfo(ctypes.Structure):
            _fields_ = [
                (name, ctypes.c_uint32)
                for name in (
                    "flags", "status", "xstatus", "pid", "ppid", "uid", "gid",
                    "ruid", "rgid", "svuid", "svgid", "reserved",
                )
            ] + [
                ("command", ctypes.c_char * 16),
                ("name", ctypes.c_char * 32),
            ] + [
                (name, ctypes.c_uint32)
                for name in ("nfiles", "pgid", "pjobc", "tdev", "tpgid")
            ] + [
                ("nice", ctypes.c_int32),
                ("start_seconds", ctypes.c_uint64),
                ("start_microseconds", ctypes.c_uint64),
            ]

        process_info = ProcBsdInfo()
        libproc = ctypes.CDLL("/usr/lib/libproc.dylib", use_errno=True)
        proc_pidinfo = libproc.proc_pidinfo
        proc_pidinfo.argtypes = [
            ctypes.c_int,
            ctypes.c_int,
            ctypes.c_uint64,
            ctypes.c_void_p,
            ctypes.c_int,
        ]
        proc_pidinfo.restype = ctypes.c_int
        size = ctypes.sizeof(process_info)
        if proc_pidinfo(pid, 3, 0, ctypes.byref(process_info), size) != size:
            raise OSError("unable to read process start time")
        if process_info.pid != pid or process_info.start_seconds == 0:
            raise OSError("invalid process start time")
        raw_identity = (
            f"darwin:{process_info.start_seconds}:{process_info.start_microseconds}"
        )
    elif proc_stat.exists():
        stat_text = proc_stat.read_text(encoding="ascii")
        _, separator, tail = stat_text.rpartition(")")
        fields = tail.split()
        if not separator or len(fields) < 20:
            raise OSError("invalid process metadata")
        boot_id = pathlib.Path("/proc/sys/kernel/random/boot_id").read_text(
            encoding="ascii"
        ).strip()
        raw_identity = f"linux:{boot_id}:{fields[19]}"
    else:
        start = subprocess.run(
            ["ps", "-p", str(pid), "-o", "lstart="],
            check=True,
            capture_output=True,
            text=True,
            timeout=2,
        ).stdout.strip()
        if not start:
            raise OSError("missing process start time")
        raw_identity = f"{sys.platform}:{start}"
    return hashlib.sha256(raw_identity.encode("ascii")).hexdigest()


def main(arguments: list[str]) -> int:
    if len(arguments) == 2 and arguments[0] == "identity":
        try:
            sys.stdout.write(process_birth_identity(arguments[1]))
            return 0
        except (OSError, UnicodeError, subprocess.SubprocessError):
            return 74
    if len(arguments) < 3 or arguments[0] not in {"read", "create", "remove"}:
        return 64
    command, directory_path, entry, *values = arguments
    if entry not in {"owner", "pid"}:
        return 64
    try:
        directory = open_directory(directory_path)
        try:
            if command == "read" and not values:
                sys.stdout.write(read_metadata(directory, entry))
                return 0
            if command == "create" and len(values) == 1:
                descriptor = os.open(
                    entry,
                    os.O_WRONLY | os.O_CREAT | os.O_EXCL | nofollow_flag(),
                    0o600,
                    dir_fd=directory,
                )
                try:
                    os.write(descriptor, (values[0] + "\n").encode("ascii"))
                finally:
                    os.close(descriptor)
                return 0
            if command == "remove" and len(values) == 1:
                if read_metadata(directory, entry) != values[0]:
                    return 74
                os.unlink(entry, dir_fd=directory)
                return 0
            return 64
        finally:
            os.close(directory)
    except (OSError, UnicodeError):
        return 74


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
