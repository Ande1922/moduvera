# Ticket 11 combined Scenario qualification

- Worker base: `04c66e84e4b05814e462ba4999759b571109e9cf`.
- Reviewed worker head: `e055adb0148d8be69b42e2874a38641efa512e04`.
- Integrated commit: `297432f9f749c09b8d0cbc1dbd7bf3cf8fe14a5e`.
- Full integrated tree: `3eafab29e06b9f901c07070bace13f2b1d86f538`, identical to the tested worker tree.
- Result: both supported public topologies PASS at the worker head; each run retained the exact head/tree and a clean checkout.
- This is ticket-level combined evidence. Final whole-integration review, Normal gate, and Scenario must bind to the final delivery head and are recorded separately.

Commands used the unchanged repository harness from the clean worker checkout:

```bash
REFERENCE_PREFLIGHT_ONLY=0 REFERENCE_KEEP_RUNNING=0 REFERENCE_SKIP_BUILD=0 REFERENCE_PORT_MANIFEST=/private/tmp/execution-context-frontier-20260905/evidence/11/scenario-e055adb/microservices-ports.json verification/reference-product/harness/verify.sh microservices
REFERENCE_PREFLIGHT_ONLY=0 REFERENCE_KEEP_RUNNING=0 REFERENCE_SKIP_BUILD=1 REFERENCE_PORT_MANIFEST=/private/tmp/execution-context-frontier-20260905/evidence/11/scenario-e055adb/business-core-monolith-ports.json verification/reference-product/harness/verify.sh business-core-monolith
```

The first command performed the harness-owned full `./mvnw -q clean install`, including real duplicate-delivery ITs. The second reused the same six application JARs; the coordinator checked every digest and size before reuse and after completion. Both topology logs report public contract and Kafka recovery PASS. The coordinator wrapper only invokes the public harness and records source/artifact identity; no harness code or test assertion was changed.

| Topology | Exit | Seconds | Public contract and Kafka recovery |
| --- | --- | --- | --- |
| microservices | 0 | 316.92 | PASS |
| business-core-monolith | 0 | 124.164 | PASS |

| Application artifact | SHA-256 |
| --- | --- |
| `apps/app-monolith/target/app-monolith-0.1.0-SNAPSHOT.jar` | `eb29dfa05bfffdb1818620e9af077ce4701d90e5bfb861e14acade60f87a73ff` |
| `apps/catalog-app/target/catalog-app-0.1.0-SNAPSHOT.jar` | `0556ebbcb5d185dfefb69fb1271df759b191ff70c3d12245630bf29d02813202` |
| `apps/gateway-app/target/gateway-app-0.1.0-SNAPSHOT.jar` | `5097d3380696aeb1917d9351b4f5245bc8f25d7884916d0729f5aef3d415b7a3` |
| `apps/identity-app/target/identity-app-0.1.0-SNAPSHOT.jar` | `f1019b7158205084ab90ba80675b6ab3c1cc139043a10e33820ddeb4d4b98879` |
| `apps/inventory-app/target/inventory-app-0.1.0-SNAPSHOT.jar` | `c905e958273904adb9ac986a4b24b8655e12757860c2f51d3e832b90e0375a07` |
| `apps/order-app/target/order-app-0.1.0-SNAPSHOT.jar` | `9cb3b262656e39df51bce6e7bae1bb541f83fdabcb48c6e5aef5ba543804507e` |

Detailed source/tree, artifact sizes, run IDs, disposable Compose projects, port/topic manifests, timestamps and log paths are preserved in `/private/tmp/execution-context-frontier-20260905/evidence/11/scenario-e055adb/scenario-evidence.json`. Full logs remain external and were not committed.
