package io.github.ande1922.moduvera.benchmark.store.shared;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

public final class DefaultStoreApi implements StoreApi {
  private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_-]{0,31}");
  private final StoreRepository repository;
  private final ExecutionContextAccessor contexts;
  private final LongSupplier ids;

  public DefaultStoreApi(
      StoreRepository repository, ExecutionContextAccessor contexts, LongSupplier ids) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.contexts = Objects.requireNonNull(contexts, "contexts");
    this.ids = Objects.requireNonNull(ids, "ids");
  }

  @Override
  public StoreView create(CreateStoreCommand command) {
    ExecutionContext context = require("store:write");
    Objects.requireNonNull(command, "command");
    Store draft =
        Store.draft(
            new StoreId(ids.getAsLong()),
            context.tenantId(),
            canonicalCode(command.code()),
            validName(command.name()),
            validZone(command.timeZoneId()));
    return StoreView.from(repository.save(draft));
  }

  @Override
  public StoreView rename(RenameStoreCommand command) {
    ExecutionContext context = require("store:write");
    Objects.requireNonNull(command, "command");
    Store current = load(context.tenantId(), command.storeId());
    ensureActive(current);
    ensureVersion(current, command.expectedVersion());
    return StoreView.from(repository.save(current.renamed(validName(command.name()))));
  }

  @Override
  public StoreView deactivate(DeactivateStoreCommand command) {
    ExecutionContext context = require("store:write");
    Objects.requireNonNull(command, "command");
    Store current = load(context.tenantId(), command.storeId());
    ensureActive(current);
    ensureVersion(current, command.expectedVersion());
    return StoreView.from(repository.save(current.deactivated()));
  }

  @Override
  public StoreView get(GetStoreQuery query) {
    ExecutionContext context = require("store:read");
    Objects.requireNonNull(query, "query");
    return StoreView.from(load(context.tenantId(), query.storeId()));
  }

  private ExecutionContext require(String permission) {
    ExecutionContext context =
        contexts.current().orElseThrow(() -> new StoreError("security.context-missing"));
    if (!context.hasPermission(permission)) {
      throw new StoreError("security.permission-denied");
    }
    return context;
  }

  private Store load(TenantId tenantId, StoreId storeId) {
    return repository
        .findById(tenantId, Objects.requireNonNull(storeId, "storeId"))
        .orElseThrow(() -> new StoreError("store.not-found"));
  }

  private static void ensureActive(Store store) {
    if (store.status() != StoreStatus.ACTIVE) {
      throw new StoreError("store.state-conflict");
    }
  }

  private static void ensureVersion(Store store, long expectedVersion) {
    if (expectedVersion < 0 || store.version() != expectedVersion) {
      throw new StoreError("store.version-conflict");
    }
  }

  private static String canonicalCode(String raw) {
    if (raw == null) {
      throw new StoreError("request.validation-failed");
    }
    String value = raw.trim().toUpperCase(Locale.ROOT);
    if (!CODE.matcher(value).matches()) {
      throw new StoreError("request.validation-failed");
    }
    return value;
  }

  private static String validName(String raw) {
    if (raw == null) {
      throw new StoreError("request.validation-failed");
    }
    String value = raw.trim();
    if (value.isEmpty() || value.length() > 128) {
      throw new StoreError("request.validation-failed");
    }
    return value;
  }

  private static String validZone(String raw) {
    if (raw == null) {
      throw new StoreError("request.validation-failed");
    }
    try {
      return ZoneId.of(raw.trim()).getId();
    } catch (DateTimeException exception) {
      throw new StoreError("request.validation-failed", exception);
    }
  }
}
