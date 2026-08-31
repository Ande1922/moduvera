# Repository Guidelines

## Project Structure & Module Organization

This is a Java 26, Spring Boot 4.1 multi-module Maven repository. Shared contracts and infrastructure live in `foundation/`; reusable Spring Boot integrations are in `starters/`; concrete implementations belong in `adapters/`. Each capability under `services/<name>/` separates protocol-neutral `<name>-api` contracts from application, domain, and service-owned adapters in `<name>-service`. Runnable assemblies are under `apps/`, including the five-service topology and `app-monolith`. Put shared test utilities and architecture rules in `testing/`, examples in `examples/`, deployment assets in `deployment/`, and design decisions in `docs/adr/`. Follow Maven layout: `src/main/java`, `src/main/resources`, and `src/test/java`.

## Build, Test, and Development Commands

- `./mvnw clean verify` builds the full reactor and runs unit, integration, architecture, formatting, PMD, and JaCoCo checks.
- `./mvnw -pl services/order/order-service -am test` tests one module plus required dependencies during focused development.
- `./mvnw spotless:apply` removes unused imports and normalizes Java/POM whitespace before review.
- `scripts/reference-product/verify.sh [microservices|business-core-monolith]` runs the public HTTP acceptance contract. It requires JDK 26, Docker Compose, and `uv`; omit the argument to verify both topologies.

## Coding Style & Naming Conventions

Use four-space Java indentation, `UpperCamelCase` types, `lowerCamelCase` members, and `UPPER_SNAKE_CASE` constants. Keep packages below `io.github.ande1922.moduvera`. Place transport-neutral interfaces and records in API modules; keep HTTP, persistence, and messaging details behind service or platform seams. Compilation treats selected warnings as errors. Run Spotless and honor the focused rules in `config/pmd/ruleset.xml`.

## Testing Guidelines

Use JUnit Jupiter; use Spring Boot tests and Testcontainers when infrastructure behavior matters, and ArchUnit for module-boundary rules. Name fast tests `*Test` and Failsafe integration tests `*IT`. Add a regression test for changed behavior and run the narrowest module test before `clean verify`. JaCoCo reports coverage during `verify`; no repository-wide numeric minimum is configured.

## Commit & Pull Request Guidelines

History follows Conventional Commit prefixes such as `feat:`, `fix:`, and `chore:`. Keep each commit focused and use an imperative summary. Pull requests should explain affected modules and behavior, link the issue or ADR, list executed verification, and include request/response examples or screenshots when public APIs or UI-visible behavior changes.

## Security & Configuration

Never commit production secrets. Treat credentials in reference fixtures as local-only. Tenant, identity, and correlation data must come from trusted execution context; adapters should fail closed when that context is absent.
