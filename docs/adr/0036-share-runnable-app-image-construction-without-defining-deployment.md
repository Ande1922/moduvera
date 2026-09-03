---
status: accepted
---

# Share Runnable App image construction without defining deployment

Every executable assembly under `apps/` uses the repository-owned,
parameterized `build/docker/Dockerfile.jvm`. The Dockerfile consumes an already
built Spring Boot executable JAR, extracts its four tools-jarmode layers, and
runs it on the digest-pinned Java 26 JRE as a fixed non-root identity. Extracted
application content remains root-owned and is not writable by that runtime
identity. Runtime Spring configuration and optional JVM debugging remain
deployment inputs; `EXPOSE` is parameterized metadata rather than a server-port
setting.

This decision narrows ADR 0013 only for application-image construction.
Publication, vulnerability scanning, signing and verification policy remain
open. It does not decide production topology, secrets, availability,
Kubernetes, Helm or release orchestration, and it does not promote the
disposable Reference Compose harness into deployment guidance.
