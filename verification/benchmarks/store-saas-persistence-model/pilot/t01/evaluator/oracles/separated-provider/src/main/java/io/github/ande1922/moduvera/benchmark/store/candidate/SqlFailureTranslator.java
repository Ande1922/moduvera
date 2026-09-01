package io.github.ande1922.moduvera.benchmark.store.candidate;

import io.github.ande1922.moduvera.benchmark.store.shared.StoreError;
import java.sql.SQLException;
import java.util.Locale;

final class SqlFailureTranslator {
  private static final int MYSQL_DUPLICATE_KEY = 1062;
  private static final String STORE_CODE_KEY = "uk_store_tenant_code";

  private SqlFailureTranslator() {}

  static RuntimeException translate(RuntimeException exception) {
    if (exception instanceof StoreError) {
      return exception;
    }
    if (isStoreCodeConflict(exception)) {
      return new StoreError("store.code-conflict", exception);
    }
    return new StoreError("store.persistence-failure", exception);
  }

  private static boolean isStoreCodeConflict(Throwable failure) {
    for (Throwable current = failure; current != null; current = current.getCause()) {
      if (current instanceof SQLException sqlException
          && containsStoreCodeDuplicate(sqlException)) {
        return true;
      }
    }
    return false;
  }

  private static boolean containsStoreCodeDuplicate(SQLException first) {
    for (SQLException current = first; current != null; current = current.getNextException()) {
      String message = current.getMessage();
      if (current.getErrorCode() == MYSQL_DUPLICATE_KEY
          && message != null
          && message.toLowerCase(Locale.ROOT).contains(STORE_CODE_KEY)) {
        return true;
      }
    }
    return false;
  }
}
