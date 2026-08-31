# T01 Store lifecycle implementation

Work only in the provided candidate workspace. Implement the Store persistence
provider for the frozen task card. Domain behavior, Application service and
public contracts are already implemented identically for both candidates. Do
not edit files in `src/main/java/io/github/ande1922/moduvera/benchmark/store/shared`, `src/test`,
`ddl`, or `TASK.md`.

Your public no-argument entry point must be
`io.github.ande1922.moduvera.benchmark.store.candidate.CandidateStorePersistenceProvider` and
implement `StorePersistenceProvider`. Put implementation Java sources under the
candidate package and MyBatis XML under the corresponding candidate resource
path.

Use MyBatis-Plus with MySQL. Every read and write, including optimistic-lock
CAS, must be Tenant-scoped. Trusted Tenant/Actor/time come only from the supplied
dependencies. Persistence owns Audit and version; `save` returns the refreshed
entity and the service must use it. Translate failures to the frozen safe error
codes. Do not expose persistence or ORM types through shared interfaces.

Keep Store SQL in MyBatis XML and use bound `#{...}` parameters. For this frozen
Pilot task, Store select/insert/update/delete statements are static: do not use
`${...}`, annotation SQL, raw JDBC, or conditional `<if>`, `<choose>`, `<foreach>`
or `<bind>` fragments. INSERT must name all frozen Store columns explicitly.

Run `./mvnw -s .mvn/benchmark-settings.xml -o -q test` before finishing. The host runs MySQL public and hidden acceptance
tests after your turn, so a local unit-only green is not the final gate.
