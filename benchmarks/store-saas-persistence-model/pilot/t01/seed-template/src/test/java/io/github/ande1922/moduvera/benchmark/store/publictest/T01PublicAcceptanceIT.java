package io.github.ande1922.moduvera.benchmark.store.publictest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ande1922.moduvera.benchmark.store.shared.Actor;
import io.github.ande1922.moduvera.benchmark.store.shared.ActorType;
import io.github.ande1922.moduvera.benchmark.store.shared.CreateStoreCommand;
import io.github.ande1922.moduvera.benchmark.store.shared.DeactivateStoreCommand;
import io.github.ande1922.moduvera.benchmark.store.shared.DefaultStoreApi;
import io.github.ande1922.moduvera.benchmark.store.shared.ExecutionContext;
import io.github.ande1922.moduvera.benchmark.store.shared.ExecutionContextAccessor;
import io.github.ande1922.moduvera.benchmark.store.shared.GetStoreQuery;
import io.github.ande1922.moduvera.benchmark.store.shared.RenameStoreCommand;
import io.github.ande1922.moduvera.benchmark.store.shared.StoreApi;
import io.github.ande1922.moduvera.benchmark.store.shared.StoreError;
import io.github.ande1922.moduvera.benchmark.store.shared.StorePersistenceDependencies;
import io.github.ande1922.moduvera.benchmark.store.shared.StorePersistenceProvider;
import io.github.ande1922.moduvera.benchmark.store.shared.StoreStatus;
import io.github.ande1922.moduvera.benchmark.store.shared.StoreView;
import io.github.ande1922.moduvera.benchmark.store.shared.TenantId;
import com.mysql.cj.jdbc.MysqlDataSource;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class T01PublicAcceptanceIT {
  private static final String PROVIDER_CLASS =
      "io.github.ande1922.moduvera.benchmark.store.candidate.CandidateStorePersistenceProvider";
  private static final Instant INITIAL_TIME = Instant.parse("2026-08-30T01:02:03.123456Z");
  private static final Actor ACTOR = new Actor(ActorType.USER, "operator-7");
  private static final TenantId TENANT_A = new TenantId("tenant-A");
  private static final TenantId TENANT_B = new TenantId("tenant-B");

  private DataSource dataSource;
  private MutableContextAccessor contexts;
  private MutableClock clock;
  private AtomicLong ids;
  private StoreApi api;

  @BeforeAll
  void createSchema() throws Exception {
    dataSource = testDataSource();
    applyDdl(dataSource);
  }

  @BeforeEach
  void reset() throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.executeUpdate("DELETE FROM store_location");
    }
    contexts = new MutableContextAccessor();
    clock = new MutableClock(INITIAL_TIME);
    ids = new AtomicLong(10_000);
    StorePersistenceProvider provider = loadProvider();
    api =
        new DefaultStoreApi(
            provider.create(new StorePersistenceDependencies(dataSource, contexts, clock)),
            contexts,
            ids::getAndIncrement);
    contexts.set(context(TENANT_A));
  }

  @Test
  void t01P01CreateReturnsCanonicalStateVersionAndTrustedAudit() {
    StoreView created = api.create(new CreateStoreCommand("  sz_north ", "  North Store  ", "Asia/Shanghai"));

    assertEquals("SZ_NORTH", created.code());
    assertEquals("North Store", created.name());
    assertEquals("Asia/Shanghai", created.timeZoneId());
    assertEquals(StoreStatus.ACTIVE, created.status());
    assertEquals(0, created.version());
    assertEquals(INITIAL_TIME, created.audit().createdAt());
    assertEquals(INITIAL_TIME, created.audit().updatedAt());
    assertEquals(ACTOR, created.audit().createdBy());
    assertEquals(ACTOR, created.audit().updatedBy());
  }

  @Test
  void t01P02RenamePreservesStableFieldsAndReturnsRefreshedPersistenceState() {
    StoreView created = createStore("S1", "First");
    Instant renamedAt = Instant.parse("2026-08-30T02:03:04.654321Z");
    clock.set(renamedAt);

    StoreView renamed = api.rename(new RenameStoreCommand(created.storeId(), "  Renamed  ", 0));

    assertEquals("S1", renamed.code());
    assertEquals("Renamed", renamed.name());
    assertEquals(created.audit().createdAt(), renamed.audit().createdAt());
    assertEquals(created.audit().createdBy(), renamed.audit().createdBy());
    assertEquals(renamedAt, renamed.audit().updatedAt());
    assertEquals(ACTOR, renamed.audit().updatedBy());
    assertEquals(1, renamed.version());
  }

  @Test
  void t01P03DeactivateReturnsInactiveAndIncrementedVersion() {
    StoreView created = createStore("S2", "Second");

    StoreView deactivated = api.deactivate(new DeactivateStoreCommand(created.storeId(), 0));

    assertEquals(StoreStatus.INACTIVE, deactivated.status());
    assertEquals(1, deactivated.version());
  }

  @Test
  void t01P04DuplicateCodeWithinTenantIsAStableConflict() {
    createStore("unique_1", "One");

    StoreError error =
        assertThrows(StoreError.class, () -> createStore(" UNIQUE_1 ", "Duplicate"));

    assertEquals("store.code-conflict", error.code());
  }

  @Test
  void t01P05SameCodeIsAllowedInDifferentTenants() {
    StoreView first = createStore("SHARED", "Tenant A");
    contexts.set(context(TENANT_B));

    StoreView second = createStore("SHARED", "Tenant B");

    assertNotEquals(first.storeId(), second.storeId());
    assertEquals("SHARED", second.code());
  }

  @Test
  void t01P06StaleVersionFailsWithoutChangingTheWinner() {
    StoreView created = createStore("CAS", "Before");
    StoreView winner = api.rename(new RenameStoreCommand(created.storeId(), "Winner", 0));

    StoreError error =
        assertThrows(
            StoreError.class,
            () -> api.rename(new RenameStoreCommand(created.storeId(), "Loser", 0)));

    assertEquals("store.version-conflict", error.code());
    assertEquals(winner, api.get(new GetStoreQuery(created.storeId())));
  }

  @Test
  void t01P07InactiveStoreRejectsRenameAndRepeatedDeactivation() {
    StoreView created = createStore("STATE", "Before");
    StoreView inactive = api.deactivate(new DeactivateStoreCommand(created.storeId(), 0));

    StoreError renameError =
        assertThrows(
            StoreError.class,
            () -> api.rename(new RenameStoreCommand(created.storeId(), "Nope", inactive.version())));
    StoreError deactivateError =
        assertThrows(
            StoreError.class,
            () -> api.deactivate(new DeactivateStoreCommand(created.storeId(), inactive.version())));

    assertEquals("store.state-conflict", renameError.code());
    assertEquals("store.state-conflict", deactivateError.code());
  }

  @Test
  void t01P08GetReturnsTheStoredProjection() {
    StoreView created = createStore("READ", "Readable");

    assertEquals(created, api.get(new GetStoreQuery(created.storeId())));
  }

  private StoreView createStore(String code, String name) {
    return api.create(new CreateStoreCommand(code, name, "UTC"));
  }

  private static ExecutionContext context(TenantId tenantId) {
    return new ExecutionContext(
        tenantId, ACTOR, "corr-public", Set.of("store:read", "store:write"));
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
    String url = requiredProperty("benchmark.jdbc.url");
    MysqlDataSource source = new MysqlDataSource();
    source.setUrl(url);
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
        T01PublicAcceptanceIT.class.getResourceAsStream("/ddl/mysql-v1-baseline.sql")) {
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

    void set(Instant instant) {
      this.instant = instant;
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
}
