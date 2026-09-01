# T01 Candidate Workspace

Implement only the Store persistence provider behind the frozen Domain,
Application and contract types in `src/main/java/io/github/ande1922/moduvera/benchmark/store/shared`.
Do not edit the shared package, the task card, the DDL, or public tests.

The entry point must be a public no-argument class named
`io.github.ande1922.moduvera.benchmark.store.candidate.CandidateStorePersistenceProvider`
implementing `StorePersistenceProvider`.

Candidate implementation files belong under
`src/main/java/io/github/ande1922/moduvera/benchmark/store/candidate`; MyBatis XML belongs under
`src/main/resources/io/github/ande1922/moduvera/benchmark/store/candidate`.

`./mvnw -s .mvn/benchmark-settings.xml -o -q test` checks the immutable seed contracts without a database. The host
evaluator runs the public and hidden MySQL acceptance suites separately.
