# Repository Guidelines

## Project Structure & Module Organization

This is a Java 26, Spring Boot 4.1 multi-module Maven repository. The root `pom.xml` is the repository reactor only. Reusable framework artifacts live under `framework/`: internal build conventions in `framework/parent/`, the standalone consumer BOM in `framework/bom/`, shared contracts and infrastructure in `framework/foundation/`, Spring Boot integrations in `framework/starters/`, concrete implementations in `framework/adapters/`, and shared test utilities and architecture rules in `framework/testing/`. Each capability under `services/<name>/` separates protocol-neutral `<name>-api` contracts from application, domain, and service-owned adapters in `<name>-service`. Runnable assemblies are under `apps/`, including the five-service topology and `app-monolith`. Verification assets live under `verification/`, examples in `examples/`, and design decisions in `docs/adr/`. Follow Maven layout: `src/main/java`, `src/main/resources`, and `src/test/java`.

## Build, Test, and Development Commands

- `./mvnw clean verify` builds the full reactor and runs unit, integration, architecture, formatting, PMD, and JaCoCo checks.
- `./mvnw -pl services/order/order-service -am test` tests one module plus required dependencies during focused development.
- `./mvnw -pl '!framework/bom,!framework/testing/moduvera-bom-smoke,!examples/simple-notes-demo,!:moduvera-reactor' spotless:apply` removes unused imports and normalizes Java/POM whitespace in Parent-managed modules before review.
- `verification/reference-product/harness/verify.sh [microservices|business-core-monolith]` runs the public HTTP acceptance contract. It requires JDK 26, Docker Compose, and `uv`; omit the argument to verify both topologies.

## Coding Style & Naming Conventions

Use four-space Java indentation, `UpperCamelCase` types, `lowerCamelCase` members, and `UPPER_SNAKE_CASE` constants. Keep packages below `io.github.ande1922.moduvera`. Place transport-neutral interfaces and records in API modules; keep HTTP, persistence, and messaging details behind service or framework seams. Compilation treats selected warnings as errors. Run Spotless and honor the focused rules in `config/pmd/ruleset.xml`.

## Integration Contracts & Adapter Seams

- For an asynchronous-only capability, publish provider-owned, versioned command and event records in the provider's API module, and let the message inbound adapter invoke the Application Service. Add a Java `*Api` method only when a supported direct local or remote call exists; a message handler alone does not justify a synchronous interface.
- Treat transport adaptation and model conversion as separate decisions. Adapters own envelope, routing, authentication, validation, status, and error mechanics. Before adding a transport/application Mapper or duplicate DTO, identify the observable difference in meaning, invariants, shape, serialization, or versioning that it protects; reuse the protocol-neutral contract type when no such difference exists.
- Keep API modules independent of transport frameworks. Publish canonical kind, type, and destination values with the provider-owned message contract, while the consumer keeps its consumer ID, allowed source, execution Actor, and permissions as local policy. Broker authentication and destination ACLs are part of trusted-producer verification; envelope source matching alone is not authentication.
- Before changing Service API, HTTP/message DTO, or inbound-adapter seams, read ADR 0004, ADR 0021, and ADR 0031 in `docs/adr/` and preserve their stated applicability conditions.
- Verification: When adding or changing provider message identities, asynchronous-only commands, message inbound consumers, or asynchronous negative assertions, follow [the message-contract verification workflow](docs/agents/message-contract-verification.md).

## Agent skills

### Issue tracker

Issues and specs use local Markdown under `.scratch/<feature-slug>/`. See `docs/agents/issue-tracker.md`.

### Triage labels

Triage uses the canonical `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, and `wontfix` states. See `docs/agents/triage-labels.md`.

### Domain docs

This is a single-context repository with `CONTEXT.md` and system-wide ADRs under `docs/adr/`. See `docs/agents/domain.md`.

## Testing Guidelines

Use JUnit Jupiter; use Spring Boot tests and Testcontainers when infrastructure behavior matters, and ArchUnit for module-boundary rules. Adapter evidence: Before testing infrastructure semantics, defining Application test doubles, or adding an in-memory runtime Adapter, follow [ADR 0034](docs/adr/0034-verify-infrastructure-with-runtime-adapters.md). Name fast tests `*Test` and Failsafe integration tests `*IT`. Add a regression test for changed behavior and run the narrowest module test before `clean verify`. JaCoCo reports coverage during `verify`; no repository-wide numeric minimum is configured.

## Commit & Pull Request Guidelines

History follows Conventional Commit prefixes such as `feat:`, `fix:`, and `chore:`. Keep each commit focused and use an imperative summary. Pull requests should explain affected modules and behavior, link the issue or ADR, list executed verification, and include request/response examples or screenshots when public APIs or UI-visible behavior changes.

## Security & Configuration

Never commit production secrets. Treat credentials in reference fixtures as local-only. Tenant, identity, and correlation data must come from trusted execution context; adapters should fail closed when that context is absent.
