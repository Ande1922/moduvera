---
status: superseded by ADR-0022
---

# Make the multi-process microservice topology the Golden Path

The first complete reference path runs Catalog, Order, and Inventory as separate application processes and exercises real HTTP and asynchronous messaging boundaries. Protocol-neutral Service APIs and build-time composition remain valid, but a modular-monolith App Assembly is optional and must not block the first version. Local implementations remain available for direct tests and a possible later assembly; they do not create a second full acceptance or infrastructure matrix.

This refines ADR 0001 and ADR 0004 without introducing runtime topology switching.

Evidence: explicitly confirmed by the user during the 2026-08-30 architecture simplification review.
