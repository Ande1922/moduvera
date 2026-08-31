package io.github.ande1922.moduvera.benchmark.store.hiddentest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ande1922.moduvera.benchmark.store.shared.Actor;
import io.github.ande1922.moduvera.benchmark.store.shared.ActorType;
import io.github.ande1922.moduvera.benchmark.store.shared.CreateStoreCommand;
import io.github.ande1922.moduvera.benchmark.store.shared.DefaultStoreApi;
import io.github.ande1922.moduvera.benchmark.store.shared.ExecutionContext;
import io.github.ande1922.moduvera.benchmark.store.shared.ExecutionContextAccessor;
import io.github.ande1922.moduvera.benchmark.store.shared.GetStoreQuery;
import io.github.ande1922.moduvera.benchmark.store.shared.RenameStoreCommand;
import io.github.ande1922.moduvera.benchmark.store.shared.ScopedExecutionContext;
import io.github.ande1922.moduvera.benchmark.store.shared.Store;
import io.github.ande1922.moduvera.benchmark.store.shared.StoreApi;
import io.github.ande1922.moduvera.benchmark.store.shared.StoreError;
import io.github.ande1922.moduvera.benchmark.store.shared.StoreId;
import io.github.ande1922.moduvera.benchmark.store.shared.StorePersistenceDependencies;
import io.github.ande1922.moduvera.benchmark.store.shared.StorePersistenceProvider;
import io.github.ande1922.moduvera.benchmark.store.shared.StoreRepository;
import io.github.ande1922.moduvera.benchmark.store.shared.StoreView;
import io.github.ande1922.moduvera.benchmark.store.shared.TenantId;
import com.mysql.cj.jdbc.MysqlDataSource;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class T01HiddenAcceptanceIT {
  private static final String PROVIDER_CLASS =
      "io.github.ande1922.moduvera.benchmark.store.candidate.CandidateStorePersistenceProvider";
  private static final TenantId TENANT_A = new TenantId("hidden-A");
  private static final TenantId TENANT_B = new TenantId("hidden-B");
  private static final Instant BASE_TIME = Instant.parse("2026-08-30T03:04:05.123456Z");

  private DataSource dataSource;
  private MutableContextAccessor contexts;
  private MutableClock clock;
  private AtomicLong ids;
  private StorePersistenceProvider provider;
  private StoreApi api;

  @BeforeAll
  void createSchemaAndProvider() throws Exception {
    dataSource = testDataSource();
    applyDdl(dataSource);
    provider = loadProvider();
  }

  @BeforeEach
  void reset() throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.executeUpdate("DELETE FROM store_location");
    }
    contexts = new MutableContextAccessor();
    clock = new MutableClock(BASE_TIME);
    ids = new AtomicLong(50_000);
    api = createApi(contexts, clock, ids);
    contexts.set(context(TENANT_A, "hidden-actor", Set.of("store:read", "store:write")));
  }

  @Test
  void t01H01CrossTenantIdIsIndistinguishableFromAbsence() {
    StoreView created = api.create(new CreateStoreCommand("ISOLATED", "A", "UTC"));
    contexts.set(context(TENANT_B, "other", Set.of("store:read")));

    StoreError crossTenant =
        assertThrows(StoreError.class, () -> api.get(new GetStoreQuery(created.storeId())));
    StoreError absent =
        assertThrows(StoreError.class, () -> api.get(new GetStoreQuery(new StoreId(999_999))));

    assertEquals("store.not-found", crossTenant.code());
    assertEquals(absent.code(), crossTenant.code());
    assertEquals(absent.getMessage(), crossTenant.getMessage());
  }

  @Test
  void t01H02MissingContextFailsBeforeRepositoryAccess() {
    DefaultStoreApi guarded = new DefaultStoreApi(new ExplodingRepository(), Optional::empty, () -> 1);

    StoreError error =
        assertThrows(StoreError.class, () -> guarded.get(new GetStoreQuery(new StoreId(1))));

    assertEquals("security.context-missing", error.code());
  }

  @Test
  void t01H03DeniedPermissionFailsBeforeRepositoryAccess() {
    ExecutionContext denied = context(TENANT_A, "denied", Set.of());
    DefaultStoreApi guarded =
        new DefaultStoreApi(new ExplodingRepository(), () -> Optional.of(denied), () -> 1);

    StoreError error =
        assertThrows(StoreError.class, () -> guarded.get(new GetStoreQuery(new StoreId(1))));

    assertEquals("security.permission-denied", error.code());
  }

  @Test
  void t01H05ConcurrentStaleCasHasExactlyOneWinner() throws Exception {
    ScopedExecutionContext scope = new ScopedExecutionContext();
    StoreApi concurrentApi = createApi(scope, clock, new AtomicLong(60_000));
    ExecutionContext creator =
        context(TENANT_A, "creator", Set.of("store:read", "store:write"));
    StoreView created =
        scope.call(
            creator,
            () -> concurrentApi.create(new CreateStoreCommand("RACE", "Before", "UTC")));
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      List<Callable<Object>> changes =
          List.of(
              change(scope, concurrentApi, created.storeId(), "Winner-A", "actor-A", ready, start),
              change(scope, concurrentApi, created.storeId(), "Winner-B", "actor-B", ready, start));
      List<Future<Object>> futures = new ArrayList<>();
      for (Callable<Object> change : changes) {
        futures.add(pool.submit(change));
      }
      ready.await();
      start.countDown();
      List<Object> outcomes = List.of(futures.get(0).get(), futures.get(1).get());

      long winners = outcomes.stream().filter(StoreView.class::isInstance).count();
      long conflicts =
          outcomes.stream()
              .filter(StoreError.class::isInstance)
              .map(StoreError.class::cast)
              .filter(error -> "store.version-conflict".equals(error.code()))
              .count();
      assertEquals(1, winners);
      assertEquals(1, conflicts);
      RawRow row = rawRow(created.storeId());
      assertEquals(1, row.version());
      assertTrue(Set.of("Winner-A", "Winner-B").contains(row.name()));
      assertTrue(Set.of("actor-A", "actor-B").contains(row.updatedById()));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void t01H06AuditUsesOnlyTheCurrentTrustedActor() {
    Actor trusted = new Actor(ActorType.SERVICE, "trusted-service");
    contexts.set(
        new ExecutionContext(
            TENANT_A,
            trusted,
            "corr-hidden",
            Set.of("store:read", "store:write")));

    StoreView created = api.create(new CreateStoreCommand("AUDIT", "trusted-service", "UTC"));

    assertEquals(trusted, created.audit().createdBy());
    assertEquals(trusted, created.audit().updatedBy());
    assertEquals("trusted-service", rawRow(created.storeId()).updatedById());
  }

  @Test
  void t01H07NanosecondsAreNormalizedOnceToDatabaseMicroseconds() {
    Instant nanosecondTime = Instant.parse("2026-08-30T03:04:05.123456789Z");
    Instant expected = Instant.parse("2026-08-30T03:04:05.123456Z");
    clock.set(nanosecondTime);

    StoreView created = api.create(new CreateStoreCommand("MICROS", "Micros", "UTC"));
    RawRow row = rawRow(created.storeId());

    assertEquals(expected, created.audit().createdAt());
    assertEquals(expected, created.audit().updatedAt());
    assertEquals(expected, row.createdAt());
    assertEquals(expected, row.updatedAt());
  }

  @Test
  void t01H08ContextScopeRestoresOnSuccessAndException() {
    ScopedExecutionContext scope = new ScopedExecutionContext();
    ExecutionContext outer = context(TENANT_A, "outer", Set.of("store:read"));
    ExecutionContext inner = context(TENANT_B, "inner", Set.of("store:read"));

    scope.run(
        outer,
        () -> {
          scope.run(inner, () -> assertEquals(inner, scope.current().orElseThrow()));
          assertEquals(outer, scope.current().orElseThrow());
          assertThrows(
              IllegalStateException.class,
              () -> scope.run(inner, () -> { throw new IllegalStateException("boom"); }));
          assertEquals(outer, scope.current().orElseThrow());
        });

    assertTrue(scope.current().isEmpty());
  }

  @Test
  void t01H09RawPhysicalColumnsMatchReturnedState() {
    StoreView created = api.create(new CreateStoreCommand("PHYSICAL", "Before", "Asia/Shanghai"));
    clock.set(Instant.parse("2026-08-30T04:05:06.654321Z"));
    StoreView renamed = api.rename(new RenameStoreCommand(created.storeId(), "After", 0));
    RawRow row = rawRow(created.storeId());

    assertEquals(TENANT_A.value(), row.tenantId());
    assertEquals(renamed.storeId().value(), row.storeId());
    assertEquals(renamed.code(), row.code());
    assertEquals(renamed.name(), row.name());
    assertEquals(renamed.timeZoneId(), row.timeZoneId());
    assertEquals(renamed.status().name(), row.status());
    assertEquals(renamed.version(), row.version());
    assertEquals(renamed.audit().createdAt(), row.createdAt());
    assertEquals(renamed.audit().updatedAt(), row.updatedAt());
    assertNotNull(row.createdById());
    assertNotNull(row.updatedById());
  }

  @Test
  void t01H10ServiceApiSurfaceAndRuntimeValuesStayPersistenceNeutral() {
    for (Method method : StoreApi.class.getMethods()) {
      assertPersistenceNeutral(method.getReturnType(), new java.util.HashSet<>());
      for (Class<?> parameterType : method.getParameterTypes()) {
        assertPersistenceNeutral(parameterType, new java.util.HashSet<>());
      }
    }

    StoreView created = api.create(new CreateStoreCommand("SURFACE", "Surface", "UTC"));
    StoreView loaded = api.get(new GetStoreQuery(created.storeId()));
    assertEquals(StoreView.class, created.getClass());
    assertEquals(StoreView.class, loaded.getClass());
  }

  private static void assertPersistenceNeutral(Class<?> type, Set<Class<?>> visited) {
    if (type.isPrimitive() || !visited.add(type)) {
      return;
    }
    if (type.isArray()) {
      assertPersistenceNeutral(type.componentType(), visited);
      return;
    }
    String name = type.getName();
    assertFalse(name.startsWith("io.github.ande1922.moduvera.benchmark.store.candidate."), name);
    assertFalse(name.startsWith("com.baomidou."), name);
    assertFalse(name.startsWith("org.apache.ibatis."), name);
    assertFalse(name.startsWith("jakarta.persistence."), name);
    assertFalse(name.startsWith("java.sql."), name);
    assertFalse(name.startsWith("javax.sql."), name);
    if (name.startsWith("io.github.ande1922.moduvera.benchmark.store.shared.") && type.isRecord()) {
      for (RecordComponent component : type.getRecordComponents()) {
        assertPersistenceNeutral(component.getType(), visited);
      }
    }
  }

  private Callable<Object> change(
      ScopedExecutionContext scope,
      StoreApi concurrentApi,
      StoreId storeId,
      String name,
      String actor,
      CountDownLatch ready,
      CountDownLatch start) {
    return () -> {
      ready.countDown();
      start.await();
      try {
        return scope.call(
            context(TENANT_A, actor, Set.of("store:read", "store:write")),
            () -> concurrentApi.rename(new RenameStoreCommand(storeId, name, 0)));
      } catch (StoreError error) {
        return error;
      }
    };
  }

  private StoreApi createApi(
      ExecutionContextAccessor accessor, Clock candidateClock, AtomicLong candidateIds) {
    StoreRepository repository =
        provider.create(new StorePersistenceDependencies(dataSource, accessor, candidateClock));
    return new DefaultStoreApi(repository, accessor, candidateIds::getAndIncrement);
  }

  private RawRow rawRow(StoreId storeId) {
    String sql =
        "SELECT tenant_id, store_id, code, name, time_zone_id, status, version, "
            + "created_at, created_by_id, updated_at, updated_by_id "
            + "FROM store_location WHERE tenant_id = 'hidden-A' AND store_id = "
            + storeId.value();
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery(sql)) {
      assertTrue(result.next());
      RawRow row =
          new RawRow(
              result.getString("tenant_id"),
              result.getLong("store_id"),
              result.getString("code"),
              result.getString("name"),
              result.getString("time_zone_id"),
              result.getString("status"),
              result.getLong("version"),
              result.getTimestamp("created_at").toInstant(),
              result.getString("created_by_id"),
              result.getTimestamp("updated_at").toInstant(),
              result.getString("updated_by_id"));
      assertFalse(result.next());
      return row;
    } catch (SQLException error) {
      throw new AssertionError(error);
    }
  }

  private static ExecutionContext context(
      TenantId tenantId, String actor, Set<String> permissions) {
    return new ExecutionContext(
        tenantId, new Actor(ActorType.USER, actor), "corr-hidden", permissions);
  }

  private static StorePersistenceProvider loadProvider()
      throws ClassNotFoundException,
          NoSuchMethodException,
          InvocationTargetException,
          InstantiationException,
          IllegalAccessException {
    Class<?> type = Class.forName(PROVIDER_CLASS);
    return (StorePersistenceProvider) type.getConstructor().newInstance();
  }

  private static DataSource testDataSource() {
    MysqlDataSource source = new MysqlDataSource();
    source.setUrl(requiredProperty("benchmark.jdbc.url"));
    source.setUser(requiredProperty("benchmark.jdbc.user"));
    source.setPassword(System.getenv().getOrDefault("BENCHMARK_JDBC_PASSWORD", ""));
    return source;
  }

  private static String requiredProperty(String name) {
    String value = System.getProperty(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("missing required system property: " + name);
    }
    return value;
  }

  private static void applyDdl(DataSource source) throws SQLException, IOException {
    String ddl;
    try (InputStream stream =
        T01HiddenAcceptanceIT.class.getResourceAsStream("/ddl/mysql-v1-baseline.sql")) {
      if (stream == null) {
        throw new IllegalStateException("missing frozen DDL resource");
      }
      ddl = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
    String withoutComments = ddl.replaceAll("(?m)^\\s*--.*$", "");
    try (Connection connection = source.getConnection();
        Statement statement = connection.createStatement()) {
      for (String sql : withoutComments.split(";")) {
        if (!sql.isBlank()) {
          statement.execute(sql);
        }
      }
    }
  }

  private record RawRow(
      String tenantId,
      long storeId,
      String code,
      String name,
      String timeZoneId,
      String status,
      long version,
      Instant createdAt,
      String createdById,
      Instant updatedAt,
      String updatedById) {}

  private static final class MutableContextAccessor implements ExecutionContextAccessor {
    private ExecutionContext current;

    void set(ExecutionContext context) {
      current = context;
    }

    @Override
    public Optional<ExecutionContext> current() {
      return Optional.ofNullable(current);
    }
  }

  private static final class MutableClock extends Clock {
    private Instant instant;

    MutableClock(Instant instant) {
      this.instant = instant;
    }

    void set(Instant value) {
      instant = value;
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }

  private static final class ExplodingRepository implements StoreRepository {
    @Override
    public Optional<Store> findById(TenantId tenantId, StoreId storeId) {
      throw new AssertionError("repository must not be called");
    }

    @Override
    public Store save(Store candidate) {
      throw new AssertionError("repository must not be called");
    }
  }
}
