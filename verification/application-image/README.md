# Runnable App image verification

This directory verifies the repository-owned image-construction contract for
the six executable assemblies under `apps/`. The shared
[`Dockerfile.jvm`](../../build/docker/Dockerfile.jvm) consumes executable JARs
that Maven has already built; it does not run the Reactor inside an image
build.

Run the complete scenario with Docker available:

```bash
verification/application-image/verify.sh
```

The scenario packages all Runnable Apps, builds every image with its own
parameterized `EXPOSE` metadata, checks the four Spring Boot tools layers and
their shared loader layer, inspects runtime identity and filesystem content,
then starts `catalog-app` with a real PostgreSQL dependency. The smoke overrides
the application port, checks Actuator health and an unauthenticated HTTP
response, and proves that JDWP is closed unless JVM options explicitly enable
it. The same smoke then supplies the JDWP agent option at runtime and completes
the protocol handshake over an independently mapped host port.

`MODUVERA_IMAGE_REPOSITORY` and `MODUVERA_IMAGE_TAG` select local image names.
Set `MODUVERA_IMAGE_SKIP_PACKAGE=1` only when the executable JARs are already
current, or `MODUVERA_IMAGE_SKIP_SMOKE=1` for the build-and-inspect portion.
`JAVA_TOOL_OPTIONS` and all Spring configuration remain runtime inputs; the
Dockerfile does not prescribe a server or debug port.

This is a reusable construction and verification baseline, not a production
deployment template. It does not define production secrets, topology,
availability policy, publication, signing, Kubernetes, or Helm behavior. The
Reference Compose remains disposable local acceptance infrastructure.
