#!/usr/bin/env python3
"""Operate on small lock metadata without following directory or entry symlinks."""

from __future__ import annotations

import os
import stat
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


def main(arguments: list[str]) -> int:
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
