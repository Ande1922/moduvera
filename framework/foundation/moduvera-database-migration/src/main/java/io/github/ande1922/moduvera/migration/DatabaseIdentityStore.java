package io.github.ande1922.moduvera.migration;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;

final class DatabaseIdentityStore {

    static final String TABLE = "moduvera_database_components";

    void verifyOrInitialize(DataSource dataSource, DatabaseComponent component, boolean initialize) {
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                boolean tableExists = tableExists(connection);
                if (!tableExists && !initialize) {
                    throw missing(component);
                }
                if (!tableExists) {
                    createIdentityTable(connection);
                }
                if (!contains(connection, component)) {
                    if (!initialize) {
                        throw missing(component);
                    }
                    insert(connection, component);
                }
                connection.commit();
            } catch (RuntimeException | SQLException exception) {
                rollback(connection, exception);
                throw exception;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (DatabaseIdentityException exception) {
            throw exception;
        } catch (SQLException exception) {
            throw new DatabaseIdentityException("database identity verification failed", exception);
        }
    }

    private static boolean tableExists(Connection connection) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet tables = metadata.getTables(connection.getCatalog(), null, "%", new String[] {"TABLE"})) {
            while (tables.next()) {
                if (TABLE.equalsIgnoreCase(tables.getString("TABLE_NAME"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void createIdentityTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + TABLE
                    + " (component_name VARCHAR(48) NOT NULL PRIMARY KEY, initialized_at TIMESTAMP NOT NULL)");
        }
    }

    private static boolean contains(Connection connection, DatabaseComponent component) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT component_name FROM " + TABLE + " WHERE component_name = ?")) {
            statement.setString(1, component.value());
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static void insert(Connection connection, DatabaseComponent component) throws SQLException {
        try (var statement = connection.prepareStatement(
                "INSERT INTO " + TABLE + " (component_name, initialized_at) VALUES (?, CURRENT_TIMESTAMP)")) {
            statement.setString(1, component.value());
            statement.executeUpdate();
        }
    }

    private static DatabaseIdentityException missing(DatabaseComponent component) {
        return new DatabaseIdentityException("database is not initialized for component " + component.value());
    }

    private static void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }
}
