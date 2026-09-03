#!/usr/bin/env python3
"""Fail when a required local TCP port cannot be reserved for harness startup."""

from __future__ import annotations

import socket
import sys


def main(specifications: list[str]) -> int:
    reservations: list[socket.socket] = []
    try:
        for specification in specifications:
            try:
                label, raw_port = specification.split("=", 1)
                port = int(raw_port)
            except (ValueError, TypeError):
                print(f"Invalid port specification: {specification}", file=sys.stderr)
                return 64

            reservation = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            try:
                reservation.bind(("0.0.0.0", port))
            except OSError as failure:
                reservation.close()
                print(
                    f"Required port is unavailable before startup: {label}={port} "
                    f"({failure.strerror or failure})",
                    file=sys.stderr,
                )
                return 69
            reservations.append(reservation)
    finally:
        for reservation in reservations:
            reservation.close()

    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
