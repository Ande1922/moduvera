#!/usr/bin/env python3
"""Fail when a required local TCP port cannot be reserved for harness startup."""

from __future__ import annotations

import socket
import sys


def reserve_wildcard(
    family: socket.AddressFamily,
    address: str,
    port: int,
    label: str,
    reservations: list[socket.socket],
) -> bool:
    try:
        reservation = socket.socket(family, socket.SOCK_STREAM)
    except OSError:
        return False
    try:
        reservation.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    except OSError:
        reservation.close()
        return False
    if family == socket.AF_INET6:
        try:
            reservation.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 1)
        except (AttributeError, OSError):
            reservation.close()
            return False
    try:
        reservation.bind((address, port))
        reservation.listen(1)
    except OSError as failure:
        reservation.close()
        print(
            f"Required port is unavailable before startup: {label}={port} "
            f"on wildcard {address} ({failure.strerror or failure})",
            file=sys.stderr,
        )
        raise
    reservations.append(reservation)
    return True


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

            try:
                reserve_wildcard(socket.AF_INET, "0.0.0.0", port, label, reservations)
                if socket.has_ipv6:
                    reserve_wildcard(socket.AF_INET6, "::", port, label, reservations)
            except OSError:
                return 69
    finally:
        for reservation in reservations:
            reservation.close()

    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
